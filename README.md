# KMS — Key Indicator Monitoring System

关键指标监控系统 (Key Indicator Monitoring System, KMS): monitors the local host's performance
metrics (CPU, memory, disk, network, temperature) via OSHI, stores time-series data in TDengine
and metadata/alerts in DM8, analyses thresholds/volatility with graded alerting, and serves a
RuoYi-based web UI with real-time charts, alert management, and a dashboard (大屏).

## Modules

| Module    | Folder         | Role                                                                  |
|-----------|----------------|-----------------------------------------------------------------------|
| parent    | `pom.xml`      | Multi-module Maven parent: Spring Boot 3.x + shared dependencyManagement |
| database  | `kms-database` | Data-access layer, dual datasources: DM8 primary + TDengine time-series |
| collector | `kms-collector`| OSHI metric collection, dynamic period, REST control API (module A)   |
| analyser  | `kms-analyser` | Rule engine, 3-level alerts, dynamic thresholds, WebSocket push (C)   |
| web       | `kms-web`      | RuoYi-style Vue3 UI + REST backend: real-time charts, history, alert management, dashboard (D) |

## Dependency versions (Step 1)

| Dependency       | Artifact                                             | Version   |
|------------------|------------------------------------------------------|-----------|
| Spring Boot      | `org.springframework.boot:spring-boot-starter-parent`| 3.5.16    |
| MyBatis-Plus     | `com.baomidou:mybatis-plus-spring-boot3-starter`     | 3.5.17    |
| OSHI             | `com.github.oshi:oshi-core`                          | 7.6.1     |
| DM8 driver       | `com.dameng:DmJdbcDriver18`                          | 8.1.3.140 |
| TDengine driver  | `com.taosdata.jdbc:taos-jdbcdriver`                  | 3.9.0     |

Java 17 bytecode target (builds and runs on JDK 17+; verified on JDK 21).

## Build

```bash
mvn clean package        # builds all four modules
```

The web UI is a Vite project in `kms-web/frontend` (Vue 3 + Element Plus + ECharts).
Its build output (`dist/`) is copied into `kms-web/src/main/resources/static` and
served by Spring Boot, so no Node.js is needed at runtime:

```bash
cd kms-web/frontend
npm install              # first time only
npm run build            # writes dist/
cp -r dist/* ../src/main/resources/static/   # then rebuild kms-web with Maven
# development mode: npm run dev  (Vite on :3000, proxies /api to :8080)
```

`mvn test -pl kms-database` runs the live dual-datasource connection tests
(requires the Docker containers to be up; connection settings are in
`kms-database/src/test/resources/application.yml`).

## Dual datasource configuration (Step 2)

Configuration lives in `kms-database` (`com.kms.database.config`) and is shared by all app
modules through `@SpringBootApplication(scanBasePackages = "com.kms")`:

- `DmDataSourceConfig` — DM8 **primary**: HikariCP pool + MyBatis-Plus (`@MapperScan` on
  `com.kms.database.mapper`, pagination with the DM dialect). Marked `@Primary` so
  MyBatis-Plus and the transaction manager bind to it.
- `TdengineDataSourceConfig` — TDengine **secondary**: HikariCP pool + `taosJdbcTemplate`
  for raw SQL against the time-series tables.
- `MybatisPlusConfig` — pagination interceptor (`DbType.DM`). Note: since MyBatis-Plus
  3.5.9 the JSqlParser interceptors live in the separate `mybatis-plus-jsqlparser`
  artifact, which the parent pom pins.

Connection keys in `application.yml` (`kms.datasource.dm.*` / `kms.datasource.tdengine.*`),
overridable via environment variables:

| Env var            | Default      | Applies to |
|--------------------|--------------|------------|
| `DB_HOST`          | `localhost`  | both       |
| `DB_USER` / `DB_PASSWORD` | `SYSDBA` / `SYSDBA001` | DM8 |
| `TDENGINE_USER` / `TDENGINE_PASSWORD` | `root` / `taosdata` | TDengine |
| `SERVER_PORT`      | 8080/8081/8082 (web/collector/analyser) | server |

**Default TDengine protocol is `TAOS-WS` (WebSocket, port 6041)** — pure Java, no host
install. A protocol probe confirmed the native `jdbc:TAOS://...:6030` fails on this host
(missing taos.dll from the TDengine C client) while TAOS-RS and TAOS-WS both work. To use
the native protocol, install the TDengine client and switch `driver-class-name` +
`url` in `application.yml`.

Run an app module:

```bash
java -jar kms-collector/target/kms-collector-1.0.0.jar   # port 8081
java -jar kms-analyser/target/kms-analyser-1.0.0.jar     # port 8082
java -jar kms-web/target/kms-web-1.0.0.jar               # port 8080
```

## Collector module (Step 3)

