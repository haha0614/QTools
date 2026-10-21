package com.qtools.app.mapper;

import org.apache.ibatis.annotations.Param;
import com.qtools.app.domain.AppInviteCode;

/**
 * 邀请码 数据层（表 app_invite_code）
 *
 * @author qtools
 */
public interface AppInviteCodeMapper
{
    /** 按邀请码查询 */
    AppInviteCode selectByCode(@Param("code") String code);

    /**
     * 原子占用一次：只有「存在 / 未过期 / 未用尽」时才 +1
     *
     * @return 1 占用成功，0 表示邀请码无效、已过期或已用尽（并发下不会超发）
     */
    int increaseUsedCount(@Param("code") String code);
}
