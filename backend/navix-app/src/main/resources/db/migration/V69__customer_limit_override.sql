-- V69 — ADMIN per-customer eligible-limit override.
--
-- The eligible limit has always been derived, never decided: 25% of monthly salary, floored to ₹100
-- (LimitCalculator / LoanMath). An admin who wants to give one person more headroom had no control —
-- and could not get one by editing loan_application.eligible_limit, because that value is re-derived
-- from salary on payslip verification, on a salary edit, and on every reborrow (a NEW application
-- row), so an in-place edit is silently overwritten.
--
-- So the override is stored per CUSTOMER and wins over the formula wherever the limit is recomputed
-- (EligibilityService.effectiveLimitPaise). Sparse, like customer_owner (V41): no row = the
-- 25%-of-salary rule. No FKs (schema convention); hand-cascaded in CustomerService.deleteCustomer.
--
-- limit_paise has no upper ceiling — an admin may exceed the ₹10,00,000 instant-loan cap the salary
-- formula applies. The floor is the usual ₹1,000 minimum loan, enforced in the service.

create table customer_limit_override (
    customer_id bigint primary key,
    limit_paise bigint      not null,
    note        text,
    set_by      bigint,
    set_at      timestamptz not null default now()
);
