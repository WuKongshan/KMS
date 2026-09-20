# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

All steps (0–7) are done: docker-compose + containers (Step 0), multi-module Maven skeleton (Step 1), dual-datasource configuration (Step 2), collector module (Step 3), analyser module (Step 4), web module (Steps 5–6), dashboard (Step 7).

- `kms-collector` (8081) collects real host metrics via OSHI (60 s default, dynamic via `t_collector_config`, no restart), writes time series to TDengine `t_perf_realtime` and current values to DM8 `t_metric_latest`, auto-creates its schema on startup, logs via Logback (`logs/kms-collector.log`, `LOG_LEVEL` env var) and exposes the control API on port 8081 (`/api/collector/status|period|enable|disable`).
- `kms-analyser` (8082) runs the rule engine over `t_alert_rule` expressions (`RuleExpression`, recursive-descent parser), derives graded alerts from the latest two TDengine points per metric (level 1 threshold-only / 2 volatility-only / 3 both, 5-min cooldown dedup, everything logged), recomputes dynamic thresholds every 5 min (mean ± 2×std over the last hour for `dynamic_threshold=1` metrics, e.g. `cpu_usage`), inserts alerts into DM8 `t_alert_record` and broadcasts them over the `/ws/alerts` WebSocket.
- `kms-web` (3000) serves the RuoYi-style Vue 3 + Element Plus + ECharts frontend (built by Vite from `kms-web/frontend`, output copied to `src/main/resources/static`) plus REST APIs: ops management (proxy to the collector 8081: status/period/enable/disable), metrics (latest from DM8, realtime/history from TDengine, metric codes validated against the `KmsMetrics` whitelist), alerts (paged with filters, mark handled, recent N). Pages: 实时监控 (30 min line chart, 5 s refresh), 历史查询 (table + chart), 预警管理, 运维管理, 数据大屏 (`/#/screen`: marquee alerts + pie + line chart + live tiles) with WebSocket alert popups + sound (connects to the analyser WS directly).
- The three new DM8 tables (`t_metric_def`, `t_alert_rule`, `t_alert_record`) are created + seeded idempotently on startup by the shared `AlertSchemaInitializer` in kms-database (runs in every module); canonical DDL in `kms-database/src/main/resources/sql/dm8-schema.sql`. `TdengineTableNames` now lives in kms-database (`com.kms.database.tdengine`), shared by all three apps. Entities/mappers for the alert tables are in kms-database too.
- End-to-end verified live: forced threshold breach → level-3 record in DM8 + WebSocket push captured by a WS client; period change 60→90→60 via the web proxy applied without restart; dynamic threshold job adjusts `cpu_usage` from real data; frontend served at `http://localhost:3000/`.

The four `4.x *.txt` files are the source of truth — read them before implementing anything:

- `4.1 项目整体提示词.txt` — overall prompt: tech stack, modules, development steps (Step 0–7)
- `4.2 数据采集模块（A）细化提示词.txt` — collector module (A) spec
- `4.3 数据分析模块（C）细化提示词.txt` — analyser module (C) spec
- `4.4 应用系统（D）细化提示词.txt` — web application (D) spec

`dm8_20241022_x86_rh6_64_single.tar` is the DM8 Docker image archive; its RepoTag is `dm8_single:dm8_20241022_rev244896_x86_rh6_64`.

## Overview

关键指标监控系统 (Key Indicator Monitoring System, KMS): monitors the local host's performance metrics (CPU, memory, disk, network, temperature) via OSHI, stores time-series data in TDengine and metadata/alerts in DM8, analyses thresholds/volatility with graded alerting, and serves a RuoYi-based web UI with real-time charts, alert management, and a dashboard (大屏).

## Tech stack and architecture

- Backend: Spring Boot 3.x + MyBatis-Plus, JDK 17, multi-module Maven project
- Frontend: Vue 3 + Element Plus, based on RuoYi-Vue (RuoYi framework); ECharts for charts
- Databases (all Docker-deployed): DM8 (relational), TDengine (time-series), Redis (RuoYi cache/session)

Four modules (from spec 4.1, the numbered modules map to letters: **A=collector, B=database, C=analyser, D=web**). Only the A/C/D spec files (4.2/4.3/4.4) exist in this directory; B's requirements come from spec 4.1 item 2 (DM8 for metadata and alert results, TDengine for real-time time-series data):

