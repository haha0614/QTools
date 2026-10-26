package com.qtools.media.controller;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaFrameService;
import com.qtools.media.support.MediaAvatarCrop;
import com.qtools.media.support.MediaFrameCommand;

/**
 * 媒体文件流 对外公开接口（用户端免登录）
 *
 * <p>行为对齐 Node 版 server.js 的 /media、/api/sub、/thumb/**:</p>
 * <ul>
 *   <li>/file：字节级 Range（ResourceRegion + HttpRange），200/206/416/If-Range/HEAD 全覆盖；</li>
 *   <li>/sub：.vtt 直传，.srt 内存转 VTT；</li>
 *   <li>/thumb 与 /preview：thumbs 目录下 jpg，长缓存；</li>
 *   <li>/sprite、/preview/{id}：视频精灵图与预览视频（build-thumbs.js 按内容寻址落盘，
 *       默认不生成，产物缺失即 404；预览视频复用 /file 的 Range 实现）。</li>
 *   <li>/frame/{id}?t=：P5.15 实时取帧（hover 精确到 1 秒），按整秒落盘永久缓存，
 *       命中缓存不启动 ffmpeg；ffmpeg 缺失 503、该秒没有帧 404、并发超限 429。</li>
 * </ul>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaFileController
{
    private static final Logger log = LoggerFactory.getLogger(MediaFileController.class);

    /** 文件流缓存（与 Node 版 /media 一致） */
    private static final String FILE_CACHE = "public, max-age=86400";

    /**
     * 内容寻址档：URL 里的定位符就是内容哈希，同一 URL 的内容永远不会变 → 可以永久缓存
     *
     * <p>{@code immutable} 让浏览器连刷新都不回源，这是内容寻址该有的性能。</p>
     */
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";

    /**
     * 非内容寻址档（册内序号 / 视频 id / 老命名）：同一个 URL 可能先后指向不同内容
     *
     * <p>这正是「缩略图 7 天缓存导致页面显示旧图」的根因：早先一律 {@code max-age=604800}，
     * 序号 URL 重建缩略图后内容变了而 URL 没变，浏览器继续拿旧图。这里改成每次都回源校验，
     * 再配合 ETag / Last-Modified 让校验以 304 收场，不必重下整张图。</p>
     */
    private static final String REVALIDATE_CACHE = "no-cache, must-revalidate";

    /** 内容哈希：build-thumbs.js 的落盘名，也是内容寻址 URL 里的定位符 */
    private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{16}$");

    /** 需要向跨域脚本暴露的响应头（Range 相关） */
    private static final String EXPOSE_HEADERS = "Accept-Ranges, Content-Range, Content-Length, ETag";

    private static final String VTT_CONTENT_TYPE = "text/vtt; charset=utf-8";

    private static final String JSON_UTF8 = "application/json; charset=utf-8";

    private static final int COPY_BUFFER = 64 * 1024;

    /** 区间判定结果：无区间（整份） */
    private static final int RANGE_NONE = 0;

    /** 区间判定结果：单区间，回 206 */
    private static final int RANGE_PARTIAL = 1;

    /** 区间判定结果：起点越界，回 416 */
    private static final int RANGE_UNSATISFIABLE = 2;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private IMediaFrameService mediaFrameService;

    /**
     * 字节级 Range 流（等价 Node 版 /media）
     *
     * <p>{@code r} 是可选的多库根 id（{@code media_root.id}）：带上就只在该根内找，
     * 不带（前端现状）则用配置 {@code qtools.media.rootPath}，找不到再依次试其它媒体根。</p>
     */
    @GetMapping("/file")
    public void file(@RequestParam(value = "p", required = false) String p,
            @RequestParam(value = "r", required = false) Integer r, HttpServletRequest request,
            HttpServletResponse response) throws IOException
    {
        long begin = System.currentTimeMillis();
        Path path = mediaFileService.resolveMedia(p, r);
        streamFile(path, request, response, "p=" + p, begin, FILE_CACHE);
    }

    /** 视频精灵图 thumbs/s/<content_hash>.jpg（回退 thumbs/s/<id>.jpg；无 hash 或文件不存在 → 404） */
    @GetMapping("/sprite/{id}")
    public ResponseEntity<Resource> sprite(@PathVariable("id") String id, HttpServletRequest request)
    {
        Path path = mediaFileService.resolveSprite(id);
        return imageResponse(path, isContentHash(path.getFileName().toString()), request);
    }

    /**
     * 视频预览视频 thumbs/pv2/<content_hash>.mp4｜.webm（回退 <id>.mp4｜.webm；都没有 → 404）
     *
     * <p><b>支持 Range</b>：直接把 /file 那套 200/206/416 实现拿来复用，
     * {@code <video preload="none">} 按比例 {@code currentTime} 拖动时浏览器才会发区间请求。</p>
     */
    @GetMapping("/preview/{id}")
    public void previewClip(@PathVariable("id") String id, HttpServletRequest request,
            HttpServletResponse response) throws IOException
    {
        long begin = System.currentTimeMillis();
        Path path = mediaFileService.resolvePreviewClip(id);
        // pv2 产物同样是内容寻址：命中 <hash>.mp4/.webm 就永久缓存，回退 <id>.* 就得回源校验
        boolean contentAddressed = isContentHash(path.getFileName().toString());
        streamFile(path, request, response, "preview=" + id, begin,
                contentAddressed ? IMMUTABLE_CACHE : REVALIDATE_CACHE);
    }

    /**
     * 视频实时取帧（P5.15）：{@code /media/frame/{id}?t=<秒>} → 该时刻的 480 宽 jpeg 一帧
     *
     * <p><b>为什么不预生成</b>：hover 要 &lt;1 秒精度，2 小时片就是 7200 格 —— 精灵图单图放不下，
     * 多图方案磁盘 1-2GB、生成 10 小时。所以改成按需实时取帧 + 按整秒永久缓存。</p>
     *
     * <p><b>缓存语义</b>：URL 里带的就是秒，同一个 URL 的字节永不变化 → {@code immutable} 长缓存，
     * 浏览器第二次根本不回源；服务端命中 {@code <thumbs>/frames/<hash>/<秒>.jpg} 时也不启动 ffmpeg。</p>
     *
     * <p><b>t 的越界选择：拒绝（400），不是夹取</b>。夹取会把 {@code t=999999} 这样的请求悄悄
     * 写成片尾那一秒的缓存（占盘且对调用方撒谎），而越界本身就是调用方的 bug。
     * 唯一例外是 {@code t == dur}：它夹到 {@code dur-1}（{@code -ss dur} 已越过最后一帧，ffmpeg 不出片）。</p>
     *
     * <p><b>P5.20 新增 {@code w}（输出宽度）</b>：同一个接口有两种用途 —— hover / 拖动跟随层是
     * 160×90 的卡片（480 足够），换流覆盖层要铺满整个舞台（480 放大就是用户在反馈里说的「低清」）。
     * 前端按舞台 CSS 宽度要 960-1280。宽度只影响 ffmpeg 的 {@code scale}，不参与任何缓存键：
     * 精确取帧那一支本来就不落盘，整秒那一支的缓存键仍是 {@code <秒>.jpg}（传了 w 也忽略，见下）。</p>
     *
     * @param id 视频 id（{@code sha1(rel)[:12]}）
     * @param t  目标时刻（秒，可带小数；同一秒内的小数命中同一份缓存）
     * @param w  输出宽度（可空；整数像素，{@code <= 0} 或缺失按 480，越界夹到 [480, 1280]）
     */
    @GetMapping("/frame/{id}")
    public ResponseEntity<byte[]> frame(@PathVariable("id") String id,
            @RequestParam(value = "t", required = false) String t,
            @RequestParam(value = "w", required = false) String w)
    {
        double raw = parseSecond(t);
        int dur = mediaFrameService.durationOf(id);
        if (dur > 0 && raw > dur)
        {
            throw MediaException.badRequest("t 越界：应在 [0, " + dur + "] 秒内");
        }
        // P5.18：带小数的 t = 精确取帧（换流覆盖层用），不落盘缓存 —— 落点每次都可能不同。
        // 整秒（hover / 拖动跟随层）仍走原来的「按秒永久缓存」口径，行为与请求 URL 都不变。
        if (isPrecise(raw))
        {
            byte[] exact = mediaFrameService.frameAt(id, raw, parseWidth(w));
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    // 不缓存：同一 id 的精确落点没有「内容寻址」性质，缓存会无上限增长
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentLength(exact.length)
                    .body(exact);
        }
        int sec = MediaFrameCommand.secondOf(raw, dur);
        byte[] bytes = mediaFrameService.frame(id, sec);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                // 按秒寻址：内容永不变化，永久缓存
                .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(bytes.length)
                .body(bytes);
    }

    /**
     * 输出宽度：缺失 / 非数字 / 非正数一律按 0（= 服务端默认 480）；正数原样交给服务层夹取
     *
     * <p>为什么不在这里夹：区间常量只有 {@code MediaFrameCommand} 一处（前后端与文档都引用它），
     * 这里再夹一次就会出现两个真相。</p>
     */
    private static int parseWidth(String w)
    {
        if (w == null || w.trim().isEmpty())
        {
            return 0;
        }
        try
        {
            int value = Integer.parseInt(w.trim());
            return value > 0 ? value : 0;
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /** t 是否带小数（= 精确取帧）。1e-6 秒以下是浮点噪声，仍按整秒口径走缓存 */
    private static boolean isPrecise(double raw)
    {
        return Math.abs(raw - Math.rint(raw)) > 1e-6;
    }

    /** 取帧参数 t：缺失 / 非数字 / 负数一律 400（越界判定留给 duration 已知时再做） */
    private static double parseSecond(String t)
    {
        if (t == null || t.trim().isEmpty())
        {
            throw MediaException.badRequest("missing t");
        }
        double value;
        try
        {
            value = Double.parseDouble(t.trim());
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("bad t");
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0)
        {
            throw MediaException.badRequest("bad t");
        }
        return value;
    }

    /**
     * 字节级 Range 落盘（/file 与 /preview 共用）
     *
     * @param label        日志用的资源标识
     * @param cacheControl 按「地址是否内容寻址」分级后的 Cache-Control 值
     */
    private void streamFile(Path path, HttpServletRequest request, HttpServletResponse response, String label,
            long begin, String cacheControl) throws IOException
    {
        long size = Files.size(path);
        long mtime = Files.getLastModifiedTime(path).toMillis();
        String etag = '"' + Long.toHexString(size) + '-' + Long.toHexString(mtime) + '"';
        boolean head = isHead(request);

        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", etag);
        response.setHeader("Last-Modified", httpDate(mtime));
        response.setHeader("Cache-Control", cacheControl);
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 跨域场景下浏览器要读到这些头才能用 Range/断点续传
        response.setHeader("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        response.setContentType(mediaFileService.contentType(path));

        RangeDecision decision = decideRange(request.getHeader(HttpHeaders.RANGE),
                request.getHeader(HttpHeaders.IF_RANGE), etag, mtime, size);

        if (decision.kind == RANGE_UNSATISFIABLE)
        {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + size);
            byte[] body = "{\"error\":\"range not satisfiable\"}".getBytes(StandardCharsets.UTF_8);
            response.setContentType(JSON_UTF8);
            response.setContentLength(body.length);
            if (!head)
            {
                response.getOutputStream().write(body);
            }
            log.debug("媒体文件 416 {} size={}", label, size);
            return;
        }

        long start = decision.kind == RANGE_PARTIAL ? decision.start : 0L;
        long count = decision.kind == RANGE_PARTIAL ? decision.end - decision.start + 1 : size;

        if (decision.kind == RANGE_PARTIAL)
        {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + (start + count - 1) + "/" + size);
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setContentLengthLong(count);

        if (!head && count > 0)
        {
            // 用 ResourceRegion 描述字节区间，再按 position/count 落盘到响应
            ResourceRegion region = new ResourceRegion(new FileSystemResource(path), start, count);
            writeRegion(region, response);
        }
        log.debug("媒体文件 {} {} range={} len={} cost={}ms", response.getStatus(), label,
                response.getHeader("Content-Range"), count, System.currentTimeMillis() - begin);
    }

    /**
     * 字幕：.srt 内存转 VTT，其余按 VTT 直传（缺头时补 WEBVTT）
     */
    @GetMapping("/sub")
    public void sub(@RequestParam(value = "p", required = false) String p,
            @RequestParam(value = "r", required = false) Integer r, HttpServletRequest request,
            HttpServletResponse response) throws IOException
    {
        Path path = mediaFileService.resolveMedia(p, r);
        // 用字节解码：非法 UTF-8 会被替换而不是抛异常，避免个别字幕整条 500
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        boolean srt = path.getFileName().toString().toLowerCase().endsWith(".srt");
        byte[] body = mediaFileService.toVtt(raw, srt).getBytes(StandardCharsets.UTF_8);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(VTT_CONTENT_TYPE);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(body.length);
        if (!isHead(request))
        {
            response.getOutputStream().write(body);
        }
    }

    /** 视频缩略图 thumbs/v/<content_hash>.jpg（回退 thumbs/v/<id>.jpg） */
    @GetMapping("/thumb/v/{id}")
    public ResponseEntity<Resource> thumbVideo(@PathVariable("id") String id, HttpServletRequest request)
    {
        Path path = mediaFileService.resolveThumbVideo(id);
        // 定位符是视频 id 而不是哈希，但命中的文件名可能是内容寻址的 <hash>.jpg —— 以实际文件为准
        return imageResponse(path, isContentHash(path.getFileName().toString()), request);
    }

    /**
     * 相册缩略图（内容寻址）thumbs/p/<content_hash>.jpg
     *
     * <p>P5.55：可选 {@code ?crop=x,y,w,h}（四个 0..1 的比例）→ 现裁一张小图并落盘缓存。
     * 不带这个参数时行为与之前完全一致（字节都一样，还是那个文件）。</p>
     */
    @GetMapping("/thumb/p/{i}")
    public ResponseEntity<Resource> thumbPhotoByHash(@PathVariable("i") String i,
            @RequestParam(value = "crop", required = false) String crop, HttpServletRequest request)
    {
        Path path = mediaFileService.resolveThumbPhotoByHash(i, false);
        return croppedImageResponse(path, i, crop, request);
    }

    /** 相册预览图（内容寻址）thumbs/pv/<content_hash>.jpg */
    @GetMapping("/preview/p/{i}")
    public ResponseEntity<Resource> previewPhotoByHash(@PathVariable("i") String i, HttpServletRequest request)
    {
        Path path = mediaFileService.resolveThumbPhotoByHash(i, true);
        return imageResponse(path, isContentHash(i), request);
    }

    /**
     * 相册缩略图 thumbs/p/{albumId}/{i}.jpg（i = 册内序号或内容哈希，老 URL 兼容）
     *
     * <p>P5.55：可选 {@code ?crop=x,y,w,h}。人物头像的裁剪就走这条路
     * （{@code avatar_ref = a164:4#crop=...} → 后端拼成这条 URL 带 crop 参数），
     * 前端只负责显示，不自己算像素。</p>
     */
    @GetMapping("/thumb/p/{albumId}/{i}")
    public ResponseEntity<Resource> thumbPhoto(@PathVariable("albumId") String albumId, @PathVariable("i") String i,
            @RequestParam(value = "crop", required = false) String crop, HttpServletRequest request)
    {
        Path path = mediaFileService.resolveThumbPhoto(albumId, i, false);
        // 序号 URL 可能先指向 A 图、重建后指向 B 图，判定只看定位符本身，不看落盘命中的文件名
        return croppedImageResponse(path, albumId + "/" + i, crop, request);
    }

    /**
     * 带（可选）裁剪的图片响应
     *
     * <p>{@code crop} 为空 → 原样走 {@link #imageResponse}（老路径零变化）；
     * 有 → 现裁一张落到 {@code <thumbs>/avcrop/} 再返回（{@link MediaAvatarCrop#render} 内部按
     * 源文件 size+mtime 做键，缩略图重建后自动失效）。裁剪参数不合法 → 400，不静默退成整图
     * —— 用户明确选了一个框，悄悄给他整图才是更难查的错。</p>
     */
    private ResponseEntity<Resource> croppedImageResponse(Path src, String cacheKey, String cropParam,
            HttpServletRequest request)
    {
        String crop;
        try
        {
            crop = MediaAvatarCrop.normalize(cropParam);
        }
        catch (IllegalArgumentException e)
        {
            throw MediaException.badRequest(e.getMessage());
        }
        if (crop == null)
        {
            return imageResponse(src, isContentHash(src.getFileName().toString()), request);
        }
        Path out = MediaAvatarCrop.render(src, crop, mediaFileService.thumbsRoot(), cacheKey);
        return imageResponse(out, false, request);
    }

    /** 相册预览图 thumbs/pv/{albumId}/{i}.jpg（i = 册内序号或内容哈希，老 URL 兼容） */
    @GetMapping("/preview/p/{albumId}/{i}")
    public ResponseEntity<Resource> previewPhoto(@PathVariable("albumId") String albumId, @PathVariable("i") String i,
            HttpServletRequest request)
    {
        Path path = mediaFileService.resolveThumbPhoto(albumId, i, true);
        return imageResponse(path, isContentHash(i), request);
    }

    // ------------------------------------------------------------------ Range

    /**
     * 区间判定：与 Node 版 parseRange 语义对齐
     *
     * <ul>
     *   <li>无 Range / 语法错误 / 多区间 / If-Range 不匹配 → 整份 200；</li>
     *   <li>单区间且起点越界 → 416；</li>
     *   <li>单区间合法 → 206（即使恰好是整份，也与 Node 版一致）。</li>
     * </ul>
     */
    private RangeDecision decideRange(String rangeHeader, String ifRangeHeader, String etag, long mtime, long size)
    {
        if (rangeHeader == null || size <= 0)
        {
            return RangeDecision.NONE;
        }
        if (ifRangeHeader != null && !ifRangeMatches(ifRangeHeader, etag, mtime))
        {
            // 资源已变，按 RFC 退回整份
            return RangeDecision.NONE;
        }
        List<HttpRange> ranges;
        try
        {
            ranges = HttpRange.parseRanges(rangeHeader);
        }
        catch (IllegalArgumentException e)
        {
            // bytes=-0 这类"后缀长度为 0"按 RFC 属不可满足；其余语法错误忽略区间
            return SUFFIX_ZERO.matcher(rangeHeader.trim()).matches() ? RangeDecision.UNSATISFIABLE
                    : RangeDecision.NONE;
        }
        if (ranges.size() != 1)
        {
            return RangeDecision.NONE;
        }
        long start;
        long end;
        try
        {
            start = ranges.get(0).getRangeStart(size);
            end = Math.min(ranges.get(0).getRangeEnd(size), size - 1);
        }
        catch (IllegalArgumentException e)
        {
            return RangeDecision.UNSATISFIABLE;
        }
        if (start >= size)
        {
            return RangeDecision.UNSATISFIABLE;
        }
        if (end < start)
        {
            // 语义非法的区间（如 bytes=100-50）退回整份
            return RangeDecision.NONE;
        }
        return new RangeDecision(RANGE_PARTIAL, start, end);
    }

    private static final java.util.regex.Pattern SUFFIX_ZERO =
            java.util.regex.Pattern.compile("^bytes\\s*=\\s*-\\s*0+$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** If-Range：支持强 ETag 与 HTTP 日期（弱 ETag 一律视为不匹配） */
    private static boolean ifRangeMatches(String ifRange, String etag, long mtime)
    {
        String value = ifRange.trim();
        if (value.isEmpty() || value.startsWith("W/"))
        {
            return false;
        }
        if (value.startsWith("\""))
        {
            return value.equals(etag);
        }
        try
        {
            long since = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.floorDiv(since, 1000L) == Math.floorDiv(mtime, 1000L);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private void writeRegion(ResourceRegion region, HttpServletResponse response) throws IOException
    {
        long remaining = region.getCount();
        try (InputStream source = new BufferedInputStream(region.getResource().getInputStream(), COPY_BUFFER))
        {
            long toSkip = region.getPosition();
            while (toSkip > 0)
            {
                long skipped = source.skip(toSkip);
                if (skipped <= 0)
                {
                    return;
                }
                toSkip -= skipped;
            }
            ServletOutputStream out = response.getOutputStream();
            byte[] buffer = new byte[COPY_BUFFER];
            while (remaining > 0)
            {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0)
                {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
        catch (IOException e)
        {
            // 客户端中断（拖拽进度/关页面）属正常现象，不必污染错误日志
            log.debug("媒体文件传输中断: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 图片响应：Cache-Control 按「地址是否内容寻址」分两级
     *
     * <p>内容寻址（定位符就是 16 位内容哈希）→ {@link #IMMUTABLE_CACHE}，永久缓存；
     * 其余（册内序号 / 视频 id / 老命名回退）→ {@link #REVALIDATE_CACHE}，每次回源校验，
     * 命中 ETag 或 Last-Modified 时回 304，不重下整张图。</p>
     *
     * <p>注意判定依据是<b>URL 里的定位符</b>，不是落盘命中的文件名：序号 URL 重建后可能
     * 命中 {@code p/<hash>.jpg}，但那个 URL 本身仍不是内容寻址的，必须继续回源校验——
     * 否则又回到「URL 不变内容变、浏览器用旧图」的老坑。</p>
     */
    private ResponseEntity<Resource> imageResponse(Path path, boolean contentAddressed, HttpServletRequest request)
    {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, contentAddressed ? IMMUTABLE_CACHE : REVALIDATE_CACHE)
                .header("X-Content-Type-Options", "nosniff");
        if (contentAddressed)
        {
            // 永久缓存档不需要校验器：浏览器不会再回来问
            return builder.body(new FileSystemResource(path));
        }
        String etag;
        long mtime;
        try
        {
            mtime = Files.getLastModifiedTime(path).toMillis();
            etag = '"' + Long.toHexString(Files.size(path)) + '-' + Long.toHexString(mtime) + '"';
        }
        catch (IOException e)
        {
            log.debug("缩略图校验器生成失败 {}: {}", path, e.getMessage());
            return builder.body(new FileSystemResource(path));
        }
        builder.header(HttpHeaders.ETAG, etag).header(HttpHeaders.LAST_MODIFIED, httpDate(mtime));
        if (notModified(request, etag, mtime))
        {
            return ResponseEntity.<Resource>status(HttpServletResponse.SC_NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, REVALIDATE_CACHE)
                    .header(HttpHeaders.ETAG, etag)
                    .build();
        }
        return builder.body(new FileSystemResource(path));
    }

    /**
     * 定位符是否内容哈希：截掉扩展名后匹配 {@code ^[0-9a-f]{16}$}
     *
     * <p>16 位小写 hex 是 build-thumbs.js 的落盘名约定；视频 id 是
     * {@code sha1(rel_path)[:12]}（12 位）、相册 id 是 {@code a006}，都不会误判。</p>
     */
    private static boolean isContentHash(String name)
    {
        if (name == null)
        {
            return false;
        }
        int dot = name.indexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        return CONTENT_HASH.matcher(stem).matches();
    }

    /** If-None-Match / If-Modified-Since：命中返回 true（调用方回 304） */
    private static boolean notModified(HttpServletRequest request, String etag, long mtime)
    {
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null)
        {
            for (String candidate : ifNoneMatch.split(","))
            {
                String value = candidate.trim();
                if (value.startsWith("W/"))
                {
                    value = value.substring(2);
                }
                if (etag.equals(value))
                {
                    return true;
                }
            }
            return false;
        }
        String ifModifiedSince = request.getHeader(HttpHeaders.IF_MODIFIED_SINCE);
        if (ifModifiedSince == null)
        {
            return false;
        }
        try
        {
            long since = ZonedDateTime.parse(ifModifiedSince.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return Math.floorDiv(since, 1000L) >= Math.floorDiv(mtime, 1000L);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean isHead(HttpServletRequest request)
    {
        return "HEAD".equalsIgnoreCase(request.getMethod());
    }

    private static String httpDate(long epochMillis)
    {
        return DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
    }

    /** Range 判定结果 */
    private static final class RangeDecision
    {
        private static final RangeDecision NONE = new RangeDecision(RANGE_NONE, 0L, 0L);

        private static final RangeDecision UNSATISFIABLE = new RangeDecision(RANGE_UNSATISFIABLE, 0L, 0L);

        private final int kind;

        private final long start;

        /** 闭区间右端 */
        private final long end;

        private RangeDecision(int kind, long start, long end)
        {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }
    }
}
