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
import java.time.Duration;
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
 * system-tray icon with Open / Quit. When Windows starts it at sign-in it stays in the tray instead.
 */
@Component
@ConditionalOnProperty(name = "crm.desktop", havingValue = "true")
public class DesktopIntegration {
    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private final SetupService setup;
    private final StartWithWindows startWithWindows;
    private final ConfigurableApplicationContext context;
    private final LocalPageTracker pages;

    /** The old tab polls every 3 seconds; the margin covers browsers slowing timers in a background tab. */
    static final Duration AFTER_UPDATE_WAIT = Duration.ofSeconds(30);

    public DesktopIntegration(SetupService setup, StartWithWindows startWithWindows, ConfigurableApplicationContext context,
                              LocalPageTracker pages) {
        this.setup = setup;
        this.startWithWindows = startWithWindows;
        this.context = context;
        this.pages = pages;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String code = setup.setupCode();
        String url = code == null ? DesktopMode.baseUrl() + "/"
                : DesktopMode.baseUrl() + "/setup.html#code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
        installTray();
        // Started by Windows at sign-in: stay in the tray. First-run setup still opens, since it needs her.
        if (code != null) DesktopMode.openBrowser(url);
        else if (DesktopMode.afterUpdate()) openUnlessPageReconnects(url);
        else if (!DesktopMode.background()) DesktopMode.openBrowser(url);
        Thread.ofVirtual().name("start-with-windows").start(startWithWindows::applyDefault);
    }

    /**
     * After an in-app update her old tab is still open and polling, and reloads itself once we're back.
     * Only open a new tab if no page on this computer checks in, e.g. she closed it during the install.
     */
    private void openUnlessPageReconnects(String url) {
        Thread.ofVirtual().name("after-update-open").start(() -> {
            try {
                if (!pages.awaitLocalPage(AFTER_UPDATE_WAIT)) DesktopMode.openBrowser(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
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
