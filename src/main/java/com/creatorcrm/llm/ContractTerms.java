package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** What a brand contract says, as Claude read it. 0 / empty means "not stated in the contract". */
public record ContractTerms(
        @JsonPropertyDescription("Days until the creator is paid, counted from the invoice, posting or signing date as written, e.g. 30 for 'net 30'. 0 if not stated.")
        int paymentDays,
        @JsonPropertyDescription("Payment terms as written, e.g. 'Net 60 from receipt of invoice'. Empty if not stated.")
        String paymentTerms,
        @JsonPropertyDescription("Total fee to the creator as a number. 0 if not stated or product only.")
        double fee,
        @JsonPropertyDescription("ISO currency code of the fee, e.g. USD. Empty if unknown.")
        String currency,
        @JsonPropertyDescription("Months the brand may use the content in paid ads, whitelisting, Spark Ads or licensing. 0 if none or organic reposting only. 999 if perpetual, unlimited or 'in all media'.")
        int usageMonths,
        @JsonPropertyDescription("Usage-rights terms as written. Empty if not stated.")
        String usageTerms,
        @JsonPropertyDescription("Months the creator may not work with competitors. 0 if no exclusivity. 999 if it has no end date.")
        int exclusivityMonths,
        @JsonPropertyDescription("Exclusivity terms as written, including which competitors. Empty if none.")
        String exclusivityTerms,
        @JsonPropertyDescription("Revision rounds included. 0 if not stated. -1 if unlimited or at the brand's discretion.")
        int revisionRounds,
        @JsonPropertyDescription("Kill fee or cancellation payment terms as written. Empty if the contract has none.")
        String killFee,
        @JsonPropertyDescription("Dates the creator must meet, resolved to absolute dates.")
        List<ContractDate> deadlines,
        @JsonPropertyDescription("Up to 5 other clauses a creator should look at closely, each in plain words, e.g. 'The brand owns the content forever', 'Auto-renews every year', 'You pay back the fee if the post is removed early'. Empty if none.")
        List<String> concerns,
        @JsonPropertyDescription("A plain 2-3 sentence summary of the deal the contract describes.")
        String summary) {

    public record ContractDate(
            @JsonPropertyDescription("YYYY-MM-DD")
            String date,
            @JsonPropertyDescription("What is due, e.g. 'Draft video to the brand'.")
            String what) {}
}
