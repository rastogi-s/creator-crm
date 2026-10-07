package com.creatorcrm.contacts;

import com.creatorcrm.domain.BrandContact.Role;
import java.util.Locale;
import java.util.regex.Pattern;

/** Guesses what someone does at a brand from their job title, or else from their inbox name. */
public final class ContactRoles {
    private ContactRoles() {}

    private static final Pattern T_PARTNERSHIPS = Pattern.compile("partnership|influencer|creator|affiliate|collab|ambassador|talent|ugc");
    private static final Pattern T_PR = Pattern.compile("\\bpr\\b|public relations|communications|\\bcomms\\b|press|publicist");
    private static final Pattern T_MARKETING = Pattern.compile("marketing|brand manager|brand lead|social media|\\bsocial\\b|growth|content|campaign|cmo");
    private static final Pattern T_FOUNDER = Pattern.compile("founder|\\bceo\\b|owner|president|managing director");
    private static final Pattern T_SUPPORT = Pattern.compile("support|customer|service|care");

    public static Role guess(String email, String title) {
        if (title != null && !title.isBlank()) {
            String t = title.toLowerCase(Locale.ROOT);
            if (T_PARTNERSHIPS.matcher(t).find()) return Role.PARTNERSHIPS;
            if (T_PR.matcher(t).find()) return Role.PR;
            if (T_FOUNDER.matcher(t).find()) return Role.FOUNDER;
            if (T_MARKETING.matcher(t).find()) return Role.MARKETING;
            if (T_SUPPORT.matcher(t).find()) return Role.SUPPORT;
        }
        String local = Emails.localOf(email).replaceAll("[^a-z]", "");
        if (local.matches("collabs?|collaborations?|partnerships?|partners?|influencers?|creators?|affiliates?|ambassadors?|ugc|talent|influencermarketing|creatorpartnerships"))
            return Role.PARTNERSHIPS;
        if (local.matches("pr|press|media|comms|communications|publicity")) return Role.PR;
        if (local.matches("marketing|social|brand|brands|growth|content")) return Role.MARKETING;
        if (local.matches("founder|founders|ceo|owner")) return Role.FOUNDER;
        if (local.matches("hello|hi|hey|info|contact|contactus|team|office|enquiries|inquiries|general|sales|wholesale|business"))
            return Role.GENERAL;
        if (local.matches("support|help|care|customercare|customerservice|service|orders|order|returns|shop|store"))
            return Role.SUPPORT;
        return Role.OTHER;
    }

    /** A role learned from a title wins over one guessed from the inbox name; a known role wins over OTHER. */
    public static Role better(Role current, Role found) {
        if (current == null || current == Role.OTHER) return found;
        return current;
    }
}
