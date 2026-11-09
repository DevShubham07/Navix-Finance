-- V95 — admin customer messaging, repay-now fee discount, ADMIN "allow re-application".
--
-- customer_message         ledger of every admin send attempt (one row per channel, incl. FAILED/SKIPPED).
-- repayment_discount_offer a discount of 1..7% of the NEW loan's principal taken off the processing fee,
--                          unlocked by full repayment by repay_by, used once on the next loan.
-- reapply_allowance        one-use ADMIN permission for a disqualified customer to reach credit review.
-- loan_application / loan  carry the pinned discount so summary, Key Fact Statement and disbursal agree.
-- feature_flag             admin-messaging, shipped OFF (it sends real messages and changes money).

create table customer_message (
    id                bigserial primary key,
    customer_id       bigint       not null,
    application_id    bigint,
    loan_id           bigint,
    template_key      varchar(64)  not null,
    provider_template varchar(128) not null,
    channel           varchar(16)  not null,
    mobile            varchar(20),
    params            text,
    status            varchar(16)  not null check (status in ('SENT', 'FAILED', 'SKIPPED')),
    reason            varchar(1000),
    provider_ref      varchar(200),
    discount_percent  smallint,
    repay_by          date,
    sent_by_staff_id  bigint,
    sent_by_name      varchar(200),
    created_at        timestamptz  not null default now()
);
create index idx_customer_message_customer on customer_message (customer_id);
create index idx_customer_message_created on customer_message (created_at desc);
create index idx_customer_message_dedupe on customer_message (customer_id, template_key, channel, created_at);

create table repayment_discount_offer (
    id                    bigserial primary key,
    customer_id           bigint      not null,
    loan_id               bigint      not null,
    percent               smallint    not null check (percent between 1 and 7),
    repay_by              date        not null,
    message_id            bigint,
    created_by_staff_id   bigint,
    created_at            timestamptz not null default now(),
    used_application_id   bigint,
    used_loan_id          bigint,
    used_at               timestamptz
);
create index idx_repayment_discount_offer_customer on repayment_discount_offer (customer_id);

create table reapply_allowance (
    id                    bigserial primary key,
    customer_id           bigint       not null,
    reason                text         not null,
    document_id           bigint       not null,
    granted_by_staff_id   bigint,
    granted_by_name       varchar(200),
    granted_at            timestamptz  not null default now(),
    used_application_id   bigint,
    used_at               timestamptz
);
create index idx_reapply_allowance_customer on reapply_allowance (customer_id);

alter table loan_application
    add column fee_discount_percent  smallint,
    add column fee_discount_offer_id bigint;

alter table loan
    add column fee_discount_paise bigint not null default 0;

insert into feature_flag (flag_key, enabled, description, created_at)
values ('admin-messaging', false,
        'ADMIN customer messaging (SMS/WhatsApp templates) and the repay-now fee discount offers. OFF: '
            || 'send, preview and new offers refuse; already-issued offers stay honoured.',
        now())
on conflict (flag_key) do nothing;
