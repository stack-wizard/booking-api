-- OPERA-led sales: a booking_function is an OPERA event on the hotel's block, catering and space rental are
-- posted as charges to the block's Posting Master reservation.

alter table booking_function add column opera_event_id text;
alter table booking_function add column event_type text;

alter table booking_opera_block add column date_from date;
alter table booking_opera_block add column date_to date;

create table booking_opera_posting (
    id bigserial primary key,
    tenant_id bigint not null,
    booking_id bigint not null references booking (id) on delete cascade,
    booking_opera_block_id bigint not null references booking_opera_block (id) on delete cascade,
    booking_function_id bigint references booking_function (id) on delete set null,
    booking_function_item_id bigint references booking_function_item (id) on delete set null,
    source text not null check (source in ('ITEM', 'RENTAL')),
    description text,
    trx_code text,
    amount numeric(14, 2) not null,
    currency text not null,
    status text not null check (status in ('POSTED', 'FAILED')),
    last_error text,
    request jsonb,
    response jsonb,
    posted_at timestamptz,
    created_at timestamptz not null default now()
);

create index idx_booking_opera_posting_tenant on booking_opera_posting (tenant_id);
create index idx_booking_opera_posting_booking on booking_opera_posting (booking_id);
create index idx_booking_opera_posting_block on booking_opera_posting (booking_opera_block_id);
create index idx_booking_opera_posting_function on booking_opera_posting (booking_function_id);
create index idx_booking_opera_posting_item on booking_opera_posting (booking_function_item_id);

-- booking_opera_block and booking_opera_posting are chain (ORG) rows like booking.
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
