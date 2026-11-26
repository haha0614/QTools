package com.qtools.tool.web.subtitle;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日语字幕批量翻译（docs/视频字幕-实施交接.md §T4）
 *
 * <p>按固定批次串行调 OpenAI 兼容的 chat/completions；输入输出都是 JSON 数组，靠 {@code i} 对齐。
 * 校验不过就整批重试，仍不过则该批中文置空、整体仍算成功（err 里留一句备注）。</p>
 *
 * <p>日志只打条数/耗时/状态码：字幕原文属于隐私（§12），任何层级都不落日志。</p>
 *
 * @author qtools
 */
@Component
public class SubtitleTranslateService
{
    private static final Logger log = LoggerFactory.getLogger(SubtitleTranslateService.class);

    /** 系统提示词：逐条翻译、条数与 i 一一对应、不规避内容（§T4 原文） */
    private static final String SYSTEM = "你是日语到简体中文的字幕译者。输入是一个 JSON 数组，元素形如 {\"i\":0,\"ja\":\"…\"}。"
            + "逐条翻译并只输出 JSON 数组 [{\"i\":0,\"zh\":\"…\"}]：条数与 i 必须一一对应，禁止合并、拆分、省略、增行；"
            + "保留口语与语气词，不要书面化、不要解释、不要加注；色情/粗俗内容照实翻译，不规避、不美化、不使用委婉替代；"
            + "人名、地名、片假名外来语用中文常见音译；听不清的片段保留日文原文。输出必须是合法 JSON，不要 markdown 代码块。";

    /** 一批最多重试几次（第一次之外再试 2 次，退避 1s / 3s） */
    private static final int RETRY = 2;

    private static final long[] BACKOFF_MS = { 1000L, 3000L };

    private static final String PARTIAL = "部分行未翻译";

    private final SubtitleProperties properties;

    private final HttpClient http;

    public SubtitleTranslateService(SubtitleProperties properties)
    {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 翻译结果：{@code zh} 与输入等长（翻译不到的行是空串），{@code err} 非空表示有瑕疵 */
    public static final class Result
    {
        public final List<String> zh;

        public final String err;

        Result(List<String> zh, String err)
        {
            this.zh = zh;
            this.err = err == null ? "" : err;
        }
    }

    /** 逐批翻译；{@code ja} 里的空行也照发（保持一行对一行的对齐关系） */
    public Result translate(List<String> ja)
    {
        List<String> out = new ArrayList<>();
        if (ja == null || ja.isEmpty())
        {
            return new Result(out, "");
        }
        for (int i = 0; i < ja.size(); i++)
        {
            out.add("");
        }
        SubtitleProperties.Translate cfg = properties.getTranslate();
        String apiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey().trim();
        if (apiKey.isEmpty())
        {
            return new Result(out, "翻译未配置（缺少 apiKey）");
        }
        int batch = cfg.getBatch();
        String err = "";
        long started = System.currentTimeMillis();
        int done = 0;
        for (int from = 0; from < ja.size(); from += batch)
        {
            int to = Math.min(ja.size(), from + batch);
            List<String> part = ja.subList(from, to);
            List<String> zh = translateBatch(part, from, apiKey, cfg);
            if (zh == null)
            {
                err = PARTIAL;
                log.warn("翻译批次未通过校验，该批 {} 行置空 from={}", part.size(), from);
                continue;
            }
            for (int k = 0; k < part.size(); k++)
            {
                out.set(from + k, zh.get(k));
            }
            done += part.size();
        }
        log.info("字幕翻译完成 lines={} ok={} err={} ms={}", ja.size(), done, err, System.currentTimeMillis() - started);
        return new Result(out, err);
    }

    /** 一批：失败按 1s/3s 退避重试，条数或 i 集合对不上也算失败 */
    private List<String> translateBatch(List<String> part, int offset, String apiKey, SubtitleProperties.Translate cfg)
    {
        String content = request(part, offset, apiKey, cfg);
        if (content == null)
        {
            return null;
        }
        List<String> zh = parse(content, part.size(), offset);
        if (zh != null)
        {
            return zh;
        }
        for (int attempt = 0; attempt < RETRY; attempt++)
        {
            sleep(BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]);
            content = request(part, offset, apiKey, cfg);
            if (content == null)
            {
                continue;
            }
            zh = parse(content, part.size(), offset);
            if (zh != null)
            {
                return zh;
            }
        }
        return null;
    }

    /** 发一次请求，返回 message.content；网络/HTTP 层失败返回 null（不抛） */
    private String request(List<String> part, int offset, String apiKey, SubtitleProperties.Translate cfg)
    {
        String body = buildBody(part, offset, cfg);
        try
        {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl()))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200)
            {
                log.warn("翻译接口返回 {} lines={}", resp.statusCode(), part.size());
                return null;
            }
            JSONObject obj = JSON.parseObject(resp.body());
            JSONArray choices = obj == null ? null : obj.getJSONArray("choices");
            if (choices == null || choices.isEmpty())
            {
                log.warn("翻译接口没有 choices lines={}", part.size());
                return null;
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message == null ? null : message.getString("content");
            return content == null || content.trim().isEmpty() ? null : content;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (Exception e)
        {
            log.warn("翻译请求失败 {} lines={}", e.getClass().getSimpleName(), part.size());
            return null;
        }
    }

    private String buildBody(List<String> part, int offset, SubtitleProperties.Translate cfg)
    {
        JSONArray input = new JSONArray();
        int chars = 0;
        for (int i = 0; i < part.size(); i++)
        {
            JSONObject item = new JSONObject();
            item.put("i", offset + i);
            item.put("ja", part.get(i) == null ? "" : part.get(i));
            input.add(item);
            chars += part.get(i) == null ? 0 : part.get(i).length();
        }
        JSONObject body = new JSONObject();
        body.put("model", cfg.getModel());
        body.put("temperature", 0.2);
        body.put("max_tokens", Math.max(256, (int) (chars * 2.5)));
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", SYSTEM);
        messages.add(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", input.toJSONString());
        messages.add(user);
        body.put("messages", messages);
        return body.toJSONString();
    }

    /** 解析并校验：条数与 i 集合都必须与输入一致，否则返回 null（调用方整批重试） */
    private List<String> parse(String content, int size, int offset)
    {
        String json = stripFence(content);
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start)
        {
            return null;
        }
        JSONArray arr;
        try
        {
            arr = JSON.parseArray(json.substring(start, end + 1));
        }
        catch (Exception e)
        {
            return null;
        }
        if (arr == null || arr.size() != size)
        {
            return null;
        }
        Map<Integer, String> byIndex = new HashMap<>();
        for (int i = 0; i < arr.size(); i++)
        {
            JSONObject item = arr.getJSONObject(i);
            if (item == null || item.get("i") == null)
            {
                return null;
            }
            byIndex.put(item.getIntValue("i"), item.getString("zh") == null ? "" : item.getString("zh"));
        }
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            String zh = byIndex.get(offset + i);
            if (zh == null)
            {
                return null;
            }
            out.add(zh);
        }
        return out;
    }

    /** 去掉 markdown 代码块围栏（模型偶尔会加） */
    private static String stripFence(String content)
    {
        String text = content == null ? "" : content.trim();
        if (!text.startsWith("```"))
        {
            return text;
        }
        int firstLine = text.indexOf('\n');
        if (firstLine < 0)
        {
            return text;
        }
        String body = text.substring(firstLine + 1);
        int fence = body.lastIndexOf("```");
        return (fence >= 0 ? body.substring(0, fence) : body).trim();
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
