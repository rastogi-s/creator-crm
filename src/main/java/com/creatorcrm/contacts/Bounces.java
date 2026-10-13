package com.creatorcrm.contacts;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Spots "your email wasn't delivered" notices from mail servers, with plain code. */
public final class Bounces {
    private Bounces() {}

    private static final Pattern SENDER = Pattern.compile("(?i)^(mailer-daemon|postmaster|mail-daemon|mail\\.delivery\\.subsystem)@.*");
    private static final Pattern SUBJECT = Pattern.compile("(?i)(delivery status notification|undeliver|delivery has failed|"
            + "delivery failure|mail delivery (failed|subsystem)|returned mail|failure notice|address not found|message not delivered)");

    /**
     * A delivery failure notice: sent by the mail system, or with a bounce subject that isn't someone's reply or
     * forward (a brand writing "Re: undelivered package" is a real email).
     */
    public static boolean isBounce(String sender, String subject) {
        if (sender != null && SENDER.matcher(addressOf(sender)).matches()) return true;
        if (subject == null) return false;
        String s = subject.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("re:") || s.startsWith("fw:") || s.startsWith("fwd:")) return false;
        return SUBJECT.matcher(s).find();
    }

    /** Every address a notice mentions; the caller keeps the ones she actually wrote to. */
    public static List<String> addressesIn(String subject, String content) {
        return Emails.findAll((subject == null ? "" : subject) + "\n" + (content == null ? "" : content));
    }

    /** The bare address in "Mail Delivery Subsystem <mailer-daemon@googlemail.com>", lower-case. */
    private static String addressOf(String header) {
        String e = header.strip();
        int lt = e.lastIndexOf('<'), gt = e.lastIndexOf('>');
        if (lt >= 0 && gt > lt) e = e.substring(lt + 1, gt);
        return e.strip().toLowerCase(Locale.ROOT);
    }
}
