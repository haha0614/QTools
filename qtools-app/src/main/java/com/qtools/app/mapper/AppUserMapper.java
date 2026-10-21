package com.qtools.app.mapper;

import org.apache.ibatis.annotations.Param;
import com.qtools.app.domain.AppUser;

/**
 * 用户端账号 数据层（表 app_user）
 *
 * <p>包名符合 framework 的 {@code @MapperScan("com.qtools.**.mapper")}，无需 {@code @Mapper} 注解。</p>
 *
 * @author qtools
 */
public interface AppUserMapper
{
    /** 按主键查询 */
    AppUser selectById(@Param("id") Long id);

    /** 按用户名查询（登录第一顺位） */
    AppUser selectByUsername(@Param("username") String username);

    /** 按身份查询（email / phone，登录开关打开后才用） */
    AppUser selectByIdentity(@Param("type") String type, @Param("identifier") String identifier);

    /** 新增账号，回填自增主键 */
    int insert(AppUser user);

    /** 刷新最近登录时间 */
    int updateLastLogin(@Param("id") Long id);
}
