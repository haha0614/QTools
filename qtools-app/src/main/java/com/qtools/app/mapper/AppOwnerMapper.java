package com.qtools.app.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 匿名设备认领 数据层（表 media_owner）
 *
 * <p>只改 {@code media_owner.user_id}：媒体模块的收藏 / 最近浏览按 {@code owner_key} 关联，
 * 认领后自然归到账号名下，不需要搬动任何行。</p>
 *
 * <p>SQL 写在本模块（qtools-app）里、只触碰 {@code media_owner} 的一张表的一列，
 * 这样 {@code qtools-media} 保持无改动、也不产生 app → media 的模块依赖。</p>
 *
 * @author qtools
 */
public interface AppOwnerMapper
{
    /**
     * 把匿名设备绑定到账号：仅当该设备尚未绑定、或已绑定到同一账号时才生效
     *
     * @return 1 绑定成功（或本来就是自己的），0 表示已属于别的账号或设备不存在
     */
    int claimOwner(@Param("ownerKey") String ownerKey, @Param("userId") Long userId);

    /** 该设备当前绑定的账号（未绑定为 null；设备不存在也为 null，靠 countOwner 区分） */
    Long selectOwnerUserId(@Param("ownerKey") String ownerKey);

    /** 该设备是否存在 */
    int countOwner(@Param("ownerKey") String ownerKey);
}
