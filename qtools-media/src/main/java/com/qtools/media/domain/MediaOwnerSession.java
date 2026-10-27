package com.qtools.media.domain;

import com.qtools.media.support.OwnerKeys;

/**
 * 当前请求的所有者上下文（数据 owner_key + 设备 key + 个人资料）（P5.74）
 *
 * <p>从 P5.74 起「这次请求属于谁」有两个层次，本对象同时携带：</p>
 * <ul>
 * <li>{@link #getOwnerKey()} —— <b>数据归属键</b>：登录态是 {@code u:<appUserId>}，匿名为裸设备键。
 * 播放列表 / 收藏 / 收藏夹 / 最近浏览 / 保存的筛选全部按它读写。</li>
 * <li>{@link #getDeviceKey()} —— <b>设备键</b>：昵称与头像（{@code media_owner}）仍按设备保存，
 * 响应体里的 {@code ownerKey} 字段也回设备键（前端要拿它继续发 {@code X-Ms-Owner}，
 * 回 {@code u:<id>} 会被服务端按「客户端自称账号」拒绝）。</li>
 * </ul>
 *
 * <p>登录 / 匿名两种形态只差 {@link #getUserId()} 一个字段：{@link #isAccount()} 是唯一判定入口，
 * 业务代码不要再自己读请求头判断登录态。</p>
 *
 * @author qtools
 */
public class MediaOwnerSession
{
    /** 设备 key（cookie ms_owner 或请求头 X-Ms-Owner；匿名时它就是数据归属键） */
    private final String deviceKey;

    /** 登录账号 app_user.id；null = 匿名设备态 */
    private final Long userId;

    /** 本次请求是否新生成设备 key（需要回写 Set-Cookie） */
    private final boolean fresh;

    /** 昵称，默认「我」（媒体站的设备级资料，与账号昵称互不干扰） */
    private String nick;

    /** 头像 data URI，默认 null */
    private String avatar;

    public MediaOwnerSession(String deviceKey, Long userId, boolean fresh)
    {
        this.deviceKey = deviceKey;
        this.userId = userId;
        this.fresh = fresh;
    }

    /** 数据归属键：登录态 {@code u:<id>}，匿名为设备键 */
    public String getOwnerKey()
    {
        return isAccount() ? OwnerKeys.account(userId) : deviceKey;
    }

    /** 设备键（媒体站资料与 {@code X-Ms-Owner} 回写的取值来源） */
    public String getDeviceKey()
    {
        return deviceKey;
    }

    /** 登录账号 id；未登录为 null */
    public Long getUserId()
    {
        return userId;
    }

    /** 本次请求是否带有效 app token（= 走账号态） */
    public boolean isAccount()
    {
        return userId != null;
    }

    public boolean isFresh()
    {
        return fresh;
    }

    public String getNick()
    {
        return nick;
    }

    public void setNick(String nick)
    {
        this.nick = nick;
    }

    public String getAvatar()
    {
        return avatar;
    }

    public void setAvatar(String avatar)
    {
        this.avatar = avatar;
    }
}
