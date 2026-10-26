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
     * <p>P5.53 起素材按内容含量排队：<b>字幕 → 画面 → （音频转写：未实现）</b>，
     * 成功回 {@code {id, synopsis, chars, mode:"llm", source:"subtitle"|"vision", model, provider, ms, material}}。</p>
     *
     * <p>素材不足时<b>不调模型、不编造</b>，回 200 + {@code {insufficient:true, source:"none", reason, subtitleReason?, visionReason?, audioReason?}}，
     * 前端据此提示「该片缺少字幕 / 画面素材」并给「用模板生成客观信息」按钮。</p>
     *
     * @throws com.qtools.media.exception.MediaException 503：本地模型未启用 / 连不上 / 超时
     */
    Map<String, Object> llm(String videoId);

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
     * 查询任务进度与逐条结果（{@code {jobId,mode,status,total,processed,generated,skipped,failed,items:[...]}}）
     *
     * <p>{@code status}: pending / running / done / failed；单条 {@code status}: generated / skipped / failed。
     * 处理中途查询就能看到已完成的那几条（这正是「生成一个替换一个」的可观测证据）。</p>
     */
    Map<String, Object> job(String jobId);
}
