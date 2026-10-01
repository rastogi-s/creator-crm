package com.creatorcrm.desktop;

import com.creatorcrm.security.SetupService;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Installed-app conveniences once the server is up: opens the browser (on first run straight into setup,
 * with the one-time code in the URL fragment, which browsers never send to the server) and adds a
 * system-tray icon with Open / Quit.
 */
@Component
@ConditionalOnProperty(name = "crm.desktop", havingValue = "true")
public class DesktopIntegration {
    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private final SetupService setup;
    private final ConfigurableApplicationContext context;

    public DesktopIntegration(SetupService setup, ConfigurableApplicationContext context) {
        this.setup = setup;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String code = setup.setupCode();
        String url = code == null ? DesktopMode.baseUrl() + "/"
                : DesktopMode.baseUrl() + "/setup.html#code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
        installTray();
        DesktopMode.openBrowser(url);
    }

    private void installTray() {
        if (!SystemTray.isSupported()) {
            log.info("System tray not available; close the app with Ctrl+C or your OS task manager.");
            return;
        }
        try {
            Image image = ImageIO.read(new ClassPathResource("desktop/tray.png").getInputStream());
            ActionListener open = e -> DesktopMode.openBrowser(DesktopMode.baseUrl() + "/");
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open Creator CRM");
            openItem.addActionListener(open);
            MenuItem quitItem = new MenuItem("Quit Creator CRM");
            quitItem.addActionListener(e -> quit());
            menu.add(openItem);
            menu.addSeparator();
            menu.add(quitItem);
            TrayIcon icon = new TrayIcon(image, "Creator CRM — running at " + DesktopMode.baseUrl(), menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(open);
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            log.warn("Could not add the tray icon: {}", e.getMessage());
        }
    }

    private void quit() {
        new Thread(() -> System.exit(SpringApplication.exit(context, () -> 0)), "creator-crm-quit").start();
    }
}
