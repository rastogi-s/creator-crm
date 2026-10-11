package com.creatorcrm.dms;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Instagram DMs from her own "Download your information" export (Messages, JSON). Instagram only gives apps without
 * Meta's Advanced Access the chats with people who have a role on the app, so brands' DMs never come through the API;
 * the export has all of them. Re-importing the same export adds nothing twice.
 *
 * <p>Each chat is a folder like {@code messages/inbox/glowhaus_1234567890/message_1.json} with
 * {@code participants[].name} and {@code messages[] {sender_name, timestamp_ms, content}}. Names are display names, so
 * the chat's username comes from the folder name. Instagram writes the text as UTF-8 bytes escaped one by one, which
 * {@link #fixText} undoes.
 */
@Service
public class DmExportImport {

    /** One file from the export: its path inside the export folder and its text. */
    public record ExportFile(String path, String text) {}

    public record Result(int chats, int messages, int newMessages, int olderSkipped, String you) {}

    /** Chats imported this way are keyed by their export folder, apart from the API's "ig:&lt;id&gt;" chats. */
    public static final String THREAD_PREFIX = "igx:";

    private static final Pattern CHAT_FILE = Pattern.compile("(?:^|/)(inbox|message_requests)/([^/]+)/message_\\d+\\.json$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IngestionService ingestion;

    public DmExportImport(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /** Imports chats with messages from the last {@code days} days; Claude reads them like any new DM. */
    public Result importFiles(List<ExportFile> files, int days) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("No files came through. Pick the folder you extracted.");
        if (days < 1 || days > 3650) throw new IllegalArgumentException("Pick between 1 and 3650 days");

        Map<String, List<JsonNode>> chats = new LinkedHashMap<>();
        String profileName = null;
        for (ExportFile f : files) {
            String path = f.path() == null ? "" : f.path().replace('\\', '/');
            if (path.endsWith("personal_information.json")) {
                profileName = profileName(read(f));
                continue;
            }
            Matcher m = CHAT_FILE.matcher(path);
            if (!m.find()) continue;
            JsonNode json = read(f);
            if (json != null) chats.computeIfAbsent(m.group(2), k -> new ArrayList<>()).add(json);
        }
        if (chats.isEmpty()) {
            throw new IllegalArgumentException("That folder has no Instagram messages. Pick the folder you extracted from the "
                    + "download (it has a your_instagram_activity folder), and make sure the download was in JSON format.");
        }
        String you = profileName != null ? profileName : mostCommonParticipant(chats);
        if (you == null) {
            throw new IllegalArgumentException("Couldn't tell which name is yours. Pick the whole extracted folder, not one chat.");
        }

        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        List<NormalizedMessage> out = new ArrayList<>();
        int olderSkipped = 0, chatsWithNew = 0;
        for (Map.Entry<String, List<JsonNode>> chat : chats.entrySet()) {
            String folder = chat.getKey();
            String handle = folder.replaceFirst("_\\d+$", "");
            int before = out.size();
            for (JsonNode file : chat.getValue()) {
                for (JsonNode msg : file.path("messages")) {
                    long ms = msg.path("timestamp_ms").asLong(0);
                    if (ms <= 0) continue;
                    OffsetDateTime at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
                    if (at.isBefore(since)) {
                        olderSkipped++;
                        continue;
                    }
                    String sender = fixText(msg.path("sender_name").asText(""));
                    String text = fixText(msg.path("content").asText(""));
                    if (text.isBlank()) text = msg.has("share") ? "[shared a post]" : "[photo, video or other attachment]";
                    boolean mine = sender.equals(you);
                    out.add(new NormalizedMessage(Platform.INSTAGRAM, "x:" + id(folder, ms, sender, text),
                            THREAD_PREFIX + folder, mine ? Direction.OUTBOUND : Direction.INBOUND,
                            mine ? you : handle, mine ? you : handle, mine ? handle : you, handle, "", text, "", "", at,
                            false));
                }
            }
            if (out.size() > before) chatsWithNew++;
        }
        int stored = ingestion.store(out);
        if (stored > 0) ingestion.processPendingAsync();
        return new Result(chatsWithNew, out.size(), stored, olderSkipped, you);
    }

    private static JsonNode read(ExportFile f) {
        try {
            return f.text() == null ? null : JSON.readTree(f.text());
        } catch (Exception e) {
            return null; // not JSON (an HTML download, or a half-copied file): skip it
        }
    }

    /** Her name from personal_information.json ({@code profile_user[0].string_map_data.Name.value}). */
    private static String profileName(JsonNode json) {
        if (json == null) return null;
        String name = json.path("profile_user").path(0).path("string_map_data").path("Name").path("value").asText("");
        return name.isBlank() ? null : fixText(name);
    }

    /** Without her profile file: the name in more chats than any other, when that's clear-cut. */
    static String mostCommonParticipant(Map<String, List<JsonNode>> chats) {
        if (chats.size() < 2) return null;
        Map<String, Integer> counts = new HashMap<>();
        for (List<JsonNode> files : chats.values()) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (JsonNode f : files) f.path("participants").forEach(p -> names.add(fixText(p.path("name").asText(""))));
            names.remove("");
            names.forEach(n -> counts.merge(n, 1, Integer::sum));
        }
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        if (sorted.isEmpty()) return null;
        if (sorted.size() > 1 && sorted.get(1).getValue().equals(sorted.get(0).getValue())) return null;
        return sorted.get(0).getKey();
    }

    /** Instagram's export writes "é" as "Ã©": the UTF-8 bytes as separate characters. */
    static String fixText(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0xFF) return s; // already real text
        String fixed = new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return fixed.indexOf('�') >= 0 ? s : fixed;
    }

    private static String id(String folder, long ms, String sender, String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((folder + "|" + ms + "|" + sender + "|" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
