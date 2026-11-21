-- read-only role for Grafana; password comes from the Flyway placeholder grafanaRoPassword
do $$
begin
  if not exists (select from pg_roles where rolname = 'grafana_ro') then
    create role grafana_ro login password '${grafanaRoPassword}';
  else
    alter role grafana_ro login password '${grafanaRoPassword}';
  end if;
end
$$;

grant usage on schema public to grafana_ro;
grant select on all tables in schema public to grafana_ro;
alter default privileges in schema public grant select on tables to grafana_ro;
