package com.creatorcrm.update;

import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Release notes shipped inside the app ({@code whats-new.json}): what each version added, with a short video.
 * The same file becomes the GitHub release notes, so the two never disagree.
 */
@Service
public class WhatsNewService {
    private static final String SEEN_KEY = "whatsNew.lastSeen";

    public record Feature(String id, String title, String body, String video, String tryIt) {}

    public record Entry(String version, String date, String title, List<Feature> features) {}

    /** {@code unseen}: entries added since the user last opened What's New (newest first). */
    public record View(String currentVersion, List<Entry> unseen, List<Entry> all) {}

    private final AppStateRepo state;
    private final UpdateService updates;
    private final List<Entry> entries;

    public WhatsNewService(AppStateRepo state, UpdateService updates) throws IOException {
        this.state = state;
        this.updates = updates;
        try (InputStream in = new ClassPathResource("whats-new.json").getInputStream()) {
            ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<Entry> list = List.of(json.readValue(in, Entry[].class));
            this.entries = list.stream().sorted(Comparator.comparing(Entry::version, Versions::compare).reversed()).toList();
        }
    }

    public View view() {
        String current = updates.currentVersion();
        String seen = state.findById(SEEN_KEY).map(s -> s.stateValue).orElse("0.0.0");
        List<Entry> shipped = entries.stream().filter(e -> Versions.compare(e.version(), release(current)) <= 0).toList();
        List<Entry> unseen = shipped.stream().filter(e -> Versions.isNewer(e.version(), seen)).toList();
        return new View(current, unseen, shipped);
    }

    public void markSeen() {
        AppState s = new AppState();
        s.stateKey = SEEN_KEY;
        s.stateValue = release(updates.currentVersion());
        state.save(s);
    }

    /** A beta build (1.1.0-beta.2) already shows the 1.1.0 notes, since that's what it is testing. */
    private static String release(String version) {
        return version.replaceFirst("-.*$", "");
    }

    /** Is this a video named in the release notes? Only those may be fetched through the app. */
    public boolean knowsVideo(String version, String file) {
        return entries.stream().anyMatch(e -> e.version().equals(version)
                && e.features().stream().anyMatch(f -> file.equals(f.video())));
    }
}
