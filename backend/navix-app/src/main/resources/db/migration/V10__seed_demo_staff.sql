-- V10: seed demo staff_user rows.
--
-- The demo "staff login" is cookie-only and never persisted a StaffUser, so the
-- staff_user table was empty. That left the admin staff page, the collections
-- assignee pickers, and (newly) the credit "assign an executive" dropdown with
-- nothing to show. Seed an ACTIVE roster mirroring the login personas, with
-- THREE active Credit Executives so the assign dropdown is meaningful.
--
-- Roles use the post-V8 names (COLLECTION_HEAD / COLLECTION_EXECUTIVE / DEVELOPER).
-- id is identity (omitted); created_at is required.

insert into staff_user (email, name, role, status, created_at, created_by) values
  ('ananya.rao@navix.example',      'Ananya Rao',      'KYC_APPROVER',         'ACTIVE', now(), 'seed'),
  ('rahul.mehta@navix.example',     'Rahul Mehta',     'CREDIT_EXECUTIVE',     'ACTIVE', now(), 'seed'),
  ('kabir.singh@navix.example',     'Kabir Singh',     'CREDIT_EXECUTIVE',     'ACTIVE', now(), 'seed'),
  ('neha.gupta@navix.example',      'Neha Gupta',      'CREDIT_EXECUTIVE',     'ACTIVE', now(), 'seed'),
  ('priya.nair@navix.example',      'Priya Nair',      'CREDIT_HEAD',          'ACTIVE', now(), 'seed'),
  ('vikram.shah@navix.example',     'Vikram Shah',     'DISBURSEMENT_HEAD',    'ACTIVE', now(), 'seed'),
  ('deepa.iyer@navix.example',      'Deepa Iyer',      'ACCOUNTANT',           'ACTIVE', now(), 'seed'),
  ('arjun.patel@navix.example',     'Arjun Patel',     'COLLECTION_HEAD',      'ACTIVE', now(), 'seed'),
  ('sana.khan@navix.example',       'Sana Khan',       'COLLECTION_EXECUTIVE', 'ACTIVE', now(), 'seed'),
  ('meera.krishnan@navix.example',  'Meera Krishnan',  'ADMIN',                'ACTIVE', now(), 'seed'),
  ('dev.ops@navix.example',         'Dev Ops',         'DEVELOPER',            'ACTIVE', now(), 'seed');
