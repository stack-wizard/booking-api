insert into uom (code, name)
values ('HALF_DAY', 'Half Day')
on conflict (code) do nothing;
