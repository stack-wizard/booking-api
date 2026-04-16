alter table reservation_request
  drop constraint if exists reservation_request_status_check;

alter table reservation_request
  add constraint reservation_request_status_check
    check (status in (
      'DRAFT',
      'PENDING_PAYMENT',
      'MANUAL_REVIEW',
      'FINALIZED',
      'CHECKED_IN',
      'CHECKED_OUT',
      'CANCELLED',
      'EXPIRED'
    ));

-- Replace any existing check on reservation.status (constraint name may be auto-generated)
do $$
declare
  r record;
begin
  for r in
    select c.conname
    from pg_constraint c
    join pg_class t on c.conrelid = t.oid
    where t.relname = 'reservation'
      and c.contype = 'c'
      and pg_get_constraintdef(c.oid) ilike '%status%in (%'
  loop
    execute format('alter table reservation drop constraint if exists %I', r.conname);
  end loop;
end $$;

alter table reservation
  add constraint reservation_status_check
    check (status in ('HOLD', 'CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED'));
