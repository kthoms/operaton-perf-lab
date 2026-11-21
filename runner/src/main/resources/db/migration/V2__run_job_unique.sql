-- one stored run per job: a job whose run was stored can never produce a second one
alter table run add constraint run_job_id_unique unique (job_id);
