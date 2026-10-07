-- Manual demo: one complete sales flow for a resolved tenant (:tenant).
-- Invoked only by seed-dh-sales-flow.sh after Opera hotel DH is confirmed.
-- Not a Flyway migration — never runs on deploy by itself.
-- Safe to re-run: fixed seed codes; skips when the demo deal already exists.

\set ON_ERROR_STOP on
\if :{?tenant}
\else
  \echo 'ERROR: :tenant is required (use seed-dh-sales-flow.sh)'
  \quit 1
\endif

begin;

-- Owner: prefer admin1 for this tenant, else any ADMIN/STAFF on the tenant, else null.
create temporary table seed_ctx (
  tenant_id bigint primary key,
  owner_id bigint,
  team_id bigint,
  pipeline_id bigint,
  stage_inquiry bigint,
  stage_proposal bigint,
  stage_negotiation bigint,
  stage_won bigint,
  reason_won bigint,
  account_id bigint,
  contact_id bigint,
  lead_id bigint,
  opportunity_id bigint,
  event_id bigint,
  quote_id bigint,
  contract_id bigint,
  main_resource_id bigint,
  breakout_resource_id bigint,
  package_id bigint,
  rent_product_id bigint,
  coffee_product_id bigint,
  lunch_product_id bigint,
  date_from date,
  date_to date
) on commit drop;

insert into seed_ctx (tenant_id, owner_id, date_from, date_to)
select :tenant,
       (select u.id from app_user u
        where u.tenant_id = :tenant and u.role in ('ADMIN', 'STAFF', 'SUPER_ADMIN')
        order by case when u.username = 'admin1' then 0 else 1 end, u.id
        limit 1),
       date '2026-11-18',
       date '2026-11-19';

-- Already seeded? leave existing demo untouched.
do $$
begin
  if exists (
    select 1 from event e
    where e.tenant_id = (select tenant_id from seed_ctx)
      and e.attrs->>'seed' = 'DH_SALES_FLOW'
  ) then
    raise notice 'DH sales-flow demo already present for tenant % — skipping', (select tenant_id from seed_ctx);
    -- Abort the rest of this DO block path by setting a flag row; transaction continues with no-ops below via WHERE NOT EXISTS.
  end if;
end $$;

-- Pipeline + stages (default only when the tenant has none yet — never fights an existing default)
insert into crm_pipeline (tenant_id, code, name, description, is_default, active)
select c.tenant_id, 'DH_EVENTS', 'DH Events', 'Seeded sales pipeline for Opera hotel DH',
       not exists (select 1 from crm_pipeline p where p.tenant_id = c.tenant_id and p.is_default),
       true
from seed_ctx c
where not exists (
  select 1 from crm_pipeline p where p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS'
)
  and not exists (select 1 from event e where e.tenant_id = c.tenant_id and e.attrs->>'seed' = 'DH_SALES_FLOW');

insert into crm_pipeline_stage (tenant_id, pipeline_id, code, name, display_order, probability, stage_kind)
select c.tenant_id, p.id, s.code, s.name, s.ord, s.prob, s.kind
from seed_ctx c
join crm_pipeline p on p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS'
cross join (values
  ('INQUIRY', 'Inquiry', 10, 10, 'OPEN'),
  ('PROPOSAL', 'Proposal', 20, 40, 'OPEN'),
  ('NEGOTIATION', 'Negotiation', 30, 70, 'OPEN'),
  ('WON', 'Won', 90, 100, 'WON'),
  ('LOST', 'Lost', 95, 0, 'LOST')
) as s(code, name, ord, prob, kind)
where not exists (
  select 1 from crm_pipeline_stage x where x.pipeline_id = p.id and upper(x.code) = upper(s.code)
);

