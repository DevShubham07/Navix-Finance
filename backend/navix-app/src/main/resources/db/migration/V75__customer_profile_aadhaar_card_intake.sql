-- V75 — Aadhaar number captured again at intake (reverses V35 by product decision, 2026-10-03).
--
-- The Phase-1 wizard gains two mandatory document screens between salary slips and consent:
--   * /signup/aadhaar  — the borrower TYPES their 12-digit Aadhaar number and uploads both sides of the
--                        card (document types AADHAAR_CARD_FRONT / AADHAAR_CARD_BACK, distinct from the
--                        AADHAAR_FRONT / AADHAAR_BACK pair the post-sanction DigiLocker fallback writes,
--                        so staff can tell the two uploads apart in the Documents tab);
--   * /signup/pan-card — both sides of the PAN card (PAN_CARD_FRONT / PAN_CARD_BACK).
-- submit-kyc now refuses (KYC_INCOMPLETE) until the number and all four images are on file.
--
-- The typed number is stored IN FULL, in plaintext, on the profile — V35 dropped exactly this column
-- when identity was re-anchored on PAN + mobile + DigiLocker. It comes back because the number now
-- drives a fraud rule: the last four digits are cross-checked against the masked Aadhaar the PAN
-- record (Fintrix pan_comprehensive) and DigiLocker return, and a mismatch auto-rejects the
-- application into the rejection register under the new reason code FRAUD_REJECTED. Staff and ADMIN
-- read the number unmasked on the customer Personal tab and the applicant review panel.
--
-- The lookup index backs the cross-customer duplicate check (DUPLICATE_AADHAAR), the same shape as
-- the PAN / mobile uniqueness rules in CustomerReviewService.saveProfile. Nullable, no backfill:
-- applications submitted before this migration simply have no number, and the gate only runs on
-- DRAFT → KYC_PENDING, so nothing already past intake is affected.
ALTER TABLE customer_profile
    ADD COLUMN aadhaar VARCHAR(12) NULL;

CREATE INDEX ix_customer_profile_aadhaar
    ON customer_profile (aadhaar)
    WHERE aadhaar IS NOT NULL;
