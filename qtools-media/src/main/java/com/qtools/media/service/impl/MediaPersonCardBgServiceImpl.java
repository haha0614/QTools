package com.qtools.media.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaPerson;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaFrameService;
import com.qtools.media.service.IMediaPersonCardBgService;
import com.qtools.media.support.MediaAvatarCrop;
import com.qtools.media.support.MediaPersonCardBg;

/**
 * 人物卡背景预渲染服务实现（P5.90）
 *
 * <p>四步：解析人物头像来源 → 取有效参数（请求参数 &gt; 已保存的 bg_scale/bg_dx/bg_dy &gt; 自动几何）
 * → 拼内容哈希定位缓存文件 → 命中读盘、未命中渲染落盘。</p>
 *
 * <p><b>并发</b>：同一 key 的并发未命中会各渲染一次（最后一次原子替换胜出），字节相同，
 * 不会出现半张图 —— 写临时文件再 {@code ATOMIC_MOVE}。</p>
 *
 * <p><b>清理</b>：命中过期的判定是文件 mtime；命中时若 mtime 超过 1 天会 touch 一次，
 * 因此「30 天未被访问」真的等于没人看。清理在渲染后顺带跑（6 小时最多一次），
 * 不需要外部定时任务，也不需要人工 {@code --gc}。</p>
 *
 * @author qtools
 */
