package com.qtools.app.mapper;

import java.util.List;
import java.util.Map;
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

    /** 只查审核相关三列（鉴权过滤器每请求一次，不取 avatar 等大字段） */
    AppUser selectStatusById(@Param("id") Long id);

    /** 新增账号，回填自增主键 */
    int insert(AppUser user);

    /** 刷新最近登录时间 */
    int updateLastLogin(@Param("id") Long id);

    /** 写审核结论：status + review_note + reviewed_at(now) + reviewed_by */
    int updateReviewStatus(@Param("id") Long id, @Param("status") String status,
            @Param("reviewNote") String reviewNote, @Param("reviewedBy") Long reviewedBy);

    /** 管理端分页列表（status 为空=全部；keyword 匹配 username / nickname） */
    List<AppUser> selectPage(@Param("status") String status, @Param("keyword") String keyword,
            @Param("offset") int offset, @Param("size") int size);

    /** 与 {@link #selectPage} 同条件的总数 */
    int countPage(@Param("status") String status, @Param("keyword") String keyword);

    /** 各状态账号数：[{status, cnt}] */
    List<Map<String, Object>> selectStatusCounts();
}
