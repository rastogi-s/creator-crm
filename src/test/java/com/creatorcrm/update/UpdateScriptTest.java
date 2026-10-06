package com.creatorcrm.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateScriptTest {

    @Test
    void scriptWaitsForTheAppThenInstallsAndRelaunches() {
        String s = UpdateService.windowsScript(4242, Path.of("C:\\Users\\Ana O'Neil\\.creator-crm\\updates\\Creator-CRM-1.2.0-windows-x64.msi"),
                Path.of("C:\\Users\\Ana O'Neil\\.creator-crm\\updates\\install-update.log"),
                "C:\\Users\\Ana O'Neil\\AppData\\Local\\Creator CRM\\Creator CRM.exe");
        assertThat(s).contains("Wait-Process -Id 4242");
        assertThat(s).contains("/passive /norestart");
        // Single quotes in paths are doubled inside PowerShell's single-quoted strings.
        assertThat(s).contains("Ana O''Neil").doesNotContain("Ana O'Neil");
        assertThat(s.indexOf("msiexec")).isLessThan(s.indexOf("Start-Process -FilePath $app"));
        // The relaunch tells the app her old tab will reload itself, so it doesn't open a second one.
        assertThat(s).contains("Start-Process -FilePath $app -ArgumentList '--after-update'");
    }

    @Test
    void picksTheInstallerForThisComputer() {
        assertThat(UpdateService.installerSuffix("Windows 11", "amd64")).isEqualTo("-windows-x64.msi");
        assertThat(UpdateService.installerSuffix("Mac OS X", "aarch64")).isEqualTo("-macos-apple-silicon.dmg");
        assertThat(UpdateService.installerSuffix("Mac OS X", "x86_64")).isEqualTo("-macos-intel.dmg");
        assertThat(UpdateService.installerSuffix("Linux", "amd64")).isNull();
    }

    @Test
    void findsTheMacAppFromItsLauncher() {
        assertThat(UpdateService.macAppBundle("/Applications/Creator CRM.app/Contents/MacOS/Creator CRM"))
                .isEqualTo(Path.of("/Applications/Creator CRM.app"));
        assertThat(UpdateService.macAppBundle("/usr/bin/java")).isNull();
        assertThat(UpdateService.macAppBundle(null)).isNull();
    }

    @Test
    void macAppMustBeSomewhereItCanBeReplaced(@TempDir Path dir) throws IOException {
        Path app = Files.createDirectories(dir.resolve("Creator CRM.app"));
        assertThat(UpdateService.macInstallProblem(app)).isNull();
        // Opened straight from the disk image, or from the read-only copy macOS runs a quarantined app from
        assertThat(UpdateService.macInstallProblem(Path.of("/Volumes/Creator CRM/Creator CRM.app"))).contains("Applications folder");
        assertThat(UpdateService.macInstallProblem(Path.of("/private/var/folders/x/AppTranslocation/1/d/Creator CRM.app")))
                .contains("Applications folder");
        assertThat(UpdateService.macInstallProblem(null)).contains("release page");
    }

    @Test
    void macScriptWaitsThenSwapsTheAppAndRelaunches(@TempDir Path dir) throws Exception {
        String s = UpdateService.macScript(4242, Path.of("/Users/ana o'neil/.creator-crm/updates/Creator-CRM-1.2.0-macos-apple-silicon.dmg"),
                Path.of("/Users/ana o'neil/.creator-crm/updates/install-update.log"), Path.of("/Applications/Creator CRM.app"));
        assertThat(s).contains("kill -0 4242");
        // Single quotes in paths end the quoted word, add an escaped quote, and start a new one.
        assertThat(s).contains("DMG='/Users/ana o'\\''neil/");
        assertThat(s).contains("APP='/Applications/Creator CRM.app'");
        assertThat(s.indexOf("hdiutil attach")).isLessThan(s.indexOf("ditto")).isLessThan(s.indexOf("open -n"));
        // The old app is only moved aside after the new one is fully copied, and put back if the swap fails.
        assertThat(s.indexOf("ditto")).isLessThan(s.indexOf("mv \"$APP\" \"$APP.old\""));
        assertThat(s).contains("else mv \"$APP.old\" \"$APP\"");
        assertThat(s).contains("open -n \"$APP\" --args --after-update");
        // And it's valid bash
        Path f = Files.writeString(dir.resolve("s.sh"), s);
        Process p = new ProcessBuilder("bash", "-n", f.toString()).redirectErrorStream(true).start();
        assertThat(new String(p.getInputStream().readAllBytes())).isEmpty();
        assertThat(p.waitFor()).isZero();
    }

    @Test
    void readsTheChecksumForTheRightFile() throws IOException {
        String a = "a".repeat(64), b = "b".repeat(64);
        String sums = a + "  creator-crm-1.2.0.jar\n" + b + " *Creator-CRM-1.2.0-windows-x64.msi\n";
        assertThat(UpdateService.expectedHash(sums, "Creator-CRM-1.2.0-windows-x64.msi")).isEqualTo(b);
        assertThatThrownBy(() -> UpdateService.expectedHash(sums, "other.msi")).isInstanceOf(IOException.class);
    }

    @Test
    void hashesFiles(@TempDir Path dir) throws IOException {
        Path f = Files.writeString(dir.resolve("x"), "abc");
        assertThat(UpdateService.sha256(f)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
