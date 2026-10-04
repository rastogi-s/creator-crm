package com.creatorcrm.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StartWithWindowsTest {

    @Test
    void commandQuotesThePathAndStartsInTheTray() {
        assertThat(StartWithWindows.command("C:\\Users\\me\\AppData\\Local\\Creator CRM\\Creator CRM.exe"))
                .isEqualTo("\"C:\\Users\\me\\AppData\\Local\\Creator CRM\\Creator CRM.exe\" --background");
    }

    @Test
    void readsTheRegisteredCommandFromRegQuery() {
        String out = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\r\n"
                + "    Creator CRM    REG_SZ    \"C:\\Apps\\Creator CRM\\Creator CRM.exe\" --background\r\n\r\n";
        assertThat(StartWithWindows.parseQuery(out)).isEqualTo("\"C:\\Apps\\Creator CRM\\Creator CRM.exe\" --background");
        assertThat(StartWithWindows.parseQuery("ERROR: The system was unable to find the specified registry key or value."))
                .isNull();
    }

    @Test
    void scriptsSingleQuoteEveryValue() {
        String add = StartWithWindows.addScript("\"C:\\Users\\O'Brien\\Creator CRM.exe\" --background");
        assertThat(add).contains("-Path 'Registry::HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run'")
                .contains("-Name 'Creator CRM'")
                .contains("-Value '\"C:\\Users\\O''Brien\\Creator CRM.exe\" --background'");
        assertThat(StartWithWindows.removeScript()).contains("Remove-ItemProperty").contains("-Name 'Creator CRM'");
    }

    @Test
    void backgroundArgumentIsTakenOutBeforeSpring() {
        String[] rest = DesktopMode.stripArgs(new String[] {"--background", "--server.port=9090"});
        assertThat(rest).containsExactly("--server.port=9090");
        assertThat(DesktopMode.background()).isTrue();
        DesktopMode.stripArgs(new String[0]);
        assertThat(DesktopMode.background()).isFalse();
    }
}
