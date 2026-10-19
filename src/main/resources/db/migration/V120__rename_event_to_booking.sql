-- An "event" is really a chain-level B2B block (Opera: business block): halls with an agenda, hourly
-- office rental, group rooms or any mix. It becomes `booking`; a function is still a function.
-- event.event_type keeps its name: it is the EVENT_TYPE code list, also used by Opera catering events.

alter table event rename to booking;
alter table event_function rename to booking_function;
alter table event_function_item rename to booking_function_item;
alter table event_order rename to booking_order;
alter table event_status rename to booking_status;
alter table event_status_flow rename to booking_status_flow;
alter table event_status_history rename to booking_status_history;

alter table booking_function rename column event_id to booking_id;
alter table booking_function_item rename column event_function_id to booking_function_id;
alter table booking_order rename column event_id to booking_id;
alter table booking_status_history rename column event_id to booking_id;
alter table crm_alert rename column event_id to booking_id;
alter table crm_cost_item rename column event_id to booking_id;
alter table portal_access_token rename column event_id to booking_id;
alter table sales_contract rename column event_id to booking_id;
alter table sales_quote rename column event_id to booking_id;
alter table sales_quote_line rename column event_function_id to booking_function_id;
alter table reservation rename column event_function_id to booking_function_id;
alter table crm_pipeline_stage rename column event_status_code to booking_status_code;

-- Constraint, index and sequence names follow the tables. Generated names differ between environments,
-- so they are renamed from the catalog instead of by hand. payment_event is a different thing.
do $$
declare
  r record;
begin
  for r in
    select c.conname, c.conrelid::regclass as rel
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    where c.connamespace = current_schema()::regnamespace
      and t.relname <> 'payment_event'
      and c.conname ~ '(^|_)event(_|$)'
  loop
    execute format('alter table %s rename constraint %I to %I',
                   r.rel, r.conname, regexp_replace(r.conname, '(^|_)event(?=_|$)', '\1booking', 'g'));
  end loop;

  for r in
    select i.relname
    from pg_class i
    join pg_index x on x.indexrelid = i.oid
    join pg_class t on t.oid = x.indrelid
    where i.relnamespace = current_schema()::regnamespace
      and t.relname <> 'payment_event'
      and i.relname ~ '(^|_)event(_|$)'
  loop
    execute format('alter index %I rename to %I',
                   r.relname, regexp_replace(r.relname, '(^|_)event(?=_|$)', '\1booking', 'g'));
  end loop;

  for r in
    select s.relname
    from pg_class s
    where s.relkind = 'S'
      and s.relnamespace = current_schema()::regnamespace
      and s.relname ~ '^event_'
  loop
    execute format('alter sequence %I rename to %I', r.relname, regexp_replace(r.relname, '^event_', 'booking_'));
  end loop;
end;
$$;

create or replace function booking_promote_catalog(p_property bigint, p_org bigint)
returns void
language plpgsql
as $$
declare
  t text;
  org_tables text[] := array[
    'product', 'product_component', 'product_package_listing', 'price_profile',
    'booking', 'booking_order', 'booking_status', 'booking_status_flow', 'booking_status_history', 'portal_access_token',
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

-- Invoices point at their source by table name.
update invoice set reference_table = 'booking' where reference_table = 'event';
