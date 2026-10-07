package com.creatorcrm.contacts;

import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.BrandContact.Verified;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Scores who to pitch, 0-100. Anyone who has answered her ranks above everyone who hasn't (60-100), and among
 * them the ones who led to deals, answer fast and answered recently come first. People who haven't answered yet
 * rank by role (a partnerships inbox beats support@), verification and how often she has already tried them (5-55).
 * Bounced, opted-out and invalid addresses score 0 and are never emailed.
 */
public final class ContactRanking {
    private ContactRanking() {}

    public record Score(int score, String reason) {}

    public static Score score(BrandContact c, OffsetDateTime now) {
        if (c.optedOut) return new Score(0, "Asked not to be emailed");
        if (c.bounced) return new Score(0, "Email bounced");
        if (c.verified == Verified.INVALID) return new Score(0, "Address doesn't exist");

        List<String> why = new ArrayList<>();
        if (c.replies > 0) {
            int s = 60;
            why.add(c.replies == 1 ? "Replied once" : "Replied " + c.replies + " times");
            s += Math.min(10, (c.replies - 1) * 2);
            if (c.dealsWon > 0) {
                s += Math.min(20, c.dealsWon * 10);
                why.add(c.dealsWon == 1 ? "1 deal" : c.dealsWon + " deals");
            }
            if (c.avgReplyHours != null && c.avgReplyHours <= 24) {
                s += 6;
                why.add("answers within a day");
            } else if (c.avgReplyHours != null && c.avgReplyHours <= 72) {
                s += 3;
                why.add("answers within 3 days");
            }
            if (c.lastRepliedAt != null) {
                long days = Duration.between(c.lastRepliedAt, now).toDays();
                if (days <= 180) s += 4;
                else if (days > 540) {
                    s -= 6;
                    why.add("last heard from over a year ago");
                }
            }
            return new Score(clamp(s, 60, 100), String.join(" · ", why));
        }

        int s = switch (c.role == null ? Role.OTHER : c.role) {
            case PARTNERSHIPS -> 42;
            case PR -> 36;
            case MARKETING -> 34;
            case FOUNDER -> 30;
            case GENERAL -> 24;
            case OTHER -> 22;
            case SUPPORT -> 12;
        };
        why.add(label(c.role));
        if (c.name != null && !c.name.isBlank()) s += 4;
        if (c.verified == Verified.VALID) {
            s += 5;
            why.add("verified");
        } else if (c.verified == Verified.RISKY) {
            s -= 6;
            why.add("may not get through");
        }
        if (c.confidence != null && c.confidence >= 90) s += 2;
        if (c.emailsSent >= 3) {
            s -= 12;
            why.add("emailed " + c.emailsSent + " times, no reply");
        } else if (c.emailsSent > 0) {
            s -= 4;
            why.add(c.emailsSent == 1 ? "emailed once, no reply yet" : "emailed twice, no reply yet");
        } else {
            why.add("not contacted yet");
        }
        return new Score(clamp(s, 5, 55), String.join(" · ", why));
    }

    static String label(Role r) {
        return switch (r == null ? Role.OTHER : r) {
            case PARTNERSHIPS -> "Partnerships contact";
            case PR -> "PR contact";
            case MARKETING -> "Marketing contact";
            case FOUNDER -> "Founder";
            case GENERAL -> "General inbox";
            case SUPPORT -> "Customer support";
            case OTHER -> "Contact";
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
