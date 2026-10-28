package com.qtools.media.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaTagNormalizeService;
import com.qtools.media.support.MediaScanArgs;
import com.qtools.media.support.MediaToolEnv;

/**
 * 标签原子化迁移实现：{@code node normalize-tags.js --dry-run|--apply --json-out=<tmp>}
 *
 * <p>为什么同步执行、不进「维护任务」那套任务队列：一次迁移的全部工作量是
 * 几百行标签行的重算（实测 239 视频 / 690 行 → 全量 apply 1 秒内），
 * 而管理端要的是「先看统计、再决定是否执行」的两次交互 ——
 * 拆成队列任务反而要额外做「等它跑完再取结果」的轮询。</p>
 *
 * <p>参数只走白名单：{@code --dry-run} / {@code --apply} / {@code --json-out=} 由本类决定，
 * 配置里的 {@code tagNormalizeArgs} 只能补部署参数（{@code --port=} / {@code --secret=} / {@code --map=}）。</p>
 *
 * @author qtools
 */
@Service
public class MediaTagNormalizeServiceImpl implements IMediaTagNormalizeService
{
    private static final Logger log = LoggerFactory.getLogger(MediaTagNormalizeServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 代码接管：配置里出现也忽略（dryRun 只由请求决定；--json-out= 是本类的临时文件） */
    private static final List<String> FORBIDDEN_PREFIXES = List.of("--dry-run", "--apply", "--json-out=");

    /** 永不接受的开关（脚本没有这些参数，但配置漂移时不该让它们混进命令行） */
    private static final List<String> FORBIDDEN_ARGS = List.of("--truncate", "--yes");

    /** 回给前端的 stdout 行数上限：管理端只展示尾部，全量日志进不了前端也没意义 */
    private static final int TAIL_LINES = 200;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaIndexService mediaIndexService;

    @Override
    public Map<String, Object> normalize(boolean dryRun)
    {
        Path jsonOut = null;
        try
        {
            jsonOut = Files.createTempFile("tag-normalize-", ".json");
            List<String> command = buildCommand(dryRun, jsonOut);
            RunResult run = execute(command);
            Map<String, Object> stats = readStats(jsonOut);
            if (run.exitCode != 0 || stats == null)
            {
                throw new MediaException(500, "标签规范化脚本执行失败（退出码 " + run.exitCode + "）：\n" + tail(run.output));
            }
            if (!dryRun)
            {
                // 标签变了必须让索引快照立刻失效，否则站端筛选侧栏最多 60 秒还是旧标签
                mediaIndexService.invalidateSnapshot();
                log.info("媒体库标签规范化已执行：{}", stats.get("changes"));
            }
            Map<String, Object> out = new LinkedHashMap<>(stats);
            out.put("mode", dryRun ? "dry-run" : "apply");
            out.put("exitCode", run.exitCode);
            out.put("stdout", tail(run.output));
            return out;
        }
        catch (IOException e)
        {
            throw new MediaException(500, "标签规范化失败：" + e.getMessage());
        }
        finally
        {
            if (jsonOut != null)
            {
                try
                {
                    Files.deleteIfExists(jsonOut);
                }
                catch (IOException e)
                {
                    log.warn("临时统计文件删不掉：{}（{}）", jsonOut, e.getMessage());
                }
            }
        }
    }

    /** node + 脚本 + --mysql=<配置> + tagNormalizeArgs + --dry-run|--apply + --json-out=<tmp> */
    private List<String> buildCommand(boolean dryRun, Path jsonOut)
    {
        String node = mediaProperties.getNodePath();
        requireConfigured(node, "nodePath");
        if (!new File(node).isFile())
        {
            throw new MediaException(500, "qtools.media.nodePath 不存在: " + node);
        }
        String script = mediaProperties.getTagNormalizeScript();
        requireConfigured(script, "tagNormalizeScript");
        if (!new File(script).isFile())
        {
            throw new MediaException(500, "qtools.media.tagNormalizeScript 不存在: " + script);
        }

        List<String> command = new ArrayList<>();
        command.add(node);
        command.add(script);
        String mysqlClient = mediaProperties.getMysqlClient();
        if (mysqlClient != null && !mysqlClient.trim().isEmpty())
        {
            // 与 scanArgs 同口径：mysql 不在 PATH 的机器必须靠这个绝对路径
            command.add("--mysql=" + mysqlClient.trim());
        }
        MediaScanArgs.appendConfigured(command, mediaProperties.getTagNormalizeArgs(), FORBIDDEN_ARGS,
                List.of(), FORBIDDEN_PREFIXES);
        command.add(dryRun ? "--dry-run" : "--apply");
        command.add("--json-out=" + jsonOut.toAbsolutePath());
        return command;
    }

    private RunResult execute(List<String> command)
    {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // MS_MYSQL：脚本认这个环境变量（与扫描 / 缩略图脚本同一个注入点）
        MediaToolEnv.applyMysql(builder, mediaProperties);
        Process process;
        try
        {
            process = builder.start();
        }
        catch (IOException e)
        {
            throw new MediaException(500, "启动标签规范化脚本失败：" + e.getMessage());
        }

        StringBuilder buf = new StringBuilder();
        Thread drain = new Thread(() ->
        {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    synchronized (buf)
                    {
                        buf.append(line).append('\n');
                    }
                }
            }
            catch (IOException ignored)
            {
                // 进程被强杀时流会异常关闭，输出已经收到的部分仍然有用
            }
        });
        drain.setDaemon(true);
        drain.start();

        int timeout = mediaProperties.getTagNormalizeTimeoutSeconds();
        boolean finished;
        try
        {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new MediaException(500, "标签规范化被中断");
        }
        if (!finished)
        {
            process.destroyForcibly();
            throw new MediaException(504, "标签规范化超过 " + timeout + " 秒未结束，已强杀子进程"
                    + "（qtools.media.tagNormalizeTimeoutSeconds）");
        }
        try
        {
            drain.join(5000L);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        String output;
        synchronized (buf)
        {
            output = buf.toString();
        }
        return new RunResult(process.exitValue(), output);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readStats(Path jsonOut)
    {
        try
        {
            if (!Files.isRegularFile(jsonOut))
            {
                return null;
            }
            return MAPPER.readValue(jsonOut.toFile(), Map.class);
        }
        catch (IOException e)
        {
            log.warn("标签规范化统计 JSON 读不出来：{}（{}）", jsonOut, e.getMessage());
            return null;
        }
    }

    private static String tail(String output)
    {
        if (output == null || output.isEmpty())
        {
            return "";
        }
        String[] lines = output.split("\n", -1);
        int from = Math.max(0, lines.length - TAIL_LINES);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < lines.length; i++)
        {
            out.append(lines[i]).append('\n');
        }
        return out.toString();
    }

    private static void requireConfigured(String value, String key)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new MediaException(500, "未配置 qtools.media." + key + "（请在 application.yml 中提供）");
        }
    }

    /** 子进程执行结果：退出码 + 合并后的 stdout/stderr */
    private static final class RunResult
    {
        private final int exitCode;

        private final String output;

        private RunResult(int exitCode, String output)
        {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
