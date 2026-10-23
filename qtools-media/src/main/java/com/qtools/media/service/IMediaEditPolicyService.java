package com.qtools.media.service;

/**
 * 媒体库「站端编辑策略」服务：三档 off / local / full
 *
 * <p>策略落在 {@code sys_config['media.userEditing']}，本服务是<b>唯一</b>的读写入口：
 * 站端写接口在进业务前调 {@link #requireWritable()}，管理端「编辑策略」调
 * {@link #getLevel()} / {@link #setLevel(String)}。</p>
 *
 * <p>三档语义：</p>
 * <table border="1">
 *   <caption>level → 站端写接口行为</caption>
 *   <tr><th>level</th><th>语义</th><th>站端写接口</th></tr>
 *   <tr><td>{@code off}</td><td>站端禁止编辑</td><td>403</td></tr>
 *   <tr><td>{@code local}</td><td>只在浏览器本地改（服务端不落库）</td><td>403</td></tr>
 *   <tr><td>{@code full}</td><td>完全编辑（默认档位）</td><td>正常写入</td></tr>
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

    /** 站端写接口的前置闸门：level != full 时抛 403；返回当前档位 */
    String requireWritable();
}
