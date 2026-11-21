# operaton-perf-lab

A daemon that benchmarks the Operaton BPM engine on this Mac and stores the results in
PostgreSQL, viewed in Grafana.

## Layout

- `runner/` - the daemon (Java 25, Maven), schema migrations via Flyway
- `infra/` - docker compose for the results database (port 5433) and Grafana (port 3000)

## Setup

```bash
cp infra/.env.example infra/.env   # then edit
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d
```

## Tests

Integration tests (`*IT`) use Testcontainers (`postgres:17`) and run in `mvn verify`.
The failsafe plugin sets the Colima-specific Testcontainers variables; you only need
`DOCKER_HOST` pointing at the Colima socket.

```bash
mvn -pl runner verify
```
