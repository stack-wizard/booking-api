with tenant_ids as (
    select distinct tenant_id from tenant_config
    union
    select distinct tenant_id from tenant_integration_config
    union
    select distinct tenant_id from invoice
    union
    select distinct tenant_id from payment_intent
    union
    select distinct tenant_id from reservation_request
)
insert into app_user (
    tenant_id,
    username,
    password_hash,
    role,
    employee_number
)
select
    tenant_id,
    'online-system-tenant-' || tenant_id,
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'CASHIER',
    null
from tenant_ids t
where t.tenant_id is not null
  and not exists (
    select 1
    from app_user u
    where u.username = 'online-system-tenant-' || t.tenant_id
  );
