-- Import files kept in the database when object storage is not available (loyalty.import.storage=DB) or the
-- client uploads through the API instead of a presigned S3 PUT. The row lives only until the batch is DONE or
-- CANCELLED; the tenant is the batch's tenant.
create table loyalty_import_file (
    batch_id    bigint primary key references loyalty_import_batch(id) on delete cascade,
    content     bytea not null,
    uploaded_at timestamptz not null default now()
);
