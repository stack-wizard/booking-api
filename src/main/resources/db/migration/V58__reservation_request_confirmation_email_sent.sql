alter table reservation_request
  add column if not exists confirmation_email_sent_at timestamptz;
