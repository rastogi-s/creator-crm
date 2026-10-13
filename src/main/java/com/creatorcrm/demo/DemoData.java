package com.creatorcrm.demo;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.calendar.CalendarSync;
import com.creatorcrm.domain.BrandNews;
import com.creatorcrm.domain.CampaignResult;
import com.creatorcrm.dms.DmReminders;
import com.creatorcrm.repo.CampaignResultRepo;
import com.creatorcrm.results.CampaignResults;
import com.creatorcrm.contracts.ContractService;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.invoices.InvoiceService;
import com.creatorcrm.invoices.PaymentReminders;
import com.creatorcrm.rebook.WinBack;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandNewsRepo;
import com.creatorcrm.repo.ContractRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.security.AppUser;
import com.creatorcrm.security.AppUserRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.OutreachService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo mode ({@code --spring.profiles.active=demo}): a throwaway install with made-up deals and a fixed login,
 * used to record the walkthrough videos. Never point it at a real data folder.
 */
@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoData.class);
    public static final String USERNAME = "demo";
    public static final String PASSWORD = "demo-password-123";

    private final AppUserRepo users;
    private final PasswordEncoder encoder;
    private final SettingsService settings;
    private final IngestionService ingestion;
    private final OutreachService outreach;
    private final InvoiceService invoices;
    private final OpportunityRepo opportunities;
    private final WorkflowEngine workflow;
    private final InvoiceRepo invoiceRepo;
    private final PaymentReminders paymentReminders;
    private final ActivityRepo activity;
    private final WinBack winBack;
    private final ContractService contracts;
    private final ContractRepo contractRepo;
    private final CalendarSync calendar;
    private final CampaignResults results;
    private final CampaignResultRepo resultRepo;
    private final MessageRepo messages;
    private final BrandNewsRepo brandNews;
    private final DmReminders dmReminders;
    private final com.creatorcrm.shop.ProductService products;

    /** Instagram DMs: who, hours ago, from her or not, text. Claude reads the brand ones as leads (see DemoInbox). */
    record Dm(String handle, int hoursAgo, boolean mine, String text) {}

    static final List<Dm> DMS = List.of(
            new Dm("glowhaus.skin", 9, false, "Hi Ava! We're Glow Haus and we love your skincare routines. Would you be open to a paid Reel for our new SPF?"),
            new Dm("thelittlecandleco", 20, false, "Hey! I run a small candle shop and wondered if you ever feature small businesses? Happy to send a few candles."),
            new Dm("mia.bakes", 2, false, "omg your last reel was so good!! where is that mug from?"),
            new Dm("urbanroots.coffee", 50, false, "Hi Ava, Urban Roots here. Could you send your media kit? We're planning a cold brew push."),
            new Dm("urbanroots.coffee", 30, true, "Hi Kai! Of course, here it is: avarivera.example/media-kit. Happy to chat ideas."));

    static final String DEMO_CONTRACT = """
            INFLUENCER AGREEMENT between Bloomleaf Tea Co. ("Brand") and Ava Rivera ("Creator").
            1. Services. Creator will produce one (1) Instagram Reel and two (2) Instagram Stories featuring Bloomleaf's
            October blends. Creator will make revisions as requested by Brand until the content is approved.
            2. Fee. Brand will pay Creator USD 650. Payment is due net 60 from receipt of Creator's invoice.
            3. Usage. Brand may use the content in paid social advertising for twelve (12) months from first posting.
            4. Exclusivity. For two (2) months after posting, Creator will not promote other tea or coffee brands.
            5. Term. Creator will keep the Reel live on her profile for at least twelve (12) months.
            """;

    public DemoData(AppUserRepo users, PasswordEncoder encoder, SettingsService settings, IngestionService ingestion,
                    OutreachService outreach, InvoiceService invoices, OpportunityRepo opportunities,
                    WorkflowEngine workflow, InvoiceRepo invoiceRepo, PaymentReminders paymentReminders,
                    ActivityRepo activity, WinBack winBack, ContractService contracts, ContractRepo contractRepo,
                    CalendarSync calendar, CampaignResults results, CampaignResultRepo resultRepo, MessageRepo messages,
                    BrandNewsRepo brandNews,
                    DmReminders dmReminders, com.creatorcrm.shop.ProductService products) {
        this.products = products;
        this.brandNews = brandNews;
        this.dmReminders = dmReminders;
        this.messages = messages;
        this.calendar = calendar;
        this.results = results;
        this.resultRepo = resultRepo;
        this.contracts = contracts;
        this.contractRepo = contractRepo;
        this.activity = activity;
        this.winBack = winBack;
        this.invoiceRepo = invoiceRepo;
        this.paymentReminders = paymentReminders;
        this.invoices = invoices;
        this.opportunities = opportunities;
        this.workflow = workflow;
        this.users = users;
        this.encoder = encoder;
        this.settings = settings;
        this.ingestion = ingestion;
        this.outreach = outreach;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        AppUser u = new AppUser();
        u.username = USERNAME;
        u.passwordHash = encoder.encode(PASSWORD);
        u.createdAt = OffsetDateTime.now();
        users.save(u);
        settings.update(Map.of(SettingsService.CREATOR_NAME, "Ava",
                SettingsService.INVOICE_BUSINESS_NAME, "Ava Rivera Studio",
                SettingsService.INVOICE_ADDRESS, "12 Example Street\nSpringfield, 00000",
                SettingsService.INVOICE_PAYMENT_DETAILS, "Bank transfer to Example Bank\nAccount 0000 0000 (demo)"));

        OffsetDateTime now = OffsetDateTime.now();
        int i = 0;
        for (DemoInbox.Mail m : DemoInbox.MAILS) {
            i++;
            ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, "demo-" + i, "demo-thread-" + i, Direction.INBOUND,
                    m.email(), m.contact(), "ava@creator.example", m.email(), m.subject(), m.body(),
                    "<demo-" + i + "@creator.example>", "", now.minusDays(m.daysAgo()).minusHours(i), false)));
            String html = DemoInbox.HTML.get(m.brand());
            if (html != null) messages.findByExternalId("email:demo-" + i).ifPresent(saved -> messages.saveHtml(saved.id, html));
        }
        int d = 0;
        for (Dm dm : DMS) {
            d++;
            ingestion.store(List.of(new NormalizedMessage(Platform.INSTAGRAM, "demo-dm-" + d, "ig:" + dm.handle(),
                    dm.mine() ? Direction.OUTBOUND : Direction.INBOUND, dm.mine() ? "ava.makes" : dm.handle(),
                    dm.mine() ? "ava.makes" : dm.handle(), dm.mine() ? dm.handle() : "ava.makes", dm.handle(), "", dm.text(),
                    "", "", now.minusHours(dm.hoursAgo()), false)));
        }
        ingestion.processPending();
        outreach.logPitch(new OutreachService.PitchRequest("Sunday Pantry", "Alex", "alex@sundaypantry.example", "",
                "Email", "Recipe Reel series", settings.today().minusDays(5), "", false));
        outreach.logPitch(new OutreachService.PitchRequest("Wander Cases", "Jo", "jo@wandercases.example", "",
                "Instagram", "Travel gear feature", settings.today().minusDays(4), "", false));
        // An invoice sent five weeks ago that is now overdue; the brand says it paid (see the Juniper Juice email).
        opportunities.findAll().stream()
                .filter(o -> "Juniper Juice".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> invoices.markSent(invoices.createForDeal(o.id, settings.today().minusDays(35)).id));
        // Fifteen days late with one reminder already sent: the second reminder is drafted for approval.
        opportunities.findAll().stream()
                .filter(o -> "Maple & Moss".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> {
                    Invoice inv = invoices.markSent(invoices.createForDeal(o.id, settings.today().minusDays(45)).id);
                    inv.remindersSent = 1;
                    inv.lastReminderOn = settings.today().minusDays(8);
                    invoiceRepo.save(inv);
                });
        paymentReminders.draftDue(settings.today());
        // Past collabs for "Win back past brands": Coastline Coffee paid three months ago, and Petal & Pine's gifted
        // post went up three weeks ago. Status changes are dated today in a fresh demo, so date them back.
        opportunities.findAll().stream()
                .filter(o -> "Coastline Coffee".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> invoices.markPaid(invoices.markSent(
                        invoices.createForDeal(o.id, settings.today().minusDays(130)).id).id, settings.today().minusDays(95)));
        opportunities.findAll().stream()
                .filter(o -> "Petal & Pine".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> activity.findByOpportunityIdOrderByAtDesc(o.id).forEach(a -> {
                    a.at = now.minusDays(21);
                    activity.save(a);
                }));
        // Coastline Coffee teased a new cold brew on Instagram four days ago, so its re-pitch opens with that
        // (demo mode has no Instagram). Their autumn sale email is there too, sorted out as not news.
        opportunities.findAll().stream()
                .filter(o -> "Coastline Coffee".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> {
                    brandNews.save(demoNews(o.brandId, BrandNews.Source.INSTAGRAM, "https://www.instagram.com/p/DemoMidnightHarbor/",
                            "Something new is brewing... Midnight Harbor cold brew lands 1 November",
                            BrandNews.Kind.TEASER, "Teasing Midnight Harbor, a new cold brew landing on 1 November",
                            "A 'first sip' Reel on launch morning plus three Stories of her week with it", 86, now.minusDays(4)));
                    brandNews.save(demoNews(o.brandId, BrandNews.Source.EMAIL, null, "Autumn sale: 20% off everything this weekend",
                            BrandNews.Kind.OTHER, null, null, 0, now.minusDays(9)));
                });
        winBack.draftDue(settings.today());
        // Bloomleaf Tea's contract, as if read from the PDF attached to their email (demo mode has no Gmail).
        opportunities.findAll().stream()
                .filter(o -> "Bloomleaf Tea".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> {
                    contractRepo.findByOpportunityIdOrderByIdDesc(o.id).forEach(contractRepo::delete);
                    contracts.checkText(o.id, "Bloomleaf-October-agreement.pdf", DEMO_CONTRACT);
                });
        // Petal & Pine's candle post, three weeks old, with its Instagram numbers read a week after it went up
        // (demo mode has no Instagram), and the results recap drafted for her to check.
        opportunities.findAll().stream()
                .filter(o -> "Petal & Pine".equals(workflow.brandName(o))).findFirst()
                .ifPresent(o -> {
                    CampaignResult r = new CampaignResult();
                    r.opportunityId = o.id;
                    r.postUrl = "https://www.instagram.com/p/DemoCandleSet/";
                    r.mediaId = "demo-media-1";
                    r.mediaType = "FEED";
                    r.postedAt = now.minusDays(22);
                    r.source = CampaignResult.Source.INSTAGRAM;
                    r.reach = 18_450L;
                    r.views = 26_310L;
                    r.likes = 1_284L;
                    r.comments = 96L;
                    r.saves = 412L;
                    r.shares = 138L;
                    r.fetchedAt = now.minusDays(15);
                    r.createdAt = now.minusDays(21);
                    r.updatedAt = now.minusDays(15);
                    resultRepo.save(r);
                    results.draftRecap(o.id);
                });
        // Deal dates on the pretend Google Calendar, as if she had connected it.
        calendar.sync();
        dmReminders.run(); // Glow Haus has waited past the reminder time
        // Her shop links, as if she had pasted them from Amazon, Walmart and a brand's site.
        settings.update(Map.of(SettingsService.AMAZON_TAG, "ava-20", SettingsService.CAPTION_HASHTAGS, "#avamakes"));
        for (String[] p : DEMO_PRODUCTS) {
            products.add(new com.creatorcrm.shop.ProductService.Request(p[0], p[1], p[2], p[3], p[4].isEmpty() ? null : p[4]));
        }
        log.info("Demo mode: sign in as '{}' / '{}'", USERNAME, PASSWORD);
    }

    /** link, name, brand, tags, discount code. */
    static final String[][] DEMO_PRODUCTS = {
            {"https://www.amazon.com/Glow-Haus-Dew-Drop-Serum/dp/B0DEMO0001?tag=ava-20", "Dew Drop Serum", "Glow Haus", "skincare, morning routine", ""},
            {"https://www.amazon.com/Lighted-Vanity-Mirror-Dimmable/dp/B0DEMO0002", "Lighted vanity mirror", "", "skincare, home", ""},
            {"https://www.walmart.com/ip/Mainstays-Chunky-Knit-Throw-Blanket/5551234567?wmlspartner=demo", "Chunky knit throw", "Mainstays", "cozy, home", ""},
            {"https://www.amazon.com/Ceramic-Table-Lamp-Warm-White/dp/B0DEMO0003", "Ceramic table lamp", "", "cozy, home", ""},
            {"https://shop.glowhaus.example/products/silk-scrunchie-set", "Silk scrunchie set", "Glow Haus", "hair, morning routine", "AVA15"},
    };

    private static BrandNews demoNews(Long brandId, BrandNews.Source source, String url, String title, BrandNews.Kind kind,
                                      String headline, String idea, int fit, OffsetDateTime at) {
        BrandNews n = new BrandNews();
        n.brandId = brandId;
        n.source = source;
        n.itemKey = "demo:" + title;
        n.title = title;
        n.snippet = title;
        n.url = url;
        n.publishedAt = at;
        n.foundAt = at;
        n.kind = kind;
        n.headline = headline;
        n.pitchIdea = idea;
        n.fit = fit;
        n.hidden = kind == BrandNews.Kind.OTHER;
        return n;
    }
}
