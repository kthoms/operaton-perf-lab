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

-- BENCHMARK runs: task-authorization on h2 and postgresql, 2 branches x 8 runs, 3 repetitions
-- of 10 iterations each. 'main' switches segment to 'seg-b' for the last 2 runs.
do $$
declare
  br text; dbn text; i int; rep int; it int; j bigint; r bigint; ts timestamptz; seg text;
  base double precision; v double precision; vals double precision[];
begin
  foreach br in array array['main','feat/x'] loop
    foreach dbn in array array['h2','postgresql'] loop
      for i in 0..7 loop
        ts := now() - ((7 - i) || ' days')::interval - interval '2 hours';
        seg := case when br = 'main' and i >= 6 then 'seg-b' else 'seg-a' end;
        base := case dbn when 'h2' then 2.0 else 7.0 end * (1 + i * 0.03)
                * case when br = 'feat/x' then 0.9 else 1 end;
        insert into job(kind, branch, commit_sha, benchmark, db, priority, status, started_at, finished_at)
          values ('BENCHMARK', br, 'sha' || i || md5(br), 'task-authorization', dbn, 20, 'DONE',
                  ts, ts + interval '20 minutes')
          returning id into j;
        insert into run(job_id, kind, branch, commit_sha, db, benchmark, status, started_at, finished_at, segment)
          values (j, 'BENCHMARK', br, 'sha' || i || md5(br), dbn, 'task-authorization', 'SUCCESS',
                  ts, ts + interval '20 minutes', seg)
          returning id into r;
        for rep in 1..3 loop
          vals := array[]::double precision[];
          for it in 1..10 loop
            v := base * (0.9 + ((it * 7 + rep * 3 + i) % 10) * 0.025);
            vals := vals || v;
            insert into measurement(run_id, kpi, value, unit, dims) values
              (r, 'benchmark.iteration.duration', v, 'ms',
               jsonb_build_object('benchmark', 'task-authorization', 'iteration', it::text, 'repetition', rep::text));
          end loop;
          insert into measurement(run_id, kpi, value, unit, dims)
            select r, 'benchmark.median.duration', percentile_cont(0.5) within group (order by x), 'ms',
                   jsonb_build_object('benchmark', 'task-authorization', 'repetition', rep::text)
            from unnest(vals) as x;
        end loop;
      end loop;
    end loop;
  end loop;

  select id into r from run where kind = 'BENCHMARK' and branch = 'main' and db = 'postgresql'
    order by started_at desc limit 1;
  insert into regression(run_id, series_key, kpi, dims, value, baseline_median, last_good_sha, compare_url)
    values (r, 'benchmark.median.duration|task-authorization|postgresql', 'benchmark.median.duration',
            '{"benchmark":"task-authorization"}', 9.1, 7.8, 'sha5' || md5('main'),
            'https://github.com/operaton/operaton/compare/ccc...ddd');
end
$$;
