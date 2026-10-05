-- A function is one space x one continuous window. Capacity is taken only through a generated
-- reservation line for the occupancy window, which goes into allocation the existing way.

create table event_function (
  id                    bigserial primary key,
  tenant_id             bigint not null,
  event_id              bigint not null references event(id) on delete cascade,
  resource_id           bigint null references resource(id) on delete restrict,
  function_type         text not null
                        check (function_type in ('PLENARY','BREAKOUT','COFFEE_BREAK','LUNCH','DINNER','RECEPTION','EXHIBITION','OTHER')),
  name                  text null,
  setup_style           text null
                        check (setup_style is null or setup_style in (
                          'THEATRE','CLASSROOM','U_SHAPE','BOARDROOM',
                          'BANQUET','CABARET','RECEPTION','HOLLOW_SQUARE'
                        )),
  starts_at             timestamp not null,
  ends_at               timestamp not null,
  occupancy_starts_at   timestamp not null,
  occupancy_ends_at     timestamp not null,
  pax                   int null check (pax >= 0),
  package_product_id    bigint null references product(id) on delete set null,
  notes                 text null,
  display_order         int not null default 0,
  created_at            timestamptz not null default now(),
  constraint event_function_window check (ends_at > starts_at),
  constraint event_function_occupancy check (occupancy_starts_at <= starts_at and occupancy_ends_at >= ends_at)
);

create index idx_event_function_event on event_function (event_id, starts_at);
create index idx_event_function_tenant on event_function (tenant_id);
create index idx_event_function_resource on event_function (resource_id, occupancy_starts_at);
create index idx_event_function_package on event_function (package_product_id);

alter table reservation
  add column if not exists event_function_id bigint null references event_function(id) on delete restrict;

create unique index uq_reservation_active_event_function
  on reservation (event_function_id)
  where event_function_id is not null and upper(status) <> 'CANCELLED';
create index idx_reservation_event_function on reservation (event_function_id);

do $$
begin
  if exists (select 1 from reservation where request_id is null) then
    raise exception 'reservation rows without request_id exist; resolve them before adding reservation_owner check';
  end if;
end $$;

alter table reservation
  add constraint reservation_owner_check
  check ((request_id is null) <> (event_function_id is null));