@Service
public class MediaPersonCardBgServiceImpl implements IMediaPersonCardBgService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonCardBgServiceImpl.class);

    /** 缓存子目录（相对 {@code qtools.media.thumbsPath}） */
    public static final String CACHE_DIR = "cardbg";

    /** 输出上限：再大也不是「卡片」了，只会白烧 CPU 与磁盘（超出按比例缩，不报错） */
    public static final int MAX_W = 1200;

    public static final int MAX_H = 600;

    /** 缺省尺寸：333×102 CSS 像素 @2x DPR（人物墙 1600 视口下的实测卡片） */
    public static final int DEFAULT_W = 666;

    public static final int DEFAULT_H = 204;

    /** jpeg 质量（0.82 是「皮肤 / 渐变都不出块」的下限，再高只是白占带宽） */
    private static final float QUALITY = 0.82f;

    /** 未被访问的缓存文件保留 30 天 */
    private static final long CACHE_TTL_MS = 30L * 24 * 3600 * 1000;

    /** 命中时 mtime 超过这个值才回写（避免每个请求都写一次元数据） */
    private static final long TOUCH_AFTER_MS = 24L * 3600 * 1000;

    /** 清理的最小间隔 */
    private static final long PURGE_INTERVAL_MS = 6L * 3600 * 1000;

    /**
     * 渲染版本号：进内容哈希
     *
     * <p>改了渲染管线（模糊算法 / 合成顺序 / 常量）就必须 +1 —— 否则旧缓存文件仍然命中，
     * 用户看到的还是上一版像素，而且「清了浏览器缓存也没用」。这是内容寻址缓存唯一的版本阀门。</p>
     */
    private static final int RENDER_VERSION = 2;

    private static final AtomicLong LAST_PURGE = new AtomicLong(0);

    @Autowired
    private MediaPersonMapper mediaPersonMapper;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private IMediaFrameService mediaFrameService;

    @Autowired
    private MediaProperties mediaProperties;

    @Override
    public CardBgImage cardBg(Long personId, Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy,
            Integer blur, boolean dark, String bgHex)
    {
        long begin = System.currentTimeMillis();
        MediaPerson person = personId == null ? null : mediaPersonMapper.selectPersonDetail(personId);
        if (person == null)
        {
            throw MediaException.notFound("人物不存在: " + personId);
        }
        Source source = resolveSource(person);
        int[] dims = fitDims(w, h);
        int outW = dims[0];
        int outH = dims[1];
        double effScale = param(scale, person.getBgScale(), 0.5, 3.0, 1.0);
        double effDx = param(dx, person.getBgDx(), -2.0, 2.0, 0.0);
        double effDy = param(dy, person.getBgDy(), -2.0, 2.0, 0.0);
        int effBlur = blur == null ? 6 : Math.max(0, Math.min(20, blur));
        String cleanBg = normalizeBg(bgHex);

        String seed = "v" + RENDER_VERSION + "|" + person.getId() + "|" + source.signature + "|" + outW + "x" + outH + "|"
                + String.format(Locale.ROOT, "%.3f,%.4f,%.4f", effScale, effDx, effDy) + "|b" + effBlur + "|d"
                + (dark ? 1 : 0) + "|" + cleanBg + "|f" + MediaPersonCardBg.FADE_END + "|" + source.cropKey;
        String hash = sha1(seed);
        Path dir = cacheDir();
        Path file = dir.resolve(person.getId() + "-" + outW + "x" + outH + "-" + hash + ".jpg");

        if (Files.isRegularFile(file) && sizeOf(file) > 0)
        {
            try
            {
                byte[] bytes = Files.readAllBytes(file);
                touchIfStale(file);
                return new CardBgImage(bytes, hash, true, System.currentTimeMillis() - begin, outW, outH);
            }
            catch (IOException e)
            {
                log.warn("读卡片背景缓存失败，改为重渲染 file={} err={}", file, e.getMessage());
            }
        }

        BufferedImage image = renderImage(source, outW, outH, effScale, effDx, effDy, effBlur, cleanBg, dark);
        byte[] bytes;
        try
        {
            bytes = MediaPersonCardBg.encodeJpeg(image, QUALITY);
            writeAtomically(file, bytes);
        }
        catch (IOException e)
        {
            log.error("卡片背景渲染落盘失败 person={} w={} h={} err={}", person.getId(), outW, outH, e.getMessage());
            throw MediaException.serviceUnavailable("卡片背景渲染失败: " + e.getMessage());
        }
        purgeIfDue(dir);
        long cost = System.currentTimeMillis() - begin;
        log.info("预渲染人物卡背景 person={} {}x{} scale={} dx={} dy={} blur={} dark={} key={} bytes={} cost={}ms",
                person.getId(), outW, outH, effScale, effDx, effDy, effBlur, dark, hash, bytes.length, cost);
        return new CardBgImage(bytes, hash, false, cost, outW, outH);
    }

    /* ------------------------------------------------------------------ 渲染 */

    private BufferedImage renderImage(Source source, int w, int h, double scale, double dx, double dy, int blur,
            String bgHex, boolean dark)
    {
        MediaPersonCardBg.Geometry g = MediaPersonCardBg.geometry(source.image.getWidth(),
                source.image.getHeight(), w, h, source.cropX, source.cropY, source.cropW, source.cropH, scale, dx,
                dy);
        if (g == null)
        {
            throw MediaException.notFound("该人物头像原图不可用");
        }
        int[] themeBg = hexToRgb(bgHex);
        return MediaPersonCardBg.render(source.image, g, w, h, blur, themeBg, dark);
    }

    /* ------------------------------------------------------------------ 头像来源 */

    /** 渲染源：整图 + 缓存签名 + 用于几何的裁剪框（比例） */
    private static final class Source
    {
        private final BufferedImage image;

        private final String signature;

        private final String cropKey;

        private final double cropX;

        private final double cropY;

        private final double cropW;

        private final double cropH;

        Source(BufferedImage image, String signature, String cropKey, double cropX, double cropY, double cropW,
                double cropH)
        {
            this.image = image;
            this.signature = signature;
            this.cropKey = cropKey;
            this.cropX = cropX;
            this.cropY = cropY;
            this.cropW = cropW;
            this.cropH = cropH;
        }
    }

    /**
     * 解析人物头像来源
     *
     * <p>与前端 {@code bgSrcOf} / {@code originalSrc} 同口径：背景用<b>整张原图</b>，ref 尾巴上的
     * {@code #crop=} 只参与几何（裁剪中心），不参与取图。头像来源只支持 photo（相册缩略图）与
     * frame（视频取帧）两档 —— {@code url} 档是外部地址，服务端不去抓（SSRF 面），交给前端三层兜底。</p>
     */
    private Source resolveSource(MediaPerson person)
    {
        String kind = trim(person.getAvatarKind()).toLowerCase(Locale.ROOT);
        String ref = trim(person.getAvatarRef());
        double[] crop = parseCrop(ref);
        if (ref.isEmpty())
        {
            throw MediaException.notFound("该人物没有手动头像，无法预渲染背景");
        }
        String body = ref;
        int hash = ref.indexOf('#');
        if (hash >= 0)
        {
            body = ref.substring(0, hash).trim();
        }
        if ("photo".equals(kind))
        {
            int colon = body.indexOf(':');
            if (colon <= 0)
            {
                throw MediaException.notFound("头像来源不完整: " + ref);
            }
            String albumId = body.substring(0, colon).trim();
            String seqText = body.substring(colon + 1).trim();
            Path path = mediaFileService.resolveThumbPhoto(albumId, seqText, false);
            try
            {
                BufferedImage image = ImageIO.read(path.toFile());
                if (image == null)
                {
                    throw MediaException.notFound("头像缩略图不可解码: " + path);
                }
                String sig = "photo|" + path.toAbsolutePath() + "|" + Files.size(path) + "|"
                        + Files.getLastModifiedTime(path).toMillis();
                return new Source(image, sig, ref, crop[0], crop[1], crop[2], crop[3]);
            }
            catch (IOException e)
            {
                throw MediaException.notFound("头像缩略图读取失败: " + e.getMessage());
            }
        }
        if ("frame".equals(kind))
        {
            // frame:<videoId>:<秒>
            String[] parts = body.split(":");
            if (parts.length < 3)
            {
                throw MediaException.notFound("头像来源不完整: " + ref);
            }
            String videoId = parts[1].trim();
            String secText = parts[2].trim();
            byte[] bytes;
            String sig;
            try
            {
                double sec = Double.parseDouble(secText);
                if (sec == Math.rint(sec))
                {
                    bytes = mediaFrameService.frame(videoId, (int) sec);
                    sig = "frame|" + videoId + "|" + (int) sec;
                }
                else
                {
                    bytes = mediaFrameService.frameAt(videoId, sec);
                    sig = "frame|" + videoId + "|" + sec;
                }
            }
            catch (NumberFormatException e)
            {
                throw MediaException.notFound("头像取帧秒数非法: " + ref);
            }
            try
            {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null)
                {
                    throw MediaException.notFound("头像取帧结果不可解码");
                }
                return new Source(image, sig + "|" + bytes.length, ref, crop[0], crop[1], crop[2], crop[3]);
            }
            catch (IOException e)
            {
                throw MediaException.notFound("头像取帧结果读取失败: " + e.getMessage());
            }
        }
        throw MediaException.notFound("该头像来源不支持服务端预渲染（仅 photo / frame）: kind=" + kind);
    }

    /** {@code #crop=x,y,w,h} → 四个比例；没有 / 脏值 = 整图 */
    private static double[] parseCrop(String ref)
    {
        String tail = MediaAvatarCrop.tailOf(ref);
        if (tail != null)
        {
            try
            {
                String norm = MediaAvatarCrop.normalize(tail);
                if (norm != null)
                {
                    String[] parts = norm.split(",");
                    if (parts.length == 4)
                    {
                        return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]) };
                    }
                }
            }
            catch (IllegalArgumentException ignored)
            {
                // 脏裁剪参数 → 整图（与前端 parseCrop 同口径：背景不因为裁剪坏了就不显示）
            }
        }
        return new double[] { 0, 0, 1, 1 };
    }

    /* ------------------------------------------------------------------ 缓存 */

    private Path cacheDir()
    {
        String thumbs = mediaProperties == null ? null : trim(mediaProperties.getThumbsPath());
        if (thumbs.isEmpty())
        {
            throw MediaException.serviceUnavailable("qtools.media.thumbsPath 未配置，卡片背景无法缓存");
        }
        Path dir = Path.of(thumbs, CACHE_DIR);
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException e)
        {
            throw MediaException.serviceUnavailable("卡片背景缓存目录不可创建: " + dir);
        }
        return dir;
    }

    private void writeAtomically(Path file, byte[] bytes) throws IOException
    {
        Path tmp = Files.createTempFile(file.getParent(), "cbg", ".tmp");
        try
        {
            Files.write(tmp, bytes);
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicFailed)
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(tmp);
        }
    }

    private static long sizeOf(Path file)
    {
        try
        {
            return Files.size(file);
        }
        catch (IOException e)
        {
            return -1;
        }
    }

    private static void touchIfStale(Path file)
    {
        try
        {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (System.currentTimeMillis() - mtime > TOUCH_AFTER_MS)
            {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
        }
        catch (IOException ignored)
        {
            // touch 失败不影响命中：最坏情况是这条缓存被提前清掉，下个请求重渲染一张
        }
    }

    /** 删掉 30 天没被访问的缓存文件（6 小时最多一次；失败只记日志） */
    private void purgeIfDue(Path dir)
    {
        long now = System.currentTimeMillis();
        long last = LAST_PURGE.get();
        if (now - last < PURGE_INTERVAL_MS || !LAST_PURGE.compareAndSet(last, now))
        {
            return;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir))
        {
            for (Path p : (Iterable<Path>) files::iterator)
            {
                try
                {
                    if (Files.isRegularFile(p) && now - Files.getLastModifiedTime(p).toMillis() > CACHE_TTL_MS)
                    {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                }
                catch (IOException ignored)
                {
                    // 单个文件删不掉就跳过
                }
            }
        }
        catch (IOException e)
        {
            log.warn("卡片背景缓存清理失败 dir={} err={}", dir, e.getMessage());
            return;
        }
        if (removed > 0)
        {
            log.info("卡片背景缓存清理完成 dir={} removed={} (30 天未访问)", dir, removed);
        }
    }

    /* ------------------------------------------------------------------ 工具 */

    /**
     * 输出尺寸：缺省 666×204；超出 1200×600 时<b>按比例</b>缩（不裁、不拉伸），并取偶数
     *
     * <p>取偶数是为了挡住 DPR 四舍五入的 1px 抖动：尺寸只差 1px 会变成一个新的缓存文件，
     * 而视觉上完全看不出差别。</p>
     */
    static int[] fitDims(Integer w, Integer h)
    {
        int vw = Math.max(2, Math.min(2000, w == null ? DEFAULT_W : w));
        int vh = Math.max(2, Math.min(2000, h == null ? DEFAULT_H : h));
        double factor = Math.min(1.0, Math.min((double) MAX_W / vw, (double) MAX_H / vh));
        int ow = even(Math.max(2, (int) Math.round(vw * factor)));
        int oh = even(Math.max(2, (int) Math.round(vh * factor)));
        return new int[] { ow, oh };
    }

    private static int even(int value)
    {
        return value % 2 == 0 ? value : value - 1;
    }

    /** 有效参数：请求参数（夹到值域） &gt; 人物已保存值 &gt; 默认 */
    private static double param(BigDecimal fromRequest, BigDecimal saved, double min, double max, double fallback)
    {
        BigDecimal raw = fromRequest != null ? fromRequest : saved;
        if (raw == null)
        {
            return fallback;
        }
        double v = raw.setScale(4, RoundingMode.HALF_UP).doubleValue();
        return Math.max(min, Math.min(max, v));
    }

    private static String normalizeBg(String bgHex)
    {
        String s = trim(bgHex).toLowerCase(Locale.ROOT);
        if (s.startsWith("#"))
        {
            s = s.substring(1);
        }
        return s.matches("^[0-9a-f]{6}$") ? s : "ffffff";
    }

    private static int[] hexToRgb(String hex)
    {
        return new int[] { Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16) };
    }

    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static String sha1(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.substring(0, 20);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
