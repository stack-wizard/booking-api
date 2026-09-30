# Production cutover: Mikos Platform auth for booking (single tenant)

Run **after** mikos-platform migration `V27__booking_roles.sql` and booking-api migrations
`V86` / `V87` / `V88` are applied, and **before** switching booking-admin / booking-cms / booking-api
traffic to Platform JWT.

`V88` makes `resource_type` a **global** catalog (drops `tenant_id`). Before applying it on prod,
check for duplicate codes with different names (the migration keeps the lowest `id` per code):

```sql
SELECT upper(code), count(*), array_agg(distinct name)
FROM resource_type
GROUP BY 1
HAVING count(*) > 1;
```

After V88, all tenants share the same types; new types are added only via Flyway.

Replace placeholders:

| Placeholder | Meaning |
|-------------|---------|
| `:prod_booking_tenant_id` | Existing booking bigint `tenant_id` (one property) |
| `:platform_tenant_id` | Existing Platform tenant UUID **or** a new UUID you create below |
| `:tenant_name` | Display name on Platform |
| `:hotel_code` | Optional Opera hotel code |
| `:parent_tenant_id` | Optional parent chain UUID on Platform (or NULL) |

## 0. Pre-checks

```sql
-- Booking DB: list users to migrate (exclude online-system)
SELECT id, tenant_id, username, role, employee_number
FROM app_user
WHERE tenant_id = :prod_booking_tenant_id
  AND username NOT LIKE 'online-system-tenant-%'
ORDER BY username;

-- Platform DB: username collisions
SET search_path TO mikos_platform;
SELECT u.username, u.id
FROM users u
WHERE lower(u.username) IN (
  -- paste usernames from booking app_user list
);
```

Resolve collisions manually (rename on Platform or skip / link to existing Platform user).

## 1. Platform: tenant + BOOKING catalog

```sql
SET search_path TO mikos_platform;

-- Create only if hotel is not already on Platform:
INSERT INTO tenants (id, name, active, parent_tenant_id, timezone, hotel_code)
SELECT :platform_tenant_id::uuid,
       :tenant_name,
       true,
       :parent_tenant_id::uuid,  -- or NULL
       NULL,
       :hotel_code
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE id = :platform_tenant_id::uuid);

INSERT INTO tenant_application_catalog (tenant_id, application_id)
SELECT :platform_tenant_id::uuid, a.id
FROM applications a
WHERE a.code = 'BOOKING'
  AND NOT EXISTS (
    SELECT 1 FROM tenant_application_catalog c
    WHERE c.tenant_id = :platform_tenant_id::uuid AND c.application_id = a.id
  );
```

## 2. Platform: users + membership + entitlement + BOOKING_* roles

Map local roles:

| app_user.role | Platform role |
|---------------|---------------|
| ADMIN | BOOKING_ADMIN |
| STAFF | BOOKING_STAFF |
| CASHIER | BOOKING_CASHIER |
| SUPER_ADMIN | **do not auto-migrate** — grant PLATFORM_ADMIN only intentionally |

For each booking user (example pattern; generate from export):

```sql
SET search_path TO mikos_platform;

-- password_hash from booking: prefix {bcrypt} so Spring DelegatingPasswordEncoder accepts it
INSERT INTO users (id, username, password_hash, enabled)
SELECT gen_random_uuid(),
       :username,
       '{bcrypt}' || :booking_password_hash,  -- raw $2a$... from app_user.password_hash
       true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE lower(username) = lower(:username));

-- Capture platform user id:
-- SELECT id FROM users WHERE lower(username) = lower(:username);

INSERT INTO user_tenant_memberships (id, user_id, tenant_id)
SELECT gen_random_uuid(), u.id, :platform_tenant_id::uuid
FROM users u
WHERE lower(u.username) = lower(:username)
  AND NOT EXISTS (
    SELECT 1 FROM user_tenant_memberships m
    WHERE m.user_id = u.id AND m.tenant_id = :platform_tenant_id::uuid
  );

INSERT INTO tenant_application_entitlements (user_id, tenant_id, application_id)
SELECT u.id, :platform_tenant_id::uuid, a.id
FROM users u
CROSS JOIN applications a
WHERE lower(u.username) = lower(:username)
  AND a.code = 'BOOKING'
  AND NOT EXISTS (
    SELECT 1 FROM tenant_application_entitlements e
    WHERE e.user_id = u.id
      AND e.tenant_id = :platform_tenant_id::uuid
      AND e.application_id = a.id
  );

INSERT INTO membership_roles (membership_id, role_id)
SELECT m.id, r.id
FROM user_tenant_memberships m
JOIN users u ON u.id = m.user_id
CROSS JOIN roles r
WHERE lower(u.username) = lower(:username)
  AND m.tenant_id = :platform_tenant_id::uuid
  AND r.name = :booking_role_name  -- BOOKING_ADMIN / BOOKING_STAFF / BOOKING_CASHIER
ON CONFLICT DO NOTHING;
```

Keep a spreadsheet: `booking_app_user_id`, `username`, `platform_user_id`.

## 3. Booking DB: mapping + platform_user_id

```sql
INSERT INTO platform_tenant_mapping (tenant_id, platform_tenant_id)
VALUES (:prod_booking_tenant_id, :platform_tenant_id::uuid)
ON CONFLICT (tenant_id) DO NOTHING;

-- For each migrated user:
UPDATE app_user
SET platform_user_id = :platform_user_id::uuid
WHERE id = :booking_app_user_id
  AND tenant_id = :prod_booking_tenant_id;
```

`app_user.id` must stay unchanged so `invoice.issued_by_user_id` keeps working.

## 4. booking-cms

Set CMS tenant fields:
- `platformTenantId` = `:platform_tenant_id`
- `domains` = public hostname(s), e.g. `booking.beachhvar.com`
- `slug` = short code for forotel override (`https://web.forotel.com/cms?tenant=<slug>`)

CMS no longer stores a local booking tenant id; booking-api resolves it from `X-Tenant-Id`
via `platform_tenant_mapping`.

## 5. Smoke test after deploy

1. Login via booking-admin OIDC → Platform
2. `GET /api/auth/me` returns expected role + tenantId
3. CMS public book flow (m2m + X-Tenant-Id)
4. Monri webhook/callback still anonymous
5. Issue + fiscalize invoice (employee_number present)
