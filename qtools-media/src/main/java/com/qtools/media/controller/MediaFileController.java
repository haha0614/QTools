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
import com.qtools.media.service.IMediaFileService;

/**
 * 媒体文件流 对外公开接口（用户端免登录）
 *
 * <p>行为对齐 Node 版 server.js 的 /media、/api/sub、/thumb/**:</p>
 * <ul>
 *   <li>/file：字节级 Range（ResourceRegion + HttpRange），200/206/416/If-Range/HEAD 全覆盖；</li>
 *   <li>/sub：.vtt 直传，.srt 内存转 VTT；</li>
 *   <li>/thumb 与 /preview：thumbs 目录下 jpg，长缓存。</li>
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

    /** 缩略图缓存一周 */
    private static final String THUMB_CACHE = "public, max-age=604800";

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

    /**
     * 字节级 Range 流（等价 Node 版 /media）
     */
    @GetMapping("/file")
    public void file(@RequestParam(value = "p", required = false) String p, HttpServletRequest request,
            HttpServletResponse response) throws IOException
    {
        long begin = System.currentTimeMillis();
        Path path = mediaFileService.resolveMedia(p);
        long size = Files.size(path);
        long mtime = Files.getLastModifiedTime(path).toMillis();
        String etag = '"' + Long.toHexString(size) + '-' + Long.toHexString(mtime) + '"';
        boolean head = isHead(request);

        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", etag);
        response.setHeader("Last-Modified", httpDate(mtime));
        response.setHeader("Cache-Control", FILE_CACHE);
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
            log.debug("媒体文件 416 p={} size={}", p, size);
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
        log.debug("媒体文件 {} p={} range={} len={} cost={}ms", response.getStatus(), p,
                response.getHeader("Content-Range"), count, System.currentTimeMillis() - begin);
    }

    /**
     * 字幕：.srt 内存转 VTT，其余按 VTT 直传（缺头时补 WEBVTT）
     */
    @GetMapping("/sub")
    public void sub(@RequestParam(value = "p", required = false) String p, HttpServletRequest request,
            HttpServletResponse response) throws IOException
    {
        Path path = mediaFileService.resolveMedia(p);
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

    /** 视频缩略图 thumbs/v/{id}.jpg */
    @GetMapping("/thumb/v/{id}")
    public ResponseEntity<Resource> thumbVideo(@PathVariable("id") String id)
    {
        return imageResponse(mediaFileService.resolveThumbVideo(id));
    }

    /** 相册缩略图 thumbs/p/{albumId}/{i}.jpg */
    @GetMapping("/thumb/p/{albumId}/{i}")
    public ResponseEntity<Resource> thumbPhoto(@PathVariable("albumId") String albumId, @PathVariable("i") String i)
    {
        return imageResponse(mediaFileService.resolveThumbPhoto(albumId, i, false));
    }

    /** 相册预览图 thumbs/pv/{albumId}/{i}.jpg */
    @GetMapping("/preview/p/{albumId}/{i}")
    public ResponseEntity<Resource> previewPhoto(@PathVariable("albumId") String albumId, @PathVariable("i") String i)
    {
        return imageResponse(mediaFileService.resolveThumbPhoto(albumId, i, true));
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

    private ResponseEntity<Resource> imageResponse(Path path)
    {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, THUMB_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
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
