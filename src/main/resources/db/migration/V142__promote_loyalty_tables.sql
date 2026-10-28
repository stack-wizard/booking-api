-- Loyalty and the integration outbox belong to the chain: move them with the rest when a standalone hotel is
-- promoted under an organization. The append-only ledger guard lets this one tenant_id move through via a
-- transaction-local setting.

create or replace function booking_promote_catalog(p_property bigint, p_org bigint)
returns void
language plpgsql
as $$
declare
  t text;
  org_tables text[] := array[
    'product', 'product_component', 'product_package_listing', 'price_profile',
    'booking', 'booking_opera_block', 'booking_opera_posting', 'booking_order', 'booking_room_block', 'booking_room_block_night', 'booking_status', 'booking_status_flow', 'booking_status_history', 'portal_access_token',
    'sales_quote', 'sales_quote_version', 'sales_quote_line', 'sales_quote_approval',
    'sales_contract', 'sales_contract_document', 'sales_payment_milestone',
    'crm_account', 'crm_account_contact_role', 'crm_account_relation', 'crm_activity', 'crm_alert',
    'crm_assignment_rule', 'crm_attachment', 'crm_contact', 'crm_cost_item',
    'crm_custom_field_definition', 'crm_lead', 'crm_lookup', 'crm_opportunity', 'crm_outcome_reason',
    'crm_pipeline', 'crm_pipeline_stage', 'crm_reassignment', 'crm_segment', 'crm_stage_requirement',
    'crm_stage_transition', 'crm_team', 'crm_team_member', 'crm_team_property',
    'season_calendar', 'season', 'season_operating', 'sales_catalog', 'catalog_item',
    'loyalty_program', 'loyalty_rule_set', 'loyalty_tier', 'loyalty_qualification_step', 'loyalty_channel_map',
    'loyalty_member', 'loyalty_member_identifier', 'loyalty_consent', 'loyalty_tier_history', 'loyalty_reservation',
    'loyalty_reservation_evaluation', 'loyalty_ledger', 'loyalty_import_batch', 'loyalty_import_row',
    'integration_event', 'event_subscription', 'event_delivery'
  ];
begin
  if p_property is null or p_org is null or p_property = p_org then
    return;
  end if;
  perform set_config('booking.promote_catalog', 'on', true);
  foreach t in array org_tables loop
    if exists (
      select 1 from information_schema.columns
      where table_schema = current_schema() and table_name = t and column_name = 'tenant_id'
    ) then
      execute format('update %I set tenant_id = $1 where tenant_id = $2', t) using p_org, p_property;
    end if;
  end loop;
  perform set_config('booking.promote_catalog', 'off', true);

  -- People belong to the chain and work in several hotels. The per-hotel "online" system user stays with its hotel.
  update app_user
  set tenant_id = p_org
  where tenant_id = p_property
    and username not like 'online-system-tenant-%';
end;
$$;
