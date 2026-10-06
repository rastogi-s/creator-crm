package com.creatorcrm.demo;

import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.llm.StageSeen;
import com.creatorcrm.llm.MessageAnalysis.ExtractedDeadline;
import com.creatorcrm.llm.MessageAnalysis.TaskLink;
import java.time.LocalDate;
import java.util.List;

/** Made-up brands and emails for demo mode and the walkthrough videos. No real people or companies. */
final class DemoInbox {
    private DemoInbox() {}

    record Mail(String brand, String contact, String email, int daysAgo, String subject, String body) {}

    static final List<Mail> MAILS = List.of(
            new Mail("Glowberry Skin", "Priya", "priya@glowberry.example", 1, "Paid Reel for our autumn serum",
                    "Hi Ava! Glowberry Skin here. We'd love a paid Reel for our autumn serum launch. Budget is $800. Could you share your availability?"),
            new Mail("Peak Trail Co", "Marcus", "marcus@peaktrail.example", 2, "Rates for a hiking series?",
                    "Hey Ava, Peak Trail Co is planning a 3-video hiking series. What are your rates for TikTok plus Reels?"),
            new Mail("Bloomleaf Tea", "Jenna", "jenna@bloomleaf.example", 3, "Contract for the October campaign",
                    "Hi Ava, attached is the contract for the Bloomleaf Tea October campaign ($650). Please sign by the date in the agreement."),
            new Mail("Nova Nest Home", "Sam", "sam@novanest.example", 4, "Creative brief: cozy corner",
                    "Hi Ava, here's the Nova Nest Home brief for the cozy-corner Reel: https://drive.novanest.example/cozy-corner-brief. Draft content is due soon. As agreed, please don't post for other home or lifestyle brands for a month after it goes live."),
            new Mail("Thread & Thimble", "Lee", "lee@threadthimble.example", 1, "Gifted collab: new knit line",
                    "Hello Ava! Thread & Thimble would love to send you our new knit line as a gifted collab."),
            new Mail("Lumen Labs", "Ria", "ria@lumenlabs.example", 6, "Invoice for the September Reel",
                    "Hi Ava, Lumen Labs here. The September Reel performed great! Please send your invoice for the $1,200 fee."),
            new Mail("Juniper Juice", "Dana", "dana@juniperjuice.example", 1, "Payment for the summer Reel",
                    "Hi Ava, Dana from Juniper Juice. Our finance team sent the $500 payment for the summer Reel yesterday. Let us know when it lands!"),
            new Mail("Maple & Moss", "Sky", "sky@maplemoss.example", 50, "Your August Reel is approved",
                    "Hi Ava, Sky from Maple & Moss. The August Reel looks great, approved! Go ahead and post it."),
            new Mail("Coastline Coffee", "Noor", "noor@coastlinecoffee.example", 100, "Spring Reel payment",
                    "Hi Ava, Noor from Coastline Coffee. The $900 payment for the Spring Reel is on its way. Thanks again, it did so well for us!"),
            new Mail("Sparkle Socks", "Jay", "jay@sparklesocks.example", 0, "Affiliate partnership",
                    "Hi Ava! Jay from Sparkle Socks. We'd love you to join our affiliate program: 15% commission on every sale through your link. Sign up with the creator form here: https://sparklesocks.example/creators/apply"),
            new Mail("Tiny Treats", "Bea", "bea@tinytreats.example", 0, "Quick Reel for Tiny Treats?",
                    "Hi Ava, Bea at Tiny Treats here. We have $100 for one Reel and three Stories about our new snack box. Interested?"),
            new Mail("Fern & Field", "Theo", "theo@fernfield.example", 1, "Re: Spring planter campaign",
                    "Hi Ava, Theo from Fern & Field here. Thanks for getting back to us! We can offer $600 for one Reel and two Stories, and we'd like to run the Reel as an ad for 3 months. Does that work?"),
            new Mail("Petal & Pine", "Mia", "mia@petalpine.example", 25, "Loved your candle post",
                    "Hi Ava, Mia at Petal & Pine. We saw your post with our candle gift set, it looks lovely! Thanks so much for sharing it."));