`kms-collector` (module A, spec 4.2) collects the host's real metrics with OSHI every 60 s
by default and writes them into both databases:

- **TDengine** — one row per cycle into the host's `t_perf_realtime` subtable
  (`t_perf_<sanitized-hostname>`, e.g. `t_perf_LAPTOP_S0V3S2RR`): CPU %, memory %,
  disk free GB, network send/recv KB/s, CPU temperature (NULL when OSHI cannot read it).
- **DM8** — current values into `t_metric_latest` (six rows, one per metric code).

### REST control API (port 8081)

| Method | Path                        | Meaning                                          |
|--------|-----------------------------|--------------------------------------------------|
| GET    | `/api/collector/status`     | Full status: enabled, period, last metrics, error, count |
| POST   | `/api/collector/period?period=N` | Change period (`N >= 60`; 400 otherwise)    |
| POST   | `/api/collector/enable`     | Enable collection                                |
| POST   | `/api/collector/disable`    | Disable collection                               |

All changes persist to DM8 `t_collector_config` (single row, id=1) and take effect
immediately (the loop re-reads the row every cycle and the API wakes it up) — no restart,
per spec. A disabled collector stops collecting (the loop polls the config every 10 s);
a DB outage is caught, logged as `lastError` in the status, and retried every 10 s.

### Schema auto-initialization

On startup the collector creates everything it needs (idempotent — safe on every boot):

- TDengine: database `kms`, supertable `t_perf_realtime`, this host's subtable.
- DM8: tables `t_collector_config` + `t_metric_latest` and their seed rows.

Canonical DDL for reference: `kms-database/src/main/resources/sql/dm8-schema.sql` and
`tdengine-schema.sql`. Verify with:

```bash
docker exec -it dm8_test /opt/dmdbms/bin/disql SYSDBA/SYSDBA001
#   select * from t_collector_config;  select * from t_metric_latest;
docker exec -it tdengine taos -uroot -ptaosdata -s \
  "select * from kms.t_perf_realtime order by ts desc limit 5"
```

### Logging

Logback: console + rolling file `logs/kms-collector.log` (daily, 7 days kept). Root level
configurable at runtime via env var `LOG_LEVEL` (default `INFO`).

```bash
java -jar kms-collector/target/kms-collector-1.0.0.jar   # port 8081
```

## Analyser module (Step 4)

`kms-analyser` (module C, spec 4.3) analyses the latest TDengine points of every metric and
generates graded alerts:

- **Rule engine** — condition expressions from DM8 `t_alert_rule` (e.g.
  `value > upper_threshold`) are parsed and evaluated against each metric's latest value
  (`com.kms.analyser.rule.RuleExpression`, recursive-descent parser supporting
  `> >= < <= == != && || ! and or` and parentheses; variables `value`,
  `upper_threshold`, `lower_threshold`, `volatility`, `volatility_threshold`).
- **Graded alerting** — every cycle (default 30 s) the last two TDengine points per metric
  are read: a *threshold breach* is the rule expression matching; a *volatility breach* is
  |adjacent-point change| exceeding `t_metric_def.volatility_threshold` (default 10 %).
  Level 1 = threshold only, level 2 = volatility only (value still normal), level 3 = both.
  Alerts go into DM8 `t_alert_record`; repeats for the same metric + level are suppressed by
  a 5-minute cooldown. Every evaluation is logged.
- **Dynamic thresholds (bonus)** — every 5 minutes, for metrics flagged
  `dynamic_threshold = 1` in `t_metric_def` (seeded for `cpu_usage`), the mean ± 2×std of
  the last hour of TDengine data replaces the fixed thresholds (needs ≥ 5 samples).
- **WebSocket push** — new alerts are broadcast as JSON to every client connected to
  `ws://<host>:8082/ws/alerts`.

DM8 tables `t_metric_def` / `t_alert_rule` / `t_alert_record` are created and seeded
idempotently on startup by the shared `AlertSchemaInitializer` in `kms-database` (runs in
analyser, web and collector alike). Tuning keys in `application.yml`
(`kms.analyser.*`): `interval-ms`, `alert-cooldown-minutes`, `dynamic-threshold-interval-ms`.

## Web module (Steps 5-6) and dashboard (Step 7)

`kms-web` (module D, spec 4.4) = Spring Boot REST backend (8080) + RuoYi-style
Vue 3 / Element Plus frontend (built with Vite, served from `classpath:/static`).

### Backend REST APIs

