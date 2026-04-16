-- Allow several invoices to reference the same source invoice (reference_table = 'invoice'),
-- e.g. credit note and storno, while keeping at most one row per payment_intent / reservation_request pair.

alter table invoice drop constraint if exists invoice_reference_table_reference_id_key;

create unique index if not exists invoice_uq_ref_payment_intent
    on invoice (reference_table, reference_id)
    where reference_table = 'payment_intent';

create unique index if not exists invoice_uq_ref_reservation_request
    on invoice (reference_table, reference_id)
    where reference_table = 'reservation_request';
