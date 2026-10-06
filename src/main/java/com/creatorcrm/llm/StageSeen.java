package com.creatorcrm.llm;

import com.creatorcrm.domain.Enums.DealStage;

/** Where Claude reads a deal to stand after a message. The workflow engine only ever moves a deal forward with it. */
public enum StageSeen {
    NOT_AGREED_YET(null),
    CONTRACT(DealStage.CONTRACT),
    PRODUCT(DealStage.PRODUCT),
    CREATE_CONTENT(DealStage.CREATE_CONTENT),
    BRAND_APPROVAL(DealStage.BRAND_APPROVAL),
    POST(DealStage.POST),
    INVOICE(DealStage.INVOICE),
    PAYMENT(DealStage.PAYMENT),
    DONE(DealStage.DONE),
    UNCLEAR(null);

    public final DealStage stage;

    StageSeen(DealStage stage) { this.stage = stage; }
}
