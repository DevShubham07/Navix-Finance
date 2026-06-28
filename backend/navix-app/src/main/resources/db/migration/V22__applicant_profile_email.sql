-- V22: persist the borrower's contact email for the email notification channel.
-- Nullable on purpose — older/returning borrowers may not have one, and the EMAIL channel is
-- address-gated (a missing email simply skips that channel). Independent of V21 (notification core);
-- ordered by number only.
alter table applicant_profile add column email varchar(255);
