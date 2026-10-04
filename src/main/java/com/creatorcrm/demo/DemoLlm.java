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
