-- V29: denormalized "Aadhaar verified" flag on the applicant profile, set when DigiLocker completes
-- (Aadhaar fetched + document ingested). Mirrors the existing pan_verified / address_verified /
-- penny_drop_verified flags so the staff profile-details card can show Aadhaar status without joining
-- the verification rows. Nullable (unknown until DigiLocker runs).
alter table applicant_profile
    add column aadhaar_verified boolean;
