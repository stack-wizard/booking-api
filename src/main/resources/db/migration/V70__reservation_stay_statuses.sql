-- Extend allowed status values (check-in / check-out on request; stay states on reservation).
-- No data updates: existing rows must already satisfy the new checks (or migration fails with a clear constraint error).

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

-- Drop every CHECK on reservation tied to column "status" (PG may name/render checks differently across versions).
alter table reservation drop constraint if exists reservation_status_check;

do $$
declare
  r record;
  res oid := to_regclass('reservation');
begin
  if res is null then
    raise exception 'relation reservation not found (search_path=%)', current_setting('search_path');
  end if;
  for r in
    select c.conname
    from pg_constraint c
    where c.conrelid = res
      and c.contype = 'c'
      and exists (
        select 1
        from unnest(c.conkey) as col(attnum)
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = col.attnum
        where a.attname = 'status'
      )
    loop
      execute format('alter table reservation drop constraint if exists %I', r.conname);
    end loop;
end $$;

-- Drop again immediately before ADD so the named constraint cannot survive if anything above missed it.
alter table reservation drop constraint if exists reservation_status_check;

alter table reservation
  add constraint reservation_status_check
    check (status in ('HOLD', 'CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED'));
