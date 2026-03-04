alter table payment_intent
  add column if not exists expires_at timestamptz null;

create index if not exists idx_payment_intent_expires_at
  on payment_intent (expires_at);
