package com.kms.database.schema;

import com.kms.database.entity.AlertRule;
import com.kms.database.entity.MetricDef;
import com.kms.database.mapper.AlertRuleMapper;
import com.kms.database.mapper.MetricDefMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Creates/verifies the analyser tables in DM8 on startup (idempotent, runs in
 * every module that scans {@code com.kms} — analyser, web and collector).
 * Tables: t_metric_def (thresholds), t_alert_rule (expressions),
 * t_alert_record (generated alerts). Seeds metric definitions and rules on
 * first start. Canonical DDL: kms-database/src/main/resources/sql/dm8-schema.sql.
 *
 * <p>【中文】启动时在达梦（DM8）中幂等创建/校验分析模块所需的三张表，
 * 运行于<b>每一个</b>扫描 {@code com.kms} 的模块（分析器、Web、采集器）。
 * 三张表：t_metric_def（阈值定义）、t_alert_rule（条件表达式）、t_alert_record（预警记录）。
 * 首次启动时写入种子数据（指标定义与预警规则）。规范 DDL 见
 * kms-database/src/main/resources/sql/dm8-schema.sql。
 *
 * <p>【中文】本类为 {@code @Component} + {@code ApplicationRunner} + {@code @Order(1)}，
 * 会被三个模块共同扫描到，这是<b>刻意的容错设计</b>：三个进程的启动顺序不确定，
 * 谁先启动谁就把表准备好，后启动的进程检测到表已存在便直接跳过，因此可以任意顺序启动、重复启动。
 * {@code @Order(1)} 保证它在各模块自己的业务 Runner（如采集器的采集循环 {@code @Order(2)}）之前执行。
 */
