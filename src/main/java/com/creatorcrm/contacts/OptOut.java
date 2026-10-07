package com.creatorcrm.contacts;

import java.util.regex.Pattern;

/** Spots a reply that asks her to stop emailing, so the address goes on the do-not-email list. */
public final class OptOut {
    private OptOut() {}

    private static final Pattern ASK = Pattern.compile("(?i)\\b(remove me|take me off|stop (emailing|contacting|sending)|"
            + "(do not|don't|dont) (email|contact|message) (me|us)|opt(ed)? out|no more emails|not interested,? please (stop|remove))\\b");
    private static final Pattern QUOTE_START = Pattern.compile("(?m)^(On .+wrote:|-----Original Message-----|>)");

    /**
     * True for a short reply (her quoted email removed) that plainly asks to stop. Long emails are left alone so a
     * newsletter footer's "unsubscribe" or a brand's legal text never trips it.
     */
    public static boolean asksToStop(String body) {
        if (body == null) return false;
        String own = body;
        var q = QUOTE_START.matcher(own);
        if (q.find()) own = own.substring(0, q.start());
        own = own.strip();
        if (own.isEmpty() || own.length() > 400) return false;
        if (own.length() <= 40 && own.toLowerCase(java.util.Locale.ROOT).matches("[^a-z]*unsubscribe[^a-z]*")) return true;
        return ASK.matcher(own).find();
    }
}
