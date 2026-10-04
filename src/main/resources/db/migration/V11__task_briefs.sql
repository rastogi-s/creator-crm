-- What a to-do is about: Claude's short brief of the email (what is asked, the opportunity, what to have ready) and
-- the links from it she needs (forms, briefs, contracts), as JSON [{"label": ..., "url": ...}].
ALTER TABLE tasks ADD COLUMN brief VARCHAR(2000);
ALTER TABLE tasks ADD COLUMN links_json VARCHAR;
