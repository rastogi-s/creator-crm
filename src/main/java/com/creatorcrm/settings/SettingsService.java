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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /** "false" = drafts don't get examples of the creator's past messages. */
    public static final String LEARN_FROM_HISTORY = "learnFromHistory";

    /** Invoice header and footer: who is billing, and how to pay. Blank until the creator fills them in. */
    public static final String INVOICE_BUSINESS_NAME = "invoiceBusinessName";
    public static final String INVOICE_ADDRESS = "invoiceAddress";
    public static final String INVOICE_TAX_ID = "invoiceTaxId";
    public static final String INVOICE_PAYMENT_DETAILS = "invoicePaymentDetails";
    /** Invoice numbers are PREFIX-YEAR-NNN, e.g. INV-2026-001. */
    public static final String INVOICE_PREFIX = "invoicePrefix";
    /** Days from the invoice date to its due date. */
    public static final String INVOICE_TERMS_DAYS = "invoiceTermsDays";

    /** Days after an invoice's due date to draft payment reminders, e.g. "3,7,14". Blank = no reminders. */
    public static final String PAYMENT_REMINDER_DAYS = "paymentReminderDays";

    /** Win back past brands: days a brand must have been quiet before a re-pitch, and re-pitches drafted per week. */
    public static final String WIN_BACK_QUIET_DAYS = "winBackQuietDays";
    public static final String WIN_BACK_WEEKLY_LIMIT = "winBackWeeklyLimit";

    /** Rate advisor: percent added per month of paid usage, and percent added for exclusivity. */
    public static final String RATE_USAGE_PCT = "rateUsagePercentPerMonth";
    public static final String RATE_EXCLUSIVITY_PCT = "rateExclusivityPercent";

    /** Contract check limits: longest payment wait, paid-usage months included in her fee, revision rounds included. */
    public static final String CONTRACT_MAX_PAYMENT_DAYS = "contractMaxPaymentDays";
    public static final String CONTRACT_FREE_USAGE_MONTHS = "contractFreeUsageMonths";
    public static final String CONTRACT_REVISIONS_INCLUDED = "contractRevisionsIncluded";

    /** "false" = deal dates stay off her Google Calendar (and any already there are removed). */
    public static final String CALENDAR_SYNC = "calendarSync";
    /** Her own rules for how Claude reads emails and writes to-dos, added to the classifier's instructions. */
    public static final String TASK_RULES = "taskRules";

    static final String DEFAULT_FOLLOWUP_TIME = "08:00";
    static final String DEFAULT_PAYMENT_REMINDER_DAYS = "3,7,14";

    public static final Set<String> EDITABLE = Set.of(
            CREATOR_NAME, CREATOR_PROFILE, FOLLOWUP_CADENCE, CLASSIFIER_MODEL, WRITER_MODEL, TIMEZONE, BRAND_KEYWORDS,
            FOLLOWUP_TIME, FOLLOWUP_AUTO_SEND, LEARN_FROM_HISTORY, INVOICE_BUSINESS_NAME, INVOICE_ADDRESS, INVOICE_TAX_ID,
            INVOICE_PAYMENT_DETAILS, INVOICE_PREFIX, INVOICE_TERMS_DAYS, PAYMENT_REMINDER_DAYS, WIN_BACK_QUIET_DAYS,
            WIN_BACK_WEEKLY_LIMIT, RATE_USAGE_PCT, RATE_EXCLUSIVITY_PCT,
            CONTRACT_MAX_PAYMENT_DAYS, CONTRACT_FREE_USAGE_MONTHS, CONTRACT_REVISIONS_INCLUDED, CALENDAR_SYNC, TASK_RULES);

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

    /** Marker row: the one-time switch of message reading from Opus to the cheaper Sonnet has run. */
    static final String SONNET_SWITCH_DONE = "migrated.classifierSonnet";

    /**
     * Version 1.5.0 made Sonnet 5.5 the default for reading messages. Saving "About you" used to store the old
     * default (Opus 5.5) too, so clear that once; a model picked later on purpose is kept.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void switchClassifierToSonnetOnce() {
        if (repo.existsById(SONNET_SWITCH_DONE)) return;
        repo.findById(CLASSIFIER_MODEL).filter(x -> "claude-opus-5-5".equals(x.value)).ifPresent(repo::delete);
        Setting done = new Setting();
        done.name = SONNET_SWITCH_DONE;
        done.value = "true";
        done.updatedAt = OffsetDateTime.now();
        repo.save(done);
    }

    private String raw(String name, String fallback) {
        return repo.findById(name).map(s -> s.value).filter(v -> !v.isBlank()).orElse(fallback);
    }

    public String creatorName() { return raw(CREATOR_NAME, props.defaults().creatorName()); }

    public String creatorProfile() { return raw(CREATOR_PROFILE, profileTemplate); }

    public String taskRules() { return raw(TASK_RULES, ""); }

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

    public boolean learnFromHistory() {
        return Boolean.parseBoolean(raw(LEARN_FROM_HISTORY, "true"));
    }

    public boolean calendarSync() {
        return Boolean.parseBoolean(raw(CALENDAR_SYNC, "true"));
    }

    /** The business name on invoices; the creator's name until one is set. */
    public String invoiceBusinessName() { return raw(INVOICE_BUSINESS_NAME, creatorName()); }

    public String invoiceAddress() { return raw(INVOICE_ADDRESS, ""); }

    public String invoiceTaxId() { return raw(INVOICE_TAX_ID, ""); }

    public String invoicePaymentDetails() { return raw(INVOICE_PAYMENT_DETAILS, ""); }

    public String invoicePrefix() { return raw(INVOICE_PREFIX, "INV"); }

    public int invoiceTermsDays() { return Integer.parseInt(raw(INVOICE_TERMS_DAYS, "30")); }

    /** Days after the due date for each payment reminder, ascending. Empty when she switched reminders off. */
    public List<Integer> paymentReminderDays() {
        String v = repo.findById(PAYMENT_REMINDER_DAYS).map(s -> s.value).orElse(DEFAULT_PAYMENT_REMINDER_DAYS);
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt)
                .sorted().toList();
    }

    public int winBackQuietDays() { return Integer.parseInt(raw(WIN_BACK_QUIET_DAYS, "60")); }

    /** 0 = win-back drafts are off. */
    public int winBackWeeklyLimit() { return Integer.parseInt(raw(WIN_BACK_WEEKLY_LIMIT, "5")); }

    public int rateUsagePercent() { return Integer.parseInt(raw(RATE_USAGE_PCT, "30")); }

    public int rateExclusivityPercent() { return Integer.parseInt(raw(RATE_EXCLUSIVITY_PCT, "25")); }

    public int contractMaxPaymentDays() { return Integer.parseInt(raw(CONTRACT_MAX_PAYMENT_DAYS, "30")); }

    public int contractFreeUsageMonths() { return Integer.parseInt(raw(CONTRACT_FREE_USAGE_MONTHS, "3")); }

    public int contractRevisionsIncluded() { return Integer.parseInt(raw(CONTRACT_REVISIONS_INCLUDED, "1")); }

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
        m.put(LEARN_FROM_HISTORY, String.valueOf(learnFromHistory()));
        m.put(INVOICE_BUSINESS_NAME, raw(INVOICE_BUSINESS_NAME, ""));
        m.put(INVOICE_ADDRESS, invoiceAddress());
        m.put(INVOICE_TAX_ID, invoiceTaxId());
        m.put(INVOICE_PAYMENT_DETAILS, invoicePaymentDetails());
        m.put(INVOICE_PREFIX, invoicePrefix());
        m.put(INVOICE_TERMS_DAYS, String.valueOf(invoiceTermsDays()));
        m.put(PAYMENT_REMINDER_DAYS, String.join(",", paymentReminderDays().stream().map(String::valueOf).toList()));
        m.put(WIN_BACK_QUIET_DAYS, String.valueOf(winBackQuietDays()));
        m.put(WIN_BACK_WEEKLY_LIMIT, String.valueOf(winBackWeeklyLimit()));
        m.put(RATE_USAGE_PCT, String.valueOf(rateUsagePercent()));
        m.put(RATE_EXCLUSIVITY_PCT, String.valueOf(rateExclusivityPercent()));
        m.put(CONTRACT_MAX_PAYMENT_DAYS, String.valueOf(contractMaxPaymentDays()));
        m.put(CONTRACT_FREE_USAGE_MONTHS, String.valueOf(contractFreeUsageMonths()));
        m.put(CONTRACT_REVISIONS_INCLUDED, String.valueOf(contractRevisionsIncluded()));
        m.put(CALENDAR_SYNC, String.valueOf(calendarSync()));
        m.put(TASK_RULES, taskRules());
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
            case FOLLOWUP_AUTO_SEND, LEARN_FROM_HISTORY, CALENDAR_SYNC -> {
                if (!v.isBlank() && !v.trim().matches("true|false")) throw new IllegalArgumentException(k + " must be true or false");
            }
            case PAYMENT_REMINDER_DAYS -> {
                if (!v.isBlank() && !v.matches("\\s*\\d{1,3}(\\s*,\\s*\\d{1,3}){0,5}\\s*")) {
                    throw new IllegalArgumentException("Payment reminders: up to 6 comma-separated day counts, e.g. 3,7,14 (blank = off)");
                }
            }
            case WIN_BACK_QUIET_DAYS -> {
                if (!v.isBlank() && (!v.trim().matches("\\d{1,3}") || Integer.parseInt(v.trim()) < 14)) {
                    throw new IllegalArgumentException("Win-back quiet period: a number of days, at least 14, e.g. 60");
                }
            }
            case WIN_BACK_WEEKLY_LIMIT -> {
                if (!v.isBlank() && !v.trim().matches("\\d|1\\d|20")) {
                    throw new IllegalArgumentException("Re-pitches per week: 0 to 20 (0 = off)");
                }
            }
            case CONTRACT_MAX_PAYMENT_DAYS, CONTRACT_FREE_USAGE_MONTHS, CONTRACT_REVISIONS_INCLUDED -> {
                if (!v.isBlank() && !v.trim().matches("\\d{1,3}")) {
                    throw new IllegalArgumentException("Contract limits: a whole number from 0 to 999, e.g. 30");
                }
            }
            case RATE_USAGE_PCT, RATE_EXCLUSIVITY_PCT -> {
                if (!v.isBlank() && !v.trim().matches("\\d{1,3}")) {
                    throw new IllegalArgumentException("Rate uplifts: a whole percent from 0 to 999, e.g. 30");
                }
            }
            case INVOICE_PREFIX -> {
                if (!v.isBlank() && !v.trim().matches("[A-Za-z0-9]{1,10}")) {
                    throw new IllegalArgumentException("Invoice prefix: 1-10 letters or digits, e.g. INV");
                }
            }
            case INVOICE_TERMS_DAYS -> {
                if (!v.isBlank() && !v.trim().matches("\\d{1,3}")) {
                    throw new IllegalArgumentException("Payment terms must be a number of days, e.g. 30");
                }
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
