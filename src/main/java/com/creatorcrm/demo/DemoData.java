package com.creatorcrm.demo;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.security.AppUser;
import com.creatorcrm.security.AppUserRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.OutreachService;
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

    public DemoData(AppUserRepo users, PasswordEncoder encoder, SettingsService settings, IngestionService ingestion,
                    OutreachService outreach) {
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
        settings.update(Map.of(SettingsService.CREATOR_NAME, "Ava"));

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
        log.info("Demo mode: sign in as '{}' / '{}'", USERNAME, PASSWORD);
    }
}