- `collector`: OSHI-based metric collection, default period 60 s, dynamically adjustable via the `t_collector_config` table without restart, Logback logging. Writes time-series rows to TDengine `t_perf_realtime` and current values to DM8 `t_metric_latest`. Exposes REST API for D to change period / enable-disable.
- `database`: data-access layer with dual datasources — DM8 primary + TDengine. DM8 driver `com.dameng:DmJdbcDriver18` (8.1.3.140, on Maven Central — no manual install needed), TDengine driver `com.taosdata.jdbc:taos-jdbcdriver` (3.9.0). Current versions are pinned in the parent pom.
- `analyser`: rule engine over `t_alert_rule` expressions, three alert levels (1: fixed-threshold breach; 2: adjacent-point volatility > configured % while value is normal; 3: both), dynamic thresholds (every 5 min recompute mean ± 2×std over the last hour into `t_metric_def`), inserts alerts into `t_alert_record`, pushes to frontend via WebSocket.
- `web`: RuoYi app — ops management (collector status/period), real-time charts (last 30 min, 5 s refresh), history query, alert management, dashboard with pie chart + scrolling alerts + WebSocket alert popups with sound.

Data flow: collector → TDengine (time series) + DM8 (latest values) → analyser reads TDengine, writes alerts to DM8 → web reads TDengine + DM8, receives WebSocket pushes.

Key tables: `t_collector_config` (collection config), `t_metric_def` (metric definitions/thresholds), `t_metric_latest` (latest values), `t_perf_realtime` (TDengine time series), `t_alert_rule` (rules), `t_alert_record` (alerts). Plus standard RuoYi tables (users, roles, menus) in DM8.

## Docker environment

**Preferred: use `docker-compose.yml`.** It defines `dm8` (host port 30236), `tdengine` (6030/6041) and an optional `redis` service, using named volumes (`dm8_data`, `taos_data`, `redis_data`) and healthchecks. Before the first start, load the DM8 image:

```bash
docker load -i dm8_20241022_x86_rh6_64_single.tar   # loads dm8_single:dm8_20241022_rev244896_x86_rh6_64
docker compose up -d          # first run pulls TDengine; DM8 initializes in ~1 min
docker compose ps             # wait until services are healthy
```

Verify connections:

```bash
docker exec -it dm8_test /opt/dmdbms/bin/disql SYSDBA/SYSDBA001
docker exec -it tdengine taos -uroot -ptaosdata -s "select server_version()"
```

Raw `docker run` commands from spec 4.1 (reference only):

DM8:

```bash
docker run -d -p 30236:5236 --restart=always --name dm8_test --privileged=true \
  -e PAGE_SIZE=16 -e LD_LIBRARY_PATH=/opt/dmdbms/bin -e INSTANCE_NAME=dm8_test \
  -v /data/dm8_test:/opt/dmdbms/data dm8_single:dm8_20241022_rev244896_x86_rh6_64
```

TDengine:

```bash
docker run -d -v ~/data/taos:/var/lib/taos -p 6030:6030 -p 6041:6041 \
  --name tdengine tdengine/tdengine:latest
```

Connection details (host-side access to mapped ports):

| Service  | Host port | Credentials         | JDBC URL                                            |
|----------|-----------|---------------------|-----------------------------------------------------|
| DM8      | 30236     | SYSDBA / SYSDBA001  | `jdbc:dm://localhost:30236`                         |
| TDengine | 6030      | root / taosdata     | `jdbc:TAOS://localhost:6030/?timezone=Asia/Shanghai` |
| TDengine REST | 6041 | —               | —                                                   |

## Key constraints

- The collector must run on the host (not in a container) so OSHI can read the host's real metrics; it connects via `localhost` mapped ports. If other services are containerized later, they must join a custom Docker network and address databases by container name — parameterize DB host in `application.yml` via an env var (e.g. `DB_HOST`).
- Always pass `?timezone=Asia/Shanghai` in the TDengine JDBC URL — container timezone differs from the host.
- Default TDengine JDBC protocol is TAOS-WS (WebSocket, port 6041) — pure Java, no host install. The native `jdbc:TAOS://...:6030` protocol requires the TDengine C client (taos.dll), which is not installed on this host (a protocol probe confirmed native fails while TAOS-RS/TAOS-WS work); switch by editing `driver-class-name` and `url` under `kms.datasource.tdengine.*` in `application.yml`.
- Collection period ≥ 60 s; changes to `t_collector_config` must take effect without restart.
- Watch DM8 transaction isolation/locking so analyser tasks don't conflict with collector writes.

