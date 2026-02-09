alter table reservation_request
  add column if not exists status text not null default 'DRAFT'
    check (status in ('DRAFT','FINALIZED','CANCELLED'));
