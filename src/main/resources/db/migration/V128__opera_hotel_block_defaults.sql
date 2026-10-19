-- Defaults for OPERA sales block header fields (origin + owners).
alter table opera_hotel
  add column if not exists default_origin_code text null,
  add column if not exists default_block_owner_code text null;
