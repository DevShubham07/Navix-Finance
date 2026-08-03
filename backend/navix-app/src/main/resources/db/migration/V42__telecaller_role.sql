-- V42 — TELECALLER staff role: calls the lead list and logs the outcome.
--
-- No lifecycle authority (no KYC/credit/disbursement/collections step), so it never takes part
-- in maker-checker or SoD. Extends the V8 role CHECK constraints and seeds one demo persona.

alter table staff_user   drop constraint if exists staff_user_role_check;
alter table staff_invite drop constraint if exists staff_invite_role_check;

alter table staff_user add constraint staff_user_role_check check (role in (
    'KYC_APPROVER','CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','ADMIN','DEVELOPER'));

alter table staff_invite add constraint staff_invite_role_check check (role in (
    'KYC_APPROVER','CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','ADMIN','DEVELOPER'));

-- Demo telecaller. Password: Admin@12345 — the same verified BCrypt hash V17 seeded onto every
-- demo persona (DEMO ONLY; rotate before real exposure). Guarded so a re-run is a no-op.
insert into staff_user (email, name, role, status, password_hash, created_at, created_by)
select 'tara.menon@navix.example', 'Tara Menon', 'TELECALLER', 'ACTIVE',
       '$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W',
       now(), 'seed'
 where not exists (select 1 from staff_user where email = 'tara.menon@navix.example');
