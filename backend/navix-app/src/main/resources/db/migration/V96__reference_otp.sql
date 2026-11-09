-- A reference confirms, by a one-time code, that they agree to be named. Shipped OFF: with the flag on
-- every borrower is held on the references screen until both people respond.
-- otp_required is stamped per row when the borrower saves, so files already past this screen, and
-- references carried onto a reborrow, are never pulled back.

alter table application_reference
    add column otp_required     boolean     not null default false,
    add column otp_hash         varchar(64),
    add column otp_expires_at   timestamptz,
    add column otp_attempts     integer     not null default 0,
    add column otp_send_count   integer     not null default 0,
    add column otp_last_sent_at timestamptz,
    add column verified_at      timestamptz;

insert into feature_flag (flag_key, enabled, description, created_at)
values ('reference-otp', false,
        'Each reference must confirm a one-time code before the borrower can pass the references screen.',
        now())
on conflict (flag_key) do nothing;
