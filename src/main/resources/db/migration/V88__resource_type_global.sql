-- Make resource_type a shared global catalog (no tenant_id).
-- Dedupe by upper(code), repoint resources to the canonical row, then drop tenant scoping.

-- 1. Resources pointing at duplicates move to the canonical row (lowest id per code)
update resource rsc
set resource_type_id = c.keep_id
from resource_type rt
join (select min(id) as keep_id, upper(code) as ucode from resource_type group by upper(code)) c
  on c.ucode = upper(rt.code)
where rsc.resource_type_id = rt.id
  and rsc.resource_type_id <> c.keep_id;

-- 2. Delete duplicates (FK is on delete restrict, so this must run after step 1)
delete from resource_type rt
using (select min(id) as keep_id, upper(code) as ucode from resource_type group by upper(code)) c
where upper(rt.code) = c.ucode and rt.id <> c.keep_id;

-- 3. Drop tenant scoping; unique (tenant_id, code) drops with the column
alter table resource_type drop column tenant_id;
alter table resource_type add constraint resource_type_code_key unique (code);

-- 4. Standard set for empty / new databases
insert into resource_type (code, name, default_time_model) values
  ('SUNBED', 'Sunbed', null),
  ('DECK_CABANA', 'Deck Cabana', null),
  ('BALDAHIN', 'Baldahin', null),
  ('APARTMENT', 'Apartment', null),
  ('COMPOSITION', 'Composition', null)
on conflict (code) do nothing;
