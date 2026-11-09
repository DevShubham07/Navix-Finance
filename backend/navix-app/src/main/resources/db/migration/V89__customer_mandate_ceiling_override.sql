-- V89 — ADMIN per-customer override of the eMandate ceiling.
--
-- A new NACH mandate is registered for 2 x max(eligible limit, sanctioned amount)
-- (MandateService.ceiling). An admin sometimes needs a customer's mandate registered for more than
-- that — e.g. so one authorisation covers larger future advances — without raising what they may
-- BORROW. customer_limit_override (V69) cannot do that: it changes the lending limit itself.
--
-- So this stores only the mandate ceiling, per customer (a mandate is a per-customer authority).
-- Sparse: no row = the formula. It applies to NEW registrations; an existing mandate keeps the
-- ceiling it was authorised for (raising that needs the borrower to authorise again). No FKs
-- (schema convention); hand-cascaded in CustomerService.deleteCustomer.
create table customer_mandate_ceiling_override (
    customer_id   bigint primary key,
    ceiling_paise bigint       not null,
    note          text,
    set_by        varchar(200),
    set_at        timestamptz  not null default now()
);
