package com.creatorcrm.demo;

import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.llm.BrandSearchInput;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.DraftInput;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.MessageAnalysis;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: answers from the scripted demo inbox instead of calling Claude. */
@Component
@Primary
@Profile("demo")
public class DemoLlm implements LlmClient {

    @Override
    public MessageAnalysis classify(ClassificationInput input) {
        return DemoInbox.analysisFor(input.newMessage(), input.today());
    }

    @Override
    public DraftText writeDraft(DraftInput input) {
        String who = input.contactName() == null || input.contactName().isBlank() ? "there" : input.contactName();
        if ("PAYMENT_REMINDER".equals(input.draftType())) {
            // extraInstructions: "Payment reminder N of M for invoice INV-... (USD ...), which was due ... and is N days late. ..."
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("invoice (\\S+) \\(([^)]+)\\), which was due ([^.]+?)(?: and is| \\.|\\.)").matcher(input.extraInstructions());
            String what = m.find() ? "invoice " + m.group(1) + " for " + m.group(2) + ", which was due " + m.group(3) : "my invoice";
            return new DraftText("Following up on " + (m.find(0) ? m.group(1) : "my invoice"),
                    "Hi " + who + ",\n\nJust following up on " + what + ". Could you let me know when I can expect the payment? "
                            + "I've attached the invoice again.\n\nThanks so much,\nAva");
        }
        if ("REPITCH".equals(input.draftType())) {
            // extraInstructions: "... Last collab: <what> (paid, $750), finished <Month Year>. ..." from WinBack.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Last collab: (.+?)(?: \\(([^)]*)\\))?, finished ([A-Za-z]+ \\d{4})")
                    .matcher(input.extraInstructions());
            boolean found = m.find();
            String what = found ? m.group(1) : "our last collab";
            String when = found ? " back in " + m.group(3) : "";
            boolean gifted = input.extraInstructions().contains("(gifted)");
            return new DraftText("Another idea for " + input.brandName(),
                    "Hi " + who + ",\n\nI still think about the " + what + " we did together" + when + ". My audience loved it, "
                            + "and a few people still ask me about it! I'd love to work with " + input.brandName() + " again"
                            + (gifted ? ", this time as a paid collab" : "") + ". One idea: a 3-part Reel series showing how it fits "
                            + "into my weekly routine.\n\nWould you be open to a quick chat?\n\nBest,\nAva");
        }
        if ("INVOICE".equals(input.draftType())) {
            // extraInstructions starts "Send invoice INV-... for USD ..., due ....", written by InvoiceService.
            String what = input.extraInstructions();
            int end = what.indexOf(". ");
            what = (end > 0 ? what.substring(0, end + 1) : what).replace("Send invoice", "Here's invoice");
            return new DraftText("Invoice for the " + input.brandName() + " collab",
                    "Hi " + who + ",\n\nThanks again, I loved working on this one! " + what
                            + " The PDF is attached.\n\nBest,\nAva");
        }
        return new DraftText("Re: " + input.brandName() + " collab",
                "Hi " + who + ",\n\nThanks so much for thinking of me! I'd love to work with " + input.brandName()
                        + ". For one Reel with 30 days of organic usage my rate is [RATE FOR 1 REEL].\n\nBest,\nAva");
    }

    @Override
    public BrandLeads findBrands(BrandSearchInput input) {
        List<BrandLeads.Lead> sample = List.of(
                new BrandLeads.Lead("Dewdrop Skin", "https://dewdrop.example", "dewdropskin", "creators@dewdrop.example",
                        "https://dewdrop.example/creators", "Runs a creator program for skincare routines.",
                        "A 7-day morning routine series with their gel cleanser"),
                new BrandLeads.Lead("Trailmix Co", "https://trailmix.example", "trailmixco", "",
                        "", "Sponsors outdoor and wellness creators of a similar size.",
                        "A weekend hike vlog featuring their snack packs"));
        return new BrandLeads(sample.stream()
                .filter(l -> input.excludeBrands().stream().noneMatch(l.name()::equalsIgnoreCase))
                .limit(Math.max(1, input.count())).toList());
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
