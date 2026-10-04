package com.creatorcrm.llm;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Defensive checks on what Claude read from a contract before the app's rules use it. */
public final class ContractTermsValidator {
    private ContractTermsValidator() {}

    public static ContractTerms sanitize(ContractTerms t) {
        double fee = t.fee() >= 0 && t.fee() < 10_000_000 ? t.fee() : 0;
        List<ContractTerms.ContractDate> dates = t.deadlines() == null ? List.of() : t.deadlines().stream()
                .filter(d -> d != null && plausible(d.date()))
                .map(d -> new ContractTerms.ContractDate(d.date(), AnalysisValidator.clip(d.what(), 200)))
                .limit(10).toList();
        List<String> concerns = t.concerns() == null ? List.of() : t.concerns().stream()
                .filter(c -> c != null && !c.isBlank()).map(c -> AnalysisValidator.clip(c, 300)).limit(5).toList();
        return new ContractTerms(
                Math.max(0, Math.min(t.paymentDays(), 999)),
                AnalysisValidator.clip(t.paymentTerms(), 300),
                fee,
                AnalysisValidator.clip(t.currency(), 3).toUpperCase(),
                Math.max(0, Math.min(t.usageMonths(), 999)),
                AnalysisValidator.clip(t.usageTerms(), 300),
                Math.max(0, Math.min(t.exclusivityMonths(), 999)),
                AnalysisValidator.clip(t.exclusivityTerms(), 300),
                Math.max(-1, Math.min(t.revisionRounds(), 99)),
                AnalysisValidator.clip(t.killFee(), 300),
                dates,
                concerns,
                AnalysisValidator.clip(t.summary(), 1000));
    }

    private static boolean plausible(String s) {
        try {
            LocalDate d = LocalDate.parse(s);
            LocalDate now = LocalDate.now();
            return d.isAfter(now.minusYears(1)) && d.isBefore(now.plusYears(3));
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }
}
