package com.qtools.tool.watermark.parser;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qtools.common.exception.ServiceException;
import com.qtools.tool.watermark.config.WatermarkProperties;
import com.qtools.tool.watermark.domain.vo.WatermarkMediaItem;
import com.qtools.tool.watermark.domain.vo.WatermarkParseVO;
import com.qtools.tool.watermark.spi.WatermarkParser;

/**
 * 抖音解析器
 *
 * <p><b>为什么走浏览器侧车</b>：www.douyin.com 的 aweme/detail 接口在缺 uifid/a_bogus 时
 * 直接 403（Blocked by ArgusSecurityPlugin），分享页的 _ROUTER_DATA 也只剩配置、
 * 不再带 videoInfoRes。所以取数交给 doc/watermark/douyin-parse.mjs：用 playwright-core
 * 驱动本机 Chrome 打开作品页，旁听浏览器自己发的那次 aweme/detail 响应。
 * 签名由浏览器自己算，平台改算法不用改这里。</p>
 *
 * <p><b>为什么取 play_addr</b>：video.play_addr 系播放流，平台不加水印；
 * video.download_addr 才是带 logo + 抖音号 + 昵称的下载流。侧车只认 play_addr 家族。</p>
 *
 * @author qtools
 */
@Component
public class DouyinParser implements WatermarkParser
{
    private static final Logger log = LoggerFactory.getLogger(DouyinParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Autowired
    private WatermarkProperties properties;

    @Override
    public boolean supports(String url)
    {
        return url != null && url.toLowerCase().contains("douyin.com");
    }

    @Override
    public String platformName()
    {
        return "抖音";
    }

    @Override
    public WatermarkParseVO parse(String url)
    {
        ensureService();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("url", url.trim());
        HttpResponse<String> response;
        try
        {
            response = httpClient.send(HttpRequest.newBuilder(URI.create(properties.getServiceUrl() + "/parse"))
                    .timeout(Duration.ofMillis(properties.getParseTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("解析被中断");
        }
        catch (Exception e)
        {
            throw new ServiceException("解析服务无响应（" + e.getMessage() + "）");
        }

        JsonNode root;
        try
        {
            root = objectMapper.readTree(response.body());
        }
        catch (Exception e)
        {
            throw new ServiceException("解析服务返回了非 JSON 内容");
        }
        if (!root.path("ok").asBoolean(false))
        {
            String error = root.path("error").asText("解析失败");
            throw new ServiceException(error);
        }
        log.info("抖音解析成功，耗时 {}ms", root.path("ms").asLong(0L));
        return toVO(root.path("data"));
    }

    /**
     * 把侧车返回的 data 节点映射成对外 VO
     *
     * @param data 侧车 data 节点
     * @return VO
     */
    private WatermarkParseVO toVO(JsonNode data)
    {
        WatermarkParseVO vo = new WatermarkParseVO();
        vo.setPlatform(text(data.path("platform")));
        vo.setVideoId(text(data.path("videoId")));
        vo.setTitle(text(data.path("title")));
        vo.setAuthor(text(data.path("author")));
        vo.setCover(text(data.path("cover")));
        vo.setDuration(data.path("duration").isNull() ? null : Long.valueOf(data.path("duration").asLong(0L)));
        vo.setWidth(Integer.valueOf(data.path("width").asInt(0)));
        vo.setHeight(Integer.valueOf(data.path("height").asInt(0)));

        List<WatermarkMediaItem> items = new ArrayList<>();
        for (JsonNode item : data.path("items"))
        {
            items.add(new WatermarkMediaItem(text(item.path("type")), text(item.path("label")), text(item.path("url"))));
        }
        vo.setItems(items);
        return vo;
    }

    /**
     * 安全取文本，缺失节点返回 null
     *
     * @param node 节点
     * @return 文本
     */
    private String text(JsonNode node)
    {
        if (node == null || node.isMissingNode() || node.isNull())
        {
            return null;
        }
        String value = node.asText();
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 确保侧车在跑：先探活，探不到就拉起 Node 进程并等它就绪
     */
    private synchronized void ensureService()
    {
        if (probeHealth())
        {
            return;
        }
        if (!properties.isAutoStart())
        {
            throw new ServiceException("去水印侧车未启动，请运行 doc/watermark/douyin-parse.mjs --serve");
        }
        startSidecar();
        long deadline = System.currentTimeMillis() + 25000L;
        while (System.currentTimeMillis() < deadline)
        {
            if (probeHealth())
            {
                log.info("去水印侧车已就绪");
                return;
            }
            try
            {
                Thread.sleep(500L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ServiceException("去水印侧车启动超时，请检查 Chrome 是否已安装");
    }

    /**
     * 探活：GET /health，800ms 内必须有回应
     *
     * @return 是否可用
     */
    private boolean probeHealth()
    {
        try
        {
            HttpResponse<String> r = httpClient.send(HttpRequest.newBuilder(URI.create(properties.getServiceUrl() + "/health"))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * 拉起侧车进程（独立进程，不随 JVM 退出被强杀）
     */
    private void startSidecar()
    {
        File node = new File(properties.getNodePath());
        File script = new File(properties.getScriptPath());
        if (!node.exists())
        {
            throw new ServiceException("找不到 Node：" + node.getAbsolutePath());
        }
        if (!script.exists())
        {
            throw new ServiceException("找不到侧车脚本：" + script.getAbsolutePath());
        }
        String port = properties.getServiceUrl().substring(properties.getServiceUrl().lastIndexOf(':') + 1);
        try
        {
            ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), script.getAbsolutePath(), "--serve", port);
            builder.directory(script.getParentFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("logs/watermark-sidecar.log")));
            builder.start();
            log.info("已拉起去水印侧车：{} {}", node.getAbsolutePath(), port);
        }
        catch (Exception e)
        {
            throw new ServiceException("拉起侧车失败：" + e.getMessage());
        }
    }
}
