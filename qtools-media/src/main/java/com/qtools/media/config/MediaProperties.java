package com.qtools.media.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体库本地配置（前缀 {@code qtools.media}）
 *
 * <h2>可配置项一览</h2>
 *
 * <p>本类只保留<b>语义性默认值</b>（并发、超时、脚本参数）；所有与机器环境相关的绝对路径
 * <b>都没有默认值</b>，必须由 {@code application.yml}（现网配置）或环境变量提供，缺失时相关功能
 * 报「未配置」而不是抛空指针。启动方式见 {@code application.yml} 顶部的 {@code -Dqtools.home=...}。</p>
 *
 * <table border="1">
 *   <caption>配置项 / 默认值 / 缺失时的行为 / 环境变量覆盖名</caption>
 *   <tr><th>键（qtools.media.*）</th><th>默认值</th><th>留空/缺失时</th><th>环境变量</th></tr>
 *   <tr><td>rootPath</td><td>无（必填）</td><td>文件接口报「qtools.media.rootPath 未配置」</td>
 *       <td>QTOOLS_MEDIA_ROOTPATH</td></tr>
 *   <tr><td>thumbsPath</td><td>无（必填）</td><td>缩略图接口报「qtools.media.thumbsPath 未配置」</td>
 *       <td>QTOOLS_MEDIA_THUMBSPATH</td></tr>
 *   <tr><td>trashPath</td><td>空</td><td>空 = 落在 {@code <rootPath>/_媒体站/trash}</td>
 *       <td>QTOOLS_MEDIA_TRASHPATH</td></tr>
 *   <tr><td>ffmpeg</td><td>空</td><td>实时转码接口 503「ffmpeg 不可用」</td>
 *       <td>QTOOLS_MEDIA_FFMPEG</td></tr>
 *   <tr><td>mysqlClient</td><td>空</td><td>不注入 {@code MS_MYSQL}，脚本自己从 PATH 找 mysql</td>
 *       <td>QTOOLS_MEDIA_MYSQLCLIENT</td></tr>
 *   <tr><td>maxTranscode</td><td>2</td><td>按 1 计</td><td>QTOOLS_MEDIA_MAXTRANSCODE</td></tr>
 *   <tr><td>nodePath</td><td>空</td><td>扫描任务启动即报「nodePath 未配置」</td>
 *       <td>QTOOLS_MEDIA_NODEPATH</td></tr>
 *   <tr><td>scanScript</td><td>空</td><td>full 扫描启动即报「scanScript 未配置」</td>
 *       <td>QTOOLS_MEDIA_SCANSCRIPT</td></tr>
 *   <tr><td>scanOutPath</td><td>空</td><td>扫描启动即报「scanOutPath 未配置」</td>
 *       <td>QTOOLS_MEDIA_SCANOUTPATH</td></tr>
 *   <tr><td>scanArgs</td><td>{@code --dir-mode=raw --allow-dup-dir}</td><td>等于不带附加参数</td>
 *       <td>QTOOLS_MEDIA_SCANARGS</td></tr>
 *   <tr><td>scanTimeoutMinutes</td><td>10</td><td>-</td><td>QTOOLS_MEDIA_SCANTIMEOUTMINUTES</td></tr>
 *   <tr><td>thumbScript</td><td>空</td><td>thumb 扫描启动即报「thumbScript 未配置」</td>
 *       <td>QTOOLS_MEDIA_THUMBSCRIPT</td></tr>
 *   <tr><td>thumbArgs</td><td>空（= 增量全量跑一遍）</td><td>-</td><td>QTOOLS_MEDIA_THUMBARGS</td></tr>
 *   <tr><td>thumbTimeoutMinutes</td><td>30</td><td>-</td><td>QTOOLS_MEDIA_THUMBTIMEOUTMINUTES</td></tr>
 *   <tr><td>deriveTimeoutMinutes</td><td>720</td><td>-</td><td>QTOOLS_MEDIA_DERIVETIMEOUTMINUTES</td></tr>
 *   <tr><td>enrichScript</td><td>{@code <home>/QTools/doc/media-import/enrich-meta.js}</td>
 *       <td>enrich 任务启动即报「enrichScript 未配置」</td><td>QTOOLS_MEDIA_ENRICHSCRIPT</td></tr>
 *   <tr><td>enrichArgs</td><td>空（= 不带附加参数）</td><td>-</td><td>QTOOLS_MEDIA_ENRICHARGS</td></tr>
 *   <tr><td>enrichTimeoutMinutes</td><td>120</td><td>-</td><td>QTOOLS_MEDIA_ENRICHTIMEOUTMINUTES</td></tr>
 *   <tr><td>ffprobe</td><td>空</td><td>不注入 {@code MS_FFPROBE}，脚本自己从 PATH 找 ffprobe</td>
 *       <td>QTOOLS_MEDIA_FFPROBE</td></tr>
 *   <tr><td>exportScript</td><td>{@code <home>/QTools/doc/media-import/export-bundle.js}</td>
 *       <td>export 任务启动即报「exportScript 未配置」</td><td>QTOOLS_MEDIA_EXPORTSCRIPT</td></tr>
 *   <tr><td>exportArgs</td><td>空（= 不带附加参数）</td><td>-</td><td>QTOOLS_MEDIA_EXPORTARGS</td></tr>
 *   <tr><td>exportPath</td><td>{@code <home>/data/media/export}</td><td>不落导出目录</td>
 *       <td>QTOOLS_MEDIA_EXPORTPATH</td></tr>
 *   <tr><td>exportTimeoutMinutes</td><td>60</td><td>-</td><td>QTOOLS_MEDIA_EXPORTTIMEOUTMINUTES</td></tr>
 *   <tr><td>maintainTimeoutMinutes</td><td>120</td><td>-</td><td>QTOOLS_MEDIA_MAINTAINTIMEOUTMINUTES</td></tr>
 *   <tr><td>allowOrigins</td><td>*</td><td>-</td><td>QTOOLS_MEDIA_ALLOWORIGINS</td></tr>
 *   <tr><td>watch.enabled</td><td>false</td><td>文件监听不启动，「自动扫描」整体关闭</td>
 *       <td>QTOOLS_MEDIA_WATCH_ENABLED</td></tr>
 *   <tr><td>watch.debounceSeconds</td><td>5</td><td>按 5 计；小于 1 按 1 计</td>
 *       <td>QTOOLS_MEDIA_WATCH_DEBOUNCESECONDS</td></tr>
 *   <tr><td>watch.quietMinutes</td><td>空</td><td>空 = 不静默（全天按需自动扫）</td>
 *       <td>QTOOLS_MEDIA_WATCH_QUIETMINUTES</td></tr>
 * </table>
 *
 * <p>环境变量名由 Spring Boot 宽松绑定推导：键名的 {@code .} 与 {@code -} 换成 {@code _} 并全大写
 * （{@code qtools.media.rootPath} → {@code QTOOLS_MEDIA_ROOTPATH}）。盘上根目录 {@code qtools.home}
 * 的覆盖名是 {@code QTOOLS_HOME}，也可用 {@code -Dqtools.home=...}。</p>
 *
 * <p>迁移到自己的环境后，可调用 {@code GET /api/media/admin/config} 自检：它会逐项返回路径是否存在 /
 * 可读 / 可写、ffmpeg 与 node 是否可执行、脚本是否就位，以及需要处理的 warnings。</p>
 *
 * @author qtools
 */
