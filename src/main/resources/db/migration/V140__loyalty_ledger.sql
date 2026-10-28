-- Append-only ledger of nights/points. Corrections are counter-entries; the only allowed update is
-- marking an entry inactive when it is reversed (FR-07.3). One STAY night per member per date (FR-04.2).

create table loyalty_ledger (
  id                 bigserial primary key,
  tenant_id          bigint not null,
  member_id          bigint not null references loyalty_member(id) on delete restrict,
  metric             text not null check (metric in ('QUALIFYING_NIGHT','BONUS_NIGHT','POINTS')),
  qty                integer not null check (qty <> 0),
  night_date         date null,
  as_of              date not null,
  source             text not null check (source in ('STAY','OPENING_BALANCE','WALLET','REFERRAL','ADJUSTMENT','CLAIM','REVERSAL')),
  active             boolean not null default true,
  reservation_id     bigint null references pms_reservation(id) on delete restrict,
  property_tenant_id bigint null,
  rule_set_id        bigint null references loyalty_rule_set(id) on delete restrict,
  reversal_of_id     bigint null references loyalty_ledger(id) on delete restrict,
  operator_user_id   bigint null references app_user(id) on delete set null,
  reason             text null,
  is_exception       boolean not null default false,
  created_at         timestamptz not null default now(),
  check (source <> 'STAY' or night_date is not null),
  check (source <> 'REVERSAL' or reversal_of_id is not null)
);
create unique index uq_loyalty_ledger_stay_night
  on loyalty_ledger (member_id, night_date) where source = 'STAY' and active;
create unique index uq_loyalty_ledger_reversal on loyalty_ledger (reversal_of_id) where reversal_of_id is not null;
create index idx_loyalty_ledger_member on loyalty_ledger (member_id, as_of);
create index idx_loyalty_ledger_tenant on loyalty_ledger (tenant_id);
create index idx_loyalty_ledger_reservation on loyalty_ledger (reservation_id);
create index idx_loyalty_ledger_rule_set on loyalty_ledger (rule_set_id);
create index idx_loyalty_ledger_operator on loyalty_ledger (operator_user_id);

create or replace function loyalty_ledger_guard() returns trigger as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'loyalty_ledger is append-only';
  end if;
  -- booking_promote_catalog moves a promoted hotel's rows to its organization (tenant_id only)
  if current_setting('booking.promote_catalog', true) = 'on'
     and row(new.id, new.member_id, new.qty, new.active, new.source) is not distinct from row(old.id, old.member_id, old.qty, old.active, old.source) then
    return new;
  end if;
  if old.active and not new.active
     and row(new.id, new.tenant_id, new.member_id, new.metric, new.qty, new.night_date, new.as_of, new.source,
             new.reservation_id, new.property_tenant_id, new.rule_set_id, new.reversal_of_id, new.reason, new.is_exception, new.created_at)
         is not distinct from
         row(old.id, old.tenant_id, old.member_id, old.metric, old.qty, old.night_date, old.as_of, old.source,
             old.reservation_id, old.property_tenant_id, old.rule_set_id, old.reversal_of_id, old.reason, old.is_exception, old.created_at) then
    return new;
  end if;
  -- operator_user_id may only be nulled by app_user deletion (on delete set null)
  if new.operator_user_id is null and old.operator_user_id is not null
     and row(new.active, new.qty) is not distinct from row(old.active, old.qty) then
    return new;
  end if;
  raise exception 'loyalty_ledger is append-only (only active -> false is allowed)';
end;
$$ language plpgsql;

create trigger trg_loyalty_ledger_guard
  before update or delete on loyalty_ledger
  for each row execute function loyalty_ledger_guard();
