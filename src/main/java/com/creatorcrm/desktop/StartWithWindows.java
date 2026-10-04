package com.creatorcrm.desktop;

import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Start Creator CRM when Windows starts": an entry under the current user's Run key that launches the
 * installed app with {@link DesktopMode#BACKGROUND_ARG}, so it comes up in the tray without opening a browser.
 * The Run key itself is the setting; nothing else is stored apart from a flag saying the default (on) was applied.
 */
@Service
public class StartWithWindows {
    private static final Logger log = LoggerFactory.getLogger(StartWithWindows.class);

    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALUE_NAME = "Creator CRM";
    private static final String DEFAULT_APPLIED_KEY = "desktop.startWithWindows.defaultApplied";

    public record Status(boolean supported, boolean enabled) {}

    private final AppStateRepo state;

    public StartWithWindows(AppStateRepo state) {
        this.state = state;
    }

    /** Only the installed Windows app can register itself; Docker, Mac and dev runs can't. */
    public boolean supported() {
        return DesktopMode.enabled() && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && launcher() != null;
    }

    public Status status() {
        return supported() ? new Status(true, currentEntry() != null) : new Status(false, false);
    }

    public Status set(boolean on) {
        if (!supported()) throw new IllegalStateException("Starting with Windows only works in the installed Windows app.");
        runPowerShell(on ? addScript(command(launcher())) : removeScript());
        markDefaultApplied();
        return status();
    }

    /**
     * Called once the app is up: turns the setting on the first time this version runs, and afterwards keeps an
     * existing entry pointing at the current install (the folder can change if the app is reinstalled elsewhere).
     */
    public void applyDefault() {
        if (!supported()) return;
        try {
            String entry = currentEntry();
            String wanted = command(launcher());
            if (!defaultApplied()) {
                if (entry == null) runPowerShell(addScript(wanted));
                markDefaultApplied();
            } else if (entry != null && !entry.equals(wanted)) {
                runPowerShell(addScript(wanted));
            }
        } catch (Exception e) {
            log.warn("Could not set up starting with Windows: {}", e.getMessage());
        }
    }

    private boolean defaultApplied() {
        return state.findById(DEFAULT_APPLIED_KEY).isPresent();
    }

    private void markDefaultApplied() {
        AppState s = new AppState();
        s.stateKey = DEFAULT_APPLIED_KEY;
        s.stateValue = "true";
        state.save(s);
    }

    private static String launcher() {
        String p = System.getProperty("jpackage.app-path");
        if (p == null || p.isBlank()) p = ProcessHandle.current().info().command().orElse(null);
        return p != null && p.toLowerCase(Locale.ROOT).endsWith(".exe") && !p.toLowerCase(Locale.ROOT).endsWith("java.exe")
                && !p.toLowerCase(Locale.ROOT).endsWith("javaw.exe") ? p : null;
    }

    static String command(String launcher) {
        return "\"" + launcher + "\" " + DesktopMode.BACKGROUND_ARG;
    }

    /** The command registered under the Run key, or null when there is none. */
    private static String currentEntry() {
        try {
            Process p = new ProcessBuilder("reg.exe", "query", RUN_KEY, "/v", VALUE_NAME).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return parseQuery(out);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Reads {@code reg query} output: "    Creator CRM    REG_SZ    "C:\...\Creator CRM.exe" --background". */
    static String parseQuery(String out) {
        for (String line : out.split("\\R")) {
            int i = line.indexOf("REG_SZ");
            if (i > 0 && line.substring(0, i).trim().equals(VALUE_NAME)) return line.substring(i + "REG_SZ".length()).trim();
        }
        return null;
    }

    /**
     * Written to a .ps1 file and run with -File, like the updater's script, so the quotes inside the command never
     * pass through a command line. Values are single-quoted; the only character to escape is the single quote.
     */
    static String addScript(String command) {
        return String.join("\r\n",
                "$ErrorActionPreference = 'Stop'",
                "New-ItemProperty -Path " + ps("Registry::" + RUN_KEY) + " -Name " + ps(VALUE_NAME)
                        + " -Value " + ps(command) + " -PropertyType String -Force | Out-Null",
                "");
    }

    static String removeScript() {
        return String.join("\r\n",
                "$ErrorActionPreference = 'Stop'",
                "Remove-ItemProperty -Path " + ps("Registry::" + RUN_KEY) + " -Name " + ps(VALUE_NAME)
                        + " -ErrorAction SilentlyContinue",
                "");
    }

    private static String ps(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static void runPowerShell(String script) {
        Path file = null;
        try {
            file = Files.createTempFile("creator-crm-startup", ".ps1");
            // With a BOM, Windows PowerShell 5 reads the file as UTF-8, so install paths with accents survive.
            Files.writeString(file, "\uFEFF" + script, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy();
                throw new IllegalStateException("Windows took too long to save the setting. Please try again.");
            }
            if (p.exitValue() != 0) {
                log.warn("Changing the Windows startup entry failed: {}", out.trim());
                throw new IllegalStateException("Windows didn't accept the change. Please try again.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't change the Windows startup setting: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while saving the setting", e);
        } finally {
            if (file != null) file.toFile().delete();
        }
    }
}
