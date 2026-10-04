package com.creatorcrm.workflow;

import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.llm.Intent;
import java.util.EnumMap;
import java.util.Map;

import static com.creatorcrm.domain.Enums.OpportunityStatus.*;

/**
 * The single table that turns "what a message means" (AI) into "what happens next" (application).
 * Edit here to change the workflow; no prompt changes needed.
 */
public final class IntentRules {

    /** Status to move to (null = keep) and task to create (null = none). */
    public record Rule(OpportunityStatus status, TaskType task) {}

    private static final Map<Intent, Rule> RULES = new EnumMap<>(Intent.class);

    static {
        // Brand -> creator
        RULES.put(Intent.NEW_OPPORTUNITY, new Rule(AWAITING_MY_REPLY, TaskType.REPLY));
        RULES.put(Intent.RATES_REQUEST, new Rule(AWAITING_MY_REPLY, TaskType.SEND_RATES));
        RULES.put(Intent.MEDIA_KIT_REQUEST, new Rule(AWAITING_MY_REPLY, TaskType.SEND_MEDIA_KIT));
        RULES.put(Intent.AVAILABILITY_REQUEST, new Rule(AWAITING_MY_REPLY, TaskType.CONFIRM_AVAILABILITY));
        RULES.put(Intent.APPLICATION_FORM, new Rule(null, TaskType.COMPLETE_APPLICATION));
        RULES.put(Intent.NEGOTIATION, new Rule(NEGOTIATING, TaskType.NEGOTIATE));
        RULES.put(Intent.ACCEPTANCE, new Rule(CONTRACT_PENDING, TaskType.REPLY));
        RULES.put(Intent.CONTRACT_COMING, new Rule(CONTRACT_PENDING, null));
        RULES.put(Intent.CONTRACT_SENT, new Rule(CONTRACT_TO_SIGN, TaskType.SIGN_CONTRACT));
        RULES.put(Intent.PRODUCT_SHIPPED, new Rule(PRODUCT_PENDING, null));
        RULES.put(Intent.PRODUCT_DELIVERED, new Rule(PRODUCT_RECEIVED, TaskType.CONFIRM_PRODUCT));
        RULES.put(Intent.CONTENT_BRIEF, new Rule(CONTENT_TO_CREATE, TaskType.CREATE_CONTENT));
        RULES.put(Intent.CONTENT_REVISION_REQUEST, new Rule(CONTENT_TO_CREATE, TaskType.REVISE_CONTENT));
        RULES.put(Intent.CONTENT_APPROVED, new Rule(SCHEDULED_TO_POST, TaskType.POST_CONTENT));
        RULES.put(Intent.POSTING_REMINDER, new Rule(null, TaskType.POST_CONTENT));
        RULES.put(Intent.PAYMENT_UPDATE, new Rule(PAYMENT_PENDING, TaskType.CONFIRM_PAYMENT));
        RULES.put(Intent.INVOICE_REQUEST, new Rule(PAYMENT_PENDING, TaskType.SEND_INVOICE));
        RULES.put(Intent.BRAND_FOLLOW_UP, new Rule(null, TaskType.REPLY));
        RULES.put(Intent.DECLINE, new Rule(CLOSED, null));
        // Creator -> brand
        RULES.put(Intent.PITCH, new Rule(PITCHED, null));
        RULES.put(Intent.SENT_RATES_OR_MEDIA_KIT, new Rule(NEGOTIATING, null));
        RULES.put(Intent.CONTRACT_SIGNED, new Rule(CONTENT_TO_CREATE, null));
        RULES.put(Intent.CONTENT_SUBMITTED, new Rule(AWAITING_APPROVAL, null));
        RULES.put(Intent.CONTENT_POSTED, new Rule(POSTED, null));
        RULES.put(Intent.INVOICE_SENT, new Rule(PAYMENT_PENDING, null));
        RULES.put(Intent.CREATOR_DECLINED, new Rule(CLOSED, null));
    }

    private IntentRules() {}

    public static Rule of(Intent intent) {
        return RULES.getOrDefault(intent, new Rule(null, null));
    }
}
