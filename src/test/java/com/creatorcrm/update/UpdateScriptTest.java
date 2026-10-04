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
