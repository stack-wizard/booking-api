-- Opportunities and payment milestones belong to the chain (tenant_id), but may point at the hotel they are about.
-- null = chain-wide / not decided yet.
alter table crm_opportunity
  add column if not exists property_tenant_id bigint null
    references platform_tenant_mapping (tenant_id) on delete set null;

alter table sales_payment_milestone
  add column if not exists property_tenant_id bigint null
    references platform_tenant_mapping (tenant_id) on delete set null;

create index if not exists idx_crm_opportunity_property on crm_opportunity (property_tenant_id);
create index if not exists idx_sales_payment_milestone_property on sales_payment_milestone (property_tenant_id);
