-- Win back past brands: when the app last drafted a re-pitch to this brand, so the same brand isn't picked again
-- until the quiet period (60 days by default) has passed, whether she sent that draft or not.
ALTER TABLE brands ADD COLUMN last_repitch_at TIMESTAMP WITH TIME ZONE;
