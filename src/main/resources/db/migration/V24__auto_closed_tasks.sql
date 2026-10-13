-- To-dos and deadlines the app closes on its own (she replied, the deal moved on, the date passed) say why, so she
-- can trust it and reopen one if it was wrong. A reopened one is kept open by the date rules from then on.
ALTER TABLE tasks ADD COLUMN auto_closed_reason VARCHAR(500);
ALTER TABLE tasks ADD COLUMN kept_open BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE deadlines ADD COLUMN done_reason VARCHAR(500);
ALTER TABLE deadlines ADD COLUMN done_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE deadlines ADD COLUMN kept_open BOOLEAN NOT NULL DEFAULT FALSE;
