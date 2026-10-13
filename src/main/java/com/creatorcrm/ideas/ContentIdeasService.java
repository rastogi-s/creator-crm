package com.creatorcrm.ideas;

import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.CampaignResult;
import com.creatorcrm.domain.ContentIdeas;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.llm.BriefFile;
import com.creatorcrm.llm.ContentIdeaList;
import com.creatorcrm.llm.ContentIdeasInput;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.CampaignResultRepo;
import com.creatorcrm.repo.ContentIdeasRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Content ideas: when a brand sends its content guidelines (in the email, or as a PDF, Word or PowerPoint file), Claude
 * plans reels and videos for the deal from them, her About you profile, how her recent Instagram posts did and the
 * posts she made for other brands. She can refresh them from the deal, with her own note or pasted guidelines.
 */
@Service
public class ContentIdeasService {
    private static final Logger log = LoggerFactory.getLogger(ContentIdeasService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int MAX_FILES = 4;
    /** The brand's emails on a deal looked through for the brief when she presses Refresh. */
    static final int MAX_EMAILS = 12;
    static final int MAX_BODIES = 4;
    /** PDFs and pictures go to Claude as they are, up to these limits; past them a PDF is read as text. */
    static final long MAX_VISUAL_BYTES = 18L * 1024 * 1024;
    static final int MAX_PICTURE_BYTES = 5 * 1024 * 1024;
    static final int MAX_PDF_PAGES = 60;
    static final long MAX_BYTES = 15L * 1024 * 1024;
    static final int MAX_EMAIL_CHARS = 8_000;
    static final int RECENT_POSTS = 15;
    static final int PAST_BRAND_POSTS = 8;
    static final int MAX_NOTE = 1000;
    /** Pasted guidelines: anything shorter is a note, not a brief. */
    static final int MIN_PASTED = 40;

    /** A brief the app can't open: it sits behind a link to a shared drive or design tool. */
    private static final Pattern BRIEF_LINK = Pattern.compile(
            "(?i)https?://[^\\s\"'<>]*\\b(drive\\.google|docs\\.google|dropbox|canva|notion|figma|frame\\.io|box\\.com|onedrive|sharepoint|wetransfer)[^\\s\"'<>]*");
    /** A shipping email that also carries the guidelines. */
    private static final Pattern MENTIONS_BRIEF = Pattern.compile(
            "(?i)\\b(brief|guidelines?|talking points|creative direction|do'?s and don'?ts|key messages?|content deck|shot list)\\b");

    /** What the deal drawer and MCP show: the newest ideas, what they came from, and anything she should know. */
    public record View(Long id, String status, String source, String fileNames, String note, String error,
                       String briefSummary, List<String> mustInclude, List<String> avoid, List<ContentIdeaList.Idea> ideas,
                       boolean hasGuidelines, OffsetDateTime createdAt) {}

    /** The guidelines read from one brand email: its text, the files that were read, and what couldn't be read. */
    record Brief(String text, List<String> files, List<String> notes, List<BriefFile> visuals) {
        static Brief of(String text) {
            return new Brief(text, List.of(), List.of(), List.of());
        }

        boolean isEmpty() {
            return text.isBlank() && visuals.isEmpty();
        }
    }

    private final ContentIdeasRepo runs;
    private final OpportunityRepo opportunities;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final DeadlineRepo deadlines;
    private final CampaignResultRepo results;
    private final GmailConnector gmail;
    private final InstagramStatsService instagram;
    private final LinkService links;
    private final LlmClient llm;
    private final WorkflowEngine workflow;
    private final SettingsService settings;
    private final ActivityRepo activity;

    public ContentIdeasService(ContentIdeasRepo runs, OpportunityRepo opportunities, ConversationRepo conversations,
                               MessageRepo messages, DeadlineRepo deadlines, CampaignResultRepo results, GmailConnector gmail,
                               InstagramStatsService instagram, LinkService links, LlmClient llm, WorkflowEngine workflow,
                               SettingsService settings, ActivityRepo activity) {
        this.runs = runs;
        this.opportunities = opportunities;
        this.conversations = conversations;
        this.messages = messages;
        this.deadlines = deadlines;
        this.results = results;
        this.gmail = gmail;
        this.instagram = instagram;
        this.links = links;
        this.llm = llm;
        this.workflow = workflow;
        this.settings = settings;
        this.activity = activity;
    }

    /** Whether a brand email carries content guidelines: a brief, or a shipping note that talks about the brief. */
    public static boolean carriesBrief(Intent intent, Message m) {
        if (intent == Intent.CONTENT_BRIEF) return true;
        if (intent != Intent.PRODUCT_SHIPPED && intent != Intent.PRODUCT_DELIVERED) return false;
        return m.content != null && MENTIONS_BRIEF.matcher(m.content).find();
    }

    /** The brand sent its guidelines: read them and write ideas. Never throws; one run per email. */
    public void onBrief(Message m, Opportunity o) {
        if (runs.existsByMessageId(m.id)) return;
        try {
            run(o, ContentIdeas.Source.EMAIL, m.id, read(List.of(m), true), null);
        } catch (RuntimeException e) {
            log.warn("Could not plan content for deal {}: {}", o.id, e.getMessage());
        }
    }

    /** Deal statuses where a file from the brand is likely its brief: the product is coming or the content is next. */
    private static final Set<OpportunityStatus> BRIEF_TIME = EnumSet.of(OpportunityStatus.CONTRACT_TO_SIGN,
            OpportunityStatus.PRODUCT_PENDING, OpportunityStatus.PRODUCT_RECEIVED, OpportunityStatus.CONTENT_TO_CREATE);
    /** A file name, subject or email that says it's the brief. */
    private static final Pattern BRIEF_WORDS = Pattern.compile(
            "(?i)(brief|guideline|guide\\b|deck|creative|script|talking points|direction|mood ?board|concept|key messag|do'?s|content plan)");

    /**
     * A brand email that wasn't read as a brief but has files attached, on a deal at the product or content step: when
     * a file, the subject or the email says it's the brief, plan content from it. Never throws; one run per email.
     */
    public void onAttachments(Message m, Opportunity o) {
        Intent type = intent(m);
        if (!BRIEF_TIME.contains(o.status) || (type != null && NOT_A_BRIEF.contains(type)) || runs.existsByMessageId(m.id) || !isGmail(m)) return;
        try {
            String said = (m.subject == null ? "" : m.subject) + "\n" + (m.content == null ? "" : m.content);
            GmailConnector.BriefAttachments found = gmail.briefAttachments(gmailId(m.externalId), MAX_FILES, MAX_BYTES);
            boolean brief = found.files().stream().anyMatch(f -> !isPicture(f.fileName()) || BRIEF_WORDS.matcher(f.fileName()).find())
                    && (BRIEF_WORDS.matcher(said).find() || found.files().stream().anyMatch(f -> BRIEF_WORDS.matcher(f.fileName()).find()));
            if (!brief) return;
            run(o, ContentIdeas.Source.EMAIL, m.id, read(List.of(m), true, Map.of(m.id, found)), null);
        } catch (Exception e) {
            log.warn("Could not check the files of message {} for a brief: {}", m.id, e.getMessage());
        }
    }

    /**
     * Refresh on the deal. {@code pasted}: guidelines she pasted (a brief behind a link); else the brief read fresh
     * from the deal's emails and their files. {@code note}: what she wants different this time.
     */
    public View generate(Long opportunityId, String pasted, String note) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow();
        String ask = note == null ? "" : note.strip();
        if (ask.length() > MAX_NOTE) ask = ask.substring(0, MAX_NOTE);
        String paste = pasted == null ? "" : pasted.strip();
        if (!paste.isEmpty()) {
            if (paste.length() < MIN_PASTED) throw new IllegalArgumentException("Paste the whole brief (at least a few sentences)");
            return view(run(o, ContentIdeas.Source.PASTED, null, Brief.of(BriefFiles.clip(paste)), ask));
        }
        List<Message> emails = brandEmails(o);
        Brief fresh = read(emails, false);
        if (!fresh.isEmpty()) {
            Long from = emails.stream().filter(m -> isBriefType(m)).map(m -> m.id).findFirst().orElse(null);
            return view(run(o, ContentIdeas.Source.EMAIL, from, fresh, ask));
        }
        // Nothing in the emails (or Gmail is offline): the brief she pasted before, if any.
        Optional<ContentIdeas> pastedBefore = runs.findByOpportunityIdOrderByIdDesc(o.id).stream()
                .filter(r -> r.source == ContentIdeas.Source.PASTED && r.guidelines != null).findFirst();
        if (pastedBefore.isPresent()) return view(run(o, ContentIdeas.Source.PASTED, null, Brief.of(pastedBefore.get().guidelines), ask));
        return view(run(o, ContentIdeas.Source.DEAL, null, new Brief("", List.of(), fresh.notes(), List.of()), ask));
    }