## Development sequence (from spec 4.1)

Step 0 (done): `docker-compose.yml` written — start containers with `docker compose up -d` and verify per the Docker environment section → Step 1 (done): multi-module Maven parent pom with all dependencies → Step 2 (done): dual datasource config — DM8 primary (MyBatis-Plus, mappers in `com.kms.database.mapper`) + TDengine secondary (`taosJdbcTemplate`), HikariCP pools, `kms.datasource.*` keys in `application.yml` with DB_HOST/DB_USER/DB_PASSWORD/TDENGINE_* env overrides, live connection tests in kms-database (containers must be up) → Step 3 (done): collector module A per spec 4.2 — OSHI metrics (CPU/mem/disk/net/temp, temp null when unsupported), default period 60 s dynamically adjustable via `t_collector_config` (re-read every cycle + loop wakeup, no restart), TDengine insert uses inlined literals because the stmt API rejects the `now` keyword, `SchemaInitializer` (@Order(1)) auto-creates the TDengine `kms` database/supertable/subtable `t_perf_<sanitized-hostname>` and DM8 tables + seeds before `CollectionLoop` (@Order(2)) starts, REST control API on 8081, verified live end-to-end (rows in TDengine + DM8, period/disable/enable tested) → Step 4 (done): analyser module C per spec 4.3 — rule engine over `t_alert_rule` expressions (`RuleExpression` in `com.kms.analyser.rule`, recursive-descent parser with `> >= < <= == != && || ! and or` + parentheses; variables value/upper_threshold/lower_threshold/volatility/volatility_threshold); analysis loop every 30 s reads the last 2 TDengine points per metric, derives level 1/2/3 (threshold-only / volatility-only-while-normal / both, volatility vs `t_metric_def.volatility_threshold`), 5-min cooldown per metric+level, inserts `t_alert_record` in DM8, broadcasts over `/ws/alerts` WebSocket (plain TextWebSocketHandler on 8082); dynamic threshold job every 5 min recomputes mean ± 2×std over the last hour for `dynamic_threshold=1` metrics (`cpu_usage` seeded) via `AVG/STDDEV/COUNT ... WHERE ts >= NOW - 1h` (≥5 samples required); all evaluations logged; `@EnableScheduling` on the app; new DM8 tables + seeds handled by the shared `AlertSchemaInitializer` in kms-database; verified live (level-1/2/3 alerts, cooldown suppression, WS push captured, dynamic thresholds adjusted) → Step 5–6 (done): web module D per spec 4.4 — REST backend on 3000 (ops proxy to collector 8081 with `kms.web.collector-base-url`, metrics latest/realtime/history with `KmsMetrics` whitelist validation, alerts paged/recent/status) + RuoYi-style Vue3 frontend in `kms-web/frontend` (Vite + Element Plus + ECharts; `npm run build` → copy `dist/` into `kms-web/src/main/resources/static`, served by Spring Boot; hash router; axios `/api` calls; alert popups + sound via a WS client singleton connecting to `ws://<host>:8082/ws/alerts`, override with `VITE_WS_URL`; pages ops/realtime/history/alert under a RuoYi-style dark-sidebar layout) → Step 7 (done): 大屏 at `/#/screen` — dark full-screen dashboard (validated dark palette), top marquee of latest alerts, pie chart of current metric shares, 30-min CPU/mem/bandwidth line chart, newest-alert list, bottom live tiles, 5 s refresh, responsive stacking below 1100 px, WS alert popups with Web Audio two-tone beep. End-to-end verified with curl + a Node WS client against the live containers. Each step must produce complete runnable code with DB connection notes.

---

# 中文版 (Chinese version)

## 项目状态

全部步骤（Step 0–7）已完成：docker-compose + 容器（Step 0）、多模块 Maven 骨架（Step 1）、双数据源配置（Step 2）、采集模块（Step 3）、分析模块（Step 4）、Web 模块（Step 5–6）、数据大屏（Step 7）。