insert into crm_outcome_reason (tenant_id, kind, code, name, display_order, active)
select c.tenant_id, r.kind, r.code, r.name, r.ord, true
from seed_ctx c
cross join (values
  ('WON', 'DH_VENUE_FIT', 'Best venue fit (DH seed)', 10),
  ('LOST', 'DH_PRICE', 'Price too high (DH seed)', 20)
) as r(kind, code, name, ord)
where not exists (
  select 1 from crm_outcome_reason x where x.tenant_id = c.tenant_id and x.kind = r.kind and upper(x.code) = upper(r.code)
);

update seed_ctx c set
  team_id = (select t.id from crm_team t where t.tenant_id = c.tenant_id and t.name = 'MICE' limit 1),
  pipeline_id = (select p.id from crm_pipeline p where p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS'),
  stage_inquiry = (select s.id from crm_pipeline_stage s join crm_pipeline p on p.id = s.pipeline_id
                   where p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS' and upper(s.code) = 'INQUIRY'),
  stage_proposal = (select s.id from crm_pipeline_stage s join crm_pipeline p on p.id = s.pipeline_id
                    where p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS' and upper(s.code) = 'PROPOSAL'),
  stage_negotiation = (select s.id from crm_pipeline_stage s join crm_pipeline p on p.id = s.pipeline_id
                       where p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS' and upper(s.code) = 'NEGOTIATION'),
  stage_won = (select s.id from crm_pipeline_stage s join crm_pipeline p on p.id = s.pipeline_id
               where p.tenant_id = c.tenant_id and upper(p.code) = 'DH_EVENTS' and upper(s.code) = 'WON'),
  reason_won = (select r.id from crm_outcome_reason r where r.tenant_id = c.tenant_id and r.code = 'DH_VENUE_FIT'),
  main_resource_id = (select r.id from resource r where r.tenant_id = c.tenant_id and r.code = 'EVT-MAIN'),
  breakout_resource_id = (select r.id from resource r where r.tenant_id = c.tenant_id and r.code = 'EVT-BREAKOUT'),
  package_id = (select p.id from product p where p.tenant_id = c.tenant_id and p.name = 'Day Delegate Rate'),
  rent_product_id = (select p.id from product p where p.tenant_id = c.tenant_id and p.name = 'Breakout room rent'),
  coffee_product_id = (select p.id from product p where p.tenant_id = c.tenant_id and p.name = 'Coffee break'),
  lunch_product_id = (select p.id from product p where p.tenant_id = c.tenant_id and p.name = 'Business lunch');

-- Account + contact
insert into crm_account (tenant_id, name, legal_name, account_type, segment, vat_id, owner_user_id, team_id,
                          email, phone, city, country, attrs)
select c.tenant_id, 'Adriatic Pharma (DH seed)', 'Adriatic Pharma d.o.o.', 'COMPANY', 'CORPORATE', 'HRDHSEED001',
       c.owner_id, c.team_id, 'events@adriatic-pharma-seed.example', '+385 1 555 0100', 'Zagreb', 'HR',
       jsonb_build_object('seed', 'DH_SALES_FLOW')
from seed_ctx c
where c.pipeline_id is not null
  and c.main_resource_id is not null
  and c.package_id is not null
  and not exists (select 1 from event e where e.tenant_id = c.tenant_id and e.attrs->>'seed' = 'DH_SALES_FLOW')
  and not exists (select 1 from crm_account a where a.tenant_id = c.tenant_id and a.vat_id = 'HRDHSEED001');

insert into crm_contact (tenant_id, account_id, first_name, last_name, email, phone, job_title, attrs)
select c.tenant_id, a.id, 'Ana', 'Horvat', 'ana.horvat@adriatic-pharma-seed.example', '+385 91 555 0101',
       'Event Manager', jsonb_build_object('seed', 'DH_SALES_FLOW')
from seed_ctx c
join crm_account a on a.tenant_id = c.tenant_id and a.vat_id = 'HRDHSEED001'
where not exists (
  select 1 from crm_contact x where x.tenant_id = c.tenant_id and lower(x.email) = 'ana.horvat@adriatic-pharma-seed.example'
);

insert into crm_account_contact_role (tenant_id, account_id, contact_id, role, primary_contact)
select c.tenant_id, a.id, ct.id, 'DECISION_MAKER', true
from seed_ctx c
join crm_account a on a.tenant_id = c.tenant_id and a.vat_id = 'HRDHSEED001'
join crm_contact ct on ct.tenant_id = c.tenant_id and ct.account_id = a.id
  and lower(ct.email) = 'ana.horvat@adriatic-pharma-seed.example'
on conflict (account_id, contact_id, role) do nothing;

update seed_ctx c set
  account_id = (select id from crm_account a where a.tenant_id = c.tenant_id and a.vat_id = 'HRDHSEED001'),
  contact_id = (select id from crm_contact ct where ct.tenant_id = c.tenant_id
                and lower(ct.email) = 'ana.horvat@adriatic-pharma-seed.example');

-- Opportunity (WON) + converted lead
insert into crm_opportunity (
  tenant_id, name, account_id, primary_contact_id, pipeline_id, stage_id, owner_user_id, team_id,
  amount, currency, expected_close_date, source, status, outcome_reason_id, outcome_note, closed_at, attrs
)
select c.tenant_id, 'DH Seed Congress', c.account_id, c.contact_id, c.pipeline_id, c.stage_won, c.owner_id, c.team_id,
       14520.00, 'EUR', c.date_from - 30, 'WEB', 'WON', c.reason_won, 'Seeded won deal', now() - interval '5 days',
       jsonb_build_object('seed', 'DH_SALES_FLOW')
from seed_ctx c
where c.account_id is not null
  and c.pipeline_id is not null
  and c.stage_won is not null
  and not exists (select 1 from event e where e.tenant_id = c.tenant_id and e.attrs->>'seed' = 'DH_SALES_FLOW')
  and not exists (
    select 1 from crm_opportunity o where o.tenant_id = c.tenant_id and o.attrs->>'seed' = 'DH_SALES_FLOW'
  );

update seed_ctx c set
  opportunity_id = (select id from crm_opportunity o where o.tenant_id = c.tenant_id and o.attrs->>'seed' = 'DH_SALES_FLOW');

insert into crm_stage_transition (tenant_id, opportunity_id, from_stage_id, to_stage_id, changed_by, note, changed_at)
select c.tenant_id, c.opportunity_id, null, c.stage_inquiry, c.owner_id, 'Seed: created', now() - interval '20 days'
from seed_ctx c
where c.opportunity_id is not null
  and not exists (select 1 from crm_stage_transition t where t.opportunity_id = c.opportunity_id);

insert into crm_stage_transition (tenant_id, opportunity_id, from_stage_id, to_stage_id, changed_by, note, changed_at)
select c.tenant_id, c.opportunity_id, c.stage_inquiry, c.stage_proposal, c.owner_id, 'Seed: proposal sent', now() - interval '12 days'
from seed_ctx c where c.opportunity_id is not null
  and not exists (
    select 1 from crm_stage_transition t where t.opportunity_id = c.opportunity_id and t.to_stage_id = c.stage_proposal
  );

insert into crm_stage_transition (tenant_id, opportunity_id, from_stage_id, to_stage_id, changed_by, note, changed_at)
select c.tenant_id, c.opportunity_id, c.stage_proposal, c.stage_negotiation, c.owner_id, 'Seed: negotiating', now() - interval '8 days'
from seed_ctx c where c.opportunity_id is not null
  and not exists (
    select 1 from crm_stage_transition t where t.opportunity_id = c.opportunity_id and t.to_stage_id = c.stage_negotiation
  );

insert into crm_stage_transition (tenant_id, opportunity_id, from_stage_id, to_stage_id, changed_by, note, changed_at)
select c.tenant_id, c.opportunity_id, c.stage_negotiation, c.stage_won, c.owner_id, 'Seed: won', now() - interval '5 days'
from seed_ctx c where c.opportunity_id is not null
  and not exists (
    select 1 from crm_stage_transition t where t.opportunity_id = c.opportunity_id and t.to_stage_id = c.stage_won
  );

insert into crm_lead (
  tenant_id, company_name, first_name, last_name, email, phone, source, status, owner_user_id, team_id, segment, country,
  description, converted_account_id, converted_contact_id, converted_opportunity_id, converted_at, attrs
)
select c.tenant_id, 'Adriatic Pharma (DH seed)', 'Ana', 'Horvat', 'ana.horvat@adriatic-pharma-seed.example',
       '+385 91 555 0101', 'WEB', 'CONVERTED', c.owner_id, c.team_id, 'CORPORATE', 'HR',
       'Seed lead for full sales flow', c.account_id, c.contact_id, c.opportunity_id, now() - interval '18 days',
       jsonb_build_object('seed', 'DH_SALES_FLOW')
from seed_ctx c
where c.opportunity_id is not null
  and not exists (select 1 from crm_lead l where l.tenant_id = c.tenant_id and l.attrs->>'seed' = 'DH_SALES_FLOW');

-- Event DEFINITE
insert into event (
  tenant_id, account_id, primary_contact_id, opportunity_id, name, status, event_type,
  decision_date, date_from, date_to, expected_pax, guaranteed_pax, guarantee_due_date,
  currency, owner_user_id, team_id, notes, attrs
)
select c.tenant_id, c.account_id, c.contact_id, c.opportunity_id, 'DH Seed Congress', 'DEFINITE', 'CONFERENCE',
       c.date_from - 45, c.date_from, c.date_to, 130, 120, c.date_from - 14,
       'EUR', c.owner_id, c.team_id, 'Seeded definite event after accepted quote',
       jsonb_build_object('seed', 'DH_SALES_FLOW', 'setupStyle', 'CLASSROOM', 'cocktail', true)
from seed_ctx c
where c.opportunity_id is not null
  and c.main_resource_id is not null
  and not exists (select 1 from event e where e.tenant_id = c.tenant_id and e.attrs->>'seed' = 'DH_SALES_FLOW');

update seed_ctx c set
  event_id = (select id from event e where e.tenant_id = c.tenant_id and e.attrs->>'seed' = 'DH_SALES_FLOW');

insert into event_status_history (tenant_id, event_id, from_status, to_status, changed_by, note, created_at)
select c.tenant_id, c.event_id, null, 'INQUIRY', c.owner_id, 'Seed', now() - interval '18 days'
from seed_ctx c where c.event_id is not null
  and not exists (select 1 from event_status_history h where h.event_id = c.event_id);

insert into event_status_history (tenant_id, event_id, from_status, to_status, changed_by, note, created_at)
select c.tenant_id, c.event_id, 'INQUIRY', 'TENTATIVE', c.owner_id, 'Seed', now() - interval '10 days'
from seed_ctx c where c.event_id is not null
  and not exists (select 1 from event_status_history h where h.event_id = c.event_id and h.to_status = 'TENTATIVE');

insert into event_status_history (tenant_id, event_id, from_status, to_status, changed_by, note, created_at)
select c.tenant_id, c.event_id, 'TENTATIVE', 'DEFINITE', c.owner_id, 'Seed: quote accepted', now() - interval '5 days'
from seed_ctx c where c.event_id is not null
  and not exists (select 1 from event_status_history h where h.event_id = c.event_id and h.to_status = 'DEFINITE');

-- Functions + package line items (no allocations — avoids capacity collisions with live bookings)
insert into event_function (
  tenant_id, event_id, resource_id, function_type, name, setup_style,
  starts_at, ends_at, occupancy_starts_at, occupancy_ends_at, pax, package_product_id, display_order
)
select c.tenant_id, c.event_id, c.main_resource_id, 'PLENARY', 'Day Delegate Rate', 'CLASSROOM',
       c.date_from + time '09:00', c.date_from + time '17:00',
       c.date_from + time '08:00', c.date_from + time '18:00',
       120, c.package_id, 10
from seed_ctx c
where c.event_id is not null
  and not exists (select 1 from event_function f where f.event_id = c.event_id and f.function_type = 'PLENARY');

insert into event_function (
  tenant_id, event_id, resource_id, function_type, name, setup_style,
  starts_at, ends_at, occupancy_starts_at, occupancy_ends_at, pax, display_order
)
select c.tenant_id, c.event_id, c.breakout_resource_id, 'BREAKOUT', 'Workshop', 'CLASSROOM',
       c.date_to + time '09:00', c.date_to + time '12:00',
       c.date_to + time '08:30', c.date_to + time '12:30',
       20, 20
from seed_ctx c
where c.event_id is not null and c.breakout_resource_id is not null
  and not exists (select 1 from event_function f where f.event_id = c.event_id and f.function_type = 'BREAKOUT');

insert into event_function (
  tenant_id, event_id, function_type, name,
  starts_at, ends_at, occupancy_starts_at, occupancy_ends_at, pax, package_product_id, display_order
)
select c.tenant_id, c.event_id, 'COFFEE_BREAK', 'Coffee break',
       c.date_from + time '10:30', c.date_from + time '11:00',
       c.date_from + time '10:30', c.date_from + time '11:00',
       120, c.package_id, 15
from seed_ctx c
where c.event_id is not null
  and not exists (select 1 from event_function f where f.event_id = c.event_id and f.function_type = 'COFFEE_BREAK');

insert into event_function_item (
  tenant_id, event_function_id, product_id, package_product_id, description, uom, qty, qty_basis,
  unit_price, discount_amount, gross_amount, display_order
)
select c.tenant_id, f.id, c.package_id, c.package_id, 'Day Delegate Rate', 'UNIT', 120, 'PER_GUARANTEED_PAX',
       65.00, 0, 7800.00, 1
from seed_ctx c
join event_function f on f.event_id = c.event_id and f.function_type = 'PLENARY'
where not exists (select 1 from event_function_item i where i.event_function_id = f.id);

insert into event_function_item (
  tenant_id, event_function_id, product_id, description, uom, qty, qty_basis,
  unit_price, discount_amount, gross_amount, display_order
)
select c.tenant_id, f.id, c.rent_product_id, 'Breakout room rent', 'HOUR', 3, 'FIXED',
       45.00, 0, 135.00, 1
from seed_ctx c
join event_function f on f.event_id = c.event_id and f.function_type = 'BREAKOUT'
where c.rent_product_id is not null
  and not exists (select 1 from event_function_item i where i.event_function_id = f.id);

-- Accepted quote + signed contract + BEO snapshot + deposit milestone
insert into sales_quote (
  tenant_id, event_id, opportunity_id, account_id, quote_number, status, currency, valid_until, version,
  total_base, total_offered, total_tax, discount_percent, notes, terms, sent_at, decided_at, decided_by_name, decision_note
)
select c.tenant_id, c.event_id, c.opportunity_id, c.account_id, 'Q-DH-SEED-001', 'ACCEPTED', 'EUR',
       c.date_from - 30, 1, 7800.00, 7800.00, 1014.00, 0,
       'DH seed quote', 'Net 14. Cancellation per hotel policy.',
       now() - interval '10 days', now() - interval '6 days', 'Ana Horvat', 'Accepted via portal (seed)'
from seed_ctx c
where c.event_id is not null
  and not exists (select 1 from sales_quote q where q.tenant_id = c.tenant_id and q.quote_number = 'Q-DH-SEED-001');

update seed_ctx c set
  quote_id = (select id from sales_quote q where q.tenant_id = c.tenant_id and q.quote_number = 'Q-DH-SEED-001');

insert into sales_quote_version (tenant_id, quote_id, version, snapshot, total_offered, sent_by)
select c.tenant_id, c.quote_id, 1,
       jsonb_build_object('seed', 'DH_SALES_FLOW', 'event', 'DH Seed Congress', 'total', 7800),
       7800.00, c.owner_id
from seed_ctx c
where c.quote_id is not null
on conflict (quote_id, version) do nothing;

insert into sales_quote_line (
  tenant_id, quote_id, line_group, product_id, event_function_id, description, service_date, uom, qty,
  base_rate, offered_rate, tax1_percent, amount_base, amount_offered, display_order
)
select c.tenant_id, c.quote_id, 'F_AND_B', c.package_id, f.id, 'Day Delegate Rate', c.date_from, 'UNIT', 120,
       65.00, 65.00, 13, 7800.00, 7800.00, 10
from seed_ctx c
join event_function f on f.event_id = c.event_id and f.function_type = 'PLENARY'
where c.quote_id is not null
  and not exists (select 1 from sales_quote_line l where l.quote_id = c.quote_id);

insert into sales_contract (
  tenant_id, event_id, quote_id, contract_number, status, currency, total_amount, terms, sent_at, signed_at, signed_by_name
)
select c.tenant_id, c.event_id, c.quote_id, 'C-DH-SEED-001', 'SIGNED', 'EUR', 7800.00,
       'Seeded contract terms', now() - interval '5 days', now() - interval '4 days', 'Ana Horvat'
from seed_ctx c
where c.quote_id is not null
  and not exists (select 1 from sales_contract x where x.tenant_id = c.tenant_id and x.contract_number = 'C-DH-SEED-001');

update seed_ctx c set
  contract_id = (select id from sales_contract x where x.tenant_id = c.tenant_id and x.contract_number = 'C-DH-SEED-001');

insert into sales_payment_milestone (tenant_id, contract_id, kind, label, due_date, percent, amount, status, display_order)
select c.tenant_id, c.contract_id, 'DEPOSIT', '30% deposit', c.date_from - 30, 30, 2340.00, 'PLANNED', 10
from seed_ctx c
where c.contract_id is not null
  and not exists (select 1 from sales_payment_milestone m where m.contract_id = c.contract_id and m.kind = 'DEPOSIT');

insert into sales_payment_milestone (tenant_id, contract_id, kind, label, due_date, percent, amount, status, display_order)
select c.tenant_id, c.contract_id, 'FINAL', 'Balance', c.date_from - 7, 70, 5460.00, 'PLANNED', 20
from seed_ctx c
where c.contract_id is not null
  and not exists (select 1 from sales_payment_milestone m where m.contract_id = c.contract_id and m.kind = 'FINAL');

insert into event_order (tenant_id, event_id, version, status, snapshot, note, issued_by)
select c.tenant_id, c.event_id, 1, 'ISSUED',
       jsonb_build_object(
         'seed', 'DH_SALES_FLOW',
         'eventName', 'DH Seed Congress',
         'pax', 120,
         'functions', jsonb_build_array('PLENARY', 'COFFEE_BREAK', 'BREAKOUT')
       ),
       'Seed BEO', c.owner_id
from seed_ctx c
where c.event_id is not null
on conflict (event_id, version) do nothing;

insert into crm_activity (tenant_id, account_id, lead_id, opportunity_id, contact_id, activity_type, subject, body, assigned_to, done_at)
select c.tenant_id, c.account_id,
       (select id from crm_lead l where l.tenant_id = c.tenant_id and l.attrs->>'seed' = 'DH_SALES_FLOW'),
       c.opportunity_id, c.contact_id, 'MEETING',
       'Site visit with Ana — hall and breakout rooms',
       'Seeded activity after inquiry.',
       c.owner_id, now() - interval '15 days'
from seed_ctx c
where c.opportunity_id is not null
  and not exists (
    select 1 from crm_activity a
    where a.tenant_id = c.tenant_id and a.subject = 'Site visit with Ana — hall and breakout rooms'
  );

select
  c.tenant_id,
  a.name as account,
  o.name as opportunity,
  o.status as opp_status,
  e.name as event,
  e.status as event_status,
  q.quote_number,
  q.status as quote_status,
  sc.contract_number,
  sc.status as contract_status
from seed_ctx c
left join crm_account a on a.id = c.account_id
left join crm_opportunity o on o.id = c.opportunity_id
left join event e on e.id = c.event_id
left join sales_quote q on q.id = c.quote_id
left join sales_contract sc on sc.id = c.contract_id;

commit;
