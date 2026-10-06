package com.creatorcrm.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopModeTest {

    @TempDir
    Path tmp;

    @Test
    void findsEdgeInProgramFilesOrTheUsersOwnInstall() throws Exception {
        Path x86 = tmp.resolve("x86");
        Path local = tmp.resolve("local");
        assertThat(DesktopMode.findEdge(Map.of("ProgramFiles(x86)", x86.toString())::get)).isNull();

        Path userEdge = edgeUnder(local);
        assertThat(DesktopMode.findEdge(Map.of("ProgramFiles(x86)", x86.toString(), "LOCALAPPDATA", local.toString())::get))
                .isEqualTo(userEdge);

        Path systemEdge = edgeUnder(x86);
        assertThat(DesktopMode.findEdge(Map.of("ProgramFiles(x86)", x86.toString(), "LOCALAPPDATA", local.toString())::get))
                .isEqualTo(systemEdge);
    }

    @Test
    void opensTheAppWithoutTabsOrAddressBar() {
        Path edge = Path.of("msedge.exe");
        assertThat(DesktopMode.appWindowCommand(edge, "http://localhost:8080/setup.html#code=abc"))
                .containsExactly("msedge.exe", "--app=http://localhost:8080/setup.html#code=abc");
    }

    private static Path edgeUnder(Path root) throws Exception {
        Path exe = root.resolve("Microsoft").resolve("Edge").resolve("Application").resolve("msedge.exe");
        Files.createDirectories(exe.getParent());
        Files.createFile(exe);
        return exe;
    }
}
