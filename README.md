# KMS — 关键指标监控系统

关键指标监控系统（Key Indicator Monitoring System，KMS）：通过 OSHI 采集本机性能指标（CPU、内存、磁盘、网络、温度），时序数据存入 TDengine，元数据与预警结果存入达梦 DM8，对阈值/波动率进行分析并分级预警，前端为自研 Vue 3 + Element Plus 单页应用（借鉴若依后台布局风格，未引入若依框架），提供实时图表、预警管理和数据大屏。

## 模块划分

| 模块 | 目录 | 职责 |
|-----------|----------------|-----------------------------------------------------------------------|
| parent | `pom.xml` | 多模块 Maven 父工程：Spring Boot 3.x + 统一 dependencyManagement |
| database | `kms-database` | 数据访问层，双数据源：达梦 DM8 主库 + TDengine 时序库 |
| collector | `kms-collector` | OSHI 指标采集、动态周期、REST 控制接口（模块 A） |
| analyser | `kms-analyser` | 规则引擎、三级预警、动态阈值、WebSocket 推送（模块 C） |
| web | `kms-web` | 若依风格 Vue3 前端 + REST 后端：实时图表、历史查询、预警管理、操作日志、登录鉴权（JWT）、数据大屏（模块 D） |

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
# 开发模式：npm run dev  (Vite 端口 :3001，/api 代理到 :3000)
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
| `DM_PORT` | `30236`（Docker 映射端口；本机安装的达梦为 `5236`） | 达梦 |
| `DB_USER` / `DB_PASSWORD` | `SYSDBA` / `SYSDBA001` | 达梦 |
| `TDENGINE_PORT` | `6041`（本机原生协议为 `6030`） | TDengine |
| `TDENGINE_PROTO` | `TAOS-WS`（可选 `TAOS` 原生 / `TAOS-RS` REST） | TDengine |
| `TDENGINE_DRIVER` | `com.taosdata.jdbc.ws.WebSocketDriver`（改协议必须同步改） | TDengine |
| `TDENGINE_USER` / `TDENGINE_PASSWORD` | `root` / `taosdata` | TDengine |
| `JWT_SECRET` | 开发默认值（启动日志 WARN） | JWT 签名密钥；**≥ 32 字节**，web 与 analyser 必须一致 |
| `JWT_EXPIRE_MINUTES` | 120 | 令牌有效期（分钟） |
| `LOG_DIRS` | `logs,kms-collector/logs,kms-analyser/logs,kms-web/logs` | 系统日志接口的候选目录（仅 web） |
| `SERVER_PORT` | 3000/8081/8082（web/collector/analyser） | 服务端口 |

### 在 Docker 库与本机库之间切换（两者互斥，同一时刻只连一套）

默认连接 Docker 容器（宿主机映射端口）。要连**本机安装**的达梦/TDengine，启用 `local` profile：

```bash
java -jar kms-collector/target/kms-collector-1.0.0.jar --spring.profiles.active=local
java -jar kms-analyser/target/kms-analyser-1.0.0.jar   --spring.profiles.active=local
java -jar kms-web/target/kms-web-1.0.0.jar             --spring.profiles.active=local
# 或用环境变量统一指定：SPRING_PROFILES_ACTIVE=local
```

`local` profile 的默认值见各模块 `src/main/resources/application-local.yml`（本机达梦 5236、本机 TDengine 6041，密码按安装时的实际值调整）。

**切换前请先停掉 Docker 里对应的容器**：TDengine 的 6030/6041 与本机实例端口完全相同，不停容器会**静默连到容器里的那一套**；达梦不冲突（容器映射 30236 / 本机默认 5236），两者可同时运行。

也可以只临时覆盖单个参数、不启用 profile：

```bash
java -jar kms-web/target/kms-web-1.0.0.jar --DM_PORT=5236 --DB_PASSWORD=你的本机密码
```

详见 `kms-web/src/main/resources/application-local.yml` 与 `docker-compose.yml`。

### Swagger 交互式接口文档

集成了 springdoc-openapi，**接口文档由代码自动生成、页面可直接联调**：

| 入口 | 地址 |
|---|---|
| Swagger UI（可交互调试） | `http://localhost:3000/swagger-ui/index.html` |
| OpenAPI 3.1 JSON（可导入 Postman／代码生成器） | `http://localhost:3000/v3/api-docs` |
| 采集器控制 API 文档（8081 内部接口） | `http://localhost:8081/swagger-ui/index.html` |

用法：先在页面里调用 `POST /api/auth/login`（admin / admin123）拿到 token，再点右上角 **Authorize** 粘贴该 token（无需手写 `Bearer ` 前缀），之后即可对任意受保护接口点 **Try it out** 执行。

接口清单与摘要由 `@RestController` / `@Tag` / `@Operation` 注解生成，**代码改了文档自动同步**，不会出现手写文档过期的问题。`SecurityConfig` 已显式放行 `/swagger-ui/**` 与 `/v3/api-docs/**`。

