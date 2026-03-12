alter table app_user
  add column if not exists employee_number text null;

create index if not exists idx_app_user_tenant_employee_number
  on app_user (tenant_id, employee_number);
