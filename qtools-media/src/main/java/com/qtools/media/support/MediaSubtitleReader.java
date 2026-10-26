package com.qtools.media.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaFileService;

/**
 * 字幕素材读取（P5.53 交付 2）：{@code media_video.sub_path} → 磁盘文件 → 纯文本节选。
 *
 * <h2>路径解析一律走文件服务</h2>
 *
 * <p>字幕和视频在同一个媒体根下（{@code sub_path} 是相对媒体根的路径，如
 * {@code ASMR/ASMR_剧情_灰丝床戏_15m.vtt}），解析必须经过 {@link IMediaFileService#resolveMedia}：
 * 那里有「拒绝绝对路径 / UNC」「realpath 后仍在根内」「多库根兜底」三道校验。自己拼
 * {@code rootPath + sub_path} 会绕过它们，等于给数据库里的脏数据开一条读任意文件的口子。</p>
 *
 * <h2>编码</h2>
 *
 * <p>现场的字幕有三种来源：扫描脚本抽的（UTF-8）、网上下载的（常见 GB18030）、Windows 记事本
 * 另存的（UTF-8 with BOM）。这里按「BOM → 严格 UTF-8 → GB18030」顺序解码，严格 UTF-8 用
 * {@code REPORT} 而不是默认的 {@code REPLACE}：默认解码器遇到 GB18030 的中文不会报错，
 * 只会把每个字变成 {@code ?}，那种「解析成功但正文全是问号」最难查。</p>
 *
 * @author qtools
 */
@Component
public class MediaSubtitleReader
{
    private static final Logger log = LoggerFactory.getLogger(MediaSubtitleReader.class);

    /** 单个字幕文件的读取上限（4MB）：正常字幕 &lt; 200KB，超限多半不是字幕而是误标 */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    /** sub_path 缺失时尝试的同名扩展名（扫描脚本有时只登记了 .vtt，磁盘上还有 .srt） */
    private static final String[] FALLBACK_EXTS = { ".vtt", ".srt", ".ass", ".ssa" };

    @Autowired
    private IMediaFileService mediaFileService;

    /** 一个字幕素材的读取结果（不可用时就带 reason，不抛异常：调用方按优先级回落） */
    public static final class Material
    {
        public final boolean available;

        public final String format;

        public final int lines;

        public final int chars;

        public final String excerpt;

        public final String reason;

        /** 实际用到的相对路径（回包与日志用） */
        public final String relPath;

        private Material(boolean available, String format, int lines, int chars, String excerpt, String reason,
                String relPath)
        {
            this.available = available;
            this.format = format;
            this.lines = lines;
            this.chars = chars;
            this.excerpt = excerpt;
            this.reason = reason;
            this.relPath = relPath;
        }

        static Material missing(String reason, String relPath)
        {
            return new Material(false, "", 0, 0, "", reason, relPath);
        }

        static Material of(SubtitleParser.Doc doc, String excerpt, String relPath)
        {
            return new Material(true, doc.format.name(), doc.lineCount(), doc.chars(), excerpt, "", relPath);
        }
    }

    /**
     * 读一个视频的字幕素材
     *
     * @param subPath 库里的 {@code media_video.sub_path}（相对媒体根；null / 空 = 没有字幕）
     * @param rootId  库根 id（多库根）；null = 配置默认根 + 多根兜底
     * @param headChars 开头字数预算（见 {@code qtools.media.llm.subtitle.headChars}）
     * @param tailChars 结尾字数预算
     */
    public Material read(String subPath, Integer rootId, int headChars, int tailChars)
    {
        if (subPath == null || subPath.trim().isEmpty())
        {
            return Material.missing("该片没有登记字幕（sub_path 为空）", "");
        }
        for (String candidate : candidates(subPath))
        {
            Path file;
            try
            {
                file = mediaFileService.resolveMedia(candidate, rootId);
            }
            catch (MediaException e)
            {
                continue;
            }
            if (file == null || !Files.isRegularFile(file))
            {
                continue;
            }
            try
            {
                byte[] bytes = readLimited(file);
                if (bytes.length == 0)
                {
                    continue;
                }
                String raw = decode(bytes);
                SubtitleParser.Doc doc = SubtitleParser.parse(raw, SubtitleParser.formatOf(candidate));
                if (doc.isEmpty())
                {
                    log.debug("字幕解析后没有正文 rel={} format={}", candidate, doc.format);
                    continue;
                }
                String excerpt = SubtitleExcerpt.of(doc, headChars, tailChars);
                if (!SubtitleExcerpt.enough(excerpt))
                {
                    log.debug("字幕正文过少 rel={} chars={}", candidate, doc.chars());
                    return Material.missing("字幕文件只有 " + doc.chars() + " 个字，不足以概括内容", candidate);
                }
                log.debug("字幕素材就绪 rel={} format={} lines={} chars={} excerptChars={}", candidate, doc.format,
                        doc.lineCount(), doc.chars(), SynopsisText.codePoints(excerpt));
                return Material.of(doc, excerpt, candidate);
            }
            catch (IOException e)
            {
                log.debug("字幕读取失败 rel={} err={}", candidate, e.getMessage());
            }
        }
        return Material.missing("字幕文件读不到或解析不出正文（" + subPath + "）", subPath);
    }

    // ------------------------------------------------------------------ 候选路径

    /** 候选顺序：登记的路径 → 同名不同扩展名（.vtt/.srt/.ass/.ssa） */
    static List<String> candidates(String subPath)
    {
        List<String> out = new ArrayList<>(FALLBACK_EXTS.length + 1);
        String trimmed = subPath.trim().replace('\\', '/');
        out.add(trimmed);
        int dot = trimmed.lastIndexOf('.');
        int slash = trimmed.lastIndexOf('/');
        String stem = dot > slash ? trimmed.substring(0, dot) : trimmed;
        String ext = dot > slash ? trimmed.substring(dot).toLowerCase(Locale.ROOT) : "";
        for (String known : FALLBACK_EXTS)
        {
            if (!known.equals(ext))
            {
                out.add(stem + known);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 读 + 解码

    /** 读文件（最多 {@link #MAX_BYTES}；超长文件截断而不是失败：字幕节选只取前 2000 字） */
    static byte[] readLimited(Path file) throws IOException
    {
        long size = Files.size(file);
        if (size <= MAX_BYTES)
        {
            return Files.readAllBytes(file);
        }
        byte[] head = new byte[MAX_BYTES];
        try (java.io.InputStream in = Files.newInputStream(file))
        {
            int read = 0;
            while (read < MAX_BYTES)
            {
                int n = in.read(head, read, MAX_BYTES - read);
                if (n < 0)
                {
                    break;
                }
                read += n;
            }
            if (read < MAX_BYTES)
            {
                byte[] out = new byte[read];
                System.arraycopy(head, 0, out, 0, read);
                return out;
            }
        }
        return head;
    }

    /**
     * 字节 → 文本：BOM → 严格 UTF-8 → GB18030
     *
     * <p>GB18030 是 GBK/GB2312 的超集，一份解码器足够覆盖中文 Windows 上的三种老编码。</p>
     */
    static String decode(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            return "";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf)
        {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe)
        {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff)
        {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null)
        {
            return utf8;
        }
        String gb = strictDecode(bytes, Charset.forName("GB18030"));
        return gb != null ? gb : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 严格解码：遇到非法字节返回 null（调用方据此换编码重试） */
    private static String strictDecode(byte[] bytes, Charset charset)
    {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try
        {
            CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes));
            return buffer.toString();
        }
        catch (CharacterCodingException e)
        {
            return null;
        }
    }
}
