alter table invoice
  add column if not exists fiscalized_at timestamptz null;

alter table invoice
  add column if not exists fiscal_folio_no text null;

alter table invoice
  add column if not exists fiscal_document_no_1 text null;

alter table invoice
  add column if not exists fiscal_document_no_2 text null;

alter table invoice
  add column if not exists fiscal_special_id text null;

alter table invoice
  add column if not exists fiscal_qr_url text null;

alter table invoice
  add column if not exists fiscal_error_message text null;

alter table invoice
  add column if not exists fiscal_last_request_payload jsonb null;

alter table invoice
  add column if not exists fiscal_last_response_payload jsonb null;

create index if not exists idx_invoice_fiscalized_at
  on invoice (fiscalized_at);
