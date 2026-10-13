package com.creatorcrm.demo;

import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.llm.BrandSearchInput;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.ContractInput;
import com.creatorcrm.llm.ContractTerms;
import com.creatorcrm.llm.DraftInput;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.llm.ProfileDraft;
import com.creatorcrm.llm.ProfileDraftInput;
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

    /** The canned brand-facing version of a contract check line, for practice mode. */
    static String contractAsk(String point) {
        String p = point.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("in ads forever") || p.contains("paid usage")) return "Could we put a time limit on paid usage of the content, or add a fee for longer use?";
        if (p.contains("paid")) return "Could we make payment within 30 days of the invoice?";
        if (p.contains("exclusivity")) return "Could you tell me exactly which brands the exclusivity covers, and for how long?";
        if (p.contains("revision")) return "Could we cap revisions at 2 rounds?";
        if (p.contains("kill fee")) return "Could we add a kill fee, in case the campaign is cancelled after I've started?";
        if (p.contains("no fee")) return "Could you add the fee we agreed to the contract?";
        if (p.contains("fee") || p.contains("the deal is")) return "Could you check the fee? It doesn't match what we agreed.";
        return "Could you clarify this part: " + point;
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
        if ("DECLINE".equals(input.draftType())) {
            return new DraftText("Re: " + input.brandName() + " collab",
                    "Hi " + who + ",\n\nThank you so much for thinking of me! It isn't quite the right fit for me right now, "
                            + "but I'd love to stay in touch for future collaborations.\n\nAll the best,\nAva");
        }
        if ("NEGOTIATION".equals(input.draftType()) && input.extraInstructions().startsWith("Contract changes:")) {
            // extraInstructions: "Contract changes: ... <contract_points>\n1. ...\n</contract_points>" from ContractService.
            StringBuilder asks = new StringBuilder();
            int n = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\d+\\. (.+)$").matcher(input.extraInstructions());
            while (m.find()) asks.append(++n).append(". ").append(contractAsk(m.group(1))).append('\n');
            java.util.regex.Matcher own = java.util.regex.Pattern.compile("The creator's own note, to include: (.+?)(?: Still unknown in this deal|$)")
                    .matcher(input.extraInstructions());
            if (own.find()) asks.append(++n).append(". ").append(own.group(1).strip()).append('\n');
            return new DraftText("Re: " + input.brandName() + " contract",
                    "Hi " + who + ",\n\nThanks so much for sending the contract over! Before I sign, I had "
                            + (n == 1 ? "one thing" : "a few things") + " I wanted to check:\n\n" + asks
                            + "\nCould you send an updated version, or let me know your thoughts?\n\nThanks,\nAva");
        }
        if ("NEGOTIATION".equals(input.draftType())) {
            // extraInstructions: "Counter-offer: propose $1,250 for 1 Reel + 2 Stories, .... The creator chose ..." from RateAdvisor.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("propose (\\$[\\d,]+) for (.+?)\\. The creator")
                    .matcher(input.extraInstructions());
            boolean found = m.find();
            String amount = found ? m.group(1) : "[MY RATE]";
            String what = found ? m.group(2) : "this collab";
            return new DraftText("Re: " + input.brandName() + " collab",
                    "Hi " + who + ",\n\nThank you so much for the offer, I'd really love to make this happen! For " + what
                            + ", my rate is " + amount + ". That covers the content itself plus the extra reach "
                            + input.brandName() + " gets from running it as ads.\n\nHappy to talk it through if that helps.\n\nBest,\nAva");
        }
        if ("REPITCH".equals(input.draftType())) {
            // extraInstructions: "... Last collab: <what> (paid, $750), finished <Month Year>. ..." from WinBack.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Last collab: (.+?)(?: \\(([^)]*)\\))?, finished ([A-Za-z]+ \\d{4})")
                    .matcher(input.extraInstructions());
            boolean found = m.find();
            String what = found ? m.group(1) : "our last collab";
            String when = found ? " back in " + m.group(3) : "";
            boolean gifted = input.extraInstructions().contains("(gifted)");
            // " News hook: <brand> recently shared this (teaser, 6 October): <headline>. Open ... for example: <idea>. Then ..."
            java.util.regex.Matcher hook = java.util.regex.Pattern
                    .compile("News hook: .+? recently shared this \\([^)]*\\): (.+?)\\.? Open the email.*?(?:for example: (.+?))?\\. Then recall")
                    .matcher(input.extraInstructions());
            if (hook.find()) {
                String news = hook.group(1);
                String idea = hook.group(2) != null ? hook.group(2) : "a short Reel series built around it";
                return new DraftText(input.brandName() + " x Ava, round two?",
                        "Hi " + who + ",\n\nI just saw " + input.brandName() + " is " + Character.toLowerCase(news.charAt(0)) + news.substring(1)
                                + ", and I got so excited! The " + what + " we did together" + when + " is still one of my "
                                + "favourites, and my audience still asks about it.\n\nOne idea for the launch: "
                                + Character.toLowerCase(idea.charAt(0)) + idea.substring(1) + ". I'd love to make it happen"
                                + (gifted ? " as a paid collab" : "") + ".\n\nWould you be open to a quick chat?\n\nBest,\nAva");
            }
            return new DraftText("Another idea for " + input.brandName(),
                    "Hi " + who + ",\n\nI still think about the " + what + " we did together" + when + ". My audience loved it, "
                            + "and a few people still ask me about it! I'd love to work with " + input.brandName() + " again"
                            + (gifted ? ", this time as a paid collab" : "") + ". One idea: a 3-part Reel series showing how it fits "
                            + "into my weekly routine.\n\nWould you be open to a quick chat?\n\nBest,\nAva");
        }
        if ("RESULTS_RECAP".equals(input.draftType())) {
            // extraInstructions: "Results recap: ... Numbers: 18,450 accounts reached, .... Quote these ..." from CampaignResults.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Numbers: (.+?)\\. Quote").matcher(input.extraInstructions());
            String numbers = m.find() ? m.group(1) : "[NUMBERS]";
            boolean gifted = input.extraInstructions().contains("paid collab");
            return new DraftText("How our " + input.brandName() + " post did",
                    "Hi " + who + ",\n\nThank you again for the collab! I wanted to share how the post did after its first week: "
                            + numbers + ". The saves were the standout: people kept it to come back to.\n\n"
                            + "The full results are in the attached one-pager. I'd love to do more together"
                            + (gifted ? ", maybe a paid Reel series next" : "") + ".\n\nBest,\nAva");
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

    /** A canned edit per quick button, so the walkthrough shows the text really changing. */
    @Override
    public DraftText reviseDraft(DraftInput input, DraftText current, String request) {
        String r = request.toLowerCase(java.util.Locale.ROOT);
        String body = current.body();
        int greetingEnd = body.indexOf("\n\n");
        if (r.contains("short")) {
            String[] parts = body.split("\n\n");
            if (parts.length > 3) body = parts[0] + "\n\n" + parts[1] + "\n\n" + parts[parts.length - 1];
            int firstStop = body.indexOf(". ", greetingEnd < 0 ? 0 : greetingEnd);
            int paraEnd = body.indexOf("\n\n", greetingEnd + 2);
            if (firstStop > 0 && paraEnd > firstStop) body = body.substring(0, firstStop + 1) + body.substring(paraEnd);
        } else if (r.contains("warm")) {
            if (greetingEnd > 0) body = body.substring(0, greetingEnd + 2) + "I hope your week is off to a lovely start! "
                    + body.substring(greetingEnd + 2);
            body = body.replace("Best,", "Warmly,");
        } else if (r.contains("formal")) {
            body = body.replaceFirst("^Hi ", "Dear ").replace("Thanks so much", "Thank you very much")
                    .replace("I'd love to", "I would be glad to").replace("Best,", "Kind regards,");
        } else if (r.startsWith("mention ") && body.lastIndexOf("\n\n") > 0) {
            String add = request.strip().substring(8).strip();
            add = Character.toUpperCase(add.charAt(0)) + add.substring(1) + (add.endsWith(".") ? "" : ".");
            int signOff = body.lastIndexOf("\n\n");
            body = body.substring(0, signOff) + "\n\n" + add + body.substring(signOff);
        } else {
            body = body + "\n\n(Demo mode: Claude would apply \"" + request + "\" here.)";
        }
        return new DraftText(current.subject(), body);
    }

    /** Demo mode: anything found counts as news, mildly on-niche. Demo news is seeded already sorted (see DemoData). */
    @Override
    public com.creatorcrm.llm.NewsSort sortBrandNews(com.creatorcrm.llm.NewsSortInput input) {
        return new com.creatorcrm.llm.NewsSort(input.items().stream().map(i -> new com.creatorcrm.llm.NewsSort.Verdict(i.id(),
                true, com.creatorcrm.domain.BrandNews.Kind.LAUNCH, i.title(), "", 60)).toList());
    }

    /** Demo mode has no web: nothing found. */
    @Override
    public com.creatorcrm.llm.NewsFound searchBrandNews(com.creatorcrm.llm.NewsSearchInput input) {
        return new com.creatorcrm.llm.NewsFound(List.of());
    }

    /** Demo mode's ideas: Nova Nest Home's cozy-corner Reel (see DemoInbox), or a general set for any other deal. */
    @Override
    public com.creatorcrm.llm.ContentIdeaList contentIdeas(com.creatorcrm.llm.ContentIdeasInput input) {
        String brand = input.brandName();
        boolean nest = brand.contains("Nova Nest");
        String product = nest ? "the Nova Nest throw and lamp" : "the " + brand + " product";
        String tag = "@" + brand.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        String refresh = input.note() == null || input.note().isBlank() ? "" : " (" + input.note().strip() + ")";
        return new com.creatorcrm.llm.ContentIdeaList(
                nest ? "Nova Nest Home wants one paid Reel ($900) of a cozy reading corner styled with their throws and lamps. "
                        + "The draft Reel is due in 5 days, and there's a month of exclusivity for home and lifestyle brands after it goes live."
                        : brand + " wants content showing " + product + " in your everyday routine.",
                List.of("Tag " + tag + " in the caption and on the video", "Show " + product + " in the first 3 seconds",
                        "Say it's an ad: #ad in the caption and the paid-partnership label", nest ? "Send the draft Reel for approval before posting" : "Mention ___ (the key message from the brief)"),
                List.of("No other home or lifestyle brands in the video", "Don't name prices or discounts"),
                List.of(
                        new com.creatorcrm.llm.ContentIdeaList.Idea((nest ? "Empty corner to coziest spot" : "Before and after") + refresh, "Instagram Reel",
                                "\"This corner was wasted space. Watch.\"",
                                "A fast before-and-after: the bare corner, then you style it piece by piece until it's the coziest spot in the flat. Ends with you curled up reading.",
                                List.of("Wide shot: the empty corner, lights off", "Quick cuts: chair in, rug down, " + product + " unfolded",
                                        "Close-up: switch the lamp on, warm glow fills the frame", "Slow pan: the finished corner", "You settle in with a book and a mug"),
                                "Turned the saddest corner of my flat into my favourite place to read 🕯️ Styled with " + product + " " + tag + " #ad #cozycorner #readingnook",
                                "Your before-and-after room Reels get the most saves, and this one ends on the calm, cozy mood your audience loves."),
                        new com.creatorcrm.llm.ContentIdeaList.Idea("Sunday reset, 5pm", "Instagram Reel",
                                "\"My Sunday reset always ends here.\"",
                                "A slow, ASMR-style Sunday evening: tidy, tea, then light the corner and read. The product is part of the ritual, not the focus.",
                                List.of("Hands folding laundry, soft daylight", "Kettle on, pouring tea", "Fluff the cushions, drape " + product,
                                        "Lamp on as the light fades", "Book open, cup in hand, last frame held for 2 seconds"),
                                "The best part of my Sunday reset 🍵 " + tag + " #ad #sundayreset #slowliving",
                                "Your calm routine videos keep people watching to the end, which is what a brand wants from a Reel."),
                        new com.creatorcrm.llm.ContentIdeaList.Idea("3 ways to style one corner", "Instagram Reel",
                                "\"Same corner, three moods.\"",
                                "Style the corner three ways (morning coffee, work-from-home, movie night) to show how versatile the product is.",
                                List.of("Title card: 3 ways to style one corner", "Morning: daylight, coffee, throw folded neatly",
                                        "Work: laptop, lamp on, throw over the chair", "Night: blankets piled, lamp dimmed, film on"),
                                "Which one is your vibe: 1, 2 or 3? 👇 " + tag + " #ad #homestyling",
                                "A question in the caption invites comments, and your comment counts are strongest on posts that ask one."),
                        new com.creatorcrm.llm.ContentIdeaList.Idea("Honest first impressions", "Story set",
                                "\"Unboxing the cozy stuff Nova Nest sent me.\"".replace("Nova Nest", nest ? "Nova Nest" : brand),
                                "Three to five Stories: unbox, first touch, and where it's going in your home, with a poll for your followers.",
                                List.of("Box on the doorstep", "Unbox, close-up of the texture", "Poll sticker: which corner should it go in?", "Link sticker to the brand"),
                                "Stories have no caption: add " + tag + " and the paid-partnership label on each one.",
                                "Polls in your Stories get lots of taps, and they warm people up for the Reel.")));
    }

    /**
     * Demo mode's captions: built from what she typed, with the products whose names share a word with it (or the
     * first one when none do) when she let Claude pick.
     */
    @Override
    public com.creatorcrm.llm.CaptionDraft captions(com.creatorcrm.llm.CaptionInput input) {
        String about = input.about().strip();
        String lower = about.toLowerCase(java.util.Locale.ROOT);
        List<Integer> picked = new java.util.ArrayList<>();
        for (int i = 0; i < input.products().size(); i++) {
            String name = input.products().get(i).replaceFirst("^\\d+\\. ", "").replaceFirst(" \\(.*$", "").toLowerCase(java.util.Locale.ROOT);
            boolean match = java.util.Arrays.stream(name.split("[^a-z]+")).anyMatch(w -> w.length() >= 4 && lower.contains(w));
            if (!input.pick() || match) picked.add(i + 1);
        }
        if (input.pick() && picked.isEmpty() && !input.products().isEmpty()) picked.add(1);
        boolean shop = !picked.isEmpty();
        String funny = input.note() != null && input.note().toLowerCase(java.util.Locale.ROOT).contains("fun") ? " (and yes, I tripped over the rug twice 😂)" : "";
        String topic = about.length() > 60 ? about.substring(0, 60).replaceFirst("\\s+\\S*$", "") : about;
        String keyword = lower.contains("serum") || lower.contains("skin") ? "GLOW" : lower.contains("lamp") || lower.contains("cozy") ? "COZY" : "LINK";
        List<String> ps = input.platforms();
        return new com.creatorcrm.llm.CaptionDraft(
                capitalise(String.join(" ", java.util.Arrays.asList(topic.split("\\s+")).subList(0, Math.min(5, topic.split("\\s+").length)))),
                picked, keyword,
                List.of("Wait for the last shot 👀", "The 5-minute reset I swear by", "I didn't expect this to work"),
                ps.contains("YOUTUBE") ? new com.creatorcrm.llm.CaptionDraft.YouTube(
                        ("SHORT".equals(input.youtubeFormat()) ? "" : "My honest routine: ") + capitalise(topic),
                        capitalise(topic) + ", start to finish." + funny + "\n\nEverything I use is linked below, and I've added my favourite tips in the video. "
                                + "Tell me in the comments what you'd like to see next!",
                        List.of("cozy home", "self care routine", "everyday favourites", "what I use", "honest review", "routine"),
                        shop ? "Everything from this video is linked below 👇 Any questions, ask me here!" : "What should I try next? Tell me below!")
                        : new com.creatorcrm.llm.CaptionDraft.YouTube("", "", List.of(), ""),
                ps.contains("INSTAGRAM") ? new com.creatorcrm.llm.CaptionDraft.Instagram(
                        "The little routine that makes my whole day better ✨\n\n" + capitalise(topic) + "." + funny
                                + (shop ? "\n\nComment " + keyword + " and I'll send you the links 💌" : "\n\nSave this for later 🤍"),
                        List.of("#cozyhome", "#selfcare", "#everydayroutine", "#favouritefinds"),
                        shop ? "Everything I used is on the link 👆" : "New Reel is up! 🎬")
                        : new com.creatorcrm.llm.CaptionDraft.Instagram("", List.of(), ""),
                ps.contains("TIKTOK") ? new com.creatorcrm.llm.CaptionDraft.TikTok(
                        capitalise(topic) + " ✨" + (shop ? " Links in my bio!" : ""), List.of("#fyp", "#routine", "#cozytok"))
                        : new com.creatorcrm.llm.CaptionDraft.TikTok("", List.of()),
                shop ? "Hi! So happy you liked it 💕 Here's everything from the video:" : "Thanks for watching! 💕");
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ContractTerms extractContractTerms(ContractInput input) {
        // The demo contract is Bloomleaf Tea's (see DemoData): long payment wait, a year of ads, unlimited revisions.
        return new ContractTerms(60, "Net 60 from receipt of invoice", 650, "USD", 12,
                "Brand may use the content in paid social ads for 12 months", 2, "No other tea or coffee brands for 2 months",
                -1, "", List.of(new ContractTerms.ContractDate(input.today().plusDays(10).toString(), "Draft Reel to Bloomleaf for review")),
                List.of("You must keep the post up for at least 12 months"),
                "Bloomleaf Tea pays $650 for 1 Reel and 2 Stories for its October campaign, with a year of paid ads.");
    }

    @Override
    public PictureContacts readContactPicture(byte[] image, String mediaType) {
        return new PictureContacts(List.of(new com.creatorcrm.llm.ContactCards.Card("maya.chen@sunleafbotanics.com", "Maya Chen",
                "Influencer Partnerships Manager", "Sunleaf Botanics", "sunleafbotanics.com", "+1 (415) 555-0142",
                "sunleafbotanics", "")), 0.004);
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
    public ProfileDraft draftProfile(ProfileDraftInput input) {
        String name = input.creatorName().isBlank() ? "Ava" : input.creatorName();
        List<String> brands = input.deals().stream().map(d -> d.replaceFirst(" \\(.*", "")).distinct().limit(4).toList();
        String portfolio = input.links().stream().filter(l -> l.toLowerCase().matches(".*(portfolio|ugc|media kit).*"))
                .findFirst().map(l -> l.substring(l.indexOf(": ") + 2)).orElse("___");
        String profile = "# About me\n"
                + "- I make warm, practical skincare, wellness and healthy-food content: Reels, Stories and UGC videos brands can run as ads.\n"
                + "- Audience: mostly women 25–34 in the US who want simple routines that actually work.\n"
                + "- Platforms: Instagram ___, TikTok ___\n"
                + "- Portfolio and media kit: " + portfolio + "\n"
                + (brands.isEmpty() ? "" : "- Brands I've talked to or worked with: " + String.join(", ", brands) + "\n")
                + "\n# My voice\n"
                + "- Friendly and upbeat, but straight to the point.\n"
                + "- Short emails: a thank-you, one or two sentences on the idea, then a clear next step.\n"
                + "- I open with \"Hi <first name>,\" and never use emoji in emails.\n"
                + "- Confident about rates; I never apologise for them.\n"
                + "- Sign-off: \"Best, " + name + "\"\n"
                + "\n# Rates (drafts will only quote what is written here)\n"
                + "- 1 Instagram Reel: $800\n"
                + "- 3-frame Story set: $300\n"
                + "- UGC video, no posting (30–60s): $___\n"
                + "- Usage rights / whitelisting: +30% per 30 days\n"
                + "\n# Rules\n"
                + "- Gifted-only collabs only for products I already love; never for Reels.\n"
                + "- Budget, deliverables, timeline and usage rights must be clear before I say yes.\n"
                + "- Payment terms: net 30 or better; 50% upfront for projects over $1,000.\n";
        return new ProfileDraft(profile, List.of(
                "Add your Instagram and TikTok handles and follower numbers.",
                "Fill in your UGC video rate; none of your deals showed one.",
                "The Reel and Story rates come from your Bloomleaf Tea deal; check they're still right."));
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
