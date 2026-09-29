-- Maps Mikos Platform tenant UUID -> local booking bigint tenant_id.
create table if not exists platform_tenant_mapping (
  id bigserial primary key,
  tenant_id bigint not null unique,
  platform_tenant_id uuid not null unique,
  created_at timestamptz not null default now()
);

create index if not exists idx_platform_tenant_mapping_platform
  on platform_tenant_mapping (platform_tenant_id);

-- Dedicated sequence for newly provisioned booking tenant ids (avoids collisions with existing data).
create sequence if not exists booking_tenant_id_seq;

do $$
declare
  max_tid bigint;
begin
  select coalesce(max(tid), 0) into max_tid
  from (
    select tenant_id as tid from tenant_config where tenant_id is not null
    union
    select tenant_id from tenant_integration_config where tenant_id is not null
    union
    select tenant_id from app_user where tenant_id is not null
    union
    select tenant_id from invoice where tenant_id is not null
    union
    select tenant_id from payment_intent where tenant_id is not null
    union
    select tenant_id from reservation_request where tenant_id is not null
    union
    select tenant_id from resource where tenant_id is not null
    union
    select tenant_id from platform_tenant_mapping where tenant_id is not null
  ) t;

  -- nextval will return max_tid + 1
  perform setval('booking_tenant_id_seq', max_tid, true);
end $$;
