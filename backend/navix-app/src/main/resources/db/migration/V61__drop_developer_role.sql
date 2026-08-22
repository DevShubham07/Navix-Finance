-- V61 — drop the DEVELOPER staff role.
--
-- DEVELOPER holders are deactivated, not deleted, so historical application_event.actor_id /
-- call-log rows stay resolvable. Data first, then the CHECK constraints (V55's pattern), or the
-- update below would violate the new CHECK.

update staff_user set role = 'ADMIN', status = 'DISABLED' where role = 'DEVELOPER';
delete from staff_invite where role = 'DEVELOPER';

alter table staff_user   drop constraint if exists staff_user_role_check;
alter table staff_invite drop constraint if exists staff_invite_role_check;

alter table staff_user add constraint staff_user_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','DSA','ADMIN'));

alter table staff_invite add constraint staff_invite_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','DSA','ADMIN'));

-- Unrelated parallel workstream (accountant repayment attribution over payment.decided_by /
-- decided_at, added in V59) — placed here per instruction, not split into its own migration.
create index if not exists idx_payment_decided_by_at on payment (decided_by, decided_at);