@Component
@Order(1)
public class AlertSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertSchemaInitializer.class);

    // t_metric_def：指标定义与阈值（主键为指标代码）。upper/lower 允许为 NULL，
    // 例如 disk_free 只有下限没有上限；dynamic_threshold 为 1 表示由动态阈值任务定期重算。
    private static final String CREATE_METRIC_DEF_TABLE = "CREATE TABLE t_metric_def ("
            + "metric_code VARCHAR(32) NOT NULL, metric_name VARCHAR(64), unit VARCHAR(16), "
            + "upper_threshold DOUBLE, lower_threshold DOUBLE, volatility_threshold DOUBLE, "
            + "dynamic_threshold INT, update_time TIMESTAMP, PRIMARY KEY (metric_code))";

    // t_alert_rule：预警规则，expression 存条件表达式文本（由分析器的 RuleExpression 解析执行），
    // enabled 为 0/1 开关；id 是手工指定的整数主键，便于种子数据幂等。
    private static final String CREATE_ALERT_RULE_TABLE = "CREATE TABLE t_alert_rule ("
            + "id INT NOT NULL, rule_code VARCHAR(64), rule_name VARCHAR(128), "
            + "metric_code VARCHAR(32), expression VARCHAR(256), enabled INT, "
            + "update_time TIMESTAMP, PRIMARY KEY (id))";

    // t_alert_record：预警记录。id 使用达梦自增列 IDENTITY(1,1)；
    // alert_level 取 1/2/3（1 仅阈值超限、2 仅波动率异常、3 两者同时），
    // status 取 0/1（0 未处理、1 已处理），create_time 为产生时间、update_time 为状态变更时间。
    private static final String CREATE_ALERT_RECORD_TABLE = "CREATE TABLE t_alert_record ("
            + "id BIGINT IDENTITY(1,1), metric_code VARCHAR(32), metric_name VARCHAR(64), "
            + "alert_level INT, alert_type VARCHAR(32), message VARCHAR(512), "
            + "current_value DOUBLE, threshold_value DOUBLE, volatility DOUBLE, "
            + "status INT, create_time TIMESTAMP, update_time TIMESTAMP, PRIMARY KEY (id))";

    // 达梦数据源专用的 JdbcTemplate（由 @Qualifier 指定），用于执行 DDL 与系统表查询：
    // 双数据源环境下必须显式限定，否则可能误注入 TDengine 的 JdbcTemplate。
    private final JdbcTemplate dmJdbcTemplate;
    private final MetricDefMapper metricDefMapper;
    private final AlertRuleMapper alertRuleMapper;

    // 注入达梦 JdbcTemplate 与两个 MyBatis-Plus Mapper（种子数据用 Mapper 读写，保证与实体字段映射一致）。
    public AlertSchemaInitializer(@Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate,
                                  MetricDefMapper metricDefMapper,
                                  AlertRuleMapper alertRuleMapper) {
        this.dmJdbcTemplate = dmJdbcTemplate;
        this.metricDefMapper = metricDefMapper;
        this.alertRuleMapper = alertRuleMapper;
    }

    // ApplicationRunner 回调：Spring 容器启动完成后执行，顺序由类上的 @Order(1) 决定。
    @Override
    public void run(ApplicationArguments args) {
        // 建表传参必须是"大写表名"：达梦在 USER_TABLES 中存的是大写名，写小写会永远查不到，
        // 于是每次启动都尝试建表，第二次启动就会报"表已存在"。
        ensureTable("T_METRIC_DEF", CREATE_METRIC_DEF_TABLE);
        ensureTable("T_ALERT_RULE", CREATE_ALERT_RULE_TABLE);
        ensureTable("T_ALERT_RECORD", CREATE_ALERT_RECORD_TABLE);
        // 先建三张表再写种子数据：种子写入依赖表已存在。
        seedMetricDefs();
        seedAlertRules();
        log.info("DM8 analyser schema ready: t_metric_def, t_alert_rule, t_alert_record verified/created");
    }

    /**
     * DM8 has no CREATE TABLE IF NOT EXISTS: check USER_TABLES first (names are uppercase).
     *
     * <p>【中文】达梦没有 {@code CREATE TABLE IF NOT EXISTS} 语法：所以先查 USER_TABLES 判断表是否已存在，
     * 不存在才执行建表，借此实现幂等。注意 {@code tableName} 必须传<b>大写</b>表名——
     * 达梦在 USER_TABLES 里存的是大写名，传小写会永远查不到 → 每次启动都尝试建表 → 第二次启动报"表已存在"。
     */
    private void ensureTable(String tableName, String ddl) {
        // 用参数化查询传入表名：表名是"值"的位置（USER_TABLES 的过滤条件），可以用 ? 绑定，无注入风险。
        Integer count = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?", Integer.class, tableName);
        // count 为 null 也按"不存在"处理，避免空指针；count > 0 说明表已建好，只做校验不再执行 DDL。
        if (count == null || count == 0) {
            dmJdbcTemplate.execute(ddl);
        }
    }

    /**
     * Seed metric definitions (spec 4.3: thresholds per metric; volatility in percent).
     *
     * <p>【中文】写入指标定义种子数据（规格 4.3：每项指标一组阈值；volatility_threshold 的单位是百分比）。
     * 用 {@code selectById(...) == null} 判空实现幂等：已存在的记录不覆盖，因此不会把运行时由动态阈值任务
     * 调整过的阈值又刷回初始值。
     */
    private void seedMetricDefs() {
        // metricCode, metricName, unit, upper, lower, volatility%, dynamic(0/1)
        // 二维数组各列依次为：指标代码、中文名/展示名、单位、上限阈值、下限阈值、波动率阈值(%)、是否启用动态阈值(0/1)。
        Object[][] defs = {
                {"cpu_usage", "CPU Usage", "%", 90.0, 0.0, 10.0, 1},
                {"mem_usage", "Memory Usage", "%", 90.0, 0.0, 10.0, 0},
                // disk_free 的 upper_threshold 故意留 null：磁盘剩余空间只有下限（低于 10 GB 才告警），没有上限。
                {"disk_free", "Disk Free Space", "GB", null, 10.0, 10.0, 0},
                {"net_send", "Network Send Rate", "KB/s", 51200.0, 0.0, 15.0, 0},
                {"net_recv", "Network Receive Rate", "KB/s", 51200.0, 0.0, 15.0, 0},
                {"cpu_temp", "CPU Temperature", "C", 90.0, 0.0, 10.0, 0},
        };
        for (Object[] def : defs) {
            String code = (String) def[0];
            // 主键 metric_code 已存在则跳过（幂等），保证重复启动不会插入重复定义、也不会覆盖已调整的阈值。
            if (metricDefMapper.selectById(code) == null) {
                MetricDef row = new MetricDef();
                row.setMetricCode(code);
                row.setMetricName((String) def[1]);
                row.setUnit((String) def[2]);
                // 注意 disk_free 的 def[3] 是 null，转 Double 仍为 null，正对应"无上限"的语义。
                row.setUpperThreshold((Double) def[3]);
                row.setLowerThreshold((Double) def[4]);
                row.setVolatilityThreshold((Double) def[5]);
                row.setDynamicThreshold((Integer) def[6]);
                row.setUpdateTime(LocalDateTime.now());
                metricDefMapper.insert(row);
            }
        }
    }

    /**
     * Seed alert rules (spec 4.3: condition expressions matched against the latest value).
     *
     * <p>【中文】写入预警规则种子数据（规格 4.3：用条件表达式与最新值比较）。
     * expression 文本由分析器的 {@code RuleExpression} 解析执行，可用变量包括
     * value / upper_threshold / lower_threshold / volatility / volatility_threshold。
     * 同样以 {@code selectById(id) == null} 保证幂等，enabled 统一初始化为 1（启用）。
     */
    private void seedAlertRules() {
        // 各行依次为：规则 id（手工指定，便于幂等）、规则代码、规则名称、指标代码、条件表达式。
        // 六条规则与六个指标一一对应：cpu/mem/net_send/net_recv/cpu_temp 判上限，disk_free 判下限。
        Object[][] rules = {
                {1, "RULE_CPU", "CPU usage threshold", "cpu_usage", "value > upper_threshold"},
                {2, "RULE_MEM", "Memory usage threshold", "mem_usage", "value > upper_threshold"},
                {3, "RULE_DISK", "Disk free space floor", "disk_free", "value < lower_threshold"},
                {4, "RULE_NET_SEND", "Network send rate ceiling", "net_send", "value > upper_threshold"},
                {5, "RULE_NET_RECV", "Network receive rate ceiling", "net_recv", "value > upper_threshold"},
                {6, "RULE_TEMP", "CPU temperature threshold", "cpu_temp", "value > upper_threshold"},
        };
        for (Object[] rule : rules) {
            Integer id = (Integer) rule[0];
            // 主键 id 已存在则跳过（幂等）：重复启动不会重复插入规则，也不会覆盖用户改过的表达式。
            if (alertRuleMapper.selectById(id) == null) {
                AlertRule row = new AlertRule();
                row.setId(id);
                row.setRuleCode((String) rule[1]);
                row.setRuleName((String) rule[2]);
                row.setMetricCode((String) rule[3]);
                row.setExpression((String) rule[4]);
                // enabled=1 表示规则默认启用；置 0 可由外部停用该规则。
                row.setEnabled(1);
                row.setUpdateTime(LocalDateTime.now());
                alertRuleMapper.insert(row);
            }
        }
    }
}
