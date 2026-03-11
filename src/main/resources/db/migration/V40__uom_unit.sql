insert into uom (code, name, active)
values ('UNIT', 'Unit', true)
on conflict (code) do update
set name = excluded.name,
    active = true;
