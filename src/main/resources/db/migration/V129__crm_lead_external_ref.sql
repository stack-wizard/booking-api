-- Id of the record in the channel that submitted the lead (e.g. booking-cms inquiry id); makes website submissions idempotent.
alter table crm_lead add column external_ref text null;

create unique index uq_crm_lead_external_ref on crm_lead (tenant_id, source, external_ref) where external_ref is not null;
