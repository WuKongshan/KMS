package com.kms.web.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kms.database.entity.SysUser;
import com.kms.database.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Creates the login table {@code t_sys_user} and seeds the first administrator
 * (idempotent, same pattern as {@code AlertSchemaInitializer}).
 *
 * <p>【中文】创建登录表 {@code t_sys_user} 并写入首个管理员账号（幂等，
 * 与 AlertSchemaInitializer 同一套模式：每次启动都安全）。
 * <p>【中文】本类放在 kms-web 而不是共享库，因为它依赖 {@link PasswordEncoder}——
 * 密码哈希器来自 spring-boot-starter-security，只有 web 模块引入了该依赖；
 * 采集器与分析器不需要登录表，也就不会被打扰。
 */
@Component
@Order(1)
public class SysUserSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SysUserSchemaInitializer.class);

    // 达梦没有 CREATE TABLE IF NOT EXISTS，且数据字典里表名为大写，故建表前先查系统表
    private static final String CREATE_USER_TABLE = "CREATE TABLE t_sys_user ("
            + "id BIGINT IDENTITY(1,1), username VARCHAR(64) NOT NULL, password VARCHAR(100) NOT NULL, "
            + "nickname VARCHAR(64), role VARCHAR(32), enabled INT, "
            + "create_time TIMESTAMP, update_time TIMESTAMP, "
            + "PRIMARY KEY (id), CONSTRAINT uk_sys_user_username UNIQUE (username))";

    // 首个管理员的初始密码：首次登录后应立即修改（启动日志会 WARN 提醒）
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final JdbcTemplate dmJdbcTemplate;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public SysUserSchemaInitializer(@Qualifier("dmJdbcTemplate") JdbcTemplate dmJdbcTemplate,
                                    SysUserMapper sysUserMapper,
                                    PasswordEncoder passwordEncoder) {
        // 双数据源环境下必须用 @Qualifier 指定达梦，否则可能注入到 TDengine 的 JdbcTemplate
        this.dmJdbcTemplate = dmJdbcTemplate;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        seedAdmin();
    }

    // 建表：查 USER_TABLES 判断是否已存在（表名必须大写），不存在才执行 DDL
    private void ensureTable() {
        Integer count = dmJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?", Integer.class, "T_SYS_USER");
        if (count == null || count == 0) {
            dmJdbcTemplate.execute(CREATE_USER_TABLE);
            log.info("DM8 login schema ready: t_sys_user created");
        }
    }

    // 种子管理员：仅在缺失时插入，绝不覆盖已修改的密码（否则每次重启都会把密码重置）
    private void seedAdmin() {
        Long existing = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, DEFAULT_ADMIN_USERNAME));
        if (existing != null && existing > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(DEFAULT_ADMIN_USERNAME);
        // 只存 BCrypt 哈希，不存明文；matches() 校验时会从哈希里解出盐
        admin.setPassword(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
        admin.setNickname("系统管理员");
        admin.setRole("ADMIN");
        admin.setEnabled(1);
        LocalDateTime now = LocalDateTime.now();
        admin.setCreateTime(now);
        admin.setUpdateTime(now);
        sysUserMapper.insert(admin);
        log.warn("Seeded default admin account '{}/{}' — change this password before production!",
                DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
    }
}
