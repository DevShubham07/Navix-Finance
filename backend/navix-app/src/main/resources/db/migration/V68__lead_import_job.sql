-- V68 — Bulk lead import: a job row per uploaded file, and the PAN index the import needs.
--
-- The CSV import was a synchronous, ADMIN-only, 2000-row request that carried every row as one JSON
-- array. Uploading a real list (1-2 lakh rows) breaks that shape in three independent places before
-- it ever reaches this table: the platform request-body cap, the 65535 bind-parameter ceiling on the
-- dedup `in (...)` lookups, and the heap of a 2 GB task holding the whole file. The file now goes
-- browser -> S3 directly and the backend streams it in chunks, which means the work outlives the
-- request that started it — so it needs somewhere durable to record what it is doing.
--
-- `issues_json` deliberately holds only the first 50 bad rows (MAX_ISSUES), so a file that is wrong
-- on every line cannot write a 50 MB row here.
create table lead_import_job (
    id                   bigserial primary key,
    s3_key               varchar(512) not null,
    file_name            varchar(200) not null,
    status               varchar(16)  not null,
    uploaded_by_staff_id bigint       not null,
    uploader_role        varchar(32)  not null,
    merge_requested      boolean      not null default false,
    total_rows           integer,
    processed_rows       integer      not null default 0,
    inserted_count       integer      not null default 0,
    merged_count         integer      not null default 0,
    skipped_duplicates   integer      not null default 0,
    skipped_customers    integer      not null default 0,
    issue_count          integer      not null default 0,
    issues_json          jsonb,
    error_message        varchar(2000),
    started_at           timestamptz,
    finished_at          timestamptz,
    created_at           timestamptz  not null,
    created_by           varchar(160),
    updated_at           timestamptz,
    updated_by           varchar(160),
    constraint chk_lead_import_job_status check (
        status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')
    )
);

-- "my recent imports", the only listing the UI asks for.
create index idx_lead_import_job_staff on lead_import_job (uploaded_by_staff_id, created_at desc);
-- The reaper's lookup on boot, and the one-live-job-per-user guard. Partial: finished jobs accumulate
-- forever and are never queried this way.
create index idx_lead_import_job_live on lead_import_job (status) where status in ('QUEUED', 'RUNNING');

-- The import's PAN dedup lookup had no usable index. V55 added `uq_lead_dsa_pan on lead (pan) where
-- owner_dsa_id is not null` — but an imported lead is deliberately unattributed (owner_dsa_id null,
-- source OTHER), so that partial index excludes every row this feature writes and `findByPanIn` fell
-- back to a sequential scan. Harmless at 2000 rows against a small table; quadratic once a couple of
-- 200k imports have landed.
create index idx_lead_pan on lead (pan) where pan is not null;
