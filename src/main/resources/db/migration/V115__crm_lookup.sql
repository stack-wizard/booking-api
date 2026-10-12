-- Chain-level code lists: event types, setup styles, event preferences, additional services.
-- Defaults are filled by CrmLookupService the first time a chain reads a list.
create table crm_lookup (
  id            bigserial primary key,
  tenant_id     bigint not null,
  kind          text not null
                check (kind in ('EVENT_TYPE', 'SETUP_STYLE', 'EVENT_PREFERENCE', 'ADDITIONAL_SERVICE')),
  code          text not null,
  label         text not null,
  display_order int not null default 0,
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);
create unique index uq_crm_lookup_code on crm_lookup (tenant_id, kind, upper(code));
create index idx_crm_lookup_tenant_kind on crm_lookup (tenant_id, kind, display_order);

-- Setup styles become chain codes; the service validates them against crm_lookup.
alter table resource_setup_capacity drop constraint if exists resource_setup_capacity_setup_style_check;
alter table event_function drop constraint if exists event_function_setup_style_check;
alter table product_package_listing drop constraint if exists product_package_listing_setup_style_check;

-- Event types were stored as labels ("Gala dinner"); they become codes ("GALA_DINNER").
update event
   set event_type = upper(regexp_replace(trim(event_type), '[\s-]+', '_', 'g'))
 where event_type is not null;
update crm_assignment_rule
   set event_type = upper(regexp_replace(trim(event_type), '[\s-]+', '_', 'g'))
 where event_type is not null;
update crm_lead
   set attrs = jsonb_set(attrs, '{eventType}', to_jsonb(upper(regexp_replace(trim(attrs->>'eventType'), '[\s-]+', '_', 'g'))))
 where jsonb_typeof(attrs->'eventType') = 'string';
update crm_opportunity
   set attrs = jsonb_set(attrs, '{eventType}', to_jsonb(upper(regexp_replace(trim(attrs->>'eventType'), '[\s-]+', '_', 'g'))))
 where jsonb_typeof(attrs->'eventType') = 'string';

-- Code lists move to the organization with the rest of the catalog (replaces the V114 function).
create or replace function booking_promote_catalog(p_property bigint, p_org bigint)
returns void
language plpgsql
as $$
declare
  t text;
  org_tables text[] := array[
    'product', 'product_component', 'product_package_listing', 'price_profile',
    'event', 'event_order', 'event_status_history', 'portal_access_token',
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
