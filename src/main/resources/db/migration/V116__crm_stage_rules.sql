-- Per-stage transition rules. Defaults keep the current behaviour (any open stage to any open stage).
alter table crm_pipeline_stage
  add column allow_back   boolean not null default true,
  add column allow_skip   boolean not null default true,
  add column locks_intake boolean not null default false;

-- Set once a deal enters a stage that locks the intake; moving back does not clear it.
alter table crm_opportunity
  add column intake_locked_at timestamptz;
