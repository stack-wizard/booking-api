-- Chain-configurable event statuses. event.status stays the fixed phase the logic depends on;
-- every status belongs to one phase. Defaults are filled by EventStatusService the first time a chain reads them.
create table event_status (
  id            bigserial primary key,
  tenant_id     bigint not null,
  code          text not null,
  label         text not null,
  phase         text not null
                check (phase in ('INQUIRY','TENTATIVE','DEFINITE','ACTUAL','TURNED_DOWN','LOST','CANCELLED')),
  color         text null,
  is_start      boolean not null default false,
  display_order int not null default 0,
  active        boolean not null default true,
  opera_code    text null,
  created_at    timestamptz not null default now(),
  constraint event_status_start_is_inquiry check (not is_start or phase = 'INQUIRY')
);
create unique index uq_event_status_code on event_status (tenant_id, upper(code));
create index idx_event_status_tenant on event_status (tenant_id, display_order);

-- Allowed moves between statuses. A status without outgoing rows falls back to the phase rules.
create table event_status_flow (
  id              bigserial primary key,
  tenant_id       bigint not null,
  from_status_id  bigint not null references event_status(id) on delete cascade,
  to_status_id    bigint not null references event_status(id) on delete cascade,
  created_at      timestamptz not null default now(),
  constraint event_status_flow_distinct check (from_status_id <> to_status_id),
  unique (from_status_id, to_status_id)
);
create index idx_event_status_flow_tenant on event_status_flow (tenant_id);
create index idx_event_status_flow_to on event_status_flow (to_status_id);

-- Default codes equal the phase names, so existing rows map one to one.
alter table event add column status_code text null;
update event set status_code = status;

alter table event_status_history
  add column from_code text null,
  add column to_code   text null;
update event_status_history set from_code = from_status, to_code = to_status;

-- Entering the stage moves the open events of the deal to this status.
alter table crm_pipeline_stage add column event_status_code text null;

create or replace function booking_promote_catalog(p_property bigint, p_org bigint)
returns void
language plpgsql
as $$
declare
  t text;
  org_tables text[] := array[
    'product', 'product_component', 'product_package_listing', 'price_profile',
    'event', 'event_order', 'event_status', 'event_status_flow', 'event_status_history', 'portal_access_token',
    'sales_quote', 'sales_quote_version', 'sales_quote_line', 'sales_quote_approval',
    'sales_contract', 'sales_contract_document', 'sales_payment_milestone',
    'crm_account', 'crm_account_contact_role', 'crm_account_relation', 'crm_activity', 'crm_alert',
    'crm_assignment_rule', 'crm_attachment', 'crm_contact', 'crm_cost_item',
    'crm_custom_field_definition', 'crm_lead', 'crm_lookup', 'crm_opportunity', 'crm_outcome_reason',
    'crm_pipeline', 'crm_pipeline_stage', 'crm_reassignment', 'crm_segment', 'crm_stage_requirement',
    'crm_stage_transition', 'crm_team', 'crm_team_member', 'crm_team_property'
  ];
begin
  if p_property is null or p_org is null or p_property = p_org then
    return;
  end if;
  foreach t in array org_tables loop
    if exists (
      select 1 from information_schema.columns
      where table_schema = current_schema() and table_name = t and column_name = 'tenant_id'
    ) then
      execute format('update %I set tenant_id = $1 where tenant_id = $2', t) using p_org, p_property;
    end if;
  end loop;

  -- People belong to the chain and work in several hotels. The per-hotel "online" system user stays with its hotel.
  update app_user
  set tenant_id = p_org
  where tenant_id = p_property
    and username not like 'online-system-tenant-%';
end;
$$;
