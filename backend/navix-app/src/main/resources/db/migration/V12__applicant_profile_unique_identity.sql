-- Unique borrower identity at signup: a mobile number, PAN, and Aadhaar may each
-- belong to only one applicant profile. Partial unique indexes (WHERE ... IS NOT
-- NULL) so profiles that have not captured a field yet (multiple NULLs) are still
-- allowed; only populated values must be unique.
--
-- NOTE: Aadhaar is stored in full here at the project owner's explicit request.
-- It is masked on every read path (see ReviewDtos.ProfileView). Revisit storing a
-- hash instead before go-live (project rule: never persist the raw Aadhaar number).

alter table applicant_profile add column aadhaar varchar(12);
alter table applicant_profile add column mobile  varchar(15);

create unique index uq_applicant_profile_pan     on applicant_profile (pan)     where pan     is not null;
create unique index uq_applicant_profile_aadhaar on applicant_profile (aadhaar) where aadhaar is not null;
create unique index uq_applicant_profile_mobile  on applicant_profile (mobile)  where mobile  is not null;
