package com.creatorcrm.llm;

/** What a single message means. The workflow engine maps each intent to status/task changes. */
public enum Intent {
    // From a brand (inbound)
    NEW_OPPORTUNITY,
    RATES_REQUEST,
    MEDIA_KIT_REQUEST,
    AVAILABILITY_REQUEST,
    APPLICATION_FORM,
    NEGOTIATION,
    ACCEPTANCE,
    CONTRACT_COMING,
    CONTRACT_SENT,
    PRODUCT_SHIPPED,
    PRODUCT_DELIVERED,
    CONTENT_BRIEF,
    CONTENT_REVISION_REQUEST,
    CONTENT_APPROVED,
    POSTING_REMINDER,
    PAYMENT_UPDATE,
    INVOICE_REQUEST,
    BRAND_FOLLOW_UP,
    DECLINE,
    GENERAL_REPLY,
    // From the creator (outbound)
    PITCH,
    SENT_RATES_OR_MEDIA_KIT,
    CONTRACT_SIGNED,
    CONTENT_SUBMITTED,
    CONTENT_POSTED,
    INVOICE_SENT,
    CREATOR_FOLLOW_UP,
    CREATOR_DECLINED,
    CREATOR_REPLY,
    // Anything else
    NOT_BRAND_RELATED,
    OTHER
}