@Component
@ConfigurationProperties(prefix = "qtools.media")
public class MediaProperties
{
    /** 媒体根目录（只读，例如 {@code <qtools.home>/视频}）；必填，本类不给默认值 */
    private String rootPath;

    /** 缩略图根目录（含 v/p/pv 三个子目录）；必填，本类不给默认值 */
    private String thumbsPath;

    /** ffmpeg 可执行文件路径；留空则实时转码不可用 */
    private String ffmpeg;

    /**
     * mysql 客户端可执行文件路径；留空 = 不注入 {@code MS_MYSQL}（脚本自己从 PATH 找 mysql）
     *
     * <p>管理端触发「重建索引 / 重建缩略图」时，本值非空就以 {@code MS_MYSQL} 注入子进程环境变量，
     * 脚本的优先级是 {@code --mysql=} &gt; {@code MS_MYSQL} &gt; {@code PATH}。mysql 不在 PATH 的机器
     * （例如本机 {@code E:/111/tools/mysql/bin/mysql.exe}）必须配这一项，否则脚本读库失败。</p>
     */
    private String mysqlClient = "";

    /** 实时转码并发上限 */
    private int maxTranscode = 2;

    /**
     * 回收站根目录（软删只移动、绝不真删）
     *
     * <p>留空 = 使用 {@code <rootPath>/_媒体站/trash}；非空时必须位于 {@code <rootPath>/_媒体站} 之下。</p>
     */
    private String trashPath = "";

    /** Node 可执行文件（重建索引 / 缩略图任务用）；留空则扫描任务无法启动 */
    private String nodePath = "";

    /** 重建索引脚本（复用 doc/media-import/import.js）；留空则 full 扫描无法启动 */
    private String scanScript = "";

