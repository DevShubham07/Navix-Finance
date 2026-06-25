-- Salary-linked due date (per product owner): the due date is the latest salary credit within
-- ~40 days of disbursement, so the application carries the borrower's salary-credit day-of-month.
alter table loan_application add column if not exists salary_credit_day integer;
