-- The application aggregate (dfd.md §8) is created at DRAFT before the borrower applies; the
-- requested amount is captured later, at the apply() step. Relax the NOT NULL accordingly.
alter table loan_application alter column amount_requested drop not null;