    /** 扫描产物目录（import.sql / report.md），与仓库隔离；留空则扫描无法启动 */
    private String scanOutPath = "";

    /**
     * 扫描附加参数。
     *
     * <p>必须带 {@code --dir-mode=raw --allow-dup-dir}：念念 144 本相册共用同一物理目录
     * {@code 写真/念念 Nian Nian}，默认的 mark 模式会给 dir 追加 " #<albumId>"，
     * 每次扫描都会改写 media_album.dir，导致按目录定位的软删/恢复落到不存在的路径。</p>
     *
     * <p>绝不能带 {@code --truncate}：那会清空 upsert 才能保留的 hidden 状态与收藏引用。</p>
     */
    private String scanArgs = "--dir-mode=raw --allow-dup-dir";

    /** 单次扫描超时（分钟），超时强杀进程并置 failed */
    private int scanTimeoutMinutes = 10;

    /**
     * 缩略图增量脚本（{@code type=thumb} 扫描任务执行它）；留空则 thumb 扫描无法启动
     *
     * <p>脚本自带增量判定（按源文件 mtime 与产物对比）：默认参数即为增量模式，
     * 绝不能带 {@code --force}（那会重建全部 1 万多张缩略图）。</p>
     */
    private String thumbScript = "";

    /** 缩略图扫描附加参数；空 = 增量全量跑一遍（videos + photos + previews 三档，已存在的跳过） */
    private String thumbArgs = "";

    /** 缩略图扫描超时（分钟）：1 万条约 8-10 分钟，留足余量 */
    private int thumbTimeoutMinutes = 30;

    /**
     * 精灵图 / 预览视频 / GC 的超时（分钟），默认 720（12 小时）
     *
     * <p>精灵图与预览视频是<b>全片解码</b>（176 个视频约 5 小时），远超缩略图那 30 分钟的预算；
     * GC 要扫 {@code v/ s/ pv2/} 三个目录，一并按这一档放宽。超时强杀子进程并置 failed。</p>
     */
    private int deriveTimeoutMinutes = 720;

    // ------------------------------------------------------------------ 元数据补齐 / 便携包导出（P1-A）

    /**
     * 元数据补齐脚本（{@code mode=enrich} 执行它）
     *
     * <p>留空时回落到 {@code <qtools.home>/QTools/doc/media-import/enrich-meta.js}；脚本用 ffprobe
     * 读出 dur / w / h / vcodec 四列回写 media_video，幂等（补齐后自动退出待补集合）。</p>
     */
    private String enrichScript = "";

    /** 元数据补齐附加参数；空 = 不带附加参数（脚本的 --root= / --port= / --secret= 必须写在这里） */
    private String enrichArgs = "";

    /** 元数据补齐超时（分钟）：ffprobe 逐条探测比扫盘慢得多 */
    private int enrichTimeoutMinutes = 120;

    /**
     * ffprobe 可执行文件路径；留空 = 不注入 {@code MS_FFPROBE}
     *
     * <p>与 {@link #mysqlClient} 同一套路：非空才注入子进程环境变量，脚本的优先级是
     * {@code --ffprobe=} &gt; {@code MS_FFPROBE} &gt; {@code PATH}。enrich-meta.js 在 ffprobe
     * 不可用时直接退出码 1（每条都会失败，没有继续的意义）。</p>
     */
    private String ffprobe = "";

    /** 便携包导出脚本（{@code mode=export} 执行它）；留空回落到 {@code <home>/QTools/doc/media-import/export-bundle.js} */
    private String exportScript = "";

    /** 便携包导出附加参数；空 = 不带附加参数（--root= / --port= / --secret= 必须写在这里） */
    private String exportArgs = "";

    /**
     * 便携包导出根目录；留空回落到 {@code <qtools.home>/data/media/export}
     *
     * <p>每次导出落 {@code <exportPath>/<yyyyMMdd-HHmmss>/}：目录名由后端生成（前端传不进任意子目录），
     * 下载接口只允许读这个根之下的文件。</p>
     */
    private String exportPath = "";

    /** 便携包导出超时（分钟）：千万级表的 dump 也就分钟级，60 分钟已足够宽裕 */
    private int exportTimeoutMinutes = 60;

    /**
     * 维护类任务的超时（分钟），默认 120
     *
     * <p>{@code mode=backfill-hash} 要给全部行算 content_hash、{@code mode=fix-poster} 只刷一列；
     * 两者都跑 scan-media.js，但都不是「一次扫盘」那 10 分钟的量级。</p>
     */
    private int maintainTimeoutMinutes = 120;

