-- V72 — switch off the two Digitap products that have never worked, and add the employment-retry
-- kill switch (vendor API failure investigation 2026-09-17, docs/vendor-api/).
--
-- digitap-pan   : /validation/kyc/v1/pan_details_plus answered 412 "Precondition Failed" on 96 of 96
--                 live calls across the whole 90-day audit window — zero successes, ever. It sat in
--                 the PAN chain behind Signzy, so every Signzy failure bought a guaranteed-dead call
--                 before reaching Fintrix.
-- digitap-email : /cv/email_verification/v1, same story — 39 of 39, zero successes. It is the LAST
--                 email leg, so its 412 was what staff saw as the reason the check was unavailable.
--
-- Neither is a code defect: the products are not provisioned on our Digitap account. The adapter now
-- reads these flags with defaultWhenMissing=FALSE and throws CapabilityNotSupportedException, which
-- the router skips silently (exactly like Signzy's retired bureau leg). The day Digitap provisions
-- either product, flip the row — no redeploy:
--     update feature_flag set enabled = true where flag_key = 'digitap-pan';
--
-- employment-auto-retry : gates EmploymentRetryScheduler, which re-runs EPFO checks that a vendor
--                 outage parked (173 were stranded by a 27-hour Digitap balance outage on 2026-09-08
--                 and never retried). A successful re-run is the one billable UAN outcome, so this
--                 needs an instant off switch. Enabled by default; `do nothing` on conflict so an
--                 operator who has already turned it off is not overridden by a redeploy.
insert into feature_flag (flag_key, enabled, description, created_at)
values ('digitap-pan', false,
        'Digitap PAN Details Plus fallback leg. OFF: product not provisioned (412 on 96/96 calls, 0 successes). Enable when Digitap provisions it.',
        now())
on conflict (flag_key) do update set enabled = false;

insert into feature_flag (flag_key, enabled, description, created_at)
values ('digitap-email', false,
        'Digitap Email Verification v1 fallback leg. OFF: product not provisioned (412 on 39/39 calls, 0 successes). Enable when Digitap provisions it.',
        now())
on conflict (flag_key) do update set enabled = false;

insert into feature_flag (flag_key, enabled, description, created_at)
values ('employment-auto-retry', true,
        'Hourly re-run of EPFO/UAN checks parked by a vendor outage (402/5xx/transport), undecided applications only, capped at 5 attempts with exponential backoff.',
        now())
on conflict (flag_key) do nothing;
