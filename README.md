# KMS — 关键指标监控系统

关键指标监控系统（Key Indicator Monitoring System，KMS）：通过 OSHI 采集本机性能指标（CPU、内存、磁盘、网络、温度），时序数据存入 TDengine，元数据与预警结果存入达梦 DM8，对阈值/波动率进行分析并分级预警，前端基于若依（RuoYi）框架提供实时图表、预警管理和数据大屏。

## 模块划分

| 模块 | 目录 | 职责 |
|-----------|----------------|-----------------------------------------------------------------------|
| parent | `pom.xml` | 多模块 Maven 父工程：Spring Boot 3.x + 统一 dependencyManagement |
| database | `kms-database` | 数据访问层，双数据源：达梦 DM8 主库 + TDengine 时序库 |
| collector | `kms-collector` | OSHI 指标采集、动态周期、REST 控制接口（模块 A） |
| analyser | `kms-analyser` | 规则引擎、三级预警、动态阈值、WebSocket 推送（模块 C） |
| web | `kms-web` | 若依风格 Vue3 前端 + REST 后端：实时图表、历史查询、预警管理、数据大屏（模块 D） |

## 依赖版本（Step 1）

| 依赖 | Artifact | 版本 |
|------------------|------------------------------------------------------|-----------|
| Spring Boot | `org.springframework.boot:spring-boot-starter-parent` | 3.5.16 |
| MyBatis-Plus | `com.baomidou:mybatis-plus-spring-boot3-starter` | 3.5.17 |
| OSHI | `com.github.oshi:oshi-core` | 7.6.1 |
| 达梦驱动 | `com.dameng:DmJdbcDriver18` | 8.1.3.140 |
| TDengine 驱动 | `com.taosdata.jdbc:taos-jdbcdriver` | 3.9.0 |

编译目标为 Java 17 字节码（JDK 17+ 均可构建运行；已在 JDK 21 上验证）。

## 构建

```bash
mvn clean package        # 构建全部四个模块
```

前端是 `kms-web/frontend` 下的 Vite 工程（Vue 3 + Element Plus + ECharts）。构建产物（`dist/`）拷贝到 `kms-web/src/main/resources/static`，由 Spring Boot 直接托管，运行时无需 Node.js：

```bash
cd kms-web/frontend
npm install              # 首次需要
npm run build            # 生成 dist/
cp -r dist/* ../src/main/resources/static/   # 然后用 Maven 重新构建 kms-web
# 开发模式：npm run dev  (Vite 端口 :3000，/api 代理到 :8080)
```

`mvn test -pl kms-database` 运行双数据源连通性测试（需要 Docker 容器已启动；连接配置在 `kms-database/src/test/resources/application.yml`）。

## 双数据源配置（Step 2）

配置位于 `kms-database`（`com.kms.database.config`），各应用模块通过 `@SpringBootApplication(scanBasePackages = "com.kms")` 共享：

- `DmDataSourceConfig` — 达梦 **主数据源**：HikariCP 连接池 + MyBatis-Plus（`@MapperScan` 扫描 `com.kms.database.mapper`，分页使用 DM 方言）。标注 `@Primary`，MyBatis-Plus 与事务管理器绑定到它。
- `TdengineDataSourceConfig` — TDengine **次数据源**：HikariCP 连接池 + `taosJdbcTemplate`，用于对时序表执行原生 SQL。
- `MybatisPlusConfig` — 分页拦截器（`DbType.DM`）。注意：MyBatis-Plus 3.5.9 起 JSqlParser 拦截器位于独立的 `mybatis-plus-jsqlparser` 构件中，父 pom 已锁定其版本。

`application.yml` 中的连接配置键（`kms.datasource.dm.*` / `kms.datasource.tdengine.*`），可通过环境变量覆盖：

| 环境变量 | 默认值 | 作用于 |
|--------------------|--------------|------------|
| `DB_HOST` | `localhost` | 两者 |
| `DB_USER` / `DB_PASSWORD` | `SYSDBA` / `SYSDBA001` | 达梦 |
| `TDENGINE_USER` / `TDENGINE_PASSWORD` | `root` / `taosdata` | TDengine |
| `SERVER_PORT` | 8080/8081/8082（web/collector/analyser） | 服务端口 |

**TDengine 默认协议为 `TAOS-WS`（WebSocket，端口 6041）**——纯 Java 实现，无需在宿主机安装任何客户端。协议探测已确认本机原生 `jdbc:TAOS://...:6030` 会失败（缺少 TDengine C 客户端的 taos.dll），而 TAOS-RS 与 TAOS-WS 均可正常使用。如需使用原生协议，请先安装 TDengine 客户端，再修改 `application.yml` 中的 `driver-class-name` 与 `url`。

运行各应用模块：

```bash
java -jar kms-collector/target/kms-collector-1.0.0.jar   # 端口 8081
java -jar kms-analyser/target/kms-analyser-1.0.0.jar     # 端口 8082
java -jar kms-web/target/kms-web-1.0.0.jar               # 端口 8080
```