    /** 盘上根目录 {@code qtools.home}：只用于推导 enrichScript / exportScript / exportPath 的缺省值 */
    @Value("${qtools.home:}")
    private String home;

    /**
     * 允许的跨域来源，逗号分隔；* 表示不限（本地开发）
     *
     * <p>注意：实际生效的跨域放行来自 framework 的全局 CorsFilter（来源不限、放行全部请求头），
     * 它在 Security 过滤链内先执行，本模块无法在其之后再收紧。此处保留配置项，供 M4
     * 需要按模块收紧或补 Access-Control-Expose-Headers 时改 framework 一并使用。</p>
     */
    private String allowOrigins = "*";

    /**
     * 文件监听（自动增量扫描）配置：{@code qtools.media.watch.*}
     *
     * <p>默认整块关闭：监听器由 {@code @ConditionalOnProperty(qtools.media.watch.enabled=true)} 决定是否注册，
     * 关闭时 {@code /api/media/admin/watch} 仍可读，只是回 {@code enabled=false}。</p>
     */
    private Watch watch = new Watch();

    public String getRootPath()
    {
        return rootPath;
    }

    public void setRootPath(String rootPath)
    {
        this.rootPath = rootPath;
    }

    public String getThumbsPath()
    {
        return thumbsPath;
    }

    public void setThumbsPath(String thumbsPath)
    {
        this.thumbsPath = thumbsPath;
    }

    public String getFfmpeg()
    {
        return ffmpeg;
    }

    public void setFfmpeg(String ffmpeg)
    {
        this.ffmpeg = ffmpeg;
    }

    public String getMysqlClient()
    {
        return mysqlClient;
    }

    public void setMysqlClient(String mysqlClient)
    {
        this.mysqlClient = mysqlClient;
    }

    public int getMaxTranscode()
    {
        return maxTranscode;
    }

    public void setMaxTranscode(int maxTranscode)
    {
        this.maxTranscode = maxTranscode;
    }

    public String getAllowOrigins()
    {
        return allowOrigins;
    }

    public void setAllowOrigins(String allowOrigins)
    {
        this.allowOrigins = allowOrigins;
    }

    // ------------------------------------------------------------------ 文件监听（自动扫描，P2-A）

    public Watch getWatch()
    {
        return watch;
    }

    public void setWatch(Watch watch)
    {
        this.watch = watch;
    }

    /**
     * 文件监听三件套（{@code qtools.media.watch.*}）
     *
     * <p>只描述「听不听、合多久、夜间静不静」，扫描本身仍走既有的 {@code mode=scan} 任务：
     * 并发保护、日志尾部、超时强杀都由 {@code MediaScanServiceImpl} 负责，本配置不会绕过它。</p>
     */
    public static class Watch
    {
        /** 是否注册文件监听（false = 不注册任何 WatchService，自动扫描不存在） */
        private boolean enabled = false;

        /** 去抖窗口（秒）：窗口内的所有事件合并成一次扫描；事件不断则计时不断后移 */
        private int debounceSeconds = 5;

        /**
         * 夜间静默区间，默认空 = 不静默
         *
         * <p>接受两种写法：{@code HH:mm-HH:mm}（如 {@code 23:00-07:00}）或当日分钟数
         * {@code 起始-结束}（如 {@code 1380-420}）。跨零点即「起始 &gt; 结束」。
         * 静默期内照常收事件，只是把合并后的扫描推迟到区间结束。</p>
         */
        private String quietMinutes = "";

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        /** 去抖秒数：&lt; 1 一律按 1 返回，避免配置成 0 时退化成每条事件一次扫描 */
        public int getDebounceSeconds()
        {
            return debounceSeconds < 1 ? 1 : debounceSeconds;
        }

        public void setDebounceSeconds(int debounceSeconds)
        {
            this.debounceSeconds = debounceSeconds;
        }

        public String getQuietMinutes()
        {
            return quietMinutes;
        }

        public void setQuietMinutes(String quietMinutes)
        {
            this.quietMinutes = quietMinutes;
        }
    }

    public String getTrashPath()
    {
        return trashPath;
    }

    public void setTrashPath(String trashPath)
    {
        this.trashPath = trashPath;
    }

    public String getNodePath()
    {
        return nodePath;
    }

    public void setNodePath(String nodePath)
    {
        this.nodePath = nodePath;
    }

    public String getScanScript()
    {
        return scanScript;
    }

    public void setScanScript(String scanScript)
    {
        this.scanScript = scanScript;
    }

    public String getScanOutPath()
    {
        return scanOutPath;
    }

    public void setScanOutPath(String scanOutPath)
    {
        this.scanOutPath = scanOutPath;
    }

