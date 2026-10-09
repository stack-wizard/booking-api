-- Hotels a CRM team works for. A team without rows covers every hotel of the chain.
create table crm_team_property (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  team_id            bigint not null references crm_team (id) on delete cascade,
  property_tenant_id bigint not null references platform_tenant_mapping (tenant_id) on delete cascade,
  created_at         timestamptz not null default now()
);
create unique index uq_crm_team_property on crm_team_property (team_id, property_tenant_id);
create index idx_crm_team_property_property on crm_team_property (property_tenant_id);
create index idx_crm_team_property_tenant on crm_team_property (tenant_id);

-- Hotels of a team move to the organization together with the team (replaces the V110 function).
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
    'crm_custom_field_definition', 'crm_lead', 'crm_opportunity', 'crm_outcome_reason',
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