## 采集模块（Step 3）

`kms-collector`（模块 A，规格 4.2）默认每 60 秒用 OSHI 采集宿主机真实指标，并同时写入两个数据库：

- **TDengine** — 每轮一行，写入本机子表 `t_perf_realtime`（`t_perf_<清理后的主机名>`，如 `t_perf_LAPTOP_S0V3S2RR`）：CPU 使用率 %、内存使用率 %、磁盘剩余 GB、网络发送/接收 KB/s、CPU 温度（OSHI 读不到时为 NULL）。
- **达梦** — 当前值写入 `t_metric_latest`（共六行，每个指标代码一行）。

### REST 控制接口（端口 8081）

| 方法 | 路径 | 含义 |
|--------|-----------------------------|--------------------------------------------------|
| GET | `/api/collector/status` | 完整状态：启用状态、周期、最近指标、错误、采集次数 |
| POST | `/api/collector/period?period=N` | 修改采集周期（`N >= 60`，否则返回 400） |
| POST | `/api/collector/enable` | 启用采集 |
| POST | `/api/collector/disable` | 禁用采集 |

所有变更持久化到达梦 `t_collector_config`（单行，id=1）并立即生效（循环每轮重读该行，API 会唤醒循环）——按规格要求无需重启。禁用后采集停止（循环每 10 秒轮询配置）；数据库故障会被捕获，作为 `lastError` 记入状态，并每 10 秒重试。

### 建库建表自动初始化

采集器启动时幂等创建所需的一切（每次启动均可安全执行）：

- TDengine：数据库 `kms`、超级表 `t_perf_realtime`、本机子表。
- 达梦：`t_collector_config` 与 `t_metric_latest` 表及种子数据。

规范 DDL 见 `kms-database/src/main/resources/sql/dm8-schema.sql` 与 `tdengine-schema.sql`。验证方式：

```bash
docker exec -it dm8_test /opt/dmdbms/bin/disql SYSDBA/SYSDBA001
#   select * from t_collector_config;  select * from t_metric_latest;
docker exec -it tdengine taos -uroot -ptaosdata -s \
  "select * from kms.t_perf_realtime order by ts desc limit 5"
```

### 日志

Logback：控制台 + 滚动文件 `logs/kms-collector.log`（按天滚动，保留 7 天）。根日志级别可用环境变量 `LOG_LEVEL` 运行时调整（默认 `INFO`）。

## 分析模块（Step 4）

`kms-analyser`（模块 C，规格 4.3）分析每项指标在 TDengine 中的最新数据点，生成分级预警：

- **规则引擎** — 解析并求值达梦 `t_alert_rule` 中的条件表达式（如 `value > upper_threshold`），对每项指标的最新值进行判断（`com.kms.analyser.rule.RuleExpression`，递归下降解析器，支持 `> >= < <= == != && || ! and or` 与括号；变量 `value`、`upper_threshold`、`lower_threshold`、`volatility`、`volatility_threshold`）。
- **分级预警** — 每轮（默认 30 秒）读取每项指标最近两个 TDengine 数据点：*阈值超限* 指规则表达式成立；*波动率异常* 指相邻点变化率绝对值超过 `t_metric_def.volatility_threshold`（默认 10%）。1 级 = 仅阈值超限，2 级 = 仅波动率异常（当前值仍在正常范围），3 级 = 两者同时。预警写入达梦 `t_alert_record`；同指标同级别的重复预警受 5 分钟冷却抑制。每次判断均有日志。
- **动态阈值（加分项）** — 每 5 分钟，对 `t_metric_def` 中 `dynamic_threshold = 1` 的指标（种子数据中 `cpu_usage` 开启），用最近 1 小时 TDengine 数据的 mean ± 2×std 替换固定阈值（需至少 5 个样本）。
- **WebSocket 推送** — 新预警以 JSON 广播给所有连接 `ws://<host>:8082/ws/alerts` 的客户端。

达梦表 `t_metric_def` / `t_alert_rule` / `t_alert_record` 由 `kms-database` 中共享的 `AlertSchemaInitializer` 在启动时幂等建表并写入种子数据（analyser、web、collector 中均会执行）。`application.yml` 中的调优键（`kms.analyser.*`）：`interval-ms`、`alert-cooldown-minutes`、`dynamic-threshold-interval-ms`。

## Web 模块（Step 5-6）与数据大屏（Step 7）

`kms-web`（模块 D，规格 4.4）= Spring Boot REST 后端（8080）+ 若依风格 Vue 3 / Element Plus 前端（Vite 构建，由 `classpath:/static` 托管）。

### 后端 REST API

