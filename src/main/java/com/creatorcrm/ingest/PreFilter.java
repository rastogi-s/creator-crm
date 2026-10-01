package com.creatorcrm.ingest;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.settings.SettingsService;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Stage 1 of the pipeline: plain code decides which messages are obviously not worth an AI call.
 * Returns a reason to skip, or null to send the message to the classifier. Errs on the side of
 * classifying, since a missed brand deal costs more than one cheap AI call.
 */
@Component
public class PreFilter {
    private static final Pattern AUTOMATED_SENDER = Pattern.compile(
            "(?i)^(no-?reply|do-?not-?reply|notifications?|mailer-daemon|postmaster|bounce|alerts?|updates?|news(letter)?)[@+.-].*");

    private final SettingsService settings;

    public PreFilter(SettingsService settings) {
        this.settings = settings;
    }

    public String skipReason(NormalizedMessage m, Conversation conv) {
        if (Boolean.TRUE.equals(conv.brandRelated)) return null; // known brand conversation: always analyze
        if (m.platform() == Platform.INSTAGRAM) return null;       // DMs are low-volume and casual: always analyze

        List<String> keywords = settings.brandKeywords();
        String subject = lower(m.subject());
        String head = subject + "\n" + lower(m.content()).substring(0, Math.min(3000, lower(m.content()).length()));
        boolean keywordInSubject = keywords.stream().anyMatch(subject::contains);
        boolean keywordAnywhere = keywordInSubject || keywords.stream().anyMatch(head::contains);

        if (m.direction() == Direction.OUTBOUND) {
            return keywordAnywhere ? null : "outbound mail without brand keywords";
        }
        if (Boolean.FALSE.equals(conv.brandRelated) && !keywordAnywhere) {
            return "conversation previously judged not brand-related";
        }
        if (m.sender() != null && AUTOMATED_SENDER.matcher(m.sender()).matches() && !keywordInSubject) {
            return "automated sender";
        }
        if (m.bulk() && !keywordInSubject) {
            return "bulk/newsletter mail";
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
