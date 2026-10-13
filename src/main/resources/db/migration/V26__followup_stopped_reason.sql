-- Follow-ups the app stops on its own (an email bounced, the address is on the do-not-email list) say why, in the
-- deal and under "Closed for you", so she can put one back if it was wrong.
ALTER TABLE followups ADD COLUMN stopped_reason VARCHAR(500);
ALTER TABLE followups ADD COLUMN stopped_at TIMESTAMP WITH TIME ZONE;
