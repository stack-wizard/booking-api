-- Units held by one line. EXACT resources hold one unit; a POOL line holds `units` of the pool,
-- so a block of 15 rooms of a room type is one line, not 15. `qty` stays the priced quantity.
alter table reservation
  add column units int not null default 1 check (units > 0);

alter table allocation
  add column units int not null default 1 check (units > 0);

create index if not exists idx_resource_parent_pool on resource(parent_pool_id) where parent_pool_id is not null;
