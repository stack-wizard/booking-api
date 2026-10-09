-- An event may be limited to one hotel of the chain; null = chain-wide (functions may use any hotel).
alter table event
  add column if not exists property_tenant_id bigint null
    references platform_tenant_mapping (tenant_id) on delete set null;

create index if not exists idx_event_property on event (property_tenant_id);
