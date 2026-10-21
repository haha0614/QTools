package com.qtools.media.mapper;

import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaOwner;

/**
 * 媒体所有者 数据层（M2）
 *
 * @author qtools
 */
public interface MediaOwnerMapper
{
    /** 不存在则插入空行（owner_key 上有唯一键，insert ignore 幂等） */
    int insertIgnore(@Param("ownerKey") String ownerKey);

    /** 刷新最近访问时间 */
    int updateLastSeen(@Param("ownerKey") String ownerKey);

    /** 更新昵称（profile.nick） */
    int updateDisplayName(@Param("ownerKey") String ownerKey, @Param("displayName") String displayName);

    /** 更新头像（profile.avatar，data URI 原样存；null 表示清空该列） */
    int updateAvatar(@Param("ownerKey") String ownerKey, @Param("avatar") String avatar);

    /** 按 owner_key 查询 */
    MediaOwner selectOwnerByKey(@Param("ownerKey") String ownerKey);
}