- `kms-collector`（8081）已通过 OSHI 采集宿主机真实指标（默认 60 秒，可通过 `t_collector_config` 动态调整且无需重启），时序数据写入 TDengine `t_perf_realtime`、当前值写入达梦 `t_metric_latest`，启动时自动建库建表，Logback 日志（`logs/kms-collector.log`，`LOG_LEVEL` 环境变量可调），并提供控制 API（`/api/collector/status|period|enable|disable`）。
- `kms-analyser`（8082）基于 `t_alert_rule` 条件表达式运行规则引擎（`RuleExpression` 递归下降解析器），对每项指标读取 TDengine 最近两个点做分级预警（1 级仅阈值超限 / 2 级仅波动率异常 / 3 级两者同时，同指标同级别 5 分钟冷却去重，所有判断均有日志），每 5 分钟对 `dynamic_threshold=1` 的指标（如 `cpu_usage`）用最近 1 小时 mean ± 2×std 重算动态阈值，预警写入达梦 `t_alert_record` 并通过 `/ws/alerts` WebSocket 推送。
- `kms-web`（3000）提供若依风格 Vue 3 + Element Plus + ECharts 前端（Vite 构建自 `kms-web/frontend`，产物拷入 `src/main/resources/static`）与 REST API：运维管理（代理采集器 8081：状态/周期/启停）、指标查询（最新值查达梦，实时/历史查 TDengine，指标代码经 `KmsMetrics` 白名单校验）、预警管理（分页筛选、标记处理、最新 N 条）。页面：实时监控（30 分钟折线图，5 秒刷新）、历史查询（表格+折线图）、预警管理、运维管理、数据大屏（`/#/screen`：顶部滚动预警 + 饼图 + 折线图 + 底部实时指标），WebSocket 预警弹窗带声音提示（浏览器直连 analyser 的 WS）。
- 三张新达梦表（`t_metric_def`、`t_alert_rule`、`t_alert_record`）由 kms-database 中共享的 `AlertSchemaInitializer` 在各模块启动时幂等建表并写入种子数据（规范 DDL 见 `kms-database/src/main/resources/sql/dm8-schema.sql`）。`TdengineTableNames` 已移至 kms-database（`com.kms.database.tdengine`）供三个应用共用；预警表实体/Mapper 同样位于 kms-database。
- 已真机端到端验证：强制触发阈值超限 → 达梦产生 3 级预警记录 + WS 客户端实时收到推送；经 web 代理修改周期 60→90→60 无需重启即生效；动态阈值任务用真实数据自动调整 `cpu_usage` 阈值；前端可由 `http://localhost:3000/` 访问。

四个 `4.x *.txt` 文件是需求的唯一来源，动手实现前必须先阅读：

- `4.1 项目整体提示词.txt` — 总体提示词：技术栈、模块划分、开发步骤（Step 0–7）
- `4.2 数据采集模块（A）细化提示词.txt` — 采集模块（A）规格
- `4.3 数据分析模块（C）细化提示词.txt` — 分析模块（C）规格
- `4.4 应用系统（D）细化提示词.txt` — Web 应用（D）规格

`dm8_20241022_x86_rh6_64_single.tar` 是 DM8 的 Docker 镜像归档文件，其 RepoTag 为 `dm8_single:dm8_20241022_rev244896_x86_rh6_64`。

## 项目概述

关键指标监控系统（KMS）：通过 OSHI 采集本机性能指标（CPU、内存、磁盘、网络、温度），时序数据存入 TDengine，元数据和预警结果存入达梦数据库（DM8），对阈值/波动率进行分析并分级预警，前端基于若依框架提供实时图表、预警管理和数据大屏。

## 技术栈与架构

- 后端：Spring Boot 3.x + MyBatis-Plus，JDK 17，多模块 Maven 项目
- 前端：Vue3 + Element Plus，基于若依框架 RuoYi-Vue；图表使用 ECharts
- 数据库（全部 Docker 部署）：达梦 DM8（关系型）、TDengine（时序）、Redis（若依缓存/会话）

四个模块（规格 4.1 中的模块编号对应字母：**A=采集、B=数据库（实时数据库）、C=分析、D=Web**）。本目录只有 A/C/D 三个细化提示词文件（4.2/4.3/4.4），B 模块的需求以规格 4.1 第 2 条为准（达梦存元数据与预警结果，TDengine 存实时时序数据）：