| 方法 | 路径 | 含义 |
|--------|------|---------|
| GET | `/api/ops/collector/status` | 采集器状态（代理 8081；采集器宕机时 `available:false`） |
| POST | `/api/ops/collector/period?period=N` | 修改采集周期（校验 `>= 60`） |
| POST | `/api/ops/collector/enable` / `disable` | 启用 / 禁用采集 |
| GET | `/api/metrics/latest` | 各指标当前值（达梦 `t_metric_latest`） |
| GET | `/api/metrics/realtime?minutes=30` | 最近 N 分钟全部指标（TDengine） |
| GET | `/api/metrics/history?metricCode=&begin=&end=` | 单个指标原始时序（TDengine；指标代码经白名单校验） |
| GET | `/api/alerts?pageNum=&pageSize=&level=&status=&begin=&end=` | 预警分页查询，支持筛选（达梦） |
| GET | `/api/alerts/recent?limit=10` | 最新 N 条预警（大屏跑马灯） |
| PUT | `/api/alerts/{id}/status` body `{"status":1}` | 标记已处理（1）/ 未处理（0） |

### 前端页面

- **实时监控** — 指标多选、最近 30 分钟折线图，5 秒刷新，附带最新值卡片（规格第 2 条）。
- **历史查询** — 按指标 + 时间范围查询，表格与折线图两种展示（规格第 3 条）。
- **预警管理** — 预警列表，支持级别/状态/时间筛选、分页、标记处理，10 秒自动刷新（规格第 4 条）。
- **运维管理** — 采集器状态、最近采集时间、周期设置表单、经采集器 REST API 启停采集（规格第 1 条）。
- **数据大屏**（`/#/screen`）— 深色全屏大屏（规格第 5 条）：顶部最新预警滚动跑马灯、当前指标占比饼图、CPU/内存/带宽实时折线图、最新预警列表、底部实时指标卡片，每 5 秒刷新。
- **预警弹窗** — 经 analyser 的 WebSocket 到达的预警会以 Element Plus 通知弹出并播放双音提示（Web Audio API），所有页面均生效（规格第 6 条）。
- **移动端适配** — 响应式栅格；大屏在 1100px 以下堆叠为单列（规格第 7 条，加分项）。

前端直连 analyser 的 WebSocket（`ws://<hostname>:8082/ws/alerts`，构建时可用 `VITE_WS_URL` 环境变量覆盖）。认证/Redis（若依扩展项）有意省略——规格对 D 模块的功能要求不包含它们。

## 端到端验证（Step 4-7，真机）

在 Docker 容器运行、三个应用全部启动的状态下验证通过：

- analyser 启动即创建并初始化 `t_metric_def` / `t_alert_rule` / `t_alert_record`；第一轮分析即用真实采集数据生成了 2 级波动率预警。
- 强制触发超限（CPU 上限阈值调低到 1%）后，达梦产生了 **3 级** `THRESHOLD_VOLATILITY` 记录，同时 Node WS 客户端实时收到 WebSocket 推送（`{"id":7,"metricCode":"cpu_usage","alertLevel":3,...}`）——collector → TDengine → analyser → 达梦 → WS 全链路真机打通。
- 动态阈值任务每 5 分钟重算并记录日志；`cpu_usage`（开启动态标志）按最近 1 小时 mean ± 2×std 自动调整。
- Web API 用 curl 验证：运维状态/周期（60 → 90 → 60，无需重启即生效）、latest/realtime/history、预警分页与标记处理；未知指标代码返回 400（白名单防 SQL 注入）；构建后的前端由 `http://localhost:8080/` 正常访问。

## Docker 数据库环境（Step 0，已在运行）

用 `docker compose up -d` 启动容器（见仓库根目录 `docker-compose.yml`），等待 `docker compose ps` 显示所有服务 healthy。

| 服务 | 宿主机端口 | 账号密码 | JDBC URL |
|-----------|-------------------|-------------------|--------------------------------------------------------------|
| 达梦 DM8 | 30236 | SYSDBA / SYSDBA001 | `jdbc:dm://localhost:30236` |
| TDengine | 6030 原生、6041 REST/WS | root / taosdata | `jdbc:TAOS://localhost:6030/?timezone=Asia/Shanghai` |
| Redis | 6379 | — | — |

### 驱动说明

- **达梦**：`com.dameng:DmJdbcDriver18:8.1.3.140` 已发布到 Maven 中央仓库，无需手动 `mvn install:install-file`。仅当中央仓库不可达时：

  ```bash
  mvn install:install-file -Dfile=DmJdbcDriver18.jar -DgroupId=com.dameng \
    -DartifactId=DmJdbcDriver18 -Dversion=8.1.3.140 -Dpackaging=jar
  ```

- **TDengine**：原生协议（`jdbc:TAOS://...:6030`）需要宿主机安装 TDengine C 客户端（Windows 为 taos.dll / Linux 为 libtaos.so）——可从 [TDengine 发布页](https://docs.taosdata.com/releases/tdengine/) 下载。若未安装原生客户端，可使用纯 Java 的后备协议，无需任何额外安装：

  - REST：`jdbc:TAOS-RS://localhost:6041/?timezone=Asia/Shanghai`
  - WebSocket：`jdbc:TAOS-WS://localhost:6041/?timezone=Asia/Shanghai`

- **时区**：TDengine JDBC URL 必须带 `?timezone=Asia/Shanghai`——容器时区与宿主机不同。
