-- When a lead's website was last read for contact addresses (brands keep theirs on brand_domains.crawled_at).
ALTER TABLE brand_leads ADD COLUMN website_checked_at TIMESTAMP WITH TIME ZONE;
