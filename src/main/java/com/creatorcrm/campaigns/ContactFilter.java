package com.creatorcrm.campaigns;

import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.BrandContact.Verified;
import java.util.List;
import java.util.Locale;

/**
 * A saved list's rules. Empty fields match everyone.
 *
 * @param roles           only these roles (e.g. PARTNERSHIPS, PR); empty = any
 * @param verifiedOnly    only addresses a checker confirmed exist
 * @param neverEmailed    only people she hasn't emailed yet
 * @param replied         true = only people who replied to her before, false = only people who never did, null = either
 * @param brandText       brand name contains this
 * @param minScore        ranking score at least this (0-100)
 * @param skipBrandsInDeals leave out brands she already has an open deal with
 */
public record ContactFilter(List<Role> roles, boolean verifiedOnly, boolean neverEmailed, Boolean replied,
                            String brandText, Integer minScore, Boolean skipBrandsInDeals) {

    public ContactFilter {
        roles = roles == null ? List.of() : List.copyOf(roles);
        brandText = brandText == null || brandText.isBlank() ? null : brandText.strip();
    }

    public boolean skipsBrandsInDeals() {
        return skipBrandsInDeals == null || skipBrandsInDeals;
    }

    public boolean matches(BrandContact c, String brandName) {
        if (!roles.isEmpty() && !roles.contains(c.role == null ? Role.OTHER : c.role)) return false;
        if (verifiedOnly && c.verified != Verified.VALID) return false;
        if (neverEmailed && (c.emailsSent > 0 || c.lastContactedAt != null)) return false;
        if (replied != null && replied != (c.replies > 0)) return false;
        if (minScore != null && c.score < minScore) return false;
        if (brandText != null && (brandName == null
                || !brandName.toLowerCase(Locale.ROOT).contains(brandText.toLowerCase(Locale.ROOT)))) return false;
        return true;
    }
}
