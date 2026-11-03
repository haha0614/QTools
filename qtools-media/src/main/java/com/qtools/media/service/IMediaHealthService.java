package com.qtools.media.service;

import java.util.Map;

/**
 * 库健康检查（P6.06）
 *
 * <p>把「今天到底哪里不对」做成一次调用：相册计数漂移、身份没回填 / 重复、空壳相册、目录不存在、
 * 缺失行、孤儿行、无封面。只读报告 + 两个安全修复（计数重算、删「目录确实不存在」的空壳相册）。</p>
 *
 * @author qtools
 */
public interface IMediaHealthService
{
    /** 健康报告：{generatedAt, ok, problems, checks:[{key,label,count,ok,detail,fix}]} */
    Map<String, Object> report();

    /**
     * 执行修复
     *
     * @param kind {@code albumCounters}（按 media_photo 重算张数/体积）或
     *             {@code emptyAlbums}（删 0 图且目录已不存在的相册）
     * @return {kind, fixed, kept, note}
     */
    Map<String, Object> fix(String kind);
}
