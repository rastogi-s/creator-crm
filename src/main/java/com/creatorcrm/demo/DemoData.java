package com.creatorcrm.demo;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.invoices.InvoiceService;
import com.creatorcrm.invoices.PaymentReminders;
import com.creatorcrm.rebook.WinBack;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.InvoiceRepo;
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

    public DemoData(AppUserRepo users, PasswordEncoder encoder, SettingsService settings, IngestionService ingestion,
                    OutreachService outreach, InvoiceService invoices, OpportunityRepo opportunities,
                    WorkflowEngine workflow, InvoiceRepo invoiceRepo, PaymentReminders paymentReminders,
                    ActivityRepo activity, WinBack winBack) {
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
        winBack.draftDue(settings.today());
        log.info("Demo mode: sign in as '{}' / '{}'", USERNAME, PASSWORD);
    }
}
