package com.qtools.media.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.media.config.MediaProperties;

/**
 * 自动分级的<b>模型兜底层</b>（P6.02）：只处理规则一条都没命中的「不确定」条目
 *
 * <h2>为什么只在规则未命中时才调模型</h2>
 * <ol>
 *   <li><b>成本</b>：现网 409 条（239 视频 + 170 相册），规则命中率约九成，模型只需兜底几十条；</li>
 *   <li><b>可解释性</b>：规则命中能说出「哪个词命中哪个字段」，模型说不出来，能少用就少用；</li>
 *   <li><b>稳定性</b>：3B 模型对「自慰」「口穴」这类直白词未必比词表可靠，规则先跑即是「确定的不问模型」。</li>
 * </ol>
 *
 * <h2>输出契约</h2>
 * <p>提示词要求「只输出一个档位单词」，temperature 0.1（分类任务不需要创造性）、
 * {@code num_predict=8}（一个单词足够，给多了模型就开始解释自己）。解析是<b>宽松匹配</b>：
 * 只要回复里出现 {@code restricted / guidance / general}（或中文「限制级 / 家长指导 / 大众」）就取它，
 * 三个都没有才判定为「模型没给出可用档位」——失败条目由调用方保持原值并写进报告，绝不猜一档写库。</p>
 *
 * @author qtools
 */
@Component
public class MediaRatingLlmJudge
{
    private static final Logger log = LoggerFactory.getLogger(MediaRatingLlmJudge.class);

    /** 系统提示：三档定义 + 一句判断标准（模型没有 system 角色时由 MediaLlmClient 拼在 prompt 前） */
    public static final String SYSTEM = "你是内容分级助手。你只输出一个英文单词：general 或 guidance 或 restricted。"
            + "不要解释，不要标点，不要其它任何文字。";

    /** 一个档位单词足够（多给 token 只会让模型开始自我解释） */
    public static final int NUM_PREDICT = 8;

    /** 分类任务温度：0.1 —— 只要稳定复述档位，不要发挥 */
    public static final double TEMPERATURE = 0.1;

    /** 判定提示词模板（三档定义 + 一句判断标准 + 待判条目） */
    private static final String TEMPLATE = "给下面这条媒体内容分级。\n"
            + "general（大众级）：日常、教程、汽车养护、素材、壁纸屏保、软件演示，没有性暗示。\n"
            + "guidance（家长指导级）：写真、泳装、内衣、丝袜、cos、热舞、擦边、走光等穿着暴露或性暗示，但没有露骨性行为。\n"
            + "restricted（限制级）：性行为、裸露性器官、色情、成人视频等露骨内容。\n"
            + "判断标准：出现露骨性行为或裸露性器官就是 restricted；只是穿着暴露或有性暗示就是 guidance；都没有才是 general。\n"
            + "标题：%s\n"
            + "所属目录：%s\n"
            + "只输出一个单词（general / guidance / restricted）：";

    private final MediaLlmClient client;

    private final MediaProperties properties;

    @Autowired
    public MediaRatingLlmJudge(MediaLlmClient client, MediaProperties properties)
    {
        this.client = client;
        this.properties = properties;
    }

    /** 配置开关是否打开（{@code qtools.media.llm.enabled}）；关闭时接口在报告里写「未启用」而不是报错 */
    public boolean isReady()
    {
        return client.isReady();
    }

    public String modelName()
    {
        return client.modelName();
    }

    public String provider()
    {
        return client.provider();
    }

    /** 单条超时（{@code qtools.media.rating.llmTimeoutMs}，默认 15000） */
    public int timeoutMs()
    {
        return properties.getRating().getLlmTimeoutMs();
    }

    /**
     * 判一条
     *
     * @param title  标题
     * @param folder 所属目录（视频是 folder，相册是 dir；都没有传空串）
     * @return 三档之一 + 模型原始回复 + 耗时；模型没给出可用档位时返回 {@code null}（调用方保持原值）
     */
    public Judgement judge(String title, String folder)
    {
        String prompt = String.format(Locale.ROOT, TEMPLATE, nullToEmpty(title), nullToEmpty(folder));
        MediaLlmClient.Reply reply = client.generate(SYSTEM, prompt, NUM_PREDICT, TEMPERATURE, timeoutMs());
        String rating = parseRating(reply.text);
        if (rating == null)
        {
            log.warn("自动分级：模型回复无法解析为档位，本条保持原值 raw={}", brief(reply.text));
            return null;
        }
        return new Judgement(rating, reply.text, reply.model, reply.ms);
    }

    /**
     * 从模型回复里解析档位（纯函数，单测覆盖）
     *
     * <p>顺序很重要：先查 restricted 再查 guidance 再查 general —— 「not restricted, it is general」
     * 这种啰嗦回复要按最严的一档算（与规则层「宁可高估」同向）。</p>
     *
     * @return {@code restricted / guidance / general}；都没出现返回 {@code null}
     */
    public static String parseRating(String reply)
    {
        if (reply == null)
        {
            return null;
        }
        String text = reply.toLowerCase(Locale.ROOT);
        if (text.contains("restricted") || text.contains("限制"))
        {
            return MediaRating.RESTRICTED;
        }
        if (text.contains("guidance") || text.contains("家长") || text.contains("指导"))
        {
            return MediaRating.GUIDANCE;
        }
        if (text.contains("general") || text.contains("大众"))
        {
            return MediaRating.GENERAL;
        }
        return null;
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private static String brief(String value)
    {
        if (value == null)
        {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 一次模型判定的结果（报告里要原样回显「模型说了什么」，人工抽查才能判断模型靠不靠谱） */
    public static final class Judgement
    {
        private final String rating;

        private final String raw;

        private final String model;

        private final long ms;

        Judgement(String rating, String raw, String model, long ms)
        {
            this.rating = rating;
            this.raw = raw;
            this.model = model;
            this.ms = ms;
        }

        public String getRating()
        {
            return rating;
        }

        public String getRaw()
        {
            return raw;
        }

        public String getModel()
        {
            return model;
        }

        public long getMs()
        {
            return ms;
        }

        public Map<String, Object> toNode()
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rating", rating);
            out.put("raw", raw);
            out.put("ms", Long.valueOf(ms));
            return out;
        }
    }
}
