package com.qtools.media.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.service.IMediaRatingService;
import com.qtools.media.exception.MediaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 图片内容自动分级（P6.24）：把一册图片的缩略图交给本机 NSFW 模型逐张判「露点」。
 *
 * <p>为什么不塞进浏览器：nsfwjs 的权重是 UMD 分片，打进前端会把三个模型（约 40MB）一起发到
 * 每个管理端浏览器；放服务端则只加载一次，一个批次（默认 30 张）约 20 秒。</p>
 *
 * <p>为什么分批：一册 1166 张跑完要 13 分钟，一次性同步请求必然超时。所以由前端按
 * {@code offset/limit} 连续调用，每批返回 {@code nextOffset}；每批的结果立刻落库，
 * 中途关掉弹窗也不会丢已经判完的部分。</p>
 *
 * <p>只写「限制」和「指导」两档：<b>绝不写 general</b>——图只能比册更严，
 * 把一张图主动"降"成大众级不在本功能的语义里。返回里的 general/review 只是给人看的分布。</p>
 *
 * @author qtools
 */
@Service
public class MediaPhotoClassifyService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPhotoClassifyService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次最多判多少张：30 张 ≈ 20 秒，60 张就要 40 秒，再大就要撞代理/网关超时 */
    private static final int MAX_LIMIT = 60;
    private static final int DEFAULT_LIMIT = 30;
    /** 写库时每批多少 id（批量接口上限 2000，这里保守取 500） */
    private static final int APPLY_CHUNK = 500;
    /** 子进程总超时：30 张 20 秒，留足冷启动与慢盘的余量 */
    private static final int PROCESS_TIMEOUT_SECONDS = 300;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IMediaRatingService mediaRatingService;

    /**
     * 判一批图片。
     *
     * @param albumId 相册 id（必填）
     * @param offset  从第几张开始（按 seq 升序）
     * @param limit   本批最多多少张（1..60，默认 30）
     * @param dryRun  默认 true = 只预览不写库；显式传 false 才写
     */
    public Map<String, Object> classify(String albumId, Integer offset, Integer limit, Boolean dryRun)
    {
        if (albumId == null || albumId.trim().isEmpty())
        {
            throw MediaException.badRequest("albumId 不能为空");
        }
        final int off = Math.max(0, offset == null ? 0 : offset);
        final int lim = Math.min(MAX_LIMIT, Math.max(1, limit == null ? DEFAULT_LIMIT : limit));
        final boolean apply = Boolean.FALSE.equals(dryRun);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer total = jdbc.queryForObject(
                "select count(*) from media_photo where album_id = ? and content_hash is not null and coalesce(missing, 0) = 0",
                Integer.class, albumId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, seq, content_hash from media_photo where album_id = ? and content_hash is not null"
                        + " and coalesce(missing, 0) = 0 order by seq, id limit ? offset ?",
                albumId, lim, off);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("albumId", albumId);
        out.put("offset", off);
        out.put("limit", lim);
        out.put("total", total == null ? 0 : total);
        out.put("nextOffset", off + rows.size());
        out.put("done", total == null || off + rows.size() >= total);
        out.put("dryRun", !apply);

        // P6.32：以模型为准 —— 三档全部显式写库（不写继承），报告也按模型的三档计数。
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("restricted", 0);
        counts.put("guidance", 0);
        counts.put("general", 0);
        counts.put("review", 0);   // 模型跑出来了但置信度不够：不写库，交人工
        counts.put("failed", 0);   // 这一格压根没判出来（缩略图缺失 / 推理报错）
        out.put("counts", counts);

        if (rows.isEmpty())
        {
            out.put("checked", 0);
            out.put("samples", new ArrayList<>());
            out.put("reviewSamples", new ArrayList<>());
            out.put("applied", apply ? new LinkedHashMap<>() : null);
            return out;
        }

        Path listFile = null;
        try
        {
            List<Map<String, Object>> payload = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows)
            {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("seq", row.get("seq"));
                item.put("hash", row.get("content_hash"));
                payload.add(item);
            }
            listFile = Files.createTempFile("qt-photo-classify-", ".json");
            MAPPER.writeValue(listFile.toFile(), payload);

            List<Map<String, Object>> results = runClassifier(listFile);
            // P6.36：模型结论落库（auto_rating / auto_porn / auto_at）—— 复核队列要持久，
            // 不能只活在这一次扫描的返回值里；人工定档时会把 auto_rating 一并改成人工档位。
            if (!results.isEmpty())
            {
                List<Object[]> autoArgs = new ArrayList<>(results.size());
                for (Map<String, Object> r : results)
                {
                    autoArgs.add(new Object[] { String.valueOf(r.get("rating")), r.get("porn"),
                            String.valueOf(r.get("id")) });
                }
                try
                {
                    jdbc.batchUpdate(
                            "update media_photo set auto_rating = ?, auto_porn = ?, auto_at = now() where id = ?",
                            autoArgs);
                }
                catch (RuntimeException e)
                {
                    log.warn("自动分级结果落库失败（不影响本次判定）: {}", e.getMessage());
                }
            }

            Map<String, List<String>> idsByRating = new LinkedHashMap<>();
            List<Map<String, Object>> samples = new ArrayList<>();
            // P6.34：待复核也要能把图带回去 —— 只有计数没有入口等于没有复核
            List<Map<String, Object>> reviewSamples = new ArrayList<>();
            for (Map<String, Object> r : results)
            {
                String verdict = String.valueOf(r.get("rating"));
                if (!counts.containsKey(verdict))
                {
                    continue;
                }
                counts.put(verdict, counts.get(verdict) + 1);
                if ("review".equals(verdict))
                {
                    // 判不了：不写库，把整条（id/seq/分数）带回前端做人工复核清单
                    if (reviewSamples.size() < lim)
                    {
                        reviewSamples.add(r);
                    }
                    continue;
                }
                // 三档显式写库（不写继承）
                idsByRating.computeIfAbsent(verdict, k -> new ArrayList<>())
                        .add(String.valueOf(r.get("id")));
                if (samples.size() < 24 && !"general".equals(verdict) && !"review".equals(verdict))
                {
                    samples.add(r);
                }
            }
            counts.put("failed", Math.max(0, rows.size() - results.size()));
            out.put("checked", results.size());
            out.put("samples", samples);
            out.put("reviewSamples", reviewSamples);

            if (apply)
            {
                Map<String, Integer> applied = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : idsByRating.entrySet())
                {
                    String rating = e.getKey();
                    List<String> ids = e.getValue();
                    int written = 0;
                    for (int i = 0; i < ids.size(); i += APPLY_CHUNK)
                    {
                        List<String> chunk = ids.subList(i, Math.min(ids.size(), i + APPLY_CHUNK));
                        try
                        {
                            mediaRatingService.batch("photo", chunk, rating);
                            written += chunk.size();
                        }
                        catch (RuntimeException ex)
                        {
                            log.warn("批量写图片分级失败（rating={}, {} 张）: {}", rating, chunk.size(), ex.getMessage());
                        }
                    }
                    applied.put(rating, written);
                }
                out.put("applied", applied);
            }
            else
            {
                out.put("applied", null);
                // 预览态把命中的 id 一并带回去：前端点「写入」时直接走既有的 /rating/batch，
                // 不用为了拿 id 再把 1166 张图跑第二遍模型。
                out.put("ids", idsByRating);
            }
            return out;
        }
        catch (MediaException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("图片自动分级失败: album={}, offset={}, limit={}", albumId, off, lim, e);
            throw MediaException.serviceUnavailable("图片自动分级失败：" + e.getMessage());
        }
        finally
        {
            if (listFile != null)
            {
                try
                {
                    Files.deleteIfExists(listFile);
                }
                catch (Exception ignore)
                {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    /** 分级严格度：general 0 < guidance 1 < restricted 2 */
    private static int rank(String rating)
    {
        String value = rating == null ? "" : rating.trim().toLowerCase(java.util.Locale.ROOT);
        if ("restricted".equals(value)) return 2;
        if ("guidance".equals(value)) return 1;
        return 0;
    }

    /** 起一个 node 子进程跑 classify-photos.js，逐行收 RESULT */
    private List<Map<String, Object>> runClassifier(Path listFile) throws Exception
    {
        String node = mediaProperties.getNodePath();
        if (node == null || node.trim().isEmpty())
        {
            throw MediaException.serviceUnavailable("nodePath 未配置：自动分级需要本机 node 运行 nsfwjs");
        }
        String home = mediaProperties.getHome();
        String script = home + "/QTools/doc/media-import/classify-photos.js";
        if (!Files.isRegularFile(java.nio.file.Paths.get(script)))
        {
            throw MediaException.serviceUnavailable("找不到分级脚本：" + script);
        }
        String thumbs = mediaProperties.getThumbsPath();
        if (thumbs == null || thumbs.trim().isEmpty())
        {
            throw MediaException.serviceUnavailable("thumbsPath 未配置：分级读的是 400px 缩略图");
        }

        List<String> cmd = Arrays.asList(node, script,
                "--photos=" + listFile.toAbsolutePath(),
                "--thumbs=" + thumbs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<Map<String, Object>> results = new ArrayList<>();
        StringBuilder tail = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("RESULT "))
                {
                    try
                    {
                        results.add(MAPPER.readValue(line.substring(7), Map.class));
                    }
                    catch (Exception ignore)
                    {
                        // 单行解析失败不影响其它张
                    }
                }
                else if (!line.startsWith("ERROR "))
                {
                    tail.append(line).append('\n');
                    if (tail.length() > 4000)
                    {
                        tail.delete(0, tail.length() - 4000);
                    }
                }
            }
        }
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            process.destroyForcibly();
            throw MediaException.serviceUnavailable("自动分级超时（超过 " + PROCESS_TIMEOUT_SECONDS + " 秒）");
        }
        if (process.exitValue() != 0)
        {
            log.warn("classify-photos.js 退出码 {}", process.exitValue());
        }
        return results;
    }
}







