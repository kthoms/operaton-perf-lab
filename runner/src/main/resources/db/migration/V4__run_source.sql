-- where a run came from: 'branch' = scheduled series (tip of a configured branch),
-- 'commit' = manual or bisect job for an explicit commit; only 'branch' runs form trends and baselines
alter table run add column source text not null default 'branch'
  constraint run_source_check check (source in ('branch','commit'));
