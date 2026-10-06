package com.creatorcrm.practice;

import com.creatorcrm.update.WhatsNewService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A fresh practice copy shouldn't greet her with every What's New page since 1.0. */
@Component
@ConditionalOnProperty(name = "crm.practice", havingValue = "true")
public class PracticeSetup implements ApplicationRunner {
    private final WhatsNewService whatsNew;

    public PracticeSetup(WhatsNewService whatsNew) {
        this.whatsNew = whatsNew;
    }

    @Override
    public void run(ApplicationArguments args) {
        whatsNew.markSeen();
    }
}
