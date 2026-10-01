package com.creatorcrm;

import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.desktop.DesktopMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CrmProperties.class)
public class CreatorCrmApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CreatorCrmApplication.class);
        if (DesktopMode.enabled()) {
            DesktopMode.prepare();
            app.setHeadless(false); // needed for the tray icon and dialogs
        }
        app.run(args);
    }
}