    public Optional<View> latest(Long opportunityId) {
        return runs.findFirstByOpportunityIdOrderByIdDesc(opportunityId).map(this::view);
    }

    /** Emails that are about money or the contract, not the content: their files are invoices and contracts. */
    private static final Set<Intent> NOT_A_BRIEF = EnumSet.of(Intent.CONTRACT_SENT, Intent.CONTRACT_COMING, Intent.INVOICE_REQUEST,
            Intent.PAYMENT_UPDATE, Intent.DECLINE, Intent.NEW_OPPORTUNITY, Intent.RATES_REQUEST, Intent.MEDIA_KIT_REQUEST);

    /** The brand's emails on the deal, newest first, leaving out contract and money emails. */
    private List<Message> brandEmails(Opportunity o) {
        if (o.conversationId == null) return List.of();
        List<Message> all = new ArrayList<>(messages.findByConversationIdOrderBySentAtAsc(o.conversationId));
        java.util.Collections.reverse(all);
        return all.stream().filter(m -> m.direction == Direction.INBOUND)
                .filter(m -> intent(m) == null || !NOT_A_BRIEF.contains(intent(m)))
                .limit(MAX_EMAILS).toList();
    }

    private static Intent intent(Message m) {
        if (m.messageType == null) return null;
        try {
            return Intent.valueOf(m.messageType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBriefType(Message m) {
        Intent i = intent(m);
        return i != null && carriesBrief(i, m);
    }

    private boolean isGmail(Message m) {
        if (m.externalId == null || !gmail.isConnected()) return false;
        return conversations.findById(m.conversationId).map(c -> c.platform == Platform.EMAIL).orElse(false);
    }

    Brief read(List<Message> emails, boolean everyBody) {
        return read(emails, everyBody, Map.of());
    }

    /**
     * The brief from these emails (newest first): the text of brief emails (or of every email, with {@code everyBody}),
     * the text of their Word, PowerPoint and text files, and their PDFs and pictures for Claude to read directly.
     * {@code fetched}: attachments already downloaded, by message id. Files that can't be read are named in the notes.
     */
    Brief read(List<Message> emails, boolean everyBody, Map<Long, GmailConnector.BriefAttachments> fetched) {
        StringBuilder text = new StringBuilder();
        List<String> files = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<BriefFile> visuals = new ArrayList<>();
        long visualBytes = 0;
        int bodies = 0;
        for (Message m : emails) {
            List<GmailConnector.FileAttachment> attached = List.of();
            if (files.size() < MAX_FILES && isGmail(m)) {
                try {
                    GmailConnector.BriefAttachments a = fetched.containsKey(m.id) ? fetched.get(m.id)
                            : gmail.briefAttachments(gmailId(m.externalId), MAX_FILES - files.size(), MAX_BYTES);
                    attached = a.files();
                    for (String name : a.skipped()) notes.add(name + " can't be read by the app (save it as a PDF and paste or forward it)");
                } catch (Exception e) {
                    log.warn("Could not download the brief files of message {}: {}", m.id, e.getMessage());
                    notes.add("The brief's files couldn't be downloaded from Gmail");
                }
            }
            String body = m.content == null ? "" : m.content.strip();
            if ((everyBody || isBriefType(m) || !attached.isEmpty()) && !body.isEmpty() && bodies < MAX_BODIES) {
                bodies++;
                text.append(text.isEmpty() ? "" : "\n\n").append("From the brand's email")
                        .append(m.subject == null || m.subject.isBlank() ? "" : " \"" + m.subject.strip() + "\"")
                        .append(" (").append(m.sentAt.toLocalDate()).append("):\n")
                        .append(body.length() > MAX_EMAIL_CHARS ? body.substring(0, MAX_EMAIL_CHARS) + "\n[...]" : body);
                Matcher link = BRIEF_LINK.matcher(body);
                if (attached.isEmpty() && link.find()) {
                    notes.add("Part of the brief looks like it's on " + pretty(link.group(1)) + ", which the app can't open. "
                            + "Paste its text below and press Refresh for ideas that follow it closely");
                }
            }
            for (GmailConnector.FileAttachment f : attached) {
                String type = mediaType(f.fileName(), f.data());
                if (type != null && visualBytes + f.data().length <= MAX_VISUAL_BYTES
                        && (!type.startsWith("image/") || f.data().length <= MAX_PICTURE_BYTES) && pages(f, type) <= MAX_PDF_PAGES) {
                    visuals.add(new BriefFile(f.fileName(), type, f.data()));
                    visualBytes += f.data().length;
                    files.add(f.fileName());
                    continue;
                }
                try {
                    String t = BriefFiles.text(f.fileName(), f.data());
                    if (t == null) {
                        notes.add(f.fileName() + " is too big or has no text the app can read");
                        continue;
                    }
                    text.append("\n\nFrom the attached file \"").append(f.fileName()).append("\":\n").append(t);
                    files.add(f.fileName());
                } catch (Exception e) {
                    notes.add(f.fileName() + " couldn't be opened");
                }
            }
        }
        return new Brief(BriefFiles.clip(text.toString()), files, notes.stream().distinct().toList(), visuals);
    }

    /** application/pdf or a picture type Claude reads directly; null for files read as text. */
    static String mediaType(String name, byte[] data) {
        String ext = BriefFiles.extension(name);
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F' ? "application/pdf" : null;
        };
    }

    private static boolean isPicture(String name) {
        String t = mediaType(name, new byte[0]);
        return t != null && t.startsWith("image/");
    }

    /** A PDF's page count (0 for pictures); a PDF that won't open counts as too long, so its text is tried instead. */
    private static int pages(GmailConnector.FileAttachment f, String type) {
        if (!"application/pdf".equals(type)) return 0;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(f.data())) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private ContentIdeas run(Opportunity o, ContentIdeas.Source source, Long messageId, Brief brief, String note) {
        ContentIdeas r = new ContentIdeas();
        r.opportunityId = o.id;
        r.messageId = messageId;
        r.source = source;
        r.guidelines = brief.text().isBlank() ? null : brief.text();
        r.fileNames = brief.files().isEmpty() ? null : clip(String.join(", ", brief.files()), 1000);
        r.note = note == null || note.isBlank() ? null : note;
        r.createdAt = OffsetDateTime.now();
        String brand = workflow.brandName(o);
        try {
            ContentIdeaList ideas = llm.contentIdeas(input(o, brand, brief, note));
            if (ideas == null || ideas.ideas() == null || ideas.ideas().isEmpty()) throw new IllegalStateException("Claude returned no ideas");
            r.ideasJson = JSON.writeValueAsString(ideas);
            r.status = ContentIdeas.Status.READY;
            r.error = brief.notes().isEmpty() ? null : clip(String.join(". ", brief.notes()) + ".", 500);
            runs.save(r);
            int n = ideas.ideas().size();
            activity.save(Activity.of(o.id, Activity.IDEAS, n + " content " + (n == 1 ? "idea" : "ideas") + " written"
                    + (source == ContentIdeas.Source.DEAL ? "" : " from " + brand + "'s brief")));
            workflow.completeOpenTasks(o.id, Set.of(TaskType.PLAN_CONTENT), "Content ideas are ready on the deal");
            return r;
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Content ideas failed for deal {}: {}", o.id, e.getMessage());
            r.status = ContentIdeas.Status.FAILED;
            r.error = clip("Claude couldn't write the ideas: " + e.getMessage(), 500);
            runs.save(r);
            workflow.upsertTask(o, TaskType.PLAN_CONTENT, "Get content ideas for " + brand
                    + ": open the deal and press Refresh ideas", Priority.MEDIUM, settings.today().plusDays(1), messageId);
            return r;
        }
    }

    ContentIdeasInput input(Opportunity o, String brand, Brief brief, String note) {
        return new ContentIdeasInput(settings.today(), brand, dealContext(o), profile(), recentPosts(), pastBrandPosts(o.id),
                brief.text().isBlank() ? "" : wrap(brief.text()), note == null ? "" : note, brief.visuals());
    }

    private String dealContext(Opportunity o) {
        List<String> parts = new ArrayList<>();
        if (o.type != null) parts.add("type: " + o.type);
        if (o.compensation != null) parts.add("compensation: " + o.compensation.name().toLowerCase(Locale.ROOT));
        if (o.budgetText != null && !o.budgetText.isBlank()) parts.add("fee: " + field(o.budgetText));
        if (o.deliverables != null && !o.deliverables.isBlank()) parts.add("deliverables: " + field(o.deliverables));
        if (o.campaign != null && !o.campaign.isBlank()) parts.add("campaign or product: " + field(o.campaign));
        if (o.usageRights != null && !o.usageRights.isBlank()) parts.add("usage and exclusivity: " + field(o.usageRights));
        for (Deadline d : deadlines.findByOpportunityIdOrderByDueDateAsc(o.id)) {
            if (!d.done && d.dueDate != null) parts.add("due " + d.dueDate + ": " + (d.description == null || d.description.isBlank()
                    ? d.type.name().toLowerCase(Locale.ROOT).replace('_', ' ') : field(d.description)));
        }
        String summary = o.conversationId == null ? null
                : conversations.findById(o.conversationId).map(c -> c.summary).orElse(null);
        StringBuilder sb = new StringBuilder(parts.stream().map(p -> "- " + p).collect(Collectors.joining("\n")));
        if (summary != null && !summary.isBlank()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("<conversation_summary>\n").append(Untrusted.escape(summary))
                    .append("\n</conversation_summary>");
        }
        return sb.toString();
    }

    private String profile() {
        StringBuilder p = new StringBuilder("Name: ").append(settings.creatorName()).append("\n\n").append(settings.creatorProfile());
        for (String section : List.of(links.profileSection(), instagram.profileSection())) {
            if (!section.isEmpty()) p.append("\n\n").append(section);
        }
        return p.toString();
    }

    private List<String> recentPosts() {
        return instagram.recentPosts(RECENT_POSTS).stream().map(p -> {
            StringBuilder sb = new StringBuilder(p.type());
            if (p.postedAt() != null && p.postedAt().length() >= 10) sb.append(", ").append(p.postedAt(), 0, 10);
            if (p.likes() != null) sb.append(", ").append(String.format("%,d", p.likes())).append(" likes");
            if (p.comments() != null) sb.append(", ").append(String.format("%,d", p.comments())).append(" comments");
            if (p.caption() != null && !p.caption().isBlank()) sb.append(": \"").append(field(p.caption())).append('"');
            return sb.toString();
        }).toList();
    }

    /** Posts she made for other deals, with their numbers: what worked for brands before. */
    private List<String> pastBrandPosts(Long opportunityId) {
        List<CampaignResult> past = results.findAll().stream()
                .filter(r -> !Objects.equals(r.opportunityId, opportunityId) && (r.hasNumbers() || r.caption != null))
                .sorted(Comparator.comparing((CampaignResult r) -> r.postedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PAST_BRAND_POSTS).toList();
        Map<Long, String> brands = past.stream().map(r -> r.opportunityId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toMap(id -> id, id -> opportunities.findById(id).map(workflow::brandName).orElse("a brand")));
        return past.stream().map(r -> {
            StringBuilder sb = new StringBuilder(r.mediaType == null ? "Post" : pretty(r.mediaType)).append(" for ")
                    .append(field(brands.getOrDefault(r.opportunityId, "a brand")));
            if (r.postedAt != null) sb.append(", ").append(r.postedAt.toLocalDate());
            List<String> numbers = new ArrayList<>();
            if (r.views != null) numbers.add(String.format("%,d views", r.views));
            if (r.reach != null) numbers.add(String.format("%,d reached", r.reach));
            if (r.likes != null) numbers.add(String.format("%,d likes", r.likes));
            if (r.comments != null) numbers.add(String.format("%,d comments", r.comments));
            if (r.saves != null) numbers.add(String.format("%,d saves", r.saves));
            if (r.shares != null) numbers.add(String.format("%,d shares", r.shares));
            if (!numbers.isEmpty()) sb.append(", ").append(String.join(", ", numbers));
            if (r.caption != null && !r.caption.isBlank()) sb.append(": \"").append(field(r.caption)).append('"');
            return sb.toString();
        }).toList();
    }

    View view(ContentIdeas r) {
        ContentIdeaList ideas = null;
        if (r.ideasJson != null && !r.ideasJson.isBlank()) {
            try {
                ideas = JSON.readValue(r.ideasJson, ContentIdeaList.class);
            } catch (JsonProcessingException e) {
                log.warn("Stored content ideas are damaged: {}", e.getMessage());
            }
        }
        return new View(r.id, r.status.name(), r.source.name(), r.fileNames, r.note, r.error,
                ideas == null ? null : ideas.briefSummary(),
                ideas == null || ideas.mustInclude() == null ? List.of() : ideas.mustInclude(),
                ideas == null || ideas.avoid() == null ? List.of() : ideas.avoid(),
                ideas == null || ideas.ideas() == null ? List.of() : ideas.ideas(),
                (r.guidelines != null && !r.guidelines.isBlank()) || r.fileNames != null, r.createdAt);
    }

    /** The guidelines as data for Claude; they can't close the tag early to pass off instructions as ours. */
    static String wrap(String text) {
        String safe = text.replace("<untrusted_guidelines", "&lt;untrusted_guidelines").replace("</untrusted_guidelines", "&lt;/untrusted_guidelines");
        return "<untrusted_guidelines>\n" + safe + "\n</untrusted_guidelines>";
    }

    /** Messages are stored as "email:&lt;Gmail id&gt;". */
    static String gmailId(String externalId) {
        return externalId.startsWith("email:") ? externalId.substring("email:".length()) : externalId;
    }

    private static String field(String s) {
        String v = Untrusted.escape(s.strip()).replace('\n', ' ');
        return v.length() > 300 ? v.substring(0, 300) + "…" : v;
    }

    private static String pretty(String s) {
        String v = s.toLowerCase(Locale.ROOT);
        if (v.startsWith("drive.google")) return "Google Drive";
        if (v.startsWith("docs.google")) return "Google Docs";
        if (v.equals("frame.io")) return "Frame.io";
        if (v.equals("box.com")) return "Box";
        if (v.equals("onedrive")) return "OneDrive";
        if (v.equals("sharepoint")) return "SharePoint";
        if (v.equals("wetransfer")) return "WeTransfer";
        if (v.equals("carousel_album")) return "Carousel";
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