| Method | Path | Meaning |
|--------|------|---------|
| GET | `/api/ops/collector/status` | Collector status (proxied from 8081; `available:false` when the collector is down) |
| POST | `/api/ops/collector/period?period=N` | Change collection period (validated `>= 60`) |
| POST | `/api/ops/collector/enable` / `disable` | Enable / disable collection |
| GET | `/api/metrics/latest` | Current value per metric (DM8 `t_metric_latest`) |
| GET | `/api/metrics/realtime?minutes=30` | All metrics over the last N minutes (TDengine) |
| GET | `/api/metrics/history?metricCode=&begin=&end=` | Raw series of one metric (TDengine; code validated against a whitelist) |
| GET | `/api/alerts?pageNum=&pageSize=&level=&status=&begin=&end=` | Paged alerts with filters (DM8) |
| GET | `/api/alerts/recent?limit=10` | Newest N alerts (dashboard marquee) |
| PUT | `/api/alerts/{id}/status` body `{"status":1}` | Mark handled (1) / unhandled (0) |

### Frontend pages

- **实时监控** — metric multi-select line chart of the last 30 minutes, refreshed every 5 s,
  plus latest-value tiles (spec item 2).
- **历史查询** — metric + datetime range query, shown as table and line chart (spec item 3).
- **预警管理** — alert list with level/status/time filters, pagination, mark handled,
  10 s auto-refresh (spec item 4).
- **运维管理** — collector status, last collection time, period form, enable/disable
  through the collector REST API (spec item 1).
- **数据大屏** (`/#/screen`) — dark full-screen dashboard (spec item 5): scrolling marquee
  of the newest alerts on top, pie chart of the current metric shares, real-time CPU/memory/
  bandwidth line chart, newest-alert list, and bottom live tiles updated every 5 s.
- **预警弹窗** — alerts arriving over the analyser WebSocket become Element Plus
  notifications with a two-tone sound (Web Audio API), on every page (spec item 6).
- **移动端适配** — responsive grids; the dashboard stacks into one column below 1100 px
  (spec item 7, bonus).

The frontend connects to the analyser WS directly
(`ws://<hostname>:8082/ws/alerts`, override with the `VITE_WS_URL` env var at build time).
Auth/Redis (RuoYi extras) are intentionally omitted — the spec's functional requirements
for module D do not include them.

## End-to-end verification (Steps 4-7, live)

Verified against the running Docker containers with all three apps up:

- Analyser startup creates + seeds `t_metric_def` / `t_alert_rule` / `t_alert_record`;
  the first cycle generated level-2 volatility alerts from real collector data.
- Forcing a breach (CPU upper threshold lowered to 1 %) produced a **level-3**
  `THRESHOLD_VOLATILITY` record in DM8 *and* a WebSocket push captured by a Node WS client
  (`{"id":7,"metricCode":"cpu_usage","alertLevel":3,...}`) — the full pipeline
  collector → TDengine → analyser → DM8 → WS works live.
- Dynamic threshold job logs its 5-minute recomputation; `cpu_usage` (dynamic flag) is
  adjusted from the last hour's mean ± 2×std.
- Web APIs verified with curl: ops status/period (60 → 90 → 60, applied without restart),
  latest/realtime/history, alert paging + mark-handled; unknown metric codes are rejected
  with 400 (SQL-injection-safe whitelist); the built frontend is served at `http://localhost:8080/`.

## Docker database environment (Step 0, already running)

Start the containers with `docker compose up -d` (see `docker-compose.yml` at the repo root),
then wait until `docker compose ps` shows all services healthy.

| Service   | Host port         | Credentials       | JDBC URL                                                     |
|-----------|-------------------|-------------------|--------------------------------------------------------------|
| DM8       | 30236             | SYSDBA / SYSDBA001| `jdbc:dm://localhost:30236`                                   |
| TDengine  | 6030 native, 6041 REST/WS | root / taosdata | `jdbc:TAOS://localhost:6030/?timezone=Asia/Shanghai` |
| Redis     | 6379              | —                 | —                                                            |

### Driver notes

- **DM8**: `com.dameng:DmJdbcDriver18:8.1.3.140` is published on Maven Central, so no manual
  `mvn install:install-file` is required. Only if Central is unreachable:
  ```bash
  mvn install:install-file -Dfile=DmJdbcDriver18.jar -DgroupId=com.dameng \
    -DartifactId=DmJdbcDriver18 -Dversion=8.1.3.140 -Dpackaging=jar
  ```
- **TDengine**: the native protocol (`jdbc:TAOS://...:6030`) requires the TDengine C client on
  the host (taos.dll on Windows / libtaos.so on Linux) — download it from the
  [TDengine releases](https://docs.taosdata.com/releases/tdengine/) page. If the native client is
  not installed, use the pure-Java fallbacks, which need nothing extra:
  - REST: `jdbc:TAOS-RS://localhost:6041/?timezone=Asia/Shanghai`
  - WebSocket: `jdbc:TAOS-WS://localhost:6041/?timezone=Asia/Shanghai`
- **Timezone**: always append `?timezone=Asia/Shanghai` to the TDengine JDBC URL — the container
  timezone differs from the host.
