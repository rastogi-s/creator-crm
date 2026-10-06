-- The HTML version of an email (links, pictures, layout), fetched from Gmail the first time it's opened in the app
-- and cleaned every time it's shown. NULL = not fetched yet; '' = the email has no HTML version.
ALTER TABLE messages ADD COLUMN html_content VARCHAR;
