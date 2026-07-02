-- V35 — Drop the raw Aadhaar NUMBER from the customer profile.
--
-- Product decision (instant-loan model): the borrower no longer types an Aadhaar number, and the CRM
-- no longer displays one. Identity is anchored on PAN + mobile + DigiLocker verification. The DigiLocker
-- signals (aadhaar_verified / aadhaar_linked) are KEPT — only the plaintext 12-digit column is removed.
-- (The lookup index was ix_customer_profile_aadhaar after V23 relaxed it + V33 renamed it.)

drop index if exists ix_customer_profile_aadhaar;
alter table customer_profile drop column if exists aadhaar;
