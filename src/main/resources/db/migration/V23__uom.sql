create table uom (
  code        text primary key,
  name        text not null,
  active      boolean not null default true,
  created_at  timestamptz not null default now()
);

insert into uom (code, name) values
  ('DAY', 'Day'),
  ('HOUR', 'Hour'),
  ('HALF_DAY', 'Half Day')
on conflict (code) do nothing;
