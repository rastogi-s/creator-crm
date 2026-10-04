package com.creatorcrm.settings;

import com.creatorcrm.config.CrmProperties;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-creator, non-secret preferences, editable on the Settings page. Falls back to install defaults.
 * This is what makes the app generic: nothing about a specific creator is hard-coded.
 */
@Service
public class SettingsService {

    public static final String CREATOR_NAME = "creatorName";
    public static final String CREATOR_PROFILE = "creatorProfile";
    public static final String FOLLOWUP_CADENCE = "followupCadenceDays";
    public static final String CLASSIFIER_MODEL = "classifierModel";
    public static final String WRITER_MODEL = "writerModel";
    public static final String TIMEZONE = "timezone";
    public static final String BRAND_KEYWORDS = "brandKeywords";
    /** Local time (HH:mm) of the daily follow-up run: sync, draft today's follow-ups, optionally send them. */
    public static final String FOLLOWUP_TIME = "followupTime";
    /** "true" = email follow-ups drafted in the daily run are sent without waiting for approval. */
    public static final String FOLLOWUP_AUTO_SEND = "followupAutoSend";

    static final String DEFAULT_FOLLOWUP_TIME = "08:00";

    public static final Set<String> EDITABLE = Set.of(
            CREATOR_NAME, CREATOR_PROFILE, FOLLOWUP_CADENCE, CLASSIFIER_MODEL, WRITER_MODEL, TIMEZONE, BRAND_KEYWORDS,
            FOLLOWUP_TIME, FOLLOWUP_AUTO_SEND);

    static final String DEFAULT_KEYWORDS = "collab, collaboration, partnership, partner, sponsor, sponsored, campaign, "
            + "ugc, gifted, gifting, pr package, ambassador, affiliate, influencer, creator, rates, rate card, "
            + "media kit, budget, deliverables, usage rights, whitelisting, contract, agreement, docusign, "
            + "brief, invoice, payment, paid, compensation, application, apply, content, reel, tiktok, story";

    @Entity
    @Table(name = "settings")
    public static class Setting {
        @Id public String name;
        @jakarta.persistence.Column(name = "setting_value")
        public String value;
        public OffsetDateTime updatedAt;
    }

    private final SettingRepo repo;
    private final CrmProperties props;
    private final String profileTemplate;

    public SettingsService(SettingRepo repo, CrmProperties props) {
        this.repo = repo;
        this.props = props;
        try {
            this.profileTemplate = new ClassPathResource("prompts/creator-profile-template.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String raw(String name, String fallback) {
        return repo.findById(name).map(s -> s.value).filter(v -> !v.isBlank()).orElse(fallback);
    }

    public String creatorName() { return raw(CREATOR_NAME, props.defaults().creatorName()); }

    public String creatorProfile() { return raw(CREATOR_PROFILE, profileTemplate); }

    public String classifierModel() { return raw(CLASSIFIER_MODEL, props.defaults().classifierModel()); }

    public String writerModel() { return raw(WRITER_MODEL, props.defaults().writerModel()); }

    public String classifierEffort() { return props.defaults().classifierEffort(); }

    public String writerEffort() { return props.defaults().writerEffort(); }

    public List<Integer> followupCadence() {
        String v = raw(FOLLOWUP_CADENCE, null);
        if (v == null) return props.defaults().followupCadenceDays();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
    }

    public int maxFollowups() { return followupCadence().size(); }

    /** Days to wait before follow-up number n (1-based). */
    public int cadenceBefore(int n) {
        List<Integer> c = followupCadence();
        return c.get(Math.min(n, c.size()) - 1);
    }

    public LocalTime followupTime() {
        return LocalTime.parse(raw(FOLLOWUP_TIME, DEFAULT_FOLLOWUP_TIME));
    }

    public boolean followupAutoSend() {
        return Boolean.parseBoolean(raw(FOLLOWUP_AUTO_SEND, "false"));
    }

    public List<String> brandKeywords() {
        return Arrays.stream(raw(BRAND_KEYWORDS, DEFAULT_KEYWORDS).split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList();
    }

    public ZoneId zone() {
        String z = raw(TIMEZONE, props.defaults().timezone());
        return z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z);
    }

    public LocalDate today() { return LocalDate.now(zone()); }

    public Map<String, String> all() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CREATOR_NAME, creatorName());
        m.put(CREATOR_PROFILE, creatorProfile());
        m.put(FOLLOWUP_CADENCE, String.join(",", followupCadence().stream().map(String::valueOf).toList()));
        m.put(CLASSIFIER_MODEL, classifierModel());
        m.put(WRITER_MODEL, writerModel());
        m.put(TIMEZONE, zone().getId());
        m.put(BRAND_KEYWORDS, String.join(", ", brandKeywords()));
        m.put(FOLLOWUP_TIME, followupTime().toString());
        m.put(FOLLOWUP_AUTO_SEND, String.valueOf(followupAutoSend()));
        return m;
    }

    @Transactional
    public void update(Map<String, String> values) {
        values.forEach((k, v) -> {
            if (!EDITABLE.contains(k)) throw new IllegalArgumentException("Unknown setting " + k);
            validate(k, v);
            Setting s = repo.findById(k).orElseGet(Setting::new);
            s.name = k;
            s.value = v == null ? "" : v.trim();
            s.updatedAt = OffsetDateTime.now();
            repo.save(s);
        });
    }

    private static void validate(String k, String v) {
        if (v == null) return;
        switch (k) {
            case FOLLOWUP_CADENCE -> {
                if (!v.matches("\\s*\\d{1,2}(\\s*,\\s*\\d{1,2}){0,9}\\s*")) {
                    throw new IllegalArgumentException("Cadence must be 1-10 comma-separated day counts, e.g. 4,5,7,7,7");
                }
            }
            case TIMEZONE -> { if (!v.isBlank()) ZoneId.of(v.trim()); }
            case FOLLOWUP_TIME -> {
                if (!v.isBlank() && !v.trim().matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                    throw new IllegalArgumentException("Follow-up time must be HH:mm, e.g. 08:00");
                }
            }
            case FOLLOWUP_AUTO_SEND -> {
                if (!v.isBlank() && !v.trim().matches("true|false")) throw new IllegalArgumentException("Auto-send must be true or false");
            }
            case CLASSIFIER_MODEL, WRITER_MODEL -> {
                if (!v.matches("[a-z0-9.-]{3,64}")) throw new IllegalArgumentException("Invalid model id");
            }
            case CREATOR_PROFILE -> {
                if (v.length() > 20_000) throw new IllegalArgumentException("Profile is too long (max 20,000 chars)");
            }
            default -> {
                if (v.length() > 2_000) throw new IllegalArgumentException(k + " is too long");
            }
        }
    }
}
