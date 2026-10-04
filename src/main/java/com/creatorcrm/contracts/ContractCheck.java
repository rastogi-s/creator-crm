package com.creatorcrm.contracts;

import com.creatorcrm.llm.ContractTerms;
import com.creatorcrm.rates.RateAdvisor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The checklist for a contract: the terms Claude read, held against her limits by rules in code. Red is something to
 * push back on, amber something to look at, green fine. The app never decides whether she signs.
 */
public final class ContractCheck {
    private ContractCheck() {}

    public enum Level { RED, AMBER, OK }

    public record Flag(Level level, String text) {}

    /** Her limits from Settings, Contract check. */
    public record Limits(int maxPaymentDays, int freeUsageMonths, int revisionsIncluded) {}

    /** "Forever" in the terms Claude returns. */
    static final int NO_END = 999;

    public static List<Flag> check(ContractTerms t, Limits l, BigDecimal agreedFee) {
        List<Flag> out = new ArrayList<>();

        if (t.paymentDays() == 0) {
            out.add(new Flag(Level.AMBER, "When you get paid isn't stated. Ask for payment within " + l.maxPaymentDays() + " days."));
        } else if (t.paymentDays() > l.maxPaymentDays()) {
            String from = after(t.paymentTerms());
            out.add(new Flag(Level.RED, "Paid " + t.paymentDays() + (from.isEmpty() ? " days out" : " days after" + from) + ", longer than your "
                    + l.maxPaymentDays() + "-day limit." + quote(t.paymentTerms())));
        } else {
            out.add(new Flag(Level.OK, "Paid within " + t.paymentDays() + " days." + quote(t.paymentTerms())));
        }

        BigDecimal fee = t.fee() > 0 ? BigDecimal.valueOf(t.fee()) : null;
        if (fee == null) {
            out.add(new Flag(Level.AMBER, "No fee found in the contract. Make sure the amount you agreed is written in."));
        } else if (agreedFee != null && agreedFee.signum() > 0 && fee.subtract(agreedFee).abs().compareTo(BigDecimal.ONE) >= 0) {
            out.add(new Flag(Level.RED, "The contract says " + RateAdvisor.money(fee) + ", but the deal is " + RateAdvisor.money(agreedFee) + "."));
        } else {
            out.add(new Flag(Level.OK, "Fee " + RateAdvisor.money(fee) + (agreedFee != null && agreedFee.signum() > 0 ? ", as agreed." : ".")));
        }

        if (t.usageMonths() >= NO_END) {
            out.add(new Flag(Level.RED, "The brand can use your content in ads forever. Ask for a time limit, or a fee for longer use."
                    + quote(t.usageTerms())));
        } else if (t.usageMonths() > l.freeUsageMonths()) {
            out.add(new Flag(Level.AMBER, "Paid usage for " + months(t.usageMonths()) + ", more than the " + months(l.freeUsageMonths())
                    + " your fee includes. Check the fee covers it." + quote(t.usageTerms())));
        } else if (t.usageMonths() > 0) {
            out.add(new Flag(Level.OK, "Paid usage for " + months(t.usageMonths()) + "." + quote(t.usageTerms())));
        } else {
            out.add(new Flag(Level.OK, "No paid usage of your content."));
        }

        if (t.exclusivityMonths() >= NO_END) {
            out.add(new Flag(Level.RED, "Exclusivity with no end date." + quote(t.exclusivityTerms())));
        } else if (t.exclusivityMonths() > 0) {
            out.add(new Flag(Level.AMBER, "Exclusivity for " + months(t.exclusivityMonths()) + ": no work with competitors in that time."
                    + quote(t.exclusivityTerms())));
        } else if (!t.exclusivityTerms().isBlank()) {
            out.add(new Flag(Level.AMBER, "Exclusivity, length not stated." + quote(t.exclusivityTerms())));
        } else {
            out.add(new Flag(Level.OK, "No exclusivity."));
        }

        if (t.revisionRounds() < 0) {
            out.add(new Flag(Level.RED, "Unlimited revisions. Ask to cap them at " + rounds(l.revisionsIncluded()) + "."));
        } else if (t.revisionRounds() == 0) {
            out.add(new Flag(Level.AMBER, "The number of revision rounds isn't stated. Ask for " + rounds(l.revisionsIncluded()) + "."));
        } else if (t.revisionRounds() > l.revisionsIncluded()) {
            out.add(new Flag(Level.AMBER, rounds(t.revisionRounds()) + " included, more than the " + rounds(l.revisionsIncluded()) + " you usually give."));
        } else {
            out.add(new Flag(Level.OK, rounds(t.revisionRounds()) + " included."));
        }

        if (t.killFee().isBlank()) {
            out.add(new Flag(Level.AMBER, "No kill fee: if they cancel after you've started, you may not be paid."));
        } else {
            out.add(new Flag(Level.OK, "Kill fee: " + t.killFee()));
        }

        for (String c : t.concerns()) out.add(new Flag(Level.AMBER, c));
        out.sort(Comparator.comparing(Flag::level));
        return out;
    }

    public static long count(List<Flag> flags, Level level) {
        return flags.stream().filter(f -> f.level() == level).count();
    }

    private static String months(int n) {
        return n + (n == 1 ? " month" : " months");
    }

    private static String rounds(int n) {
        return n + (n == 1 ? " revision round" : " revision rounds");
    }

    private static String after(String terms) {
        String t = terms == null ? "" : terms.toLowerCase();
        if (t.contains("invoice")) return " the invoice";
        if (t.contains("post") || t.contains("live")) return " posting";
        if (t.contains("sign") || t.contains("execut")) return " signing";
        return "";
    }

    private static String quote(String asWritten) {
        return asWritten == null || asWritten.isBlank() ? "" : " (\"" + asWritten + "\")";
    }
}
