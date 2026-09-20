package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.OperLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for the operation audit log {@code t_oper_log}.
 *
 * <p>【中文】操作审计日志表的 Mapper。CRUD 由 MyBatis-Plus 提供：
 * 写入走 insert，查询走 selectPage + 条件构造器，无需手写 SQL。
 */
@Mapper
public interface OperLogMapper extends BaseMapper<OperLog> {
}
