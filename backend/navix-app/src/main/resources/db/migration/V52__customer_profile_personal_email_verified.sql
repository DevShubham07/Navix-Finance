-- Personal-email OTP (additive). customer_profile.email_verified stays what it always was: the
-- Signzy/Digitap deliverability + employer-match result on official_email. This new column records
-- that the borrower proved control of their PERSONAL inbox by entering an emailed code.
alter table customer_profile add column personal_email_verified boolean;
