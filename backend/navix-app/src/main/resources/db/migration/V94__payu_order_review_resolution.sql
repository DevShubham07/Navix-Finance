-- V94 — staff review queue for PayU orders (payu_order.review_reason set, V93).
--
-- A review row is money that was captured but not credited, needs a refund, or looks wrong. Resolving one only
-- records that a person dealt with it (who, when, why); it never changes review_reason, status or payment_id and
-- never credits or refunds (refunds are made in the PayU dashboard). Nullable, no backfill: existing rows are
-- simply unresolved.

alter table payu_order
    add column review_resolved_at timestamptz,
    add column review_resolved_by bigint,
    add column review_note        text;

create index idx_payu_order_review_open on payu_order (created_at desc)
    where review_reason is not null and review_resolved_at is null;
