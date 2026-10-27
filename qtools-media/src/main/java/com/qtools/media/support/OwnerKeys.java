package com.qtools.media.support;

/**
 * 所有者键（owner_key）的形态约定（P5.74）
 *
 * <p>媒体站的用户数据（播放列表 / 收藏 / 收藏夹 / 最近浏览 / 保存的筛选）全部按一张字符串列
 * {@code owner_key} 隔离。P5.74 起这一列装两种身份，<b>同表共存、互不干扰</b>：</p>
 * <ul>
 * <li>{@code u:<appUserId>} —— 登录账号态（跨设备 / 跨浏览器跟随账号）；</li>
 * <li>裸设备键 —— 匿名设备态，取值仍是 {@code localStorage['ms.owner']} / cookie {@code ms_owner}
 * 那串 32 字节 hex（<b>不带前缀</b>，与 P5.74 之前写入的历史数据逐字相同，因此老数据不需要迁移）。</li>
 * </ul>
 *
 * <p>为什么匿名的键不加 {@code d:} 前缀：设备键的字符集是 {@code [A-Za-z0-9._-]}（不含冒号），
 * 而账号键恒含冒号，两者已经天然不冲突；加前缀反而要让库里所有历史行跟着改一遍（一次没有收益的迁移）。
 * 也就是说「{@code u:} 前缀 + 裸设备键」= 需求里写的 {@code u:<id>} / {@code d:<owner>} 的等价实现，
 * 差异只在于设备侧省掉了能被历史数据直接命中的前缀。</p>
 *
 * <p>安全性：客户端<b>不能</b>自称账号键——{@code X-Ms-Owner} 出现 {@code u:} 前缀会被
 * {@code MediaOwnerServiceImpl} 直接 400 拒绝，账号态只能由 {@code Authorization: Bearer <app token>}
 * 校验后得出。</p>
 *
 * @author qtools
 */
public final class OwnerKeys
{
    /** 账号态前缀（含冒号；设备键字符集里没有冒号，因此不会与设备键撞车） */
    public static final String ACCOUNT_PREFIX = "u:";

    private OwnerKeys()
    {
    }

    /** 账号态键：{@code u:<appUserId>}；userId 为 null 时返回 null（调用方按匿名处理） */
    public static String account(Long userId)
    {
        return userId == null ? null : ACCOUNT_PREFIX + userId;
    }

    /** 是否账号态键 */
    public static boolean isAccount(String ownerKey)
    {
        return ownerKey != null && ownerKey.startsWith(ACCOUNT_PREFIX);
    }

    /** 从账号态键取 appUserId；不是账号态或尾巴不是数字时返回 null */
    public static Long userIdOf(String ownerKey)
    {
        if (!isAccount(ownerKey))
        {
            return null;
        }
        try
        {
            return Long.valueOf(ownerKey.substring(ACCOUNT_PREFIX.length()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
