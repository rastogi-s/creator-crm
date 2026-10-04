-- Deal dates on her Google Calendar: the event each deadline became, and what it last showed.
ALTER TABLE deadlines ADD COLUMN calendar_event_id VARCHAR(1024);
ALTER TABLE deadlines ADD COLUMN calendar_fingerprint VARCHAR(64);
