package com.qtools.common.security;

/**
 * 用户端账号的「角色」解析（P5.89）：两档 {@code user} 普通用户 / {@code owner} 站长
 *
 * <p>为什么单开一个 SPI 而不是让媒体模块自己查 app_user：{@code app_user} 属于 qtools-app，
 * 媒体模块（qtools-media）不持有用户端的表与实体。这里只暴露「这个 appUserId 是什么角色」这一条
 * 结论性接口，实现由 qtools-app 提供（{@code AppRoleResolver}，带 30s 本地缓存）；
 * 与 {@link IAppTokenVerifier} 同一模式：媒体模块拿到的只是判定结果，不复制身份体系。</p>
 *
 * <p>消费方必须容忍「没有实现」：单独部署 / 单测的媒体模块里该 bean 不存在，
 * 此时一律按 {@link #ROLE_USER} 处理（不抛异常、不启动失败）。</p>
 *
 * <p><b>唯一闸门消费点</b>：{@code MediaEditPolicyServiceImpl#requireWritable()} ——
 * 站端 owner 无视 {@code media.userEditing} 三档一律可写，普通用户仍按三档走。
 * 前端相应用 {@code GET /api/media/edit-policy} 返回的 {@code role / canWrite} 决定是否显示编辑入口。</p>
 *
 * @author qtools
 */
public interface IAppRoleResolver
{
    /** 普通用户（默认值；存量账号迁移后都是这个值） */
    String ROLE_USER = "user";

    /** 站长：站端 full 权限（无视 media.userEditing 三档） */
    String ROLE_OWNER = "owner";

    /**
     * 该账号的角色，<b>一定</b>是 {@link #ROLE_USER} 或 {@link #ROLE_OWNER}：
     * 库里是空 / 脏值 / 账号不存在 → 一律回 {@link #ROLE_USER}（保守：不当站长）。
     *
     * @param userId 用户端账号 id；null 直接回 {@link #ROLE_USER}
     */
    String roleOf(Long userId);

    /** 便捷判定：是否站长（null / 未知一律 false） */
    default boolean isOwner(Long userId)
    {
        return ROLE_OWNER.equals(roleOf(userId));
    }
}
