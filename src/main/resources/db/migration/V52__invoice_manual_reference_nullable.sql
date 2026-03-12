alter table invoice
  alter column reference_table drop not null;

alter table invoice
  alter column reference_id drop not null;

update invoice
set reference_table = null,
    reference_id = null
where reference_table = 'manual_invoice';
