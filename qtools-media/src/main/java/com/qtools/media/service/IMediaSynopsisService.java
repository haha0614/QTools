package com.qtools.media.service;

import java.util.List;
import java.util.Map;

/**
 * 作品简介自动生成 服务层（P5.47）
 *
 * <p>两条生成路径共用同一份结构化素材 {@link com.qtools.media.domain.dto.SynopsisFields}：
 * {@link #template} 纯规则拼句（无 AI、毫秒级、必然可用），{@link #llm} 走本地模型（可关闭）。
 * 两者都**不落库** —— 落库仍走既有的 {@code PUT .../synopsis}，让「先生成预览、用户改完再保存」
 * 这条交互不被后端偷偷改成「点了就写」。</p>
 *
 * <p>批量 {@link #startBatch} 是唯一的写路径写库者：为每条生成完立刻落库（生成一个替换一个），
 * 进度与逐条结果可查。</p>
 *
 * @author qtools
 */
public interface IMediaSynopsisService
{
    /**
     * 生成能力：{@code {template:{maxChars}, llm:{enabled,provider,model}}}
     *
     * <p>前端据此决定「⚡ 自动生成」走本地模型还是模板；{@code enabled=false} 时不显示任何 AI 字样。
     * 不含 baseUrl / apiKey（能力查询接口是站端可读的）。</p>
     */
    Map<String, Object> capabilities();

    /**
     * 模板生成（预览，不落库）：{@code {id, synopsis, chars, mode:"template"}}
     *
     * @param videoId 视频 id；不存在 404
     */
    Map<String, Object> template(String videoId);

    /**
     * 本地模型生成内容梗概（预览，不落库）
     *
     * <p>P5.53 起素材按内容含量排队，P5.57 补上最后一路：<b>字幕 → 画面 → 音频转写</b>，
     * 成功回 {@code {id, synopsis, chars, mode:"llm", source:"subtitle"|"vision"|"audio", model, provider, ms, material}}。</p>
     *
     * <p>素材不足时<b>不调模型、不编造</b>，回 200 + {@code {insufficient:true, source:"none", reason, subtitleReason?, visionReason?, audioReason?}}，
     * 前端据此提示「该片缺少字幕 / 画面素材」并给「用模板生成客观信息」按钮。</p>
     *
     * @throws com.qtools.media.exception.MediaException 503：本地模型未启用 / 连不上 / 超时
     */
    Map<String, Object> llm(String videoId);

    /**
     * 用音频转写重新生成（预览，不落库；P5.57）
     *
     * <p>「字幕根本不准」时的手动入口：<b>只走</b> whisper 转写这一路，不看既有字幕、不看画面 ——
     * 用户按这个按钮就是因为字幕不可信，换回字幕等于没按。</p>
     *
     * <p>回包结构同 {@link #llm(String)}（成功时 {@code source:"audio"}，
     * {@code material} 里带 {@code provider/model/language/segments/chars/audioSeconds/transcribeMs/excerpt}）；
     * 音频转写不可用或拿不到可用正文时回 200 + {@code insufficient:true} + {@code audioReason}，
     * 由前端显示原因，不做静默降级。</p>
     *
     * @throws com.qtools.media.exception.MediaException 503：本地模型未启用 / 连不上 / 超时
     */
    Map<String, Object> audio(String videoId);

    /**
     * 建批量任务（后台单线程逐个处理，每条完成即落库）
     *
     * @param videoIds  指定 id 列表（{@code all=true} 时忽略）
     * @param all       true = 全库未隐藏且未缺失的视频
     * @param mode      {@code template} | {@code llm}（缺省 template）
     * @param overwrite false（默认）= 已有简介的视频跳过
     * @return 任务节点（结构同 {@link #job}），此时通常 status=pending
     */
    Map<String, Object> startBatch(List<String> videoIds, boolean all, String mode, boolean overwrite);

    /**
     * 批量前的「影响面预览」（P5.55 两步确认的第一段弹窗就靠它）
     *
     * <p>不给这一份，前端只能用「库里有多少片子」当分母 —— 而真正的分母是「未隐藏未缺失」那批，
     * 且「已有内容会跳过几条」在前端根本算不出来。</p>
     *
     * @param videoIds  指定 id（{@code all=true} 时忽略）
     * @param all       全库未隐藏且未缺失的视频
     * @param overwrite true = 已有内容也覆盖（默认 false = 跳过）
     * @return {@code {total, withExisting, withoutExisting, willProcess, willSkip}}
     */
    Map<String, Object> preview(List<String> videoIds, boolean all, boolean overwrite);

    /**
     * 查询任务进度与逐条结果（{@code {jobId,mode,status,total,processed,generated,skipped,failed,items:[...]}}）
     *
     * <p>{@code status}: pending / running / done / stopped / failed；单条 {@code status}: generated / skipped / failed。
     * 处理中途查询就能看到已完成的那几条（这正是「生成一个替换一个」的可观测证据）。</p>
     *
     * <p>P5.55 起回包还带 {@code counts{total,processed,generated,skipped,failed,remaining}}、
     * {@code skipReasons{existing,insufficient,other}}、{@code canRollback} / {@code rollbackCount}
     * —— 成果汇报与撤销入口都只读这一份，前端不再自己加减。</p>
     */
    Map<String, Object> job(String jobId);

    /**
     * 正在跑的任务列表（P5.55「停止」按钮的第一步：先问后端到底有没有在跑的）
     *
     * @return {@code {count, jobs:[{jobId,mode,status,total,processed,generated,skipped,failed,counts,cancelRequested}]}}
     */
    Map<String, Object> activeJobs();

    /**
     * 请求停止（P5.55）：协作式，<b>不打断正在生成的那一条</b>
     *
     * <p>幂等：任务已 done / stopped / failed 时只把标志置上并返回当前状态，不报错
     * —— 前端连点两下「停止」不该看到错误。</p>
     *
     * @throws com.qtools.media.exception.MediaException 404：任务不存在或已过期（后端重启）
     */
    Map<String, Object> cancel(String jobId);

    /**
     * 撤销本次批量（P5.55）：把该任务真正写过的每一行恢复到<b>落库前的原文</b>
     *
     * <p>旧值为空 → 清空该行；旧值非空 → 写回原文。任务没记旧值（{@code canRollback=false}，
     * 例如 P5.55 之前创建的任务）→ 400，提示改用「按 ids 清空简介」。</p>
     *
     * @return {@code {jobId, total, cleared, restored, failed, rolledBackAt, items:[{videoId,action}]}}
     * @throws com.qtools.media.exception.MediaException 404 任务不存在 / 400 无旧值可撤销 / 409 已撤销过
     */
    Map<String, Object> rollback(String jobId);

    /**
     * 按 ids 清空简介（P5.55 兜底工具）：任务没记旧值时，管理端用它把这批机器生成的内容抹掉
     *
     * @param videoIds 目标视频 id 列表（去空去重）
     * @param dryRun   true = 只统计将要清空几条，不写库（默认行为，前端第一段确认用）
     * @return {@code {dryRun, requested, willClear|cleared, alreadyEmpty, missing, ids}}
     */
    Map<String, Object> clearSynopsis(List<String> videoIds, boolean dryRun);
}
