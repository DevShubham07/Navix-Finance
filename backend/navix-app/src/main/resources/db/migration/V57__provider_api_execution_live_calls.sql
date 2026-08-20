-- Capture EVERY real provider API call (Signzy/Digitap) in the admin Provider API dashboard, not
-- just the manual workbench runs V49 was built for. Live borrower/staff traffic now writes rows too,
-- so these columns separate the two sources and carry the transport detail a manual run never had.
--
-- `source` defaults to MANUAL so the rows already in the table (all workbench runs) stay correct.
-- `request_id` is the MDC requestId RequestLoggingFilter stamps on every access log line — it is the
-- join key from a CloudWatch line to the dashboard row for the same call.
alter table provider_api_execution add column source      varchar(10) not null default 'MANUAL';
alter table provider_api_execution add column endpoint    varchar(200);
alter table provider_api_execution add column http_status integer;
alter table provider_api_execution add column check_type  varchar(40);
alter table provider_api_execution add column request_id  varchar(36);

-- "show me every provider call for application N, newest first" is the dashboard's primary lookup.
create index ix_provider_api_execution_app    on provider_api_execution(application_id, created_at desc);
create index ix_provider_api_execution_source on provider_api_execution(source, created_at desc);
