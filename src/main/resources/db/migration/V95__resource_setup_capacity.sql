-- Function spaces are resources: physical attributes live on resource, seating per setup style
-- in resource_setup_capacity. Divisibility is resource_composition, durations are booking_calendar.

alter table resource add column if not exists area_sqm numeric(10,2) null;
alter table resource add column if not exists floor text null;
alter table resource add column if not exists natural_light boolean null;

create table resource_setup_capacity (
  id           bigserial primary key,
  tenant_id    bigint not null,
  resource_id  bigint not null references resource(id) on delete cascade,
  setup_style  text not null
               check (setup_style in (
                 'THEATRE','CLASSROOM','U_SHAPE','BOARDROOM',
                 'BANQUET','CABARET','RECEPTION','HOLLOW_SQUARE'
               )),
  capacity     int not null check (capacity > 0),
  notes        text null,
  created_at   timestamptz not null default now()
);

create unique index uq_resource_setup_capacity on resource_setup_capacity (resource_id, setup_style);
create index idx_resource_setup_capacity_tenant on resource_setup_capacity (tenant_id, capacity);

insert into resource_setup_capacity (tenant_id, resource_id, setup_style, capacity, notes)
select fss.tenant_id, fs.resource_id, fss.setup_style, fss.capacity, fss.notes
from function_space_setup fss
join function_space fs on fs.id = fss.function_space_id
on conflict do nothing;

update resource r
set area_sqm = fs.area_sqm,
    floor = fs.floor,
    natural_light = fs.natural_light
from function_space fs
where fs.resource_id = r.id;

drop table function_space_setup;
drop table function_space;
