package com.kms.database.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus plugin configuration. The {@link MybatisPlusInterceptor} bean
 * is picked up automatically by the MyBatis-Plus auto-configuration and applied
 * to the DM8-bound {@code SqlSessionFactory}.
 *
 * <p>【中文】MyBatis-Plus 插件配置。{@link MybatisPlusInterceptor} Bean 会被
 * MyBatis-Plus 自动配置自动收集，并应用到绑定达梦的 {@code SqlSessionFactory} 上
 * （本模块只注册了达梦的 SqlSessionFactory，因此该插件不影响 TDengine 的 JdbcTemplate 查询）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // DM8 dialect for the pagination plugin
        // 达梦分页方言：MyBatis-Plus 3.5.17 中 DbType.DM 归入 DbType.oracleSameType()，实际由 OracleDialect 生成分页 SQL，
        // 即把原语句包裹为 SELECT * FROM (SELECT TMP.*, ROWNUM ROW_ID FROM (原SQL) TMP WHERE ROWNUM <= ?) WHERE ROW_ID > ?，
        // 达梦 8 兼容 Oracle 语法，因此无需（也不存在）单独的 DmDialect
        // 若缺少该内拦截器或方言配错，IPage 分页会静默失效——退化为返回全表且 total 恒为 0，不抛任何异常
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.DM));
        return interceptor;
    }
}
