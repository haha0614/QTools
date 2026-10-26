package com.qtools.media.support;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;

/**
 * 本地模型客户端（P5.47 交付 2）：两种后端，一套出口
 *
 * <table border="1">
 *   <caption>provider → 请求 / 响应</caption>
 *   <tr><th>ollama</th><td>{@code POST <baseUrl>} {@code {model,prompt,stream:false,options}} → {@code .response}</td></tr>
 *   <tr><th>openai</th><td>{@code POST <baseUrl>} {@code {model,messages:[system,user],temperature,max_tokens,stream:false}}
 *       → {@code .choices[0].message.content}</td></tr>
 * </table>
 *
 * <p><b>失败一律 503，且文案要能指向下一步动作</b>：「未启用」指向配置开关，「未就绪（Ollama 未启动？）」
 * 指向进程没起，「超时」指向模型太大 / 首次加载慢。500 只会让人以为是站点的 bug。</p>
 *
 * <p>没有重试：简介生成是人工点一次的交互动作，重试只会把「模型没起」拖成用户等 3 倍超时。</p>
 *
 * @author qtools
 */
@Component
public class MediaLlmClient
{
    private static final Logger log = LoggerFactory.getLogger(MediaLlmClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 响应体摘要上限（错误文案里只放这么多，避免把整段 HTML 错误页塞进 toast） */
    private static final int BRIEF_CHARS = 120;

    /**
     * 单次生成的 token 上限（P5.53）：200 字的中文简介 ≥ 200 token 就够，给多了只会让 1.5B 模型
     * 跑偏去写长段落（实测超过 320 后模型开始自我复述字段）。
     */
    public static final int NUM_PREDICT = 200;

    /**
     * 文本生成温度（P5.53）：0.4 —— 旧值 0.2 太保守，模型倾向把素材原样抄回来；
     * 0.6 以上开始编造。0.4 是「愿意改写措辞、不发明情节」的折中。
     */
    public static final double TEMPERATURE = 0.4;

    /** 视觉模型逐帧描述的温度：只要客观描述，不要发挥 */
    public static final double VISION_TEMPERATURE = 0.2;

    /** 单帧描述的字数预算（一句 30-60 字足够） */
    public static final int VISION_NUM_PREDICT = 120;

    private final MediaProperties properties;

    private final HttpClient http;

    @Autowired
    public MediaLlmClient(MediaProperties properties)
    {
        this.properties = properties;
        int connectMs = Math.min(properties.getLlm().getEffectiveTimeoutMs(), 2000);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs)).build();
    }

    /** 配置是否打开（false 时前端不显示「AI 生成」，接口回 503） */
    public boolean isReady()
    {
        return properties.getLlm().isEnabled();
    }

    /** 当前生效模型名（回包与前端提示都用它） */
    public String modelName()
    {
        return properties.getLlm().getEffectiveModel();
    }

    /** 当前生效后端类型：ollama | openai */
    public String provider()
    {
        return properties.getLlm().getEffectiveProvider();
    }

    /**
     * 生成一段简介文本（已清洗、已截断到 200 字符）
     *
     * @param prompt 用户提示（见 {@link SynopsisPrompt#build}）；系统提示由本方法固定注入
     * @throws MediaException 503：未启用 / 连不上 / 超时 / 非 2xx / 返回空
     */
    public Reply generate(String prompt)
    {
        return generate(SynopsisPrompt.SYSTEM, prompt, NUM_PREDICT, TEMPERATURE);
    }

    /**
     * 生成一段文本（可指定系统提示 / token 预算 / 温度）
     *
     * @param system      系统提示（固定文案，见 {@link SynopsisPrompt#SYSTEM}）
     * @param prompt      用户提示
     * @param maxTokens   token 上限
     * @param temperature 温度
     * @throws MediaException 503：未启用 / 连不上 / 超时 / 非 2xx
     */
    public Reply generate(String system, String prompt, int maxTokens, double temperature)
    {
        MediaProperties.Llm cfg = properties.getLlm();
        if (!cfg.isEnabled())
        {
            throw MediaException.serviceUnavailable("本地模型未启用（qtools.media.llm.enabled=false）");
        }
        String provider = cfg.getEffectiveProvider();
        String url = cfg.getEffectiveBaseUrl();
        String model = cfg.getEffectiveModel();
        int timeoutMs = cfg.getEffectiveTimeoutMs();
        String payload;
        try
        {
            payload = MAPPER.writeValueAsString("openai".equals(provider)
                    ? openAiBody(model, system, prompt, maxTokens, temperature)
                    : ollamaBody(model, system, prompt, maxTokens, temperature));
        }
        catch (Exception e)
        {
            throw MediaException.serviceUnavailable("本地模型请求组装失败：" + e.getMessage());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        String apiKey = cfg.getApiKey();
        if ("openai".equals(provider) && apiKey != null && !apiKey.trim().isEmpty())
        {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        long begin = System.currentTimeMillis();
        HttpResponse<String> response;
        try
        {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (HttpTimeoutException e)
        {
            throw MediaException.serviceUnavailable("本地模型响应超时（" + timeoutMs + " ms）：模型首次加载较慢或机器太弱");
        }
        catch (ConnectException e)
        {
            throw MediaException.serviceUnavailable("本地模型未就绪（Ollama 未启动？）：" + url);
        }
        catch (IOException e)
        {
            throw MediaException.serviceUnavailable("本地模型未就绪（Ollama 未启动？）：" + url + " 连接失败");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw MediaException.serviceUnavailable("本地模型调用被中断");
        }
        long ms = System.currentTimeMillis() - begin;
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw MediaException.serviceUnavailable(
                    "本地模型返回 HTTP " + response.statusCode() + "：" + brief(response.body()) + "（模型名 " + model + " 是否正确？）");
        }
        String text = SynopsisPrompt.sanitize(extract(provider, response.body()));
        if (text.isEmpty())
        {
            throw MediaException.serviceUnavailable("本地模型返回空内容（模型名 " + model + " 是否正确？）");
        }
        log.debug("本地模型生成简介 provider={} model={} ms={} chars={}", provider, model, ms, SynopsisText.codePoints(text));
        return new Reply(text, model, provider, ms);
    }

    /** ollama /api/generate 请求体（ollama 没有 system 角色，系统提示拼在 prompt 前面） */
    static Map<String, Object> ollamaBody(String model, String system, String prompt, int maxTokens, double temperature)
    {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("top_p", 0.9);
        options.put("num_predict", maxTokens);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", (system == null || system.isEmpty() ? "" : system + "\n\n") + prompt);
        body.put("stream", false);
        body.put("options", options);
        return body;
    }

    /**
     * ollama 视觉请求体：{@code images:[base64]}（ollama 的多模态只有这一个口径）
     *
     * <p>图片必须是<b>裸 base64</b>（不带 {@code data:image/jpeg;base64,} 前缀），
     * 带前缀 ollama 会静默忽略图片，模型于是"看图说话"变成"闭眼编"。</p>
     */
    static Map<String, Object> ollamaVisionBody(String model, String prompt, String base64Jpeg)
    {
        Map<String, Object> body = ollamaBody(model, "", prompt, VISION_NUM_PREDICT, VISION_TEMPERATURE);
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        // 视觉模型在「画面过于简单」（纯色桌面壁纸 / 空镜）时容易进入复读，
        // ollama 会以 500 "token repeat limit reached" 中止。repeat_penalty 是官方给的缓解手段。
        options.put("repeat_penalty", 1.3);
        options.put("repeat_last_n", 64);
        List<String> images = new ArrayList<>(1);
        images.add(base64Jpeg);
        body.put("images", images);
        return body;
    }

    /** OpenAI 兼容端点请求体（system / user 两条消息） */
    static Map<String, Object> openAiBody(String model, String system, String prompt, int maxTokens, double temperature)
    {
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", system == null ? "" : system);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", prompt);
        List<Map<String, Object>> messages = new ArrayList<>(2);
        messages.add(systemMsg);
        messages.add(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        return body;
    }

    // ------------------------------------------------------------------ 画面理解（P5.53）

    /**
     * 视觉模型对单帧画面写一句客观描述
     *
     * <p>只支持 ollama：OpenAI 兼容端点的多模态要走 {@code content:[{type:image_url}]}，
     * 各家实现不一致，本项目只盯本机 Ollama 这一条路（与 {@link #isReady()} 同源）。</p>
     *
     * @param jpeg   画面（JPEG 字节，宽度由 {@code qtools.media.llm.vision.width} 决定）
     * @param prompt 单帧提示词（见 {@link VisionPrompt#FRAME}）
     * @return 一句描述（已压平空白；可能为空串 —— 调用方按「这一帧没话说」处理）
     * @throws MediaException 503：未启用 / 未就绪 / 超时 / 非 2xx
     */
    public String describeImage(byte[] jpeg, String prompt)
    {
        MediaProperties.Vision cfg = properties.getLlm().getVision();
        if (!cfg.isEnabled())
        {
            throw MediaException.serviceUnavailable("画面理解未启用（qtools.media.llm.vision.enabled=false）");
        }
        if (!isReady())
        {
            throw MediaException.serviceUnavailable("本地模型未启用（qtools.media.llm.enabled=false）");
        }
        if (jpeg == null || jpeg.length == 0)
        {
            return "";
        }
        String model = cfg.getEffectiveModel();
        String url = properties.getLlm().getEffectiveBaseUrl();
        int timeoutMs = cfg.getEffectiveTimeoutMs();
        String payload;
        try
        {
            payload = MAPPER.writeValueAsString(ollamaVisionBody(model, prompt, Base64.getEncoder().encodeToString(jpeg)));
        }
        catch (Exception e)
        {
            throw MediaException.serviceUnavailable("画面理解请求组装失败：" + e.getMessage());
        }
        String body = post(url, payload, timeoutMs, model, "画面理解");
        String text = SynopsisPrompt.sanitize(extract("ollama", body));
        log.debug("画面理解 model={} ms=? chars={}", model, SynopsisText.codePoints(text));
        return text;
    }

    /**
     * 卸载视觉模型（{@code keep_alive:0}）：8GB 显存装不下 3B 视觉 + 1.5B 文本两个模型
     *
     * <p>失败只记 debug 日志、不影响结果：卸载是省显存的优化，不是正确性的一环。</p>
     */
    public void unloadVisionModel()
    {
        MediaProperties.Vision cfg = properties.getLlm().getVision();
        String model = cfg.getEffectiveModel();
        String url = properties.getLlm().getEffectiveBaseUrl();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("keep_alive", 0);
        try
        {
            post(url, MAPPER.writeValueAsString(body), 5000, model, "卸载视觉模型");
            log.debug("视觉模型已请求卸载 model={}", model);
        }
        catch (Exception e)
        {
            log.debug("视觉模型卸载失败（忽略）model={} err={}", model, e.getMessage());
        }
    }

    /** 统一 POST + 状态码检查（文本与视觉两条路共用） */
    private String post(String url, String payload, int timeoutMs, String model, String what)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        String apiKey = properties.getLlm().getApiKey();
        if ("openai".equals(properties.getLlm().getEffectiveProvider()) && apiKey != null && !apiKey.trim().isEmpty())
        {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpResponse<String> response;
        try
        {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (HttpTimeoutException e)
        {
            throw MediaException.serviceUnavailable(what + "超时（" + timeoutMs + " ms）：模型首次加载较慢或机器太弱");
        }
        catch (ConnectException e)
        {
            throw MediaException.serviceUnavailable(what + "未就绪（Ollama 未启动？）：" + url);
        }
        catch (IOException e)
        {
            throw MediaException.serviceUnavailable(what + "未就绪（Ollama 未启动？）：" + url + " 连接失败");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw MediaException.serviceUnavailable(what + "调用被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            String brief = brief(response.body());
            if (brief.contains("repeat limit"))
            {
                // 画面太简单（纯色 / 空镜）时视觉模型会复读同一个 token；这不是部署问题，是这一帧没内容
                throw MediaException.serviceUnavailable(what + "：模型输出陷入重复（画面过于简单或无有效内容）");
            }
            throw MediaException.serviceUnavailable(
                    what + "返回 HTTP " + response.statusCode() + "：" + brief + "（模型名 " + model + " 是否存在？）");
        }
        return response.body();
    }

    /** 从两种响应里取正文；结构不符 → 503（不是 500：对方是外部依赖） */
    private static String extract(String provider, String rawBody)
    {
        if (rawBody == null || rawBody.trim().isEmpty())
        {
            return "";
        }
        JsonNode root;
        try
        {
            root = MAPPER.readTree(rawBody);
        }
        catch (Exception e)
        {
            throw MediaException.serviceUnavailable("本地模型返回的不是 JSON：" + brief(rawBody));
        }
        if ("openai".equals(provider))
        {
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0)
            {
                return choices.get(0).path("message").path("content").asText("");
            }
            return "";
        }
        JsonNode response = root.path("response");
        if (!response.isMissingNode() && !response.isNull())
        {
            return response.asText("");
        }
        JsonNode message = root.path("message");
        return message.path("content").asText("");
    }

    /** 响应体摘要：压平空白 + 截断（错误文案只带这一小段） */
    private static String brief(String body)
    {
        if (body == null)
        {
            return "";
        }
        String text = body.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        while (text.contains("  "))
        {
            text = text.replace("  ", " ");
        }
        return text.length() <= BRIEF_CHARS ? text : text.substring(0, BRIEF_CHARS) + "…";
    }

    /** 生成结果：正文 + 实际用到的模型 / 后端 / 耗时（回包给前端做「由本地模型生成 · {model}」提示） */
    public static final class Reply
    {
        /** 已清洗的正文（≤200 字符） */
        public final String text;

        /** 实际使用的模型名 */
        public final String model;

        /** ollama | openai */
        public final String provider;

        /** 本次调用的墙钟耗时（毫秒） */
        public final long ms;

        public Reply(String text, String model, String provider, long ms)
        {
            this.text = text;
            this.model = model;
            this.provider = provider;
            this.ms = ms;
        }
    }
}
