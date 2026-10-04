-- Payment reminders: how many reminder emails went out for an unpaid invoice, and when the last one did.
-- Reminders are due a set number of days after the invoice's due date (3, 7 and 14 by default) and stop once
-- the invoice is paid or voided.
ALTER TABLE invoices ADD COLUMN reminders_sent INTEGER NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN last_reminder_on DATE;
