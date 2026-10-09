-- Chain-level data (catalog, CRM, events, sales documents) is owned by the organization tenant; hotels own capacity
-- and operations. See TenantLevel. This function moves the chain-level rows of one hotel to its organization.
-- It is also called from TenantSetupService.promoteCatalogToOrg for chains with several hotels.

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
    'crm_stage_transition', 'crm_team', 'crm_team_member'
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

-- Single-hotel chains: promotion is unambiguous, so do it now. Chains with several hotels are promoted explicitly
-- (POST /api/admin/tenant-setup/properties/{id}/promote-catalog) because each hotel may have its own catalog.
-- Skipped when the organization already owns chain-level data. A failure rolls back that chain only.
do $$
declare
  r record;
begin
  for r in
    select parent_tenant_id as org_id, min(tenant_id) as property_id
    from platform_tenant_mapping
    where kind = 'PROPERTY' and parent_tenant_id is not null
    group by parent_tenant_id
    having count(*) = 1
  loop
    begin
      if not exists (select 1 from product where tenant_id = r.org_id)
         and not exists (select 1 from crm_account where tenant_id = r.org_id)
         and not exists (select 1 from event where tenant_id = r.org_id)
         and not exists (select 1 from crm_pipeline where tenant_id = r.org_id) then
        perform booking_promote_catalog(r.property_id, r.org_id);
      end if;
    exception when others then
      raise warning 'booking_promote_catalog(%, %) skipped: %', r.property_id, r.org_id, sqlerrm;
    end;
  end loop;
end $$;
