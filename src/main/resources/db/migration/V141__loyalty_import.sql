-- CSV imports: loyalty members (migration), PMS reservations / stays and PMS guest profiles. The file goes straight to S3 (presigned PUT); a worker on any
-- instance claims the batch with a lease, streams the file into staging rows, validates, then applies in chunks.

create table loyalty_import_batch (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  program_id         bigint null references loyalty_program(id) on delete restrict,
  import_type        text not null check (import_type in ('MEMBERS','STAYS','PROFILES')),
  property_tenant_id bigint null,
  s3_key             text not null,
  file_name          text not null,
  file_size          bigint null,
  column_mapping     jsonb not null default '{}'::jsonb,
  options            jsonb not null default '{}'::jsonb,
  status             text not null default 'UPLOADED'
                     check (status in ('UPLOADED','QUEUED','STAGING','VALIDATED','APPLY_QUEUED','APPLYING','DONE','FAILED','CANCELLED')),
  rows_total         integer not null default 0,
  rows_valid         integer not null default 0,
  rows_invalid       integer not null default 0,
  rows_applied       integer not null default 0,
  last_applied_row   integer not null default 0,
  lease_owner        text null,
  lease_until        timestamptz null,
  error              text null,
  created_by         bigint null references app_user(id) on delete set null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  check (import_type <> 'STAYS' or property_tenant_id is not null),
  check (import_type <> 'MEMBERS' or program_id is not null)
);
create index idx_loyalty_import_batch_tenant on loyalty_import_batch (tenant_id, created_at desc);
create index idx_loyalty_import_batch_program on loyalty_import_batch (program_id);
create index idx_loyalty_import_batch_created_by on loyalty_import_batch (created_by);
create index idx_loyalty_import_batch_work on loyalty_import_batch (status) where status in ('QUEUED','STAGING','APPLY_QUEUED','APPLYING');

create table loyalty_import_row (
  id         bigserial primary key,
  tenant_id  bigint not null,
  batch_id   bigint not null references loyalty_import_batch(id) on delete cascade,
  row_no     integer not null,
  raw        jsonb not null,
  status     text not null default 'STAGED' check (status in ('STAGED','VALID','INVALID','DUPLICATE','APPLIED','FAILED')),
  errors     jsonb null,
  target_id  bigint null,
  created_at timestamptz not null default now()
);
create unique index uq_loyalty_import_row on loyalty_import_row (batch_id, row_no);
create index idx_loyalty_import_row_status on loyalty_import_row (batch_id, status);
create index idx_loyalty_import_row_tenant on loyalty_import_row (tenant_id);
