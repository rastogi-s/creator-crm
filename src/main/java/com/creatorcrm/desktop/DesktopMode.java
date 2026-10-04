package com.creatorcrm.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import javax.swing.JOptionPane;

/**
 * Behaviour when started from the installed app (the installer adds {@code -Dcrm.desktop=true}):
 * no console, logs to a file, a single running instance, and the browser opens by itself.
 * Everything here runs before Spring starts.
 */
public final class DesktopMode {

    /** Added by the Windows startup entry: start in the tray without opening the browser. */
    public static final String BACKGROUND_ARG = "--background";

    /** Added by the in-app updater's relaunch: her old browser tab reloads itself, so don't open another. */
    public static final String AFTER_UPDATE_ARG = "--after-update";

    private static boolean background;
    private static boolean afterUpdate;

    private DesktopMode() {}

    public static boolean enabled() {
        return Boolean.getBoolean("crm.desktop");
    }

    public static int port() {
        String p = System.getProperty("server.port", System.getenv().getOrDefault("PORT", "8080"));
        return Integer.parseInt(p.trim());
    }

    public static String baseUrl() {
        return "http://localhost:" + port();
    }

    /** True when started by Windows at sign-in rather than by a click. */
    public static boolean background() {
        return background;
    }

    /** True when the updater relaunched us after installing a new version. */
    public static boolean afterUpdate() {
        return afterUpdate;
    }

    /** Takes out {@link #BACKGROUND_ARG} and {@link #AFTER_UPDATE_ARG}, which are ours, before Spring sees the arguments. */
    public static String[] stripArgs(String[] args) {
        java.util.List<String> list = java.util.Arrays.asList(args);
        background = list.contains(BACKGROUND_ARG);
        afterUpdate = list.contains(AFTER_UPDATE_ARG);
        return list.stream().filter(a -> !BACKGROUND_ARG.equals(a) && !AFTER_UPDATE_ARG.equals(a)).toArray(String[]::new);
    }

    /** Called from main() before Spring starts. May exit the JVM if the app is already running. */
    public static void prepare() {
        Path home = Path.of(System.getProperty("user.home"), ".creator-crm");
        if (System.getProperty("logging.file.name") == null && System.getenv("LOGGING_FILE_NAME") == null) {
            System.setProperty("logging.file.name", home.resolve("logs").resolve("creator-crm.log").toString());
        }
        if (portIsFree()) return;
        if (isOurApp()) {
            if (!background) openBrowser(baseUrl() + "/"); // second launch: just bring up the existing instance
            System.exit(0);
        }
        if (background) System.exit(0); // something else has the port; don't pop up an error at sign-in
        message("Creator CRM can't start because port " + port() + " is already used by another program.\n\n"
                + "Close that program, or set the PORT environment variable to a different number.");
        System.exit(1);
    }

    private static boolean portIsFree() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("127.0.0.1", port()));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isOurApp() {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/setup/status"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("setupComplete");
        } catch (Exception e) {
            return false;
        }
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) new ProcessBuilder("xdg-open", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception e) {
            message("Open this address in your browser:\n" + url.replaceAll("#.*$", ""));
        }
    }

    static void message(String text) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(text);
        } else {
            JOptionPane.showMessageDialog(null, text, "Creator CRM", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
