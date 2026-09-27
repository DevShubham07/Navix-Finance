-- V74 — mark a verification step the credit team explicitly reopened for the borrower to redo.
--
-- "Send the customer a link" (staff resume-link nudge for a failed/abandoned Phase-3 check) needs to
-- unstick JourneyService's derivation, which otherwise treats a row in ANY status as "attempted" and
-- so bounces the borrower past the very screen staff just sent them back to. An explicit column is
-- used instead of resetting the row to PENDING and redefining "attempted" as PASS/REVIEW/FAIL,
-- because JourneyService.attemptedChecks already counts any row (including PENDING — live borrowers
-- have genuine in-flight PENDING rows, e.g. SELFIE at liveness init, ESIGN at signing init) and
-- redefining that would rewind real in-progress applications on their next page load. This column
-- touches only rows a staffer explicitly reopened:
--   * JourneyService.attemptedChecks ignores a row while reopened_at is set.
--   * ApplicationVerificationService.upsert clears both columns on every write — the borrower's own
--     redo (retake selfie, re-run DigiLocker, re-sign) closes the reopen automatically.
--
-- Two nullable columns, no defaults, no backfill: an old task definition mid-rollout simply never
-- reads or writes them, so this is safe under a rolling ECS deploy.
ALTER TABLE application_verification
    ADD COLUMN reopened_at TIMESTAMPTZ NULL,
    ADD COLUMN reopened_by VARCHAR(64) NULL;
