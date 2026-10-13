package com.creatorcrm.shop;

import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.domain.CaptionSet;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Product;
import com.creatorcrm.ideas.ContentIdeasService;
import com.creatorcrm.llm.BriefFile;
import com.creatorcrm.llm.CaptionDraft;
import com.creatorcrm.llm.CaptionInput;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.CaptionSetRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.ProductRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Post captions: she says what the video is about (and can add screenshots and pick the products in it), and Claude
 * writes the YouTube title, description and tags, the Instagram caption and Story line, the TikTok caption and the DM
 * reply, in her voice. The app adds her links, codes, ad label and hashtags (see {@link CaptionComposer}).
 */
@Service
public class CaptionService {
    private static final Logger log = LoggerFactory.getLogger(CaptionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final List<String> PLATFORMS = List.of("YOUTUBE", "INSTAGRAM", "TIKTOK");
    static final int MAX_ABOUT = 4000;
    static final int MAX_NOTE = 1000;
    static final int MAX_PICTURES = 3;
    static final int MAX_PICTURE_BYTES = 5 * 1024 * 1024;
    static final int MAX_CHOSEN = 12;
    /** Products shown to Claude when it picks from her list: the ones she used most recently. */
    static final int MAX_TO_PICK_FROM = 60;
    static final int RECENT_CAPTIONS = 8;

    public record Picture(String name, String mediaType, String base64) {}

    /**
     * {@code productIds}: the products in the video; when empty and {@code pick} is on, Claude picks from her list.
     * {@code youtubeFormat}: SHORT or VIDEO.
     */
    public record Request(String about, String note, Long opportunityId, List<Long> productIds, boolean pick, String youtubeFormat,
                          List<String> platforms, List<Picture> pictures) {}

    public record View(Long id, String title, String about, String note, Long opportunityId, String brand, List<Long> productIds,
                       String youtubeFormat, List<String> platforms, int pictureCount, OffsetDateTime createdAt,
                       CaptionComposer.Result result) {}

    /** One line in her list of past captions. */
    public record Summary(Long id, String title, String about, String brand, List<String> platforms, OffsetDateTime createdAt) {}

    private final CaptionSetRepo sets;
    private final ProductRepo productRepo;
    private final ProductService products;
    private final OpportunityRepo opportunities;
    private final WorkflowEngine workflow;
    private final ContentIdeasService ideas;
    private final InstagramStatsService instagram;
    private final SettingsService settings;
    private final LlmClient llm;

    public CaptionService(CaptionSetRepo sets, ProductRepo productRepo, ProductService products, OpportunityRepo opportunities,
                          WorkflowEngine workflow, ContentIdeasService ideas, InstagramStatsService instagram, SettingsService settings,
                          LlmClient llm) {
        this.sets = sets;
        this.productRepo = productRepo;
        this.products = products;
        this.opportunities = opportunities;
        this.workflow = workflow;
        this.ideas = ideas;
        this.instagram = instagram;
        this.settings = settings;
        this.llm = llm;
    }

    public View write(Request r) {
        String about = r.about() == null ? "" : r.about().strip();
        List<BriefFile> pictures = pictures(r.pictures());
        if (about.length() < 3 && pictures.isEmpty()) throw new IllegalArgumentException("Say in a sentence what the video is about, or add a screenshot");
        if (about.length() > MAX_ABOUT) throw new IllegalArgumentException("That's too long; a few sentences are plenty");
        if (about.isEmpty()) about = "(see the screenshots)";
        String note = r.note() == null || r.note().isBlank() ? null : r.note().strip();
        if (note != null && note.length() > MAX_NOTE) note = note.substring(0, MAX_NOTE);
        List<String> platforms = r.platforms() == null ? List.of() : PLATFORMS.stream().filter(p -> r.platforms().contains(p)).toList();
        if (platforms.isEmpty()) throw new IllegalArgumentException("Pick at least one of YouTube, Instagram or TikTok");
        String format = "SHORT".equals(r.youtubeFormat()) ? "SHORT" : "VIDEO";
        Opportunity deal = r.opportunityId() == null ? null
                : opportunities.findById(r.opportunityId()).orElseThrow(() -> new IllegalArgumentException("That deal was deleted"));

        List<Long> chosenIds = r.productIds() == null ? List.of() : r.productIds().stream().filter(Objects::nonNull).distinct().limit(MAX_CHOSEN).toList();
        boolean pick = chosenIds.isEmpty() && r.pick();
        List<Product> offered = pick
                ? productRepo.findAll().stream()
                        .sorted(Comparator.comparing((Product p) -> p.lastUsedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(p -> p.id, Comparator.reverseOrder()))
                        .limit(MAX_TO_PICK_FROM).toList()
                : products.byIds(chosenIds);

        CaptionInput input = new CaptionInput(settings.today(), about, note, numbered(offered), pick, dealContext(deal), format, platforms,
                recentCaptions(), pictures);
        CaptionDraft draft = llm.captions(input);
        if (draft == null) throw new IllegalStateException("Claude returned no captions");

        List<Long> used = new ArrayList<>();
        if (draft.productNumbers() != null) {
            for (Integer n : draft.productNumbers()) {
                if (n != null && n >= 1 && n <= offered.size() && !used.contains(offered.get(n - 1).id)) used.add(offered.get(n - 1).id);
            }
        }
        // She said these are in the video: keep all of them, in Claude's order first.
        if (!pick) offered.stream().map(p -> p.id).filter(id -> !used.contains(id)).forEach(used::add);

        CaptionSet s = new CaptionSet();
        s.title = clip(draft.title() == null || draft.title().isBlank() ? about : draft.title().strip(), 200);
        s.about = about;
        s.note = note;
        s.opportunityId = deal == null ? null : deal.id;
        s.productIds = used.isEmpty() ? null : String.join(",", used.stream().map(String::valueOf).toList());
        s.youtubeFormat = format;
        s.platforms = String.join(",", platforms);
        s.pictureCount = pictures.size();
        s.status = CaptionSet.Status.READY;
        try {
            s.draftJson = JSON.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Couldn't save the captions", e);
        }
        s.createdAt = OffsetDateTime.now();
        sets.save(s);
        products.markUsed(used);
        return view(s);
    }

    public Optional<View> get(Long id) {
        return sets.findById(id).map(this::view);
    }

    public List<Summary> recent() {
        return sets.findTop30ByOrderByIdDesc().stream().filter(s -> s.status == CaptionSet.Status.READY)
                .map(s -> new Summary(s.id, s.title, clip(s.about, 160), brand(s.opportunityId), platforms(s), s.createdAt)).toList();
    }

    public void delete(Long id) {
        sets.deleteById(id);
    }

    View view(CaptionSet s) {
        CaptionDraft draft;
        try {
            draft = JSON.readValue(s.draftJson, CaptionDraft.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Stored captions {} are damaged: {}", s.id, e.getMessage());
            throw new IllegalStateException("These captions couldn't be opened; write them again");
        }
        List<Long> ids = ids(s.productIds);
        List<Product> shown = products.byIds(ids); // deleted products drop out; edited links show as they are now
        List<String> warnings = shown.stream().map(products::warning).toList();
        String brand = brand(s.opportunityId);
        CaptionComposer.Result result = CaptionComposer.compose(draft, shown, warnings, new LinkedHashSet<>(platforms(s)),
                new CaptionComposer.Style(settings.disclosureTag(), settings.captionHashtags()), s.opportunityId == null ? null : brand == null ? "the brand" : brand);
        return new View(s.id, s.title, s.about, s.note, s.opportunityId, brand, ids, s.youtubeFormat, platforms(s), s.pictureCount,
                s.createdAt, result);
    }

    /** "1. Dew Serum (Amazon, by Glow Haus; tags: skincare, morning; has a discount code)". Never the links. */
    static List<String> numbered(List<Product> list) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Product p = list.get(i);
            List<String> bits = new ArrayList<>();
            bits.add(switch (p.store) { case AMAZON -> "Amazon"; case WALMART -> "Walmart"; case OTHER -> "shop link"; });
            if (p.brand != null && !p.brand.isBlank()) bits.add("by " + field(p.brand));
            if (p.tags != null && !p.tags.isBlank()) bits.add("tags: " + field(p.tags));
            if (p.code != null && !p.code.isBlank()) bits.add("has a discount code");
            out.add((i + 1) + ". " + field(p.name) + " (" + String.join("; ", bits) + ")");
        }
        return out;
    }

    /** The deal the post is for: brand, what kind of deal, and the brief's must-dos and don'ts when there are ideas. */
    private String dealContext(Opportunity o) {
        if (o == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add("- brand: " + field(workflow.brandName(o)));
        if (o.type != null) parts.add("- type: " + o.type);
        if (o.compensation != null) parts.add("- compensation: " + o.compensation.name().toLowerCase(Locale.ROOT));
        if (o.campaign != null && !o.campaign.isBlank()) parts.add("- campaign or product: " + field(o.campaign));
        if (o.deliverables != null && !o.deliverables.isBlank()) parts.add("- deliverables: " + field(o.deliverables));
        ideas.latest(o.id).ifPresent(v -> {
            v.mustInclude().forEach(m -> parts.add("- must include: " + field(m)));
            v.avoid().forEach(a -> parts.add("- avoid: " + field(a)));
        });
        return "<untrusted_deal>\n" + String.join("\n", parts) + "\n</untrusted_deal>";
    }

    /** Her own captions to learn her voice from: recent Instagram posts, then captions she made here. */
    private List<String> recentCaptions() {
        List<String> out = new ArrayList<>();
        instagram.recentPosts(RECENT_CAPTIONS).stream().map(InstagramStatsService.Post::caption)
                .filter(c -> c != null && !c.isBlank()).forEach(c -> out.add(field(c)));
        if (out.size() < RECENT_CAPTIONS) {
            for (CaptionSet s : sets.findTop30ByOrderByIdDesc()) {
                if (out.size() >= RECENT_CAPTIONS) break;
                try {
                    CaptionDraft d = JSON.readValue(s.draftJson, CaptionDraft.class);
                    if (d.instagram() != null && d.instagram().caption() != null && !d.instagram().caption().isBlank()) out.add(field(d.instagram().caption()));
                } catch (Exception e) {
                    // skip a damaged one
                }
            }
        }
        return out;
    }

    static List<BriefFile> pictures(List<Picture> in) {
        if (in == null || in.isEmpty()) return List.of();
        if (in.size() > MAX_PICTURES) throw new IllegalArgumentException("Add up to " + MAX_PICTURES + " screenshots");
        List<BriefFile> out = new ArrayList<>();
        for (Picture p : in) {
            String type = p.mediaType() == null ? "" : p.mediaType().toLowerCase(Locale.ROOT);
            if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(type)) {
                throw new IllegalArgumentException("Screenshots must be pictures (JPEG, PNG, WebP or GIF)");
            }
            byte[] data;
            try {
                String b64 = p.base64() == null ? "" : p.base64().replaceFirst("^data:[^,]*,", "");
                data = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("A screenshot couldn't be read; try adding it again");
            }
            if (data.length == 0) throw new IllegalArgumentException("A screenshot is empty");
            if (data.length > MAX_PICTURE_BYTES) throw new IllegalArgumentException("A screenshot is too big (max 5 MB)");
            out.add(new BriefFile(p.name() == null || p.name().isBlank() ? "screenshot" : clip(p.name(), 100), type, data));
        }
        return out;
    }

    private String brand(Long opportunityId) {
        if (opportunityId == null) return null;
        return opportunities.findById(opportunityId).map(workflow::brandName).orElse(null);
    }

    private static List<String> platforms(CaptionSet s) {
        return s.platforms == null ? List.of() : Arrays.stream(s.platforms.split(",")).filter(p -> !p.isBlank()).toList();
    }

    private static List<Long> ids(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> out = new ArrayList<>();
        for (String v : csv.split(",")) {
            try {
                out.add(Long.parseLong(v.strip()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return out;
    }

    private static String field(String s) {
        String v = Untrusted.escape(s.strip()).replace("</untrusted_deal", "&lt;/untrusted_deal").replace('\n', ' ');
        return v.length() > 300 ? v.substring(0, 300) + "…" : v;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
