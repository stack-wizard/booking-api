alter table resource_map
  add column if not exists parent_map_id bigint null references resource_map(id) on delete set null,
  add column if not exists valid_from date null,
  add column if not exists valid_to date null;

alter table resource_map
  drop constraint if exists resource_map_valid_range_check;

alter table resource_map
  add constraint resource_map_valid_range_check
    check (valid_to is null or valid_from is null or valid_to >= valid_from);

create index if not exists idx_resource_map_parent on resource_map(parent_map_id);
create index if not exists idx_resource_map_period on resource_map(valid_from, valid_to);
