-- Each hotel numbers its own invoices (invoice_sequence is per tenant), so the number is unique per tenant.
alter table invoice drop constraint if exists invoice_invoice_number_key;
alter table invoice add constraint invoice_tenant_invoice_number_key unique (tenant_id, invoice_number);
