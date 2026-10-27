package com.qtools.app.mapper;

import java.time.LocalDateTime;
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

    /** 只查裁决相关列（审核状态 + 封禁；鉴权过滤器每请求一次，不取 avatar 等大字段） */
    AppUser selectStatusById(@Param("id") Long id);

    /**
     * 只查角色一列（P5.89；站端闸门 {@code requireWritable()} 经 {@code IAppRoleResolver} 每请求最多一次）
     *
     * @return {@code user} / {@code owner}；账号不存在返回 null（调用方按 user 处理）
     */
    String selectRoleById(@Param("id") Long id);

    /** 改角色：只写 role + updated_at（管理端行内切换；值域由服务层校验） */
    int updateRole(@Param("id") Long id, @Param("role") String role);

    /** 某角色的账号数（「不能把最后一个 owner 降级」的唯一依据） */
    int countByRole(@Param("role") String role);

    /** 新增账号，回填自增主键 */
    int insert(AppUser user);

    /** 刷新最近登录时间 */
    int updateLastLogin(@Param("id") Long id);

    /** 写审核结论：status + review_note + reviewed_at(now) + reviewed_by + updated_at(now) */
    int updateReviewStatus(@Param("id") Long id, @Param("status") String status,
            @Param("reviewNote") String reviewNote, @Param("reviewedBy") Long reviewedBy);

    /**
     * 改资料：<b>只改 map 里显式标记为「提供了」的字段</b>（P5.81）
     *
     * <p>map 约定：{@code id}、{@code setNickname}/{@code nickname}、{@code setAvatar}/{@code avatar}、
     * {@code setReviewNote}/{@code reviewNote}。用 setXxx 布尔而不是「值非 null」判定，
     * 才能区分「没传这个字段」和「传了空想清空」。每次写都刷新 {@code updated_at}。</p>
     */
    int updateProfile(Map<String, Object> profile);

    /** 重置密码：只写 password_hash + updated_at(now) */
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /** 封禁 / 解封：banned + banned_reason + banned_at（解封时后两者传 null）+ updated_at(now) */
    int updateBan(@Param("id") Long id, @Param("banned") boolean banned,
            @Param("bannedReason") String bannedReason, @Param("bannedAt") LocalDateTime bannedAt);

    /** 真删除（会话 / 身份 / 媒体绑定由 service 先级联清掉） */
    int deleteById(@Param("id") Long id);

    /**
     * 管理端分页列表
     *
     * @param status 审核状态（null=不限）
     * @param banned 封禁维度（null=不限 / 1=只看已封禁 / 0=只看未封禁）
     * @param keyword 匹配 username / nickname
     */
    List<AppUser> selectPage(@Param("status") String status, @Param("banned") Integer banned,
            @Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    /** 与 {@link #selectPage} 同条件的总数 */
    int countPage(@Param("status") String status, @Param("banned") Integer banned,
            @Param("keyword") String keyword);

    /** 管理端顶部统计（一次查询拿全：total / pending / approved / rejected / banned / newToday / activeRecently） */
    Map<String, Object> selectStats();

    /** 某张表是否在本库（用户管理里的收藏数 / 播放列表数是读媒体表的可选增强） */
    int countTable(@Param("tableName") String tableName);

    /** 一页账号的收藏数：[{userId, cnt}] */
    List<Map<String, Object>> selectFavoriteCounts(@Param("userIds") List<Long> userIds);

    /** 一页账号的播放列表数：[{userId, cnt}] */
    List<Map<String, Object>> selectPlaylistCounts(@Param("userIds") List<Long> userIds);

    /** 删除账号前解绑媒体所有权（media_owner.user_id -> NULL，避免留下指向不存在账号的绑定） */
    int clearOwnerBinding(@Param("userId") Long userId);
}
