alter table reservation
  alter column starts_at type timestamp without time zone
    using (starts_at at time zone 'Europe/Zagreb'),
  alter column ends_at type timestamp without time zone
    using (ends_at at time zone 'Europe/Zagreb');

alter table allocation
  drop column booked_range,
  alter column starts_at type timestamp without time zone
    using (starts_at at time zone 'Europe/Zagreb'),
  alter column ends_at type timestamp without time zone
    using (ends_at at time zone 'Europe/Zagreb');

alter table allocation
  add column booked_range tsrange generated always as (tsrange(starts_at, ends_at, '[)')) stored;
