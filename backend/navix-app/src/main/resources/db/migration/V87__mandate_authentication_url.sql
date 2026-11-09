-- V87 — store the Digio authorisation url with the mandate attempt that owns it.
--
-- WHY: `init` had no way to send a borrower back to an attempt already in flight, so every return to
-- the mandate screen minted a NEW mandate at NPCI. On 2026-10-20 one borrower filed seven for the same
-- Kotak account in 80 minutes and the destination bank began refusing them as duplicates of each other
-- ("Duplicate request, retry next day"), blocking every verification mode. See
-- docs/digio/13-live-verification-log.md section 13.
--
-- The url belongs to ONE attempt and expires with it (navix.digio.expire-in-days), so validity is
-- derived from registered_at rather than stored: a second column would just be another thing to drift.
--
-- Deliberately NOT exposed on MandateService.MandateView — staff have no use for a borrower's
-- authorisation link, and it is a bearer url for a legally binding mandate.
alter table mandate add column authentication_url varchar(512);

comment on column mandate.authentication_url is
    'Digio authorisation url for THIS attempt. Valid until registered_at + navix.digio.expire-in-days; '
    'reused by MandateService.init so a returning borrower is not given a second NPCI mandate.';
