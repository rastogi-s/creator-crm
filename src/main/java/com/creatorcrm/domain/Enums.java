package com.creatorcrm.domain;

import java.util.EnumSet;
import java.util.Set;

/** All domain enums in one place. */
public final class Enums {
    private Enums() {}

    public enum Platform { EMAIL, INSTAGRAM, OTHER }

    public enum Direction { INBOUND, OUTBOUND }

    public enum Origin { INBOUND, PITCH }

    public enum OpportunityStatus {
        NEW_LEAD("🆕 New Lead"),
        PITCHED("📤 Pitched"),
        AWAITING_MY_REPLY("📩 Awaiting My Reply"),
        NEGOTIATING("💰 Negotiating"),
        CONTRACT_PENDING("📝 Contract Pending"),
        CONTRACT_TO_SIGN("✍️ Contract to Sign"),
        PRODUCT_PENDING("📦 Product Pending"),
        PRODUCT_RECEIVED("📦 Product Received"),
        CONTENT_TO_CREATE("🎬 Content To Create"),
        AWAITING_APPROVAL("👀 Awaiting Approval"),
        SCHEDULED_TO_POST("📅 Scheduled to Post"),
        POSTED("✅ Posted"),
        PAYMENT_PENDING("💵 Payment Pending"),
        FOLLOW_UP_NEEDED("🔄 Follow-Up Needed"),
        COLD("🧊 Cold"),
        CLOSED("❌ Closed");

        public final String label;

        OpportunityStatus(String label) { this.label = label; }

        public boolean isOpen() { return this != CLOSED && this != COLD; }
    }

    public enum OpportunityType {
        PAID, UGC, GIFTED, AFFILIATE, AMBASSADOR, CREATOR_APPLICATION, LONG_TERM, CAMPAIGN,
        RATES_REQUEST, AVAILABILITY_REQUEST, OTHER
    }

    public enum Compensation { PAID, GIFTED, AFFILIATE, UNKNOWN }

    public enum Priority { HIGH, MEDIUM, LOW }

    public enum TaskStatus { OPEN, DONE, DISMISSED }

    public enum TaskType {
        REPLY, SEND_RATES, SEND_MEDIA_KIT, CONFIRM_AVAILABILITY, NEGOTIATE, ASK_MISSING_INFO,
        COMPLETE_APPLICATION, SIGN_CONTRACT, CONFIRM_PRODUCT, CREATE_CONTENT, REVISE_CONTENT,
        SUBMIT_CONTENT, POST_CONTENT, SEND_INVOICE, CHASE_PAYMENT, CONFIRM_PAYMENT, DECIDE, OTHER;

        /** Tasks that are satisfied by the creator sending a message in the conversation. */
        public static final Set<TaskType> ANSWERED_BY_OUTBOUND = EnumSet.of(
                REPLY, SEND_RATES, SEND_MEDIA_KIT, CONFIRM_AVAILABILITY, NEGOTIATE, ASK_MISSING_INFO);
    }

    public enum FollowUpStatus { SCHEDULED, DONE, CANCELLED }

    public enum DeadlineType { CONTENT_DUE, CONTRACT, APPLICATION, POSTING, APPROVAL, LAUNCH, PAYMENT, OTHER }

    public enum DraftStatus { PENDING, SENT, DISCARDED, SUPERSEDED, FAILED }

    public enum DraftType {
        REPLY, RATES, MEDIA_KIT, NEGOTIATION, FOLLOW_UP, CONTRACT_CONFIRMATION, CONTENT_SUBMISSION,
        PRODUCT_ARRIVAL, DECLINE, ASK_BUDGET, ASK_USAGE_RIGHTS, ASK_DETAILS, PITCH, REPITCH, INVOICE, PAYMENT_REMINDER, OTHER
    }

    public enum InvoiceStatus { DRAFT, SENT, PAID, VOID }
}
