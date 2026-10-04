package com.creatorcrm.contracts;

import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Contract;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.llm.ContractInput;
import com.creatorcrm.llm.ContractTerms;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.ContractRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Contract check: when a brand sends a contract, the PDF's text is read, Claude pulls out the terms, and rules in code
 * hold them against her limits. Red and amber flags become a "Review contract" task. Contracts behind an e-signature
 * link can't be opened by the app, so she gets a task to read it and can paste the text to have it checked.
 */
@Service
public class ContractService {
    private static final Logger log = LoggerFactory.getLogger(ContractService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int MAX_FILES = 3;
    static final long MAX_BYTES = 15L * 1024 * 1024;
    /** Pasted contract text: anything shorter can't be a contract. */
    static final int MIN_PASTED = 200;

    private static final Pattern SIGN_LINK = Pattern.compile(
            "(?i)\\b(docusign|hellosign|dropbox sign|pandadoc|adobe ?sign|echosign|signnow|signwell|docs\\.google\\.com)\\b");

    /** What the deal drawer and MCP show for one contract. */
    public record View(Long id, String fileName, String source, String status, String note, ContractTerms terms,
                       List<ContractCheck.Flag> flags, long red, long amber, OffsetDateTime checkedAt) {}

    private final ContractRepo contracts;
    private final OpportunityRepo opportunities;
    private final ConversationRepo conversations;
    private final GmailConnector gmail;
    private final LlmClient llm;
    private final WorkflowEngine workflow;
    private final SettingsService settings;
    private final ActivityRepo activity;

    public ContractService(ContractRepo contracts, OpportunityRepo opportunities, ConversationRepo conversations,
                           GmailConnector gmail, LlmClient llm, WorkflowEngine workflow, SettingsService settings,
                           ActivityRepo activity) {
        this.contracts = contracts;
        this.opportunities = opportunities;
        this.conversations = conversations;
        this.gmail = gmail;
        this.llm = llm;
        this.workflow = workflow;
        this.settings = settings;
        this.activity = activity;
    }

    /** A brand's message was read as "contract sent": read its PDFs, or note the signing link. Never throws. */
    public void onContractSent(Message m, Opportunity o) {
        if (contracts.existsByMessageId(m.id)) return;
        try {
            Conversation conv = conversations.findById(m.conversationId).orElse(null);
            List<GmailConnector.FileAttachment> pdfs = conv != null && conv.platform == Platform.EMAIL && gmail.isConnected()
                    && m.externalId != null ? gmail.pdfAttachments(gmailId(m.externalId), MAX_FILES, MAX_BYTES) : List.of();
            for (GmailConnector.FileAttachment pdf : pdfs) fromPdf(o, m.id, pdf.fileName(), pdf.data());
            if (pdfs.isEmpty()) linkOnly(o, m);
        } catch (Exception e) {
            log.warn("Could not read the contract in message {}: {}", m.id, e.getMessage());
            Contract c = blank(o.id, m.id, null, Contract.Source.PDF);
            c.status = Contract.Status.FAILED;
            c.error = clip("Couldn't download the contract from Gmail: " + e.getMessage(), 500);
            contracts.save(c);
        }
    }

    Contract fromPdf(Opportunity o, Long messageId, String fileName, byte[] data) {
        Contract c = blank(o.id, messageId, fileName, Contract.Source.PDF);
        String text;
        try {
            text = PdfText.extract(data);
        } catch (IOException e) {
            c.status = Contract.Status.UNREADABLE;
            c.error = e.getClass().getSimpleName().contains("Password")
                    ? "The PDF is password-protected, so the app can't read it." : "The PDF couldn't be opened.";
            reviewTask(o, "Read " + workflow.brandName(o) + "'s contract yourself: " + c.error);
            return contracts.save(c);
        }
        if (PdfText.looksScanned(text)) {
            c.status = Contract.Status.UNREADABLE;
            c.error = "It looks like a scan, so there's no text to read. You can paste the text on the deal.";
            reviewTask(o, "Read " + workflow.brandName(o) + "'s contract yourself: it's a scanned PDF");
            return contracts.save(c);
        }
        c.textContent = text;
        return check(o, c);
    }

    private void linkOnly(Opportunity o, Message m) {
        String body = m.content == null ? "" : m.content;
        Matcher link = SIGN_LINK.matcher(body);
        Contract c = blank(o.id, m.id, null, Contract.Source.LINK);
        c.status = Contract.Status.LINK_ONLY;
        String service = link.find() ? pretty(link.group(1)) : null;
        c.error = (service == null ? "The contract isn't attached as a PDF" : "The contract is on " + service)
                + ", which the app can't open. Paste its text here to have it checked.";
        contracts.save(c);
        reviewTask(o, "Open " + workflow.brandName(o) + "'s contract" + (service == null ? "" : " on " + service)
                + " and read it before signing, or paste its text on the deal to check it");
    }

    /** Messages are stored as "email:&lt;Gmail id&gt;". */
    static String gmailId(String externalId) {
        return externalId.startsWith("email:") ? externalId.substring("email:".length()) : externalId;
    }

    /** She pasted the contract's text, e.g. from a DocuSign page. */
    public View checkPasted(Long opportunityId, String text) {
        if (text == null || text.strip().length() < MIN_PASTED) {
            throw new IllegalArgumentException("Paste the whole contract text (at least a few paragraphs)");
        }
        Opportunity o = opportunities.findById(opportunityId).orElseThrow();
        Contract c = blank(o.id, null, "Pasted text", Contract.Source.PASTED);
        String t = text.strip();
        c.textContent = t.length() > PdfText.MAX_CHARS ? t.substring(0, PdfText.MAX_CHARS) : t;
        return view(check(o, c));
    }

    /** Check again, e.g. after she changed her limits. Uses the stored terms, so no new Claude call. */
    public View recheck(Long contractId) {
        Contract c = contracts.findById(contractId).orElseThrow();
        Opportunity o = opportunities.findById(c.opportunityId).orElseThrow();
        ContractTerms terms = terms(c);
        if (terms == null) return view(c);
        List<ContractCheck.Flag> flags = ContractCheck.check(terms, limits(), o.budgetAmount);
        c.flagsJson = write(flags);
        c.checkedAt = OffsetDateTime.now();
        return view(contracts.save(c));
    }

    private Contract check(Opportunity o, Contract c) {
        try {
            ContractTerms terms = llm.extractContractTerms(new ContractInput(settings.today(), workflow.brandName(o),
                    dealContext(o), wrap(c.textContent)));
            List<ContractCheck.Flag> flags = ContractCheck.check(terms, limits(), o.budgetAmount);
            c.termsJson = write(terms);
            c.flagsJson = write(flags);
            c.status = Contract.Status.CHECKED;
            c.checkedAt = OffsetDateTime.now();
            contracts.save(c);
            long red = ContractCheck.count(flags, ContractCheck.Level.RED);
            long amber = ContractCheck.count(flags, ContractCheck.Level.AMBER);
            activity.save(Activity.of(o.id, Activity.CONTRACT, "Contract checked: " + summary(red, amber)));
            if (red + amber > 0) {
                reviewTask(o, "Review " + workflow.brandName(o) + "'s contract: " + summary(red, amber));
            }
            return c;
        } catch (RuntimeException e) {
            log.warn("Contract check failed for deal {}: {}", o.id, e.getMessage());
            c.status = Contract.Status.FAILED;
            c.error = clip("Claude couldn't read the terms: " + e.getMessage(), 500);
            return contracts.save(c);
        }
    }

    public List<View> forDeal(Long opportunityId) {
        return contracts.findByOpportunityIdOrderByIdDesc(opportunityId).stream().map(this::view).toList();
    }

    View view(Contract c) {
        List<ContractCheck.Flag> flags = read(c.flagsJson, new TypeReference<List<ContractCheck.Flag>>() {});
        if (flags == null) flags = List.of();
        return new View(c.id, c.fileName, c.source.name(), c.status.name(), c.error, terms(c), flags,
                ContractCheck.count(flags, ContractCheck.Level.RED), ContractCheck.count(flags, ContractCheck.Level.AMBER), c.checkedAt);
    }

    ContractCheck.Limits limits() {
        return new ContractCheck.Limits(settings.contractMaxPaymentDays(), settings.contractFreeUsageMonths(),
                settings.contractRevisionsIncluded());
    }

    static String summary(long red, long amber) {
        List<String> parts = new ArrayList<>();
        if (red > 0) parts.add(red + (red == 1 ? " thing to push back on" : " things to push back on"));
        if (amber > 0) parts.add(amber + (amber == 1 ? " thing to look at" : " things to look at"));
        return parts.isEmpty() ? "nothing stands out" : String.join(", ", parts);
    }

    /** The contract as data for Claude; it can't close the tag early to pass off instructions as ours. */
    static String wrap(String text) {
        String safe = text.replace("<untrusted_contract", "&lt;untrusted_contract").replace("</untrusted_contract", "&lt;/untrusted_contract");
        return "<untrusted_contract>\n" + safe + "\n</untrusted_contract>";
    }

    private static String dealContext(Opportunity o) {
        StringBuilder sb = new StringBuilder();
        if (o.budgetText != null && !o.budgetText.isBlank()) sb.append("agreed budget: ").append(o.budgetText).append("; ");
        if (o.deliverables != null && !o.deliverables.isBlank()) sb.append("deliverables: ").append(o.deliverables).append("; ");
        if (o.usageRights != null && !o.usageRights.isBlank()) sb.append("usage discussed: ").append(o.usageRights);
        return sb.toString().strip();
    }

    private void reviewTask(Opportunity o, String description) {
        workflow.upsertTask(o, TaskType.REVIEW_CONTRACT, clip(description, 1000), Priority.HIGH, settings.today().plusDays(1), null);
    }

    private static Contract blank(Long opportunityId, Long messageId, String fileName, Contract.Source source) {
        Contract c = new Contract();
        c.opportunityId = opportunityId;
        c.messageId = messageId;
        c.fileName = fileName == null ? null : clip(fileName, 300);
        c.source = source;
        c.createdAt = OffsetDateTime.now();
        return c;
    }

    private static ContractTerms terms(Contract c) {
        return read(c.termsJson, new TypeReference<ContractTerms>() {});
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Stored contract data is damaged: {}", e.getMessage());
            return null;
        }
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pretty(String service) {
        String s = service.toLowerCase(Locale.ROOT);
        if (s.startsWith("docs.google")) return "Google Docs";
        if (s.startsWith("docusign")) return "DocuSign";
        if (s.startsWith("hellosign") || s.startsWith("dropbox")) return "Dropbox Sign";
        if (s.startsWith("pandadoc")) return "PandaDoc";
        if (s.startsWith("adobe") || s.startsWith("echosign")) return "Adobe Sign";
        if (s.startsWith("signnow")) return "signNow";
        return "SignWell";
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** For tests and demo data: check this text as if it came from a PDF. */
    public View checkText(Long opportunityId, String fileName, String text) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow();
        Contract c = blank(o.id, null, fileName, Contract.Source.PDF);
        c.textContent = text;
        return view(check(o, c));
    }
}