    static MessageAnalysis analysisFor(String text, LocalDate today) {
        String t = text == null ? "" : text;
        if (t.contains("Glowberry")) return a("Glowberry Skin", "Priya", Intent.NEW_OPPORTUNITY, OpportunityType.PAID, Compensation.PAID, 800,
                "1 Reel", "Autumn serum", List.of(), List.of("usage rights", "posting date"), true, Priority.HIGH,
                "Reply to Glowberry Skin with availability", "Glowberry Skin offers $800 for a Reel for their autumn serum launch.");
        if (t.contains("Peak Trail")) return a("Peak Trail Co", "Marcus", Intent.RATES_REQUEST, OpportunityType.RATES_REQUEST, Compensation.PAID, 0,
                "3 videos (TikTok + Reels)", "Hiking series", List.of(), List.of("budget"), true, Priority.HIGH,
                "Send Peak Trail Co your rates", "Peak Trail Co asks for rates for a 3-video hiking series.");
        if (t.contains("Bloomleaf")) return a("Bloomleaf Tea", "Jenna", Intent.CONTRACT_SENT, OpportunityType.PAID, Compensation.PAID, 650,
                "1 Reel + 2 Stories", "October campaign",
                List.of(new ExtractedDeadline(DeadlineType.CONTRACT, today.plusDays(2).toString(), "Sign the contract")),
                List.of(), true, Priority.HIGH, "Sign the Bloomleaf Tea contract", "Bloomleaf Tea sent the $650 October campaign contract.");
        if (t.contains("Nova Nest")) return withTask(a("Nova Nest Home", "Sam", Intent.CONTENT_BRIEF, OpportunityType.PAID, Compensation.PAID, 900,
                "1 Reel", "1 month exclusivity for home and lifestyle brands", "Cozy corner",
                List.of(new ExtractedDeadline(DeadlineType.CONTENT_DUE, today.plusDays(5).toString(), "Draft Reel due")),
                List.of(), false, Priority.MEDIUM, "Film the Nova Nest Home cozy-corner Reel", "Nova Nest Home sent the cozy-corner brief."),
                "Nova Nest Home wants one paid Reel ($900) showing a cozy reading corner styled with their throws and lamps. "
                        + "The brief has the shot list and talking points. The draft Reel is due in 5 days.",
                new TaskLink("Cozy-corner brief", "https://drive.novanest.example/cozy-corner-brief"));
        if (t.contains("Thread & Thimble")) return a("Thread & Thimble", "Lee", Intent.NEW_OPPORTUNITY, OpportunityType.GIFTED, Compensation.GIFTED, 0,
                "", "Knit line", List.of(), List.of("deliverables"), true, Priority.LOW,
                "Decide on the Thread & Thimble gifted offer", "Thread & Thimble offers their knit line as a gifted collab.");
        if (t.contains("Lumen Labs")) return a("Lumen Labs", "Ria", Intent.INVOICE_REQUEST, OpportunityType.PAID, Compensation.PAID, 1200,
                "1 Reel", "September Reel", List.of(), List.of(), true, Priority.HIGH,
                "Send Lumen Labs the invoice", "Lumen Labs asks for the invoice for the $1,200 September Reel.");
        if (t.contains("Juniper Juice")) return a("Juniper Juice", "Dana", Intent.PAYMENT_UPDATE, OpportunityType.PAID, Compensation.PAID, 500,
                "1 Reel", "Summer Reel", List.of(), List.of(), false, Priority.MEDIUM,
                "Check that Juniper Juice's $500 payment arrived, then mark the invoice paid", "Juniper Juice says the $500 payment for the summer Reel was sent.");
        if (t.contains("Maple & Moss")) return a("Maple & Moss", "Sky", Intent.CONTENT_APPROVED, OpportunityType.PAID, Compensation.PAID, 750,
                "1 Reel", "August Reel", List.of(), List.of(), false, Priority.MEDIUM,
                "Post the Maple & Moss Reel", "Maple & Moss approved the August Reel.");
        if (t.contains("Sparkle Socks")) return withTask(a("Sparkle Socks", "Jay", Intent.NEW_OPPORTUNITY, OpportunityType.AFFILIATE, Compensation.AFFILIATE, 0,
                "", "Affiliate program", List.of(), List.of("budget"), true, Priority.LOW,
                "Reply to Sparkle Socks about their affiliate program", "Sparkle Socks offers 15% commission as an affiliate."),
                "Sparkle Socks invites you to their affiliate program: 15% commission on every sale through your own link, "
                        + "no fee and no set posts. Joining means filling in their creator form (your handles and audience). "
                        + "Decide whether commission-only is worth it before you sign up.",
                new TaskLink("Creator sign-up form", "https://sparklesocks.example/creators/apply"));
        if (t.contains("Tiny Treats")) return a("Tiny Treats", "Bea", Intent.NEW_OPPORTUNITY, OpportunityType.PAID, Compensation.PAID, 100,
                "1 Reel + 3 Stories", "Snack box", List.of(), List.of(), true, Priority.LOW,
                "Reply to Tiny Treats about their $100 offer", "Tiny Treats offers $100 for a Reel and three Stories.");
        if (t.contains("Coastline Coffee")) return a("Coastline Coffee", "Noor", Intent.PAYMENT_UPDATE, OpportunityType.PAID, Compensation.PAID, 900,
                "1 Reel", "Spring Reel", List.of(), List.of(), false, Priority.LOW,
                "Check that Coastline Coffee's $900 payment arrived", "Coastline Coffee says the $900 payment for the Spring Reel is on its way.");
        if (t.contains("Fern & Field")) return a("Fern & Field", "Theo", Intent.NEGOTIATION, OpportunityType.PAID, Compensation.PAID, 600,
                "1 Reel + 2 Stories", "3 months paid usage (Reel run as ads)", "Spring planters", List.of(), List.of("payment terms"), true,
                Priority.HIGH, "Answer Fern & Field's $600 offer", "Fern & Field offers $600 for a Reel and two Stories, with the Reel run as an ad for 3 months.");
        if (t.contains("Petal & Pine")) return a("Petal & Pine", "Mia", Intent.CONTENT_POSTED, OpportunityType.GIFTED, Compensation.GIFTED, 0,
                "1 post", "Candle gift set", List.of(), List.of(), false, Priority.LOW,
                "", "Petal & Pine thanked Ava for posting their candle gift set.");
        return new MessageAnalysis(false, "", "", Intent.NOT_BRAND_RELATED, OpportunityType.OTHER, Compensation.UNKNOWN, 0, "",
                "", "", "", "", List.of(), List.of(), false, Priority.LOW, "", "", "", List.of(), StageSeen.UNCLEAR);
    }

