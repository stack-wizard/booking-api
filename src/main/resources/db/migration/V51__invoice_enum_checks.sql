update invoice
set invoice_type = upper(invoice_type)
where invoice_type is not null;

update invoice
set status = upper(status)
where status is not null;

update invoice
set fiscalization_status = upper(fiscalization_status)
where fiscalization_status is not null;

update invoice
set invoice_type = 'INVOICE'
where invoice_type is null
   or invoice_type not in ('INVOICE', 'DEPOSIT', 'INVOICE_STORNO', 'DEPOSIT_STORNO', 'ROOM_CHARGE');

update invoice
set status = 'DRAFT'
where status is null
   or status not in ('DRAFT', 'ISSUED');

update invoice
set fiscalization_status = 'NOT_REQUIRED'
where fiscalization_status is null
   or fiscalization_status not in ('NOT_REQUIRED', 'REQUIRED', 'FISCALIZED', 'FAILED');

alter table invoice
  drop constraint if exists invoice_invoice_type_check;

alter table invoice
  add constraint invoice_invoice_type_check
  check (invoice_type in ('INVOICE', 'DEPOSIT', 'INVOICE_STORNO', 'DEPOSIT_STORNO', 'ROOM_CHARGE'));

alter table invoice
  drop constraint if exists invoice_status_check;

alter table invoice
  add constraint invoice_status_check
  check (status in ('DRAFT', 'ISSUED'));

alter table invoice
  drop constraint if exists invoice_fiscalization_status_check;

alter table invoice
  add constraint invoice_fiscalization_status_check
  check (fiscalization_status in ('NOT_REQUIRED', 'REQUIRED', 'FISCALIZED', 'FAILED'));