- `collector`：基于 OSHI 的指标采集，默认周期 60 秒，可通过达梦 `t_collector_config` 表动态修改且无需重启，使用 Logback 记录日志。时序数据写入 TDengine `t_perf_realtime`，当前值更新到达梦 `t_metric_latest`。提供 REST API 供 D 模块修改采集周期和启停采集。
- `database`：数据访问层，双数据源——达梦主库 + TDengine 时序库。达梦驱动 `com.dameng:DmJdbcDriver18`（8.1.3.140，已在 Maven 中央仓库发布，无需手动安装），TDengine 驱动 `com.taosdata.jdbc:taos-jdbcdriver`（3.9.0）。当前版本号统一在父 pom 中管理。
- `analyser`：基于 `t_alert_rule` 条件表达式的规则引擎；三级预警（1 级：单纯超固定阈值；2 级：相邻点波动率超过设定值且当前值在正常范围；3 级：阈值超限与波动率异常同时满足）；动态阈值（每 5 分钟计算最近 1 小时数据的 mean ± 2×std 并更新 `t_metric_def` 中的阈值）；预警写入 `t_alert_record`，通过 WebSocket 实时推送给前端。
- `web`：若依应用——运维管理（采集器状态/周期配置）、实时监控（最近 30 分钟折线图，5 秒刷新）、历史查询、预警管理、大屏（饼图 + 滚动预警 + WebSocket 预警弹窗带声音提示）。

数据流：collector → TDengine（时序数据）+ 达梦（最新值）→ analyser 读取 TDengine、预警写入达梦 → web 读取 TDengine + 达梦、接收 WebSocket 推送。

核心表：`t_collector_config`（采集配置）、`t_metric_def`（指标定义/阈值）、`t_metric_latest`（最新值）、`t_perf_realtime`（TDengine 时序表）、`t_alert_rule`（预警规则）、`t_alert_record`（预警记录）。另外达梦中还有若依标准表（用户、角色、菜单等）。

## Docker 环境

**推荐方式：直接使用 `docker-compose.yml`。** 其中定义了 `dm8`（宿主机端口 30236）、`tdengine`（6030/6041）以及可选的 `redis` 服务，使用命名卷（`dm8_data`、`taos_data`、`redis_data`）并配置了健康检查。首次启动前需先导入达梦镜像：

```bash
docker load -i dm8_20241022_x86_rh6_64_single.tar   # 导入后镜像为 dm8_single:dm8_20241022_rev244896_x86_rh6_64
docker compose up -d          # 首次会拉取 TDengine 镜像；DM8 首次启动约需 1 分钟初始化
docker compose ps             # 等待各服务变为 healthy
```

验证连接：

```bash
docker exec -it dm8_test /opt/dmdbms/bin/disql SYSDBA/SYSDBA001
docker exec -it tdengine taos -uroot -ptaosdata -s "select server_version()"
```

以下为规格 4.1 中的原始 `docker run` 命令（保留作参考）：

达梦 DM8：

```bash
docker run -d -p 30236:5236 --restart=always --name dm8_test --privileged=true \
  -e PAGE_SIZE=16 -e LD_LIBRARY_PATH=/opt/dmdbms/bin -e INSTANCE_NAME=dm8_test \
  -v /data/dm8_test:/opt/dmdbms/data dm8_single:dm8_20241022_rev244896_x86_rh6_64
```

TDengine：

```bash
docker run -d -v ~/data/taos:/var/lib/taos -p 6030:6030 -p 6041:6041 \
  --name tdengine tdengine/tdengine:latest
```

连接信息（宿主机访问映射端口）：

| 服务      | 宿主机端口 | 账号密码             | JDBC URL                                             |
|-----------|-----------|----------------------|------------------------------------------------------|
| 达梦 DM8  | 30236     | SYSDBA / SYSDBA001   | `jdbc:dm://localhost:30236`                          |
| TDengine  | 6030      | root / taosdata      | `jdbc:TAOS://localhost:6030/?timezone=Asia/Shanghai` |
| TDengine REST | 6041  | —                    | —                                                    |

## 关键约束

- 采集程序必须运行在宿主机上（不能容器化），以便 OSHI 读取宿主机真实性能数据；通过 `localhost` 映射端口连接数据库。若后续其他服务容器化，需加入自定义 Docker 网络并用容器名访问数据库——建议在 `application.yml` 中用环境变量（如 `DB_HOST`）参数化数据库主机名。
- TDengine JDBC URL 必须带 `?timezone=Asia/Shanghai`——容器时区与宿主机可能不同。
- TDengine JDBC 默认使用 TAOS-WS 协议（WebSocket，端口 6041）——纯 Java 实现，无需宿主机安装任何客户端。原生 `jdbc:TAOS://...:6030` 协议需要安装 TDengine C 客户端（taos.dll），本机未安装（协议探测已确认原生失败、TAOS-RS/TAOS-WS 可用）；如需切换，修改 `application.yml` 中 `kms.datasource.tdengine.*` 的 `driver-class-name` 与 `url`。
- 采集周期 ≥ 60 秒；修改 `t_collector_config` 后必须无需重启即生效。
- 注意达梦数据库的事务隔离级别与锁策略，避免分析任务与采集任务冲突。

