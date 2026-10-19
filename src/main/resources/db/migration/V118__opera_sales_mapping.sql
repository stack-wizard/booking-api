-- Who owns space inventory and billing for a hotel connected to OPERA. Both stay LOCAL until switched.
alter table opera_hotel
  add column space_inventory text not null default 'LOCAL' check (space_inventory in ('LOCAL','OPERA')),
  add column billing         text not null default 'LOCAL' check (billing in ('LOCAL','OPERA'));

-- Codes read from the hotel's OPERA configuration. Codes no longer returned by OPERA are deactivated.
create table opera_code (
  id              bigserial primary key,
  tenant_id       bigint not null,
  opera_hotel_id  bigint not null references opera_hotel(id) on delete cascade,
  kind            text not null
                  check (kind in ('BLOCK_STATUS','EVENT_TYPE','SETUP_STYLE','FUNCTION_SPACE',
                                  'MARKET','SOURCE','GUARANTEE','ROOM_TYPE','RATE_CODE')),
  code            text not null,
  description     text null,
  attrs           jsonb not null default '{}'::jsonb,
  active          boolean not null default true,
  imported_at     timestamptz not null default now(),
  created_at      timestamptz not null default now(),
  unique (opera_hotel_id, kind, code)
);
create index idx_opera_code_tenant on opera_code (tenant_id);

-- Local value -> OPERA code per hotel. local_ref is a resource id (FUNCTION_SPACE), a chain code list code
-- (EVENT_TYPE, SETUP_STYLE) or a price profile id (RATE_CODE).
create table opera_code_map (
  id              bigserial primary key,
  tenant_id       bigint not null,
  opera_hotel_id  bigint not null references opera_hotel(id) on delete cascade,
  kind            text not null check (kind in ('EVENT_TYPE','SETUP_STYLE','FUNCTION_SPACE','RATE_CODE')),
  local_ref       text not null,
  opera_code      text not null,
  created_at      timestamptz not null default now(),
  unique (opera_hotel_id, kind, local_ref)
);
create index idx_opera_code_map_tenant on opera_code_map (tenant_id);

-- OPERA company / guest profile used for blocks.
alter table crm_account add column opera_profile_id text null;
alter table crm_contact add column opera_profile_id text null;
