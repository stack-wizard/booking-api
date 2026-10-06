-- Phase 5: costs that do not sit on an event item (external suppliers, staff, transport...).

create table crm_cost_item (
  id             bigserial primary key,
  tenant_id      bigint not null,
  event_id       bigint null references event(id) on delete cascade,
  opportunity_id bigint null references crm_opportunity(id) on delete cascade,
  category       text not null default 'OTHER'
                 check (category in ('VENUE','F_AND_B','AV','STAFF','EXTERNAL','TRANSPORT','OTHER')),
  description    text not null,
  supplier       text null,
  amount         numeric(14,2) not null check (amount >= 0),
  currency       text not null default 'EUR',
  incurred_on    date null,
  created_at     timestamptz not null default now(),
  created_by     bigint null references app_user(id) on delete set null,
  constraint crm_cost_item_parent check (event_id is not null or opportunity_id is not null)
);

create index idx_crm_cost_item_event on crm_cost_item (event_id);
create index idx_crm_cost_item_opportunity on crm_cost_item (opportunity_id);
create index idx_crm_cost_item_tenant on crm_cost_item (tenant_id);
create index idx_crm_cost_item_user on crm_cost_item (created_by);
