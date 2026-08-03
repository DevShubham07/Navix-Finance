-- V41 — Customer ownership (staff book of business) + call logs.
-- Sparse ownership: no row = Unallocated. No FKs (schema convention; hand cascade in deleteCustomer).

create table customer_owner (
    customer_id    bigint primary key,
    owner_staff_id bigint      not null,
    assigned_at    timestamptz not null default now()
);
create index idx_customer_owner_staff on customer_owner (owner_staff_id);

create table customer_call_log (
    id          bigserial primary key,
    customer_id bigint      not null,
    call_type   varchar(24) not null,   -- OUTBOUND | INBOUND | MISSED
    outcome     varchar(32) not null,   -- CONNECTED | NO_ANSWER | CALLBACK | REFUSED | WRONG_NUMBER
    callback_on date,
    notes       text,
    created_at  timestamptz not null,
    created_by  varchar(160),
    updated_at  timestamptz,
    updated_by  varchar(160)
);
create index idx_customer_call_log_customer on customer_call_log (customer_id, id desc);
create index idx_customer_call_log_callback on customer_call_log (callback_on) where callback_on is not null;
