package com.qtools.media.service;

/**
 * 媒体库「站端编辑策略」服务：三档 off / local / full，叠加 P5.89 的站长（owner）豁免
 *
 * <p>策略落在 {@code sys_config['media.userEditing']}，本服务是<b>唯一</b>的读写入口：
 * 站端写接口在进业务前调 {@link #requireWritable()}，管理端「编辑策略」调
 * {@link #getLevel()} / {@link #setLevel(String)}，站端 {@code GET /api/media/edit-policy} 调
 * {@link #getLevel()} / {@link #currentRole()} / {@link #canWrite()}。</p>
 *
 * <p>三档语义（P5.89 起先看角色，再看档位）：</p>
 * <table border="1">
 *   <caption>role + level → 站端写接口行为</caption>
 *   <tr><th>请求者</th><th>level</th><th>站端写接口</th></tr>
 *   <tr><td>{@code owner} 站长</td><td>任意（off / local / full）</td><td><b>放行</b>（full 权限，P5.89）</td></tr>
 *   <tr><td>普通用户 / 未登录</td><td>{@code off}</td><td>403</td></tr>
 *   <tr><td>普通用户 / 未登录</td><td>{@code local}</td><td>403（前端可存 localStorage）</td></tr>
 *   <tr><td>普通用户 / 未登录</td><td>{@code full}</td><td>正常写入</td></tr>
 * </table>
 *
 * <p>默认 full：单实例自托管（Stash 式）场景下站端编辑就是主人的编辑能力。
 * 公网演示实例应改成 local 或 off，见 {@code doc/media_edit_policy.sql}。</p>
 *
 * @author qtools
 */
public interface IMediaEditPolicyService
{
    /** 当前档位；库里的值非法 / 缺失时回退 {@code full}（并带 30s 本地缓存） */
    String getLevel();

    /** 设置档位（只接受三个值），写库后立即失效本地缓存 */
    void setLevel(String level);

    /**
     * 站端写接口的前置闸门：站长（{@code role=owner}）一律放行；其余角色 level != full 时抛 403
     *
     * <p><b>唯一闸门</b>：站端所有写接口（标签 / 元数据 / 演员 / 简介 / 头像 / 背景调整…）
     * 都只调这一个方法，角色判定在实现里做一次 —— 不在任何 controller 里重复写 if。</p>
     *
     * @return 当前档位（站长豁免时也返回真实档位，便于调用方记日志）
     */
    String requireWritable();

    /**
     * 当前请求者的角色（P5.89）：{@code owner} / {@code user}；未登录（或没有角色解析组件）返回 null
     *
     * <p>只用于「读」：站端 {@code GET /api/media/edit-policy} 回给前端决定是否显示编辑入口。</p>
     */
    String currentRole();

    /** 当前请求者此刻是不是能写：站长恒 true，其余看档位是否 full（未登录 false） */
    boolean canWrite();
}
