-- Scheduled drafts: an email she approved to go out at a time she picked. Null = not scheduled.
ALTER TABLE drafts ADD COLUMN scheduled_at TIMESTAMP WITH TIME ZONE;
