package com.creatorcrm.campaigns;

/**
 * The lines every campaign email ends with: who she is, her postal address (US CAN-SPAM asks for one in commercial
 * email; a PO box works) and a plain way to say no, which the app honours by never emailing that address again.
 * No tracking pixels or link shorteners are ever added.
 */
public final class CampaignFooter {
    private CampaignFooter() {}

    public static final String OPT_OUT_LINE = "Not a fit? Just reply \"no thanks\" and I won't email you again.";

    public static String footer(String name, String address) {
        String who = (name == null ? "" : name.strip());
        String where = address == null ? "" : address.strip().replaceAll("\\s*\\n\\s*", ", ");
        return "--\n" + (who.isEmpty() ? where : who + " · " + where) + "\n" + OPT_OUT_LINE;
    }

    /** The body with the footer at the end; puts it back if she edited it out. */
    public static String ensure(String body, String name, String address) {
        String b = body == null ? "" : body.stripTrailing();
        String where = address == null ? "" : address.strip().replaceAll("\\s*\\n\\s*", ", ");
        if (b.contains(OPT_OUT_LINE) && b.contains(where)) return b;
        return b + "\n\n" + footer(name, address);
    }
}
