-- reservation.request_type must allow the same values as reservation_request.type (V55).
-- Some databases still have a legacy CHECK (e.g. reservation_request_type_check1 from V19) that
-- only allows INTERNAL/EXTERNAL, while a second CHECK allows WALKIN/INHOUSE. All checks on this
-- column must be dropped before adding a single canonical constraint.

alter table reservation drop constraint if exists reservation_request_type_check;

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
        where a.attname = 'request_type'
      )
    loop
      execute format('alter table reservation drop constraint if exists %I', r.conname);
    end loop;
end $$;

alter table reservation drop constraint if exists reservation_request_type_check;

alter table reservation
  add constraint reservation_request_type_check
    check (request_type in ('INTERNAL', 'EXTERNAL', 'WALKIN', 'INHOUSE'));
