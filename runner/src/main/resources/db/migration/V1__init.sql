create table job (
  id               bigserial primary key,
  kind             text not null check (kind in ('BUILD','BENCHMARK')),
  branch           text,
  commit_sha       text,
  benchmark        text,
  db               text,
  priority         int not null,
  status           text not null check (status in ('QUEUED','RUNNING','DONE','FAILED','NOT_APPLICABLE')),
  cancel_requested boolean not null default false,
  attempts         int not null default 0,
  created_at       timestamptz not null default now(),
  started_at       timestamptz,
  finished_at      timestamptz
);

create table run (
  id           bigserial primary key,
  job_id       bigint not null references job(id),
  kind         text not null,
  branch       text not null,
  commit_sha   text not null,
  db           text,
  benchmark    text,
  status       text not null check (status in ('SUCCESS','FAILED')),
  started_at   timestamptz not null,
  finished_at  timestamptz,
  fingerprint  jsonb,
  segment      text not null,
  command_line text,
  log_path     text
);

create table measurement (
  id     bigserial primary key,
  run_id bigint not null references run(id),
  kpi    text not null,
  value  double precision not null,
  unit   text,
  dims   jsonb
);
create index measurement_kpi_idx on measurement (kpi);
create index measurement_dims_idx on measurement using gin (dims);

create table activity_sample (
  ts                timestamptz primary key,
  user_idle_seconds int,
  load1             double precision
);

create table regression (
  id              bigserial primary key,
  run_id          bigint not null references run(id),
  series_key      text not null,
  kpi             text not null,
  dims            jsonb,
  value           double precision,
  baseline_median double precision,
  last_good_sha   text,
  compare_url     text,
  created_at      timestamptz not null default now()
);

create table backup_log (
  ts   timestamptz primary key,
  path text
);
