-- P2: derived verification flags on the applicant KYC snapshot. Aadhaar/PAN stay
-- masked on read; full-Aadhaar masking remains deferred (plan decision 7).
alter table applicant_profile add column bureau_score         bigint;
alter table applicant_profile add column bureau_source        varchar(40);
alter table applicant_profile add column risk_category        varchar(4);
alter table applicant_profile add column pan_verified         boolean;
alter table applicant_profile add column aadhaar_linked       boolean;
alter table applicant_profile add column email_verified       boolean;
alter table applicant_profile add column address_verified     boolean;
alter table applicant_profile add column penny_drop_verified  boolean;
alter table applicant_profile add column name_match_score     double precision;
alter table applicant_profile add column digilocker_client_id varchar(120);
alter table applicant_profile add column agreement_accepted   boolean;
