package com.creatorcrm.contacts;

import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.SuppressionRepo;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The do-not-email list. Every new pitch, re-pitch and follow-up is checked against it before it goes out. */
@Component
public class Suppressions {
    private final SuppressionRepo repo;

    public Suppressions(SuppressionRepo repo) {
        this.repo = repo;
    }

    /** Why this address must not get outreach, or empty when it may. Matches the address and its whole domain. */
    public Optional<Suppression> find(String email) {
        String e = Emails.clean(email);
        if (e == null) e = email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
        if (e == null || e.isEmpty()) return Optional.empty();
        Optional<Suppression> s = repo.findByValue(e);
        if (s.isPresent()) return s;
        String d = Emails.domainOf(e);
        return d == null ? Optional.empty() : repo.findByValue(d).filter(x -> x.kind == Suppression.Kind.DOMAIN);
    }

    public boolean blocked(String email) {
        return find(email).isPresent();
    }

    /** Adds an address (or a bare domain) to the list. Keeps the first reason when it is already there, except that
     * "forget me" always wins, since it also stops the address being collected again. */
    public Suppression add(String emailOrDomain, Suppression.Reason reason) {
        String v = emailOrDomain.strip().toLowerCase(java.util.Locale.ROOT);
        boolean domain = !v.contains("@");
        if (!domain) {
            String clean = Emails.clean(v);
            if (clean != null) v = clean;
        }
        String value = v;
        Optional<Suppression> existing = repo.findByValue(value);
        if (existing.isPresent()) {
            Suppression s = existing.get();
            if (reason == Suppression.Reason.FORGET_ME && s.reason != reason) {
                s.reason = reason;
                return repo.save(s);
            }
            return s;
        }
        Suppression s = new Suppression();
        s.value = value;
        s.kind = domain ? Suppression.Kind.DOMAIN : Suppression.Kind.EMAIL;
        s.reason = reason;
        s.addedAt = OffsetDateTime.now();
        return repo.save(s);
    }

    public static String explain(Suppression s) {
        return switch (s.reason) {
            case OPTED_OUT -> s.value + " asked not to be emailed, so this can't be sent.";
            case BOUNCED -> "Emails to " + s.value + " bounced, so this can't be sent. Pick another contact at the brand.";
            case FORGET_ME -> s.value + " asked to be forgotten, so this can't be sent.";
            case MANUAL -> "You marked " + s.value + " as do not email, so this can't be sent.";
        };
    }
}
