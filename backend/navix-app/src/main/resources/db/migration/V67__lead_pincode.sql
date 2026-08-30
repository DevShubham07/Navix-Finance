-- Admin CSV lead import (2026-08-30): the upload carries a postal pincode, which no lead column held.
-- Additive only; telecaller/DSA leads keep null.
alter table lead add column pincode varchar(6);
alter table lead add constraint chk_lead_pincode check (pincode is null or pincode ~ '^[1-9][0-9]{5}$');
