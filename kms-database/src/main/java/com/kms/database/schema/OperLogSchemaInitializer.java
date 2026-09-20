package com.kms.database.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the operation audit table {@code t_oper_log} on startup (idempotent).
 *
 * <p>【中文】启动时幂等创建操作审计表 {@code t_oper_log}。
 * 放在共享库（而不是 Web 模块）是因为建表只依赖 JdbcTemplate；
 * 表本身只有 Web 模块写入与读取，但放在这里与其它 SchemaInitializer 保持一致的组织方式。
 */
@Component
@Order(1)
public class OperLogSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperLogSchemaInitializer.class);

    // 达梦没有 CREATE TABLE IF NOT EXISTS，且数据字典中表名为大写，故先查 USER_TABLES
    private static final String CREATE_OPER_LOG_TABLE = "CREATE TABLE t_oper_log ("
            + "id BIGINT IDENTITY(1,1), username VARCHAR(64), module VARCHAR(64), "
            + "oper_type VARCHAR(32), oper_desc VARCHAR(256), request_method VARCHAR(16), "
            + "request_uri VARCHAR(256), request_params VARCHAR(1000), status INT, "
            + "error_msg VARCHAR(512), ip VARCHAR(64), cost_ms BIGINT, create_time TIMESTAMP, "
            + "PRIMARY KEY (id))";

    private final JdbcTemplate dmJdbcTemplate;

    public OperLogSchemaInitializer(@Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate) {
        // 双数据源环境必须显式限定达梦，否则可能注入到 TDengine 的 JdbcTemplate
        this.dmJdbcTemplate = dmJdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?", Integer.class, "T_OPER_LOG");
        if (count == null || count == 0) {
            dmJdbcTemplate.execute(CREATE_OPER_LOG_TABLE);
            log.info("DM8 audit schema ready: t_oper_log created");
        }
    }
}
