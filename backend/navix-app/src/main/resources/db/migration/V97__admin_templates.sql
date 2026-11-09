-- V97 — ADMIN "Templates" library (SMS / WhatsApp / Email). Ships inert: every write and send is gated
-- by the existing admin-messaging flag (V95, false).
--
-- body        the registered wording exactly: SMS with {#var#}, WhatsApp and email with {{1}}, {{2}}...
-- variables   JSON array, one entry per placeholder: an auto-filled name, or in:text:<label> / in:date:<label>
-- provider_id SMS: the 19-digit DLT template id. WhatsApp: the name SmartChat really stored (may end _164).
-- status      DRAFT, SUBMITTED, ACTIVE, REJECTED. Only ACTIVE can be sent.
-- system_managed  the row is listed here but sent only by its own flow, never by hand.

create table message_template (
    id                  bigserial primary key,
    channel             varchar(16)  not null check (channel in ('SMS', 'WHATSAPP', 'EMAIL')),
    name                varchar(128) not null,
    subject             varchar(200),
    body                text,
    category            varchar(16)  not null default 'SERVICE' check (category in ('SERVICE', 'MARKETING')),
    status              varchar(16)  not null check (status in ('DRAFT', 'SUBMITTED', 'ACTIVE', 'REJECTED')),
    provider_id         varchar(128),
    rejection_reason    varchar(1000),
    variables           text         not null default '[]',
    rule                varchar(16)  not null default 'LIVE' check (rule in ('ANY', 'LIVE', 'OVERDUE')),
    confirm_text        varchar(300),
    system_managed      boolean      not null default false,
    created_by_staff_id bigint,
    created_by_name     varchar(200),
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now(),
    unique (channel, name)
);
create unique index uq_message_template_provider on message_template (channel, provider_id)
    where provider_id is not null;

-- Email has no mobile number; the ledger keeps which address the message went to.
alter table customer_message add column email varchar(255);

-- The nine loan-status SMS, registered on DLT. Wording from the DLT filing script of 2026-11-09.
insert into message_template (channel, name, body, category, status, provider_id, variables, rule, confirm_text, created_by_name)
select 'SMS', v.name, v.body, 'SERVICE', 'ACTIVE', v.dlt, v.vars, 'OVERDUE', v.confirm, 'DLT filing'
from (values
    ('DHANBOOST_OVERDUE_STATUS_V1', '1777179420403448844', '[]',
     'Your DhanBoost loan has an overdue amount. Log in at https://dhanboost.com/login to view your account status and pay. - DhanBoost',
     null),
    ('DHANBOOST_OVERDUE_SOFT_D7_V1', '1777179420408031626', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} is pending. Please pay at https://dhanboost.com/login. - DhanBoost',
     null),
    ('DHANBOOST_OVERDUE_SOFT_D15_V1', '1777179420411989864', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} is due. Timely payment helps credit. Pay at https://dhanboost.com/login. - DhanBoost',
     null),
    ('DHANBOOST_OVERDUE_SOFT_D30_V1', '1777179420413642807', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} remains pending. Please pay or contact us at https://dhanboost.com/login. - DhanBoost',
     null),
    ('DHANBOOST_OVERDUE_STRICT_D7_V1', '1777179420417807675', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} is overdue. Dues may be reported. Pay today at https://dhanboost.com/login. - DhanBoost',
     'MLB has confirmed in writing that it reports overdue loans to the credit bureaus.'),
    ('DHANBOOST_OVERDUE_STRICT_D15_V1', '1777179420420319621', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} is overdue. Poor credit can stop loans. Pay at https://dhanboost.com/login. - DhanBoost',
     'MLB has confirmed in writing that it reports overdue loans to the credit bureaus.'),
    ('DHANBOOST_OVERDUE_STRICT_D30_V1', '1777179420421957287', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} is unpaid. Lawful recovery may follow. Pay at https://dhanboost.com/login. - DhanBoost',
     'Earlier reminders really were sent, and MLB has confirmed reporting in writing.'),
    ('DHANBOOST_REPORTED_SOFT_V1', '1777179420416177622', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} is overdue and shared with bureaus. View at https://dhanboost.com/login. - DhanBoost',
     'MLB has actually reported this loan to the credit bureaus, confirmed in writing.'),
    ('DHANBOOST_REPORTED_STRICT_V1', '1777179420424823609', '["name","amount","loanId"]',
     'Dear {#var#}, payment of {#var#} on loan {#var#} has been reported overdue to bureaus. Pay at https://dhanboost.com/login. - DhanBoost',
     'MLB has actually reported this loan to the credit bureaus, confirmed in writing.')
) as v(name, dlt, vars, body, confirm);

-- Listed but never sent by hand: the reference check sends its own code.
insert into message_template (channel, name, body, category, status, provider_id, rule, system_managed, created_by_name)
values ('SMS', 'DHANBOOST_REFERENCE_OTP_V2',
        'DhanBoost customer {#var#} named you as a loan reference. If you agree, share OTP {#var#} with them. - DhanBoost',
        'SERVICE', 'ACTIVE', '1777179420452725798', 'ANY', true, 'DLT filing');

-- DLT refused these as service messages, so the discount goes out on WhatsApp. Never sent by hand:
-- the repay-now discount flow owns them, because that is what records the offer the message promises.
insert into message_template (channel, name, body, category, status, rejection_reason, variables, rule, system_managed, created_by_name)
select 'SMS', 'DHANBOOST_REPAY_FEE' || p || s.suffix || '_V1',
       'Dear {#var#}, repay loan {#var#} by {#var#} for ' || p || '% off processing fee on next ' || s.wording
           || '. Pay at https://dhanboost.com/login. - DhanBoost',
       'MARKETING', 'REJECTED', 'Selected content type is wrong. Template should fall under promotional.',
       '["name","loanId","in:date:Repay by"]', 'LIVE', true, 'DLT filing'
from unnest(array[20, 30, 40, 50, 60, 70]) as p
cross join (values ('', 'approved loan'), ('_REVIEW', 'loan after review')) as s(suffix, wording);
