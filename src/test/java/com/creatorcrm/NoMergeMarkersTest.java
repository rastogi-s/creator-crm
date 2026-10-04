package com.creatorcrm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A merge conflict resolved by hand once shipped its markers in app.css, which broke the styles after them. */
class NoMergeMarkersTest {

    private static final Pattern MARKER = Pattern.compile("^(<{7}|={7}|>{7})( |$)");

    @Test
    void sourcesHaveNoLeftoverConflictMarkers() throws IOException {
        List<String> found = new ArrayList<>();
        for (String root : List.of("src/main", "src/test", "walkthroughs/record.mjs", "packaging")) {
            Path start = Path.of(root);
            if (!Files.exists(start)) continue;
            try (Stream<Path> files = Files.walk(start)) {
                for (Path f : files.filter(Files::isRegularFile).filter(NoMergeMarkersTest::isText).toList()) {
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (MARKER.matcher(lines.get(i)).find()) found.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(found).as("conflict markers").isEmpty();
    }

    private static boolean isText(Path f) {
        String n = f.getFileName().toString();
        return n.matches(".*\\.(java|js|mjs|css|html|json|md|yml|yaml|properties|sql|ps1|sh|cmd|txt)$");
    }
}