    public String getScanArgs()
    {
        return scanArgs;
    }

    public void setScanArgs(String scanArgs)
    {
        this.scanArgs = scanArgs;
    }

    public int getScanTimeoutMinutes()
    {
        return scanTimeoutMinutes;
    }

    public void setScanTimeoutMinutes(int scanTimeoutMinutes)
    {
        this.scanTimeoutMinutes = scanTimeoutMinutes;
    }

    public String getThumbScript()
    {
        return thumbScript;
    }

    public void setThumbScript(String thumbScript)
    {
        this.thumbScript = thumbScript;
    }

    public String getThumbArgs()
    {
        return thumbArgs;
    }

    public void setThumbArgs(String thumbArgs)
    {
        this.thumbArgs = thumbArgs;
    }

    public int getThumbTimeoutMinutes()
    {
        return thumbTimeoutMinutes;
    }

    public void setThumbTimeoutMinutes(int thumbTimeoutMinutes)
    {
        this.thumbTimeoutMinutes = thumbTimeoutMinutes;
    }

    public int getDeriveTimeoutMinutes()
    {
        return deriveTimeoutMinutes;
    }

    public void setDeriveTimeoutMinutes(int deriveTimeoutMinutes)
    {
        this.deriveTimeoutMinutes = deriveTimeoutMinutes;
    }

    // ------------------------------------------------------------------ 元数据补齐 / 便携包导出（P1-A）

    /** 元数据补齐脚本：未显式配置时回落到 {@code <qtools.home>/QTools/doc/media-import/enrich-meta.js} */
    public String getEnrichScript()
    {
        return defaultUnderHome(enrichScript, "QTools/doc/media-import/enrich-meta.js");
    }

    public void setEnrichScript(String enrichScript)
    {
        this.enrichScript = enrichScript;
    }

    public String getEnrichArgs()
    {
        return enrichArgs;
    }

    public void setEnrichArgs(String enrichArgs)
    {
        this.enrichArgs = enrichArgs;
    }

    public int getEnrichTimeoutMinutes()
    {
        return enrichTimeoutMinutes;
    }

    public void setEnrichTimeoutMinutes(int enrichTimeoutMinutes)
    {
        this.enrichTimeoutMinutes = enrichTimeoutMinutes;
    }

    /** ffprobe 路径：留空 = 不注入 {@code MS_FFPROBE}（与 mysqlClient 同风格） */
    public String getFfprobe()
    {
        return ffprobe;
    }

    public void setFfprobe(String ffprobe)
    {
        this.ffprobe = ffprobe;
    }

    /** 便携包导出脚本：未显式配置时回落到 {@code <qtools.home>/QTools/doc/media-import/export-bundle.js} */
    public String getExportScript()
    {
        return defaultUnderHome(exportScript, "QTools/doc/media-import/export-bundle.js");
    }

    public void setExportScript(String exportScript)
    {
        this.exportScript = exportScript;
    }

    public String getExportArgs()
    {
        return exportArgs;
    }

    public void setExportArgs(String exportArgs)
    {
        this.exportArgs = exportArgs;
    }

    /** 导出根目录：未显式配置时回落到 {@code <qtools.home>/data/media/export} */
    public String getExportPath()
    {
        return defaultUnderHome(exportPath, "data/media/export");
    }

    public void setExportPath(String exportPath)
    {
        this.exportPath = exportPath;
    }

    public int getExportTimeoutMinutes()
    {
        return exportTimeoutMinutes;
    }

    public void setExportTimeoutMinutes(int exportTimeoutMinutes)
    {
        this.exportTimeoutMinutes = exportTimeoutMinutes;
    }

    public int getMaintainTimeoutMinutes()
    {
        return maintainTimeoutMinutes;
    }

    public void setMaintainTimeoutMinutes(int maintainTimeoutMinutes)
    {
        this.maintainTimeoutMinutes = maintainTimeoutMinutes;
    }

    /**
     * 缺省值推导：显式配置优先，否则拼到 {@code qtools.home} 之下
     *
     * <p>{@code qtools.home} 本身也没给时返回空串，让调用方报「未配置」而不是给出一个假的相对路径——
     * 相对路径在子进程工作目录（{@code scanOutPath}）下解析，症状是「文件不存在」而不是「少配了一项」。</p>
     */
    private String defaultUnderHome(String value, String relative)
    {
        if (value != null && !value.trim().isEmpty())
        {
            return value.trim();
        }
        if (home == null || home.trim().isEmpty())
        {
            return "";
        }
        String base = home.trim().replace('\\', '/');
        return base.endsWith("/") ? base + relative : base + "/" + relative;
    }
}
