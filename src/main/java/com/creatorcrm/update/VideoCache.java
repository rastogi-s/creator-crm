package com.creatorcrm.update;

import com.creatorcrm.config.CrmProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Walkthrough videos live on the GitHub release they shipped with. The app fetches each one once and keeps it
 * in the data folder, so the dashboard plays them from its own address (no third-party content in the page)
 * and they keep working offline.
 */
@Service
public class VideoCache {
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9-]{0,60}\\.(webm|mp4)");

    private final CrmProperties props;
    private final UpdateService updates;
    private final WhatsNewService whatsNew;

    public VideoCache(CrmProperties props, UpdateService updates, WhatsNewService whatsNew) {
        this.props = props;
        this.updates = updates;
        this.whatsNew = whatsNew;
    }

    public synchronized Path get(String version, String file) throws IOException, InterruptedException {
        if (!VERSION.matcher(version).matches() || !FILE.matcher(file).matches() || !whatsNew.knowsVideo(version, file)) {
            throw new IllegalArgumentException("Unknown video.");
        }
        String local = props.updates() == null ? null : props.updates().localVideoDir();
        if (local != null && !local.isBlank() && Files.isRegularFile(Path.of(local, file))) return Path.of(local, file);
        Path dir = Files.createDirectories(Path.of(props.dataDir()).toAbsolutePath().resolve("videos").resolve(version));
        Path target = dir.resolve(file);
        if (!Files.exists(target)) {
            updates.download(updates.downloadBaseUrl() + "/" + updates.repo() + "/releases/download/v" + version + "/" + file,
                    target);
        }
        return target;
    }
}
