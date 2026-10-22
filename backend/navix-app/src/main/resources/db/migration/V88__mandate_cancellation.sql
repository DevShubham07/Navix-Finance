-- V88 — record who cancelled a mandate, when, and why.
--
-- WHY: staff can now cancel an ACTIVE mandate (a borrower's request, or to replace it with one carrying a
-- higher ceiling), not just a stale attempt. That revokes a live debit authority at NPCI, so the row must
-- say who did it and why — the status alone folds cancelled onto AUTHORIZATION_FAILED (see
-- MandateService.revoked) and cannot.
--
-- cancelled_at is also a fence: a cancelled row is never re-read from the provider and re-adopted, even
-- if Digio still reports it active while the bank processes the cancellation.
alter table mandate add column cancelled_at  timestamptz;
alter table mandate add column cancelled_by  varchar(200);
alter table mandate add column cancel_reason varchar(500);

comment on column mandate.cancelled_at is
    'When staff cancelled this mandate at the provider. Non-null rows are never re-adopted by refresh/settle.';