## 开发顺序（来自规格 4.1）

Step 0（已完成）：`docker-compose.yml` 已编写（达梦 + TDengine + 可选 Redis），按上节命令启动容器并验证连接 → Step 1（已完成）：多模块 Maven 父 pom + 全部依赖 → Step 2（已完成）：双数据源配置——达梦主库（MyBatis-Plus，Mapper 位于 `com.kms.database.mapper`）+ TDengine 时序库（`taosJdbcTemplate`），HikariCP 连接池，`application.yml` 中 `kms.datasource.*` 配置项支持 DB_HOST/DB_USER/DB_PASSWORD/TDENGINE_* 环境变量覆盖，kms-database 内含连通性测试（需容器运行）→ Step 3（已完成）：采集模块 A（按规格 4.2）——OSHI 采集 CPU/内存/磁盘/网络/温度（温度不支持时为 NULL），默认周期 60 秒且可通过 `t_collector_config` 动态调整（每轮重读配置 + 唤醒循环，无需重启）；TDengine 插入使用内联字面量（stmt API 拒绝 `now` 关键字）；`SchemaInitializer`（@Order(1)）先自动创建 TDengine `kms` 库/超级表/子表 `t_perf_<清理后的主机名>` 与达梦表及种子数据，`CollectionLoop`（@Order(2)）随后启动；8081 端口提供 REST 控制 API；已真机端到端验证（TDengine/达梦均有数据写入，周期调整、禁用/启用均测试通过）→ Step 4–7（已完成）：分析模块 C（按规格 4.3）——基于 `t_alert_rule` 条件表达式的规则引擎（`com.kms.analyser.rule.RuleExpression` 递归下降解析器，支持 `> >= < <= == != && || ! and or` 与括号，变量 value/upper_threshold/lower_threshold/volatility/volatility_threshold）；每 30 秒读取 TDengine 最近两个点做三级预警（1 级仅阈值 / 2 级仅波动且值正常 / 3 级两者同时，波动率对比 `t_metric_def.volatility_threshold`），同指标同级别 5 分钟冷却去重，预警写入达梦 `t_alert_record` 并经 `/ws/alerts` WebSocket（8082）推送；动态阈值任务每 5 分钟用最近 1 小时 `AVG/STDDEV/COUNT`（`WHERE ts >= NOW - 1h`，样本 ≥5）重算 `dynamic_threshold=1` 指标的 mean ± 2×std（种子数据中 `cpu_usage` 开启）；所有判断均有日志；启动类 `@EnableScheduling`；三张新达梦表由 kms-database 共享的 `AlertSchemaInitializer` 幂等建表+种子；已真机验证（1/2/3 级预警、冷却抑制、WS 推送、动态阈值调整）→ Step 5–6（已完成）：Web 模块 D（按规格 4.4）——3000 端口的 REST 后端（运维代理采集器 8081，`kms.web.collector-base-url` 可配；指标 latest/realtime/history，指标代码经 `KmsMetrics` 白名单校验；预警分页/最新/标记处理）+ 若依风格 Vue3 前端（`kms-web/frontend`，Vite + Element Plus + ECharts；`npm run build` 后把 `dist/` 拷入 `kms-web/src/main/resources/static` 由 Spring Boot 托管；hash 路由；axios 调 `/api`；预警弹窗+声音由 WS 客户端单例直连 `ws://<host>:8082/ws/alerts`，可用 `VITE_WS_URL` 覆盖；深色侧边栏布局下的运维/实时/历史/预警四个页面）→ Step 7（已完成）：大屏 `/#/screen`——深色全屏（经验证的深色调色板），顶部滚动最新预警（跑马灯），当前值占比饼图，30 分钟 CPU/内存/带宽折线图，最新预警列表，底部实时指标卡片，5 秒刷新，1100px 以下响应式堆叠，WS 预警弹窗 + Web Audio 双音提示。已用 curl + Node WS 客户端对运行中的容器完成端到端验证。每步需输出完整可运行的代码，并附带数据库连接配置说明。
