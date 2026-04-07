alter table reservation_request
  drop constraint if exists reservation_request_status_check;

alter table reservation_request
  add constraint reservation_request_status_check
    check (status in ('DRAFT','PENDING_PAYMENT','MANUAL_REVIEW','FINALIZED','CANCELLED','EXPIRED'));

alter table tenant_config
  add column if not exists manual_review_ttl_minutes int not null default 2880
    check (manual_review_ttl_minutes > 0);
