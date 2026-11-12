-- V103 — merge the blocklist page with "Block customer" into one block.
--
-- Until now the two were separate: "Block customer" wrote a BLOCKED_BY_STAFF row to
-- application_rejection (that is what stopped new applications) and only copied the mobile + PAN onto
-- blocklist_entry, while nothing ever read blocklist_entry — an entry added on
-- /staff/admin/blocklist stopped nobody. From this release a customer is blocked for new
-- applications and reborrows when they hold a staff block OR any of their identifiers (mobile, PAN,
-- Aadhaar, salary / payout account) is an active entry (ApplicationFlowService.assertNotBlocked).
-- "Block customer" writes every identifier as an entry linked to the customer (customer_id); unblocking
-- a customer clears all their entries; removing a customer's last linked entry on the blocklist page
-- lifts their staff block. Files already in progress are deliberately not touched.
--
-- Values are now stored normalised (BlocklistKey.normalize) so they match however they were typed.

alter table blocklist_entry add column customer_id bigint;

-- 1. Normalise stored values exactly as BlocklistKey.normalize does.
update blocklist_entry set value = upper(regexp_replace(value, '\s', '', 'g')) where type = 'PAN';
update blocklist_entry set value = right(regexp_replace(value, '\D', '', 'g'), 10) where type = 'PHONE';
update blocklist_entry set value = regexp_replace(value, '\D', '', 'g') where type in ('AADHAAR_REF', 'BANK_ACCOUNT');
update blocklist_entry set value = trim(value) where type = 'DEVICE';
-- A value with nothing left to match on can never block anyone.
update blocklist_entry set active = false where value = '';

-- 2. Normalising can make two rows the same (type, value): keep the oldest, active if any was.
with dup as (
    select type, value, min(id) as keep_id, bool_or(active) as any_active
    from blocklist_entry group by type, value having count(*) > 1
)
update blocklist_entry b set active = dup.any_active from dup where b.id = dup.keep_id;

delete from blocklist_entry b
using (select type, value, min(id) as keep_id from blocklist_entry group by type, value having count(*) > 1) dup
where b.type = dup.type and b.value = dup.value and b.id <> dup.keep_id;

create unique index uq_blocklist_entry_type_value on blocklist_entry (type, value);
create index idx_blocklist_entry_customer on blocklist_entry (customer_id) where active;

-- 3. Every customer under a live staff block appears on the blocklist page, linked to them: link (and
--    re-activate) the entry for their mobile, or add one.
update blocklist_entry b
set customer_id = r.customer_id, active = true
from application_rejection r
where r.reason_code = 'BLOCKED_BY_STAFF' and r.blocked_until > now() and r.customer_id is not null
  and b.type = 'PHONE' and b.value = r.mobile and b.customer_id is null;

insert into blocklist_entry (type, value, reason, active, customer_id, created_at, created_by)
select distinct on (r.mobile) 'PHONE', r.mobile, 'Blocked by staff: ' || coalesce(r.reason_detail, ''),
       true, r.customer_id, now(), 'V103'
from application_rejection r
where r.reason_code = 'BLOCKED_BY_STAFF' and r.blocked_until > now()
  and r.customer_id is not null and r.mobile is not null and r.mobile <> ''
  and not exists (select 1 from blocklist_entry b where b.type = 'PHONE' and b.value = r.mobile)
order by r.mobile, r.id desc;

-- 4. Link the PAN entries "Block customer" mirrored before this release (same reason text).
update blocklist_entry b
set customer_id = r.customer_id
from application_rejection r
join loan_application a on a.customer_id = r.customer_id
join customer_profile p on p.application_id = a.id
where r.reason_code = 'BLOCKED_BY_STAFF' and r.blocked_until > now() and r.customer_id is not null
  and b.type = 'PAN' and b.customer_id is null and b.value = upper(p.pan)
  and b.reason = 'Blocked by staff: ' || r.reason_detail;
