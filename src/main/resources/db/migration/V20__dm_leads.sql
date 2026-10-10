-- Instagram DMs: her own call on whether a conversation is a lead (overrides Claude's read), and which unanswered
-- message the reply reminder was last made for, so one message gets one reminder even if she dismisses it.
ALTER TABLE conversations ADD COLUMN lead_tag VARCHAR(10);            -- LEAD | NOT_LEAD | NULL = Claude decides
ALTER TABLE conversations ADD COLUMN reminded_message_id BIGINT;
