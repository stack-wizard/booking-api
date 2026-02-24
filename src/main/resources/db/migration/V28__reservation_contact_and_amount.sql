alter table reservation_request
  add column if not exists customer_name text null,
  add column if not exists customer_email text null,
  add column if not exists customer_phone text null;

alter table reservation
  add column if not exists customer_email text null,
  add column if not exists customer_phone text null,
  add column if not exists currency text null,
  add column if not exists qty int null,
  add column if not exists unit_price numeric(14,2) null,
  add column if not exists gross_amount numeric(14,2) null;