### 账号：登录、注册与权限

| 能力 | 入口 | 说明 |
|---|---|---|
| 登录 | `POST /api/auth/login` 或页面 `/#/login` | 种子账号 `admin / admin123`（ADMIN），首次登录后请改密 |
| **注册** | `POST /api/auth/register` 或页面 `/#/register`（登录页有入口） | 开放注册，**注册即登录**（直接返回令牌）；**新账号角色固定为 `VIEWER`（只读）**——请求体里传 `role` 会被忽略，防止注册提权 |
| 权限 | 见下文 | `ADMIN` 全部可用；`VIEWER` 只能查看监控数据与预警，改配置/看日志/看审计会被拒（403） |

注册规则：用户名 3~20 位字母、数字、下划线或中文（全局唯一）；密码至少 6 位。**生产环境建议关闭开放注册**（只由管理员开号）：

```bash
# 关闭后 /api/auth/register 返回 403
java -jar kms-web/target/kms-web-1.0.0.jar --server.port=3000 --kms.security.registration-enabled=false
# 或用环境变量：REGISTRATION_ENABLED=false
```

**TDengine 默认协议为 `TAOS-WS`（WebSocket，端口 6041）**——纯 Java 实现，无需在宿主机安装任何客户端。协议探测已确认本机原生 `jdbc:TAOS://...:6030` 会失败（缺少 TDengine C 客户端的 taos.dll），而 TAOS-RS 与 TAOS-WS 均可正常使用。如需使用原生协议，请先安装 TDengine 客户端，再用 `TDENGINE_PROTO=TAOS` + `TDENGINE_PORT=6030` + `TDENGINE_DRIVER=com.taosdata.jdbc.TSDBDriver` 三项一起切换。

运行各应用模块：

```bash
java -jar kms-collector/target/kms-collector-1.0.0.jar   # 端口 8081
java -jar kms-analyser/target/kms-analyser-1.0.0.jar     # 端口 8082
java -jar kms-web/target/kms-web-1.0.0.jar               # 端口 3000
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
- **动态阈值（加分项）** — 每 5 分钟，对 `t_metric_def` 中 `dynamic_threshold = 1` 的指标（种子数据中 `cpu_usage` 开启），用最近 1 小时 TDengine 数据的 mean ± 2×std 替换固定阈值（需至少 5 个样本）。阈值配置可在"指标配置"页（`/#/metric-config`）调整；注意**动态指标的上下限归本任务所有**，页面提交的手工值会被忽略（否则刚设的值 5 分钟内就被重算覆盖）。
- **WebSocket 推送** — 新预警以 JSON 广播给所有连接 `ws://<host>:8082/ws/alerts?token=<JWT>` 的客户端（握手时校验令牌，无令牌拒绝连接）。

达梦表 `t_metric_def` / `t_alert_rule` / `t_alert_record` / `t_oper_log` 由 `kms-database` 中共享的 `AlertSchemaInitializer` 与 `OperLogSchemaInitializer` 在启动时幂等建表并写入种子数据（analyser、web、collector 中均会执行）。`application.yml` 中的调优键（`kms.analyser.*`）：`interval-ms`、`alert-cooldown-minutes`、`dynamic-threshold-interval-ms`。

## Web 模块（Step 5-6）与数据大屏（Step 7）

`kms-web`（模块 D，规格 4.4）= Spring Boot REST 后端（3000）+ 若依风格 Vue 3 / Element Plus 前端（Vite 构建，由 `classpath:/static` 托管）。

### 后端 REST API

除 `POST /api/auth/login` 外，所有 `/api/**` 需要 `Authorization: Bearer <JWT>`（否则 401）；`/api/logs`、`/api/oper-logs` 额外要求 ADMIN 角色。

| 方法 | 路径 | 含义 |
|--------|------|---------|
| POST | `/api/auth/login` body `{"username","password"}` | 登录，签发 JWT（种子账号 `admin / admin123`） |
| GET | `/api/auth/me` | 当前登录用户（刷新页面后恢复会话） |
| POST | `/api/auth/logout` | 退出（前端丢令牌，接口留作统一调用点） |
| GET | `/api/ops/collector/status` | 采集器状态（代理 8081；采集器宕机时 `available:false`） |
| POST | `/api/ops/collector/period?period=N` | 修改采集周期（校验 `>= 60`） |
| POST | `/api/ops/collector/enable` / `disable` | 启用 / 禁用采集 |
| GET | `/api/metrics/latest` | 各指标当前值（达梦 `t_metric_latest`） |
| GET | `/api/metrics/realtime?minutes=30` | 最近 N 分钟全部指标（TDengine） |
| GET | `/api/metrics/history?metricCode=&begin=&end=` | 单个指标原始时序（TDengine；指标代码经白名单校验） |
| GET | `/api/alerts?pageNum=&pageSize=&level=&status=&begin=&end=` | 预警分页查询，支持筛选（达梦） |
| GET | `/api/alerts/recent?limit=10` | 最新 N 条预警（大屏跑马灯） |
| PUT | `/api/alerts/{id}/status` body `{"status":1}` | 标记已处理（1）/ 未处理（0） |
| GET | `/api/oper-logs?pageNum=&pageSize=&username=&operType=&module=&begin=&end=` | 操作审计分页查询（仅 ADMIN） |
| GET | `/api/logs/services`、`GET /api/logs?service=&lines=&keyword=` | 系统日志查看（仅 ADMIN；服务名白名单校验，防路径穿越） |

