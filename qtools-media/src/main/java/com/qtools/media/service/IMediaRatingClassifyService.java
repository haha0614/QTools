package com.qtools.media.service;

import java.util.Map;

/**
 * 内容自动分级（P6.02）—— 规则层 + 本地模型兜底，一次跑批、可预览（dry-run）可执行（apply）
 *
 * <p>为什么独立成一个服务：它要读三张表（视频 / 相册 / 照片）、调一次本地模型、
 * 再按「最严优先」聚合，最后才写回分级。塞进 {@code IMediaRatingService}（那个是单条 / 批量写入口）
 * 会让「一次写一条」和「一次判四百条」混在同一个类里，而两者的失败语义完全不同：
 * 前者失败要 400 告诉用户，后者失败要「保持原值 + 写进报告」。</p>
 *
 * <p><b>规则表在资源文件里</b>（{@code rating-rules.json}），本服务只负责取数、聚合、写回、出报告。
 * 脚本 {@code classify-rating.js} 走 HTTP 调这里，不复制任何词表 —— 这是「后端唯一真源」的落实点。</p>
 *
 * @author qtools
 */
public interface IMediaRatingClassifyService
{
    /**
     * 跑一次自动分级
     *
     * @param dryRun  {@code true}（默认）= 只出报告不写库；{@code false} = 写库
     * @param only    {@code all}（默认）| {@code video} | {@code album}
     * @param limit   每类最多审视多少条（{@code null} / 0 = 全量）
     * @param useLlm  是否对「规则一条未命中」的条目调用本地模型兜底
     * @param force   {@code true} 才覆盖已经是 guidance / restricted 的条目；
     *                默认只把 general（含未分类）升级成更严的档，不降级、不覆盖人工判定
     * @return 报告：每档计数 / 命中词统计 / 20 条样例 / 模型兜底统计与样例 / 本次写入结果
     */
    Map<String, Object> classify(Boolean dryRun, String only, Integer limit, Boolean useLlm, Boolean force);
}
