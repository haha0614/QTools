package com.qtools.media.support;

import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;

/**
 * 子进程环境变量注入：{@code MS_FFMPEG} / {@code MS_FFPROBE} / {@code MS_MYSQL}
 *
 * <p>为什么抽出来（P5.96）：{@code MediaScanServiceImpl}（按 mode 的维护任务）与
 * {@code MediaScanIngestServiceImpl}（扫描入库）是两个执行器，之前只有前者注入 ffmpeg / ffprobe。
 * 扫描入库的第一个版本因此**没有 ffprobe 可用**——脚本 doc/media-import/scan-media.js 本身又不跑
 * ffprobe，于是新扫进来的行 dur / fps / w / h 四列全是 NULL（实测 63 条）。注入点必须只有一处，
 * 才不会下一次又被漏掉。</p>
 *
 * <p>三个变量都是「非空才注入」：留空时脚本按 {@code PATH} 自己找，也不覆盖环境里本已存在的同名变量。</p>
 *
 * @author qtools
 */
public final class MediaToolEnv
{
    /** ffmpeg：build-thumbs.js 用它出封面 / 精灵图 / 预览片段 */
    public static final String FFMPEG = "MS_FFMPEG";

    /** ffprobe：enrich-meta.js 用它读 dur / fps / w / h / vcodec */
    public static final String FFPROBE = "MS_FFPROBE";

    /** mysql 客户端：脚本读 media_* 时用（不在 PATH 的机器必须注入） */
    public static final String MYSQL = "MS_MYSQL";

    /** 自带封面的文件名后缀集合（逗号分隔）：scan-media.js 认它，见 qtools.media.scan.coverSuffixes */
    public static final String COVER_SUFFIXES = "MS_COVER_SUFFIXES";

    private MediaToolEnv()
    {
    }

    /**
     * 注入媒体工具路径（非空才注入）
     *
     * @param builder 子进程构造器
     * @param props   媒体库配置
     */
    public static void applyTools(ProcessBuilder builder, MediaProperties props)
    {
        if (builder == null || props == null)
        {
            return;
        }
        putIfPresent(builder, FFMPEG, props.getFfmpeg());
        putIfPresent(builder, FFPROBE, props.getFfprobe());
    }

    /**
     * 注入 mysql 客户端路径（非空才注入；留空 = 脚本自己从 PATH 找）
     *
     * @param builder 子进程构造器
     * @param props   媒体库配置
     */
    public static void applyMysql(ProcessBuilder builder, MediaProperties props)
    {
        if (builder == null || props == null)
        {
            return;
        }
        putIfPresent(builder, MYSQL, props.getMysqlClient());
    }

    /**
     * 注入自带封面的后缀集合（非空才注入）
     *
     * <p>为什么走环境变量而不是拼进 {@code scanArgs}：列表型配置拼进一行字符串后，
     * 逗号既可能是后缀集合的分隔符，也可能是别的参数的语法，改一个漏一个。
     * 脚本侧的优先级是 {@code --cover-suffixes=} &gt; {@code MS_COVER_SUFFIXES} &gt; 内置默认值。</p>
     *
     * @param builder 子进程构造器
     * @param props   媒体库配置
     */
    public static void applyScan(ProcessBuilder builder, MediaProperties props)
    {
        if (builder == null || props == null || props.getScan() == null)
        {
            return;
        }
        java.util.List<String> suffixes = props.getScan().getCoverSuffixes();
        if (suffixes == null || suffixes.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : suffixes)
        {
            if (s == null || s.trim().isEmpty())
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(',');
            }
            sb.append(s.trim());
        }
        putIfPresent(builder, COVER_SUFFIXES, sb.length() == 0 ? "" : sb.toString());
    }

    /** 两个都注入：管理端起的每个 node 子进程都该有（脚本各取所需） */
    public static void applyAll(ProcessBuilder builder, MediaProperties props)
    {
        applyTools(builder, props);
        applyMysql(builder, props);
        applyScan(builder, props);
    }

    private static void putIfPresent(ProcessBuilder builder, String name, String value)
    {
        if (StringUtils.isNotEmpty(value))
        {
            builder.environment().put(name, value.trim());
        }
    }
}
