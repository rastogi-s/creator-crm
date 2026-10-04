package com.creatorcrm.llm;

import java.time.LocalDate;

/**
 * A contract for Claude to read. {@code contractText} is already wrapped as untrusted data
 * (see {@code ContractService.wrap}); {@code dealContext} comes from the app's own records.
 */
public record ContractInput(LocalDate today, String brandName, String dealContext, String contractText) {}
