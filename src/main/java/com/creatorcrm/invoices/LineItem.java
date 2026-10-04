package com.creatorcrm.invoices;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;

/** One row on an invoice. Stored as JSON in {@code invoices.line_items}. */
public record LineItem(String description, BigDecimal amount) {
    private static final ObjectMapper JSON = new ObjectMapper();

    static List<LineItem> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<LineItem>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invoice line items are damaged", e);
        }
    }

    static String toJson(List<LineItem> items) {
        try {
            return JSON.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
