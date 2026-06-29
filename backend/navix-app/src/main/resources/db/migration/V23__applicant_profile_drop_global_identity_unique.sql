-- A returning borrower re-onboarding through a NEW application legitimately creates a second
-- applicant_profile row carrying the same pan/aadhaar/mobile. The global partial-unique indexes
-- (V12) forbade that, so drop them; cross-applicant uniqueness is now enforced at the application
-- layer (ApplicantReviewService + applicant-scoped repository checks). Keep plain indexes for lookups.
drop index if exists uq_applicant_profile_pan;
drop index if exists uq_applicant_profile_aadhaar;
drop index if exists uq_applicant_profile_mobile;
create index if not exists ix_applicant_profile_pan     on applicant_profile (pan)     where pan     is not null;
create index if not exists ix_applicant_profile_aadhaar on applicant_profile (aadhaar) where aadhaar is not null;
create index if not exists ix_applicant_profile_mobile  on applicant_profile (mobile)  where mobile  is not null;