### 前端页面

- **登录页**（`/#/login`）— 账号密码登录；路由前置守卫把未登录访问一律重定向到这里。
- **实时监控** — 指标多选、最近 30 分钟折线图，5 秒刷新，附带最新值卡片（规格第 2 条）。
- **历史查询** — 按指标 + 时间范围查询，表格与折线图两种展示（规格第 3 条）。
- **预警管理** — 预警列表，支持级别/状态/时间筛选、分页、标记处理，10 秒自动刷新（规格第 4 条）。
- **运维管理** — 采集器状态、最近采集时间、周期设置表单、经采集器 REST API 启停采集（规格第 1 条）。
- **指标配置**（`/#/metric-config`）— 阈值配置表 + 编辑弹窗：上下限、波动率阈值、动态阈值开关；**仅 ADMIN**（侧边栏对 VIEWER 隐藏，后端 403）。此前改阈值只能改代码或直接改库，这个页面补上了配置入口（功能域⑧）。
- **操作日志**（`/#/operlog`）— 审计记录：用户/操作类型/时间筛选 + 分页，仅 ADMIN。
- **数据大屏**（`/#/screen`）— 深色全屏大屏（规格第 5 条）：顶部最新预警滚动跑马灯、当前指标占比饼图、CPU/内存/带宽实时折线图、最新预警列表、底部实时指标卡片，每 5 秒刷新。
- **预警弹窗** — 经 analyser 的 WebSocket 到达的预警会以 Element Plus 通知弹出并播放双音提示（Web Audio API），所有页面均生效（规格第 6 条）。
- **移动端适配** — 响应式栅格；大屏在 1100px 以下堆叠为单列（规格第 7 条，加分项）。

前端直连 analyser 的 WebSocket（`ws://<hostname>:8082/ws/alerts`，浏览器会自动拼上 `?token=<JWT>`；构建时可用 `VITE_WS_URL` 环境变量覆盖）。

### 登录鉴权与操作审计（功能域⑦，Step 7 之后新增）

- **鉴权**：Spring Security + JWT（无状态）：`JwtAuthFilter` 校验 `Authorization: Bearer`，接口级 `@PreAuthorize` 控制 ADMIN/VIEWER；JWT 由 `kms-database` 共享的 `KmsJwtService`（HS256）签发与校验，web 签发、analyser 校验 WS 握手（`?token=` 查询串，因为浏览器 WebSocket 不支持自定义握手头）。
- **审计**：`@OperLog` 注解 + AOP 切面把"谁做了什么"（登录、改周期、启停采集、处理预警、查看日志）异步写入达梦 `t_oper_log`，敏感参数（密码等）落库前打码；操作日志页分页查询。
- **新表**：`t_sys_user`（登录用户，BCrypt 密码哈希，种子 `admin/admin123`）与 `t_oper_log`（审计），由各模块启动时的幂等建表器创建（`t_sys_user` 在 kms-web，其余在共享库）。

## 端到端验证（Step 4-7，真机）

在 Docker 容器运行、三个应用全部启动的状态下验证通过：

- analyser 启动即创建并初始化 `t_metric_def` / `t_alert_rule` / `t_alert_record`；第一轮分析即用真实采集数据生成了 2 级波动率预警。
- 强制触发超限（CPU 上限阈值调低到 1%）后，达梦产生了 **3 级** `THRESHOLD_VOLATILITY` 记录，同时 Node WS 客户端实时收到 WebSocket 推送（`{"id":7,"metricCode":"cpu_usage","alertLevel":3,...}`）——collector → TDengine → analyser → 达梦 → WS 全链路真机打通。
- 动态阈值任务每 5 分钟重算并记录日志；`cpu_usage`（开启动态标志）按最近 1 小时 mean ± 2×std 自动调整。
- Web API 用 curl 验证：运维状态/周期（60 → 90 → 60，无需重启即生效）、latest/realtime/history、预警分页与标记处理；未知指标代码返回 400（白名单防 SQL 注入）；构建后的前端由 `http://localhost:3000/` 正常访问。
- 鉴权验证：`POST /api/auth/login`（admin/admin123）返回 JWT；不带令牌调 `/api/metrics/latest` 返回 401；WS 客户端不带 `?token=` 握手被拒（401），带令牌正常收到推送；登录与周期修改均出现在 `t_oper_log`。

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
