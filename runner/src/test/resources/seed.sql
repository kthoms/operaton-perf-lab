-- Demo data for dashboard verification: 2 branches x 10 BUILD runs over 10 days.
-- Branch 'main' switches segment from 'seg-a' to 'seg-b' for the last 4 runs.
do $$
declare
  br text; i int; j bigint; r bigint; mod text; cls text; ts timestamptz; seg text; f double precision;
begin
  foreach br in array array['main','feat/x'] loop
    for i in 0..9 loop
      ts := now() - ((9 - i) || ' days')::interval;
      seg := case when br = 'main' and i >= 6 then 'seg-b' else 'seg-a' end;
      f := 1 + i * 0.02 + case when br = 'feat/x' then 0.1 else 0 end;
      insert into job(kind, branch, commit_sha, priority, status, started_at, finished_at)
        values ('BUILD', br, 'sha' || i || md5(br), 1, 'DONE', ts, ts + interval '30 minutes')
        returning id into j;
      insert into run(job_id, kind, branch, commit_sha, status, started_at, finished_at, segment)
        values (j, 'BUILD', br, 'sha' || i || md5(br), 'SUCCESS', ts, ts + interval '30 minutes', seg)
        returning id into r;
      foreach mod in array array['engine','rest','webapps'] loop
        insert into measurement(run_id, kpi, value, unit, dims) values
          (r, 'test.module.duration',
             f * case mod when 'engine' then 600 when 'rest' then 120 else 60 end, 's',
             jsonb_build_object('module', mod)),
          (r, 'test.module.count', 100, 'count', jsonb_build_object('module', mod)),
          (r, 'build.module.wall',
             f * case mod when 'engine' then 700 when 'rest' then 150 else 90 end, 's',
             jsonb_build_object('module', mod));
      end loop;
      foreach cls in array array['HistoryTest','JobExecutorTest','MigrationTest','ProcessInstanceTest','TaskQueryTest'] loop
        insert into measurement(run_id, kpi, value, unit, dims) values
          (r, 'test.class.duration',
             f * (30 + length(cls) * 5 + (i % 3)), 's',
             jsonb_build_object('module', 'engine', 'class', cls));
      end loop;
      insert into measurement(run_id, kpi, value, unit) values (r, 'build.total.wall', f * 1800, 's');
    end loop;
  end loop;

  select id into r from run where branch = 'main' order by started_at desc limit 1;
  insert into regression(run_id, series_key, kpi, dims, value, baseline_median, last_good_sha, compare_url)
    values (r, 'test.module.duration|engine', 'test.module.duration', '{"module":"engine"}',
            700, 620, 'sha5' || md5('main'), 'https://github.com/operaton/operaton/compare/aaa...bbb');
end
$$;
