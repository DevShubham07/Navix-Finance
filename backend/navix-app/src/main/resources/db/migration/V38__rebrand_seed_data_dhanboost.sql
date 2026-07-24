-- V38 — Rebrand user-visible seed DATA from "NAVIX" to "DhanBoost".
--
-- The V18/V19 migrations seeded brand strings into rows that borrowers/staff SEE
-- (the payment beneficiary name, the demo admin's display name). Those already-applied
-- migrations are immutable (editing them breaks Flyway checksum validation on existing
-- databases), so the rebrand of their DATA is done here as a forward, guarded, idempotent
-- UPDATE. Only the seeded values are touched — an ADMIN-edited value is left alone.
--
-- Deliberately NOT changed: the admin LOGIN email (navixfinance@gmail.com) and the UPI VPA
-- (navix.collections@hdfcbank) are functional identifiers / credentials, not display brand.

-- Payment beneficiary name shown to borrowers on the pay screen / QR instructions.
update payment_settings
   set account_name = 'DhanBoost'
 where account_name = 'NAVIX Finance';

-- Seeded ADMIN staff display name.
update staff_user
   set name = 'DhanBoost Admin'
 where name = 'NAVIX Admin';
