-- V36 — Staff remarks on a customer (shown in the customer detail popup + activity timeline).
-- Append-only; who/when come from the BaseAuditEntity audit columns (created_by / created_at).

create table customer_remark (
    id          bigserial primary key,
    customer_id bigint not null,
    body        text   not null,
    created_at  timestamptz not null,
    created_by  varchar(160),
    updated_at  timestamptz,
    updated_by  varchar(160)
);

create index idx_customer_remark_customer on customer_remark (customer_id, id desc);
