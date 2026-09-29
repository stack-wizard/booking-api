-- Dev seed: map existing booking tenant_id=1 (seed_booking_data) to Platform Demo Hotel (DH).
-- Platform demo UUIDs from mikos-platform V2/V10:
--   chain: 11111111-1111-1111-1111-111111111111
--   DH:    21111111-1111-1111-1111-111111111111
-- Other Platform tenants are provisioned automatically on first admin login.

insert into platform_tenant_mapping (tenant_id, platform_tenant_id)
values
  (1, '21111111-1111-1111-1111-111111111111'::uuid)
on conflict (tenant_id) do nothing;

-- Optional: also map chain tenant if a separate booking tenant_id=2 exists locally.
-- insert into platform_tenant_mapping (tenant_id, platform_tenant_id)
-- values (2, '11111111-1111-1111-1111-111111111111'::uuid)
-- on conflict (tenant_id) do nothing;
