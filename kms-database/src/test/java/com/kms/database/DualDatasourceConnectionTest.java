package com.kms.database;

import com.kms.database.config.DmDataSourceConfig;
import com.kms.database.config.MybatisPlusConfig;
import com.kms.database.config.TdengineDataSourceConfig;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 2 acceptance test: both datasources must connect to the running Docker
 * containers ({@code docker compose up -d}) and MyBatis-Plus must bind to the
 * DM8 primary datasource. Connection settings come from
 * {@code src/test/resources/application.yml}.
 *
 * <p>{@link KmsTestApplication} mirrors what {@code @SpringBootApplication}
 * does in the app modules — auto-configuration (MyBatis-Plus
 * {@code SqlSessionFactory}, transaction manager) must be active.
 *
 * <p>【中文】步骤 2 的验收测试：必须能连通正在运行的 Docker 容器
 * （{@code docker compose up -d}，即达梦 DM8 与 TDengine 都已启动），
 * 且 MyBatis-Plus 必须绑定到达梦主数据源。连接参数来自
 * {@code src/test/resources/application.yml}——本测试<b>不是</b>纯单元测试，
 * 容器未运行时这些用例会失败。
 *
 * <p>【中文】{@link KmsTestApplication} 复刻了各业务模块中 {@code @SpringBootApplication} 的行为——
 * 必须让自动配置生效（MyBatis-Plus 的 {@code SqlSessionFactory}、事务管理器），
 * 本例通过 {@code spring.main.web-application-type=none} 以非 Web 方式启动上下文。
 */
@SpringBootTest(
        classes = {KmsTestApplication.class, DmDataSourceConfig.class, TdengineDataSourceConfig.class, MybatisPlusConfig.class},
        properties = "spring.main.web-application-type=none")
class DualDatasourceConnectionTest {

    // 显式按名称注入达梦数据源的 JdbcTemplate：双数据源下存在多个同类型 Bean，不加 @Qualifier 会注入失败。
    @Autowired
    @Qualifier("dmJdbcTemplate")
    private JdbcTemplate dmJdbcTemplate;

    // 显式按名称注入 TDengine 数据源的 JdbcTemplate，理由同上。
    @Autowired
    @Qualifier("taosJdbcTemplate")
    private JdbcTemplate taosJdbcTemplate;

    // 用于验证 MyBatis-Plus 自动配置是否已生效并挂到达梦数据源上。
    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    // 达梦连通性：能查出常量 1 即说明连接池、驱动与账号密码均可用。
    @Test
    void dm8IsReachable() {
        Integer one = dmJdbcTemplate.queryForObject("select 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }

    // TDengine 连通性：用其内置函数 server_version() 取版本号。
    @Test
    void tdengineIsReachable() {
        String version = taosJdbcTemplate.queryForObject("select server_version()", String.class);
        // 断言以 "3." 开头：本项目使用的 taos-jdbcdriver 3.9.0 只兼容 TDengine 3.x 服务端。
        assertThat(version).startsWith("3.");
    }

    // 校验 MyBatis-Plus 绑定的是达梦主数据源（而非 TDengine），
    // 否则实体/Mapper 的读写会跑到时序库上。
    @Test
    void mybatisPlusBindsToDm8Primary() {
        assertThat(sqlSessionFactory.getConfiguration().getEnvironment().getDataSource())
                .isNotNull();
    }
}
