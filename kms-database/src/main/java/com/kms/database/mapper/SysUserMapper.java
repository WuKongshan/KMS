package com.kms.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kms.database.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code t_sys_user} (login / RBAC layer).
 *
 * <p>【中文】登录用户表的 Mapper。CRUD 全部由 MyBatis-Plus 的 {@link BaseMapper} 提供：
 * 登录按 username 查、用户管理按主键增删改，都不需要手写 SQL。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
