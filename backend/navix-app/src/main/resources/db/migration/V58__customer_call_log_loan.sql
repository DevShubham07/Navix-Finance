-- V58 — optionally tag a customer call log with the loan it was about.
--
-- customer_call_log (V41) is filed against a customer only, which is fine for a general
-- relationship-management note but loses the thread once a customer has more than one loan over
-- time (repeat/reborrow customers are the norm, not the exception) — staff reviewing loan N's
-- collections history had no way to separate "called about this loan" from every other call ever
-- logged for the person. This column lets a call optionally point at one loan; when omitted the
-- row stays exactly what it was before, a customer-level note.
--
-- Nullable, no backfill: every existing row has no loan_id and remains customer-level. No FK
-- (schema convention — see V41's header); ownership of the loan_id (must belong to the same
-- customer_id) is enforced in the service layer, not the database.

alter table customer_call_log add column if not exists loan_id bigint;

create index if not exists idx_customer_call_log_loan on customer_call_log (loan_id, id desc);

comment on column customer_call_log.loan_id is
    'Optional loan this call was about (must belong to the row''s customer_id); null = customer-level call.';