    private static MessageAnalysis a(String brand, String contact, Intent intent, OpportunityType type, Compensation comp,
                                     double budget, String deliverables, String campaign, List<ExtractedDeadline> deadlines,
                                     List<String> missing, boolean reply, Priority urgency, String action, String summary) {
        return a(brand, contact, intent, type, comp, budget, deliverables, "", campaign, deadlines, missing, reply, urgency, action, summary);
    }

    private static MessageAnalysis a(String brand, String contact, Intent intent, OpportunityType type, Compensation comp,
                                     double budget, String deliverables, String usageRights, String campaign,
                                     List<ExtractedDeadline> deadlines, List<String> missing, boolean reply, Priority urgency,
                                     String action, String summary) {
        return new MessageAnalysis(true, brand, contact, intent, type, comp, budget, budget > 0 ? "USD" : "",
                budget > 0 ? "$" + (int) budget : "", deliverables, usageRights, campaign, deadlines, missing, reply, urgency, action, summary, "", List.of(), StageSeen.UNCLEAR);
    }

    private static MessageAnalysis withTask(MessageAnalysis x, String brief, TaskLink... links) {
        return new MessageAnalysis(x.brandRelated(), x.brandName(), x.contactName(), x.intent(), x.opportunityType(),
                x.compensation(), x.budgetAmount(), x.currency(), x.budgetText(), x.deliverables(), x.usageRights(),
                x.campaign(), x.deadlines(), x.missingInfo(), x.requiresReply(), x.urgency(), x.suggestedAction(),
                x.updatedSummary(), brief, List.of(links), x.dealStage());
    }
}
