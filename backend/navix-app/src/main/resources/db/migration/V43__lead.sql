-- V43 — Pre-customer telecaller leads (DSA-style intake + call disposition).
-- Separate from customer; no FKs (schema convention).

create table lead (
    id                              bigserial primary key,
    name                            varchar(160) not null,
    mobile                          varchar(10)  not null,
    email                           varchar(160),
    city                            varchar(120),
    employer                        varchar(160),
    monthly_salary_paise            bigint,
    loan_amount_interested_paise    bigint,
    source                          varchar(24),
    source_detail                   varchar(240),
    call_status                     varchar(32)  not null default 'NOT_CALLED',
    quality_rating                  smallint,
    notes                           text,
    remarks                         text,
    created_by_staff_id             bigint       not null,
    created_at                      timestamptz  not null,
    created_by                      varchar(160),
    updated_at                      timestamptz,
    updated_by                      varchar(160),
    constraint chk_lead_source check (
        source is null or source in ('DSA', 'REFERRAL', 'WALK_IN', 'OTHER')
    ),
    constraint chk_lead_call_status check (
        call_status in (
            'NOT_CALLED', 'CALLED', 'CALLBACK', 'NO_ANSWER',
            'NOT_INTERESTED', 'WRONG_NUMBER', 'CONNECTED'
        )
    ),
    constraint chk_lead_quality_rating check (
        quality_rating is null or (quality_rating >= 1 and quality_rating <= 5)
    ),
    constraint chk_lead_mobile check (mobile ~ '^[0-9]{10}$')
);

create index idx_lead_mobile on lead (mobile);
create index idx_lead_call_status on lead (call_status);
create index idx_lead_created_at on lead (created_at desc);
create index idx_lead_created_by on lead (created_by_staff_id);
