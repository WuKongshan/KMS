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
 */
@SpringBootTest(
        classes = {KmsTestApplication.class, DmDataSourceConfig.class, TdengineDataSourceConfig.class, MybatisPlusConfig.class},
        properties = "spring.main.web-application-type=none")
class DualDatasourceConnectionTest {

    @Autowired
    @Qualifier("dmJdbcTemplate")
    private JdbcTemplate dmJdbcTemplate;

    @Autowired
    @Qualifier("taosJdbcTemplate")
    private JdbcTemplate taosJdbcTemplate;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void dm8IsReachable() {
        Integer one = dmJdbcTemplate.queryForObject("select 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }

    @Test
    void tdengineIsReachable() {
        String version = taosJdbcTemplate.queryForObject("select server_version()", String.class);
        assertThat(version).startsWith("3.");
    }

    @Test
    void mybatisPlusBindsToDm8Primary() {
        assertThat(sqlSessionFactory.getConfiguration().getEnvironment().getDataSource())
                .isNotNull();
    }
}
