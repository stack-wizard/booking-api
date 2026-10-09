-- Parent/child tenancy: ORG = hotel chain (Platform parent tenant), PROPERTY = hotel (Platform child tenant).
-- Everything stays in one schema; each row keeps a single tenant_id (owner), see TenantLevel.
--
-- Develop fix for already-seeded Demo Hotel (DH): ensure Demo Hotel Group exists and DH is its PROPERTY,
-- so V110 can move catalog/CRM/events from the hotel tenant_id to the organization.

alter table platform_tenant_mapping
  add column if not exists parent_tenant_id bigint null,
  add column if not exists kind text not null default 'ORG',
  add column if not exists status text not null default 'ACTIVE',
  add column if not exists name text null,
  add column if not exists hotel_code text null,
  add column if not exists timezone text null;

-- Platform "Demo Hotel Group" 11111111-... (parent of Demo Hotel DH 21111111-...).
insert into platform_tenant_mapping (tenant_id, platform_tenant_id, kind, status, name)
select nextval('booking_tenant_id_seq'), '11111111-1111-1111-1111-111111111111'::uuid, 'ORG', 'ACTIVE', 'Demo Hotel Group'
where not exists (
  select 1 from platform_tenant_mapping
  where platform_tenant_id = '11111111-1111-1111-1111-111111111111'::uuid
);

update platform_tenant_mapping
set kind = 'ORG',
    parent_tenant_id = null,
    name = coalesce(name, 'Demo Hotel Group'),
    status = coalesce(nullif(status, ''), 'ACTIVE')
where platform_tenant_id = '11111111-1111-1111-1111-111111111111'::uuid;

-- Ensure tenant_config exists for the new org (defaults used elsewhere).
insert into tenant_config (tenant_id, hold_ttl_minutes, manual_review_ttl_minutes)
select m.tenant_id, 15, 2880
from platform_tenant_mapping m
where m.platform_tenant_id = '11111111-1111-1111-1111-111111111111'::uuid
  and not exists (select 1 from tenant_config c where c.tenant_id = m.tenant_id);

-- Platform "Demo Hotel (DH)" 21111111-... becomes a child of the group (keeps its local tenant_id).
update platform_tenant_mapping c
set kind = 'PROPERTY',
    parent_tenant_id = p.tenant_id,
    hotel_code = coalesce(c.hotel_code, 'DH'),
    name = coalesce(c.name, 'Demo Hotel (DH)'),
    status = coalesce(nullif(c.status, ''), 'ACTIVE')
from platform_tenant_mapping p
where c.platform_tenant_id = '21111111-1111-1111-1111-111111111111'::uuid
  and p.platform_tenant_id = '11111111-1111-1111-1111-111111111111'::uuid
  and c.tenant_id <> p.tenant_id;

alter table platform_tenant_mapping
  drop constraint if exists platform_tenant_mapping_parent_fk,
  drop constraint if exists platform_tenant_mapping_kind_check,
  drop constraint if exists platform_tenant_mapping_status_check,
  drop constraint if exists platform_tenant_mapping_hierarchy_check;

alter table platform_tenant_mapping
  add constraint platform_tenant_mapping_parent_fk
    foreign key (parent_tenant_id) references platform_tenant_mapping (tenant_id) on delete restrict,
  add constraint platform_tenant_mapping_kind_check
    check (kind in ('ORG', 'PROPERTY')),
  add constraint platform_tenant_mapping_status_check
    check (status in ('SETUP', 'ACTIVE', 'SUSPENDED')),
  add constraint platform_tenant_mapping_hierarchy_check
    check ((kind = 'ORG' and parent_tenant_id is null)
        or (kind = 'PROPERTY' and parent_tenant_id is not null and parent_tenant_id <> tenant_id));

create index if not exists idx_platform_tenant_mapping_parent
  on platform_tenant_mapping (parent_tenant_id);
