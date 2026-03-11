alter table invoice
  add column if not exists reservation_request_id bigint null;

update invoice i
set reservation_request_id = rr.id
from reservation_request rr
where i.reservation_request_id is null
  and i.reference_table = 'reservation_request'
  and i.reference_id = rr.id;

update invoice i
set reservation_request_id = pi.reservation_request_id
from payment_intent pi
where i.reservation_request_id is null
  and i.reference_table = 'payment_intent'
  and i.reference_id = pi.id
  and pi.reservation_request_id is not null;

update invoice i
set reservation_request_id = src.reservation_request_id
from invoice src
where i.reservation_request_id is null
  and i.reference_table = 'invoice'
  and i.reference_id = src.id
  and src.reservation_request_id is not null;

update invoice i
set reservation_request_id = null
where i.reservation_request_id is not null
  and not exists (
    select 1
    from reservation_request rr
    where rr.id = i.reservation_request_id
  );

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'invoice_reservation_request_id_fkey'
  ) then
    alter table invoice
      add constraint invoice_reservation_request_id_fkey
      foreign key (reservation_request_id)
      references reservation_request(id)
      on delete set null;
  end if;
end $$;

create index if not exists idx_invoice_reservation_request_id
  on invoice (reservation_request_id);
