-- Office-email OTP (additive), mirroring V52's personal_email_verified.
--
-- Deliberately NOT named official_email_verified: customer_profile.email_verified already means
-- "the Signzy/Digitap deliverability + employer-match check on official_email passed". That check
-- and this one now target the SAME address for different things — one corroborates the employer,
-- one proves the borrower can open the inbox — so the names must not be confusable.
alter table customer_profile add column official_email_otp_verified boolean;

-- Carry the personal-email OTP forward onto profiles a past reborrow cloned.
--
-- ApplicationFlowService.copyProfileForReborrow copies email, official_email and email_verified but
-- has never copied personal_email_verified. That omission was invisible while JourneyService.derive
-- only looked at official_email; now that it reads the flag, every already-cloned profile would send
-- a returning borrower back to screen 6 to re-prove an inbox they already proved. The code path is
-- fixed alongside this migration; this repairs the rows it already produced.
update customer_profile c set personal_email_verified = true
 where c.personal_email_verified is null
   and exists (select 1
                 from customer_profile p
                 join loan_application a on a.id = p.application_id
                 join loan_application b on b.id = c.application_id
                where a.customer_id = b.customer_id
                  and p.personal_email_verified is true);

-- official_email_otp_verified is deliberately NOT backfilled: nobody has ever proved control of a
-- work inbox, so asserting it would be a lie in the audit trail.
