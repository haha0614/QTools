package com.qtools.media.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 媒体事件埋点 数据层（写入 + 当天汇总）
 *
 * <p>包名符合 framework 中 @MapperScan("com.qtools.**.mapper")，无需 @Mapper 注解。</p>
 *
 * <p>写事件与写当天的 media_stats_daily 是两条独立语句（不是同一个事务）：埋点是尽力而为的，
 * 明细落在 media_event 就能通过 rebuild 补回汇总，因此宁可少一次汇总也不让上报 500。</p>
 *
 * @author qtools
 */
public interface MediaEventMapper
{
    /** 插入一条事件明细（create_time 用数据库 now()，与汇总用的 curdate() 同一时钟） */
    int insertEvent(@Param("eventType") String eventType, @Param("targetType") String targetType,
            @Param("targetKey") String targetKey, @Param("ownerKey") String ownerKey,
            @Param("valueNum") long valueNum, @Param("ip") String ip, @Param("userAgent") String userAgent);

    /** 当天汇总 +1（同日同类型同目标命中主键则累加 cnt / value_sum） */
    int upsertDaily(@Param("eventType") String eventType, @Param("targetType") String targetType,
            @Param("targetKey") String targetKey, @Param("valueNum") long valueNum);
}
