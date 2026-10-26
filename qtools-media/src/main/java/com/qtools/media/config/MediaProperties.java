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
 *   <tr><td>maxFrame</td><td>4</td><td>按 1 计；超额排队 2s，仍拿不到名额回 429</td>
 *       <td>QTOOLS_MEDIA_MAXFRAME</td></tr>
 *   <tr><td>transcode.encoder</td><td>auto</td><td>非法值记 WARN 并按 auto 处理；
 *       实际生效值见 {@code GET /api/media/admin/config} 的 {@code media.transcode.effective}</td>
 *       <td>QTOOLS_MEDIA_TRANSCODE_ENCODER</td></tr>
 *   <tr><td>transcode.preset</td><td>p4</td><td>原样透传给硬件编码器的 {@code -preset}
 *       （写错时由 ffmpeg 拒绝，转码路径自动回退软件重试一次）</td>
 *       <td>QTOOLS_MEDIA_TRANSCODE_PRESET</td></tr>
 *   <tr><td>transcode.cq</td><td>23</td><td>越界按 1-51 取边界</td>
 *       <td>QTOOLS_MEDIA_TRANSCODE_CQ</td></tr>
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
 *   <tr><td>watch.allowRuntimeToggle</td><td>true</td><td>部署层能力总闸：false = 彻底封掉运行期开关，
 *       只能改 {@code sys_config} 后重启（安全 / 资源受限环境用）</td>
 *       <td>QTOOLS_MEDIA_WATCH_ALLOWRUNTIMETOGGLE</td></tr>
 *   <tr><td>watch.debounceSeconds</td><td>5</td><td>仅兜底：{@code sys_config.media.watchDebounceSeconds}
 *       缺失 / 非法时按 5 计（小于 1 按 1 计）</td><td>QTOOLS_MEDIA_WATCH_DEBOUNCESECONDS</td></tr>
 *   <tr><td>watch.quietMinutes</td><td>空</td><td>仅兜底：{@code sys_config.media.watchQuietMinutes}
 *       缺失 / 非法时按空计（= 不静默）</td><td>QTOOLS_MEDIA_WATCH_QUIETMINUTES</td></tr>
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
     * 实时取帧（P5.15 hover 精确帧）并发上限，默认 4
     *
     * <p>与 {@link #maxTranscode} 分开：取帧是 1 帧就退出的短任务（100-400ms），转码是长流。
     * 上限存在的意义只有一个 —— 鼠标横扫进度条时绝不能让 ffmpeg 把 CPU 抢满：
     * 超额请求排队 {@code 2s} 仍拿不到名额就回 429，前端静默继续用精灵图。</p>
     */
    private int maxFrame = 4;

    /**
     * 实时转码的硬件加速配置：{@code qtools.media.transcode.*}
     *
     * <p>「配置想要什么」在这里，「这台机器实际能用什么」由 {@code MediaTranscodeProbe} 探测后缓存；
     * 两者都可能不一致（配了 nvenc 但机器没显卡），所以管理端自检同时返回 requested 与 effective。</p>
     */
    private Transcode transcode = new Transcode();

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
     * <p><b>两层模型</b>：本类只承载<b>部署层</b>的东西——{@code allowRuntimeToggle}（能力总闸，false 时禁止
     * 运行期开关）+ {@code debounceSeconds} / {@code quietMinutes}（{@code sys_config} 缺失时的兜底默认）。
     * 真正的开关与参数值在 {@code sys_config}（{@code media.watchEnabled} / {@code media.watchDebounceSeconds} /
     * {@code media.watchQuietMinutes}），由 {@code MediaWatchConfigService} 读取，运行期可改、改完即时生效。</p>
     *
     * <p>旧的 {@code qtools.media.watch.enabled} <b>已迁移</b>到 {@code sys_config.media.watchEnabled}：
     * 监听器现在是常驻 Bean，启动时按库里的值决定是否注册 WatchService；yml 里再写 {@code enabled}
     * 不会报错也不再起作用（Spring 忽略未知属性），请改为写库或调
     * {@code PUT /api/media/admin/watch/config}。</p>
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

    public int getMaxFrame()
    {
        return maxFrame;
    }

    public void setMaxFrame(int maxFrame)
    {
        this.maxFrame = maxFrame;
    }

    public Transcode getTranscode()
    {
        return transcode;
    }

    public void setTranscode(Transcode transcode)
    {
        this.transcode = transcode;
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
     * 实时转码的编码器配置（{@code qtools.media.transcode.*}）
     *
     * <p>三项都很小，但仍然单独成类：以后要加 {@code lookAhead} / {@code tune} 这类编码器参数时，
     * 不该继续往 {@code qtools.media.*} 顶层堆平铺字段。</p>
     */
    public static class Transcode
    {
        /**
         * 目标编码器：{@code auto | software | libx264 | h264_nvenc | hevc_nvenc | av1_nvenc | h264_qsv | hevc_qsv}
         *
         * <p>{@code auto} 的探测顺序是 h264_nvenc → h264_qsv → libx264；非法值记 WARN 后按 auto 处理——
         * 站端接口不接受编码器参数，「非法」只可能来自 yml，一个拼错不该让整个媒体库起不来。</p>
         */
        private String encoder = "auto";

        /**
         * 硬件编码器的 {@code -preset}：nvenc 是 p1(最快)-p7(最清)，qsv 走 veryfast
         *
         * <p>只作用于硬件分支：软件分支仍是既有的 {@code veryfast + zerolatency}，保证无显卡机器上
         * 的行为与 P5 之前完全一致。</p>
         */
        private String preset = "p4";

        /** 恒定质量因子：nvenc 的 {@code -cq} / qsv 的 {@code -global_quality}（1-51，越小越清） */
        private int cq = 23;

        public String getEncoder()
        {
            return encoder;
        }

        public void setEncoder(String encoder)
        {
            this.encoder = encoder;
        }

        public String getPreset()
        {
            return preset;
        }

        public void setPreset(String preset)
        {
            this.preset = preset;
        }

        public int getCq()
        {
            return cq;
        }

        public void setCq(int cq)
        {
            this.cq = cq;
        }
    }

    /**
     * 文件监听的<b>部署层</b>配置（{@code qtools.media.watch.*}）
     *
     * <p>「听不听、合多久、夜间静不静」的实际取值在 {@code sys_config}，这里只有能力总闸与兜底默认。
     * 扫描本身仍走既有的 {@code mode=scan} 任务：并发保护、日志尾部、超时强杀都由
     * {@code MediaScanServiceImpl} 负责，本配置不会绕过它。</p>
     */
    public static class Watch
    {
        /**
         * 部署层能力总闸：是否允许运行期开关（默认 true）
         *
         * <p>false 时 {@code PUT /api/media/admin/watch/config} 一律 403，{@code pause / resume} 也不可用；
         * 想改只能直接改 {@code sys_config} 后重启。给「安全审计要求配置只能落 yml」「资源受限机器
         * 不允许有人随手打开全盘递归监听」这类场景一个硬开关。</p>
         */
        private boolean allowRuntimeToggle = true;

        /** 去抖窗口兜底（秒）：{@code sys_config.media.watchDebounceSeconds} 缺失 / 非法时用它 */
        private int debounceSeconds = 5;

        /**
         * 夜间静默区间兜底，默认空 = 不静默（{@code sys_config.media.watchQuietMinutes} 缺失 / 非法时用它）
         *
         * <p>接受两种写法：{@code HH:mm-HH:mm}（如 {@code 23:00-07:00}）或当日分钟数
         * {@code 起始-结束}（如 {@code 1380-420}）。跨零点即「起始 &gt; 结束」。
         * 静默期内照常收事件，只是把合并后的扫描推迟到区间结束。</p>
         */
        private String quietMinutes = "";

        public boolean isAllowRuntimeToggle()
        {
            return allowRuntimeToggle;
        }

        public void setAllowRuntimeToggle(boolean allowRuntimeToggle)
        {
            this.allowRuntimeToggle = allowRuntimeToggle;
        }

        /** 去抖秒数兜底：&lt; 1 一律按 1 返回，避免配置成 0 时退化成每条事件一次扫描 */
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

    // ------------------------------------------------------------------ 作品简介生成（P5.47）

    /**
     * 本地模型（作品简介生成）配置：{@code qtools.media.llm.*}
     *
     * <p>默认 {@code enabled=false}：这一项是**可选**能力，没装 Ollama 的部署不该因为媒体库启动
     * 就多出一个连不上的按钮，也不该在启动时探测网络。</p>
     */
    private Llm llm = new Llm();

    public Llm getLlm()
    {
        return llm;
    }

    public void setLlm(Llm llm)
    {
        this.llm = llm;
    }

    /**
     * 本地模型配置（两个后端：Ollama 原生 /api/generate 与 OpenAI 兼容 /v1/chat/completions）
     *
     * <table border="1">
     *   <caption>键（qtools.media.llm.*）/ 默认值 / 语义</caption>
     *   <tr><th>键</th><th>默认</th><th>语义</th></tr>
     *   <tr><td>enabled</td><td>false</td><td>总开关；false 时 {@code /synopsis/llm} 回 503「本地模型未启用」</td></tr>
     *   <tr><td>provider</td><td>ollama</td><td>{@code ollama} | {@code openai}（OpenAI 兼容本地端点，如 LM Studio / llama.cpp / vLLM）</td></tr>
     *   <tr><td>baseUrl</td><td>空</td><td>空 = 按 provider 取默认：ollama → {@code http://127.0.0.1:11434/api/generate}，
     *       openai → {@code http://127.0.0.1:8080/v1/chat/completions}</td></tr>
     *   <tr><td>model</td><td>qwen2.5:1.5b</td><td>模型名（ollama 的 tag 或 OpenAI 兼容端点的 model 字段）</td></tr>
     *   <tr><td>apiKey</td><td>空</td><td>仅 openai provider：非空时带 {@code Authorization: Bearer}；本地端点一般留空</td></tr>
     *   <tr><td>timeoutMs</td><td>20000</td><td>单次生成的读超时；小于 1000 按 1000 计（连接超时取它与 2000 的较小值）</td></tr>
     * </table>
     */
    public static class Llm
    {
        /** 总开关：默认关闭（没装本地模型的部署不该看到「AI 生成」） */
        private boolean enabled = false;

        /** 后端类型：ollama | openai */
        private String provider = "ollama";

        /** 端点地址；留空 = 按 provider 取默认 */
        private String baseUrl = "";

        /** 模型名 */
        private String model = "qwen2.5:1.5b";

        /** 仅 openai 兼容端点需要的密钥（本地端点一般留空） */
        private String apiKey = "";

        /** 单次生成的读超时（毫秒） */
        private int timeoutMs = 20000;

        /**
         * 字幕节选的「开头」字数（{@code qtools.media.llm.subtitle.headChars}）
         *
         * <p>上限是 {@link com.qtools.media.support.SubtitleExcerpt#HEAD_CHARS}（2000 字，规范口径），
         * 默认取 1200：实测 qwen2.5:1.5b 在 2000 字上会滑向「逐句转写台词」，
         * 1200 字时才开始给摘要（见 p553-evidence/tune3-*.log）。</p>
         */
        private int subtitleHeadChars = 1200;

        /** 字幕节选的「结尾」字数（{@code qtools.media.llm.subtitle.tailChars}） */
        private int subtitleTailChars = 200;

        /** 画面理解（{@code qtools.media.llm.vision.*}）：无字幕时的第二素材来源，默认关闭 */
        private Vision vision = new Vision();

        /** 音频转写（{@code qtools.media.llm.transcribe.*}）：最后一路，默认关闭 */
        private Transcribe transcribe = new Transcribe();

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getProvider()
        {
            return provider;
        }

        public void setProvider(String provider)
        {
            this.provider = provider;
        }

        public String getBaseUrl()
        {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl)
        {
            this.baseUrl = baseUrl;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public String getApiKey()
        {
            return apiKey;
        }

        public void setApiKey(String apiKey)
        {
            this.apiKey = apiKey;
        }

        public int getTimeoutMs()
        {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs)
        {
            this.timeoutMs = timeoutMs;
        }

        public int getSubtitleHeadChars()
        {
            return subtitleHeadChars;
        }

        public void setSubtitleHeadChars(int subtitleHeadChars)
        {
            this.subtitleHeadChars = subtitleHeadChars;
        }

        public int getSubtitleTailChars()
        {
            return subtitleTailChars;
        }

        public void setSubtitleTailChars(int subtitleTailChars)
        {
            this.subtitleTailChars = subtitleTailChars;
        }

        /** 实际开头字数：夹到 [300, {@link com.qtools.media.support.SubtitleExcerpt#HEAD_CHARS}] */
        public int getEffectiveSubtitleHeadChars()
        {
            int cap = com.qtools.media.support.SubtitleExcerpt.HEAD_CHARS;
            if (subtitleHeadChars < 300)
            {
                return 300;
            }
            return Math.min(subtitleHeadChars, cap);
        }

        /** 实际结尾字数：夹到 [0, {@link com.qtools.media.support.SubtitleExcerpt#TAIL_CHARS}] */
        public int getEffectiveSubtitleTailChars()
        {
            int cap = com.qtools.media.support.SubtitleExcerpt.TAIL_CHARS;
            if (subtitleTailChars <= 0)
            {
                return 0;
            }
            return Math.min(subtitleTailChars, cap);
        }

        public Vision getVision()
        {
            return vision == null ? new Vision() : vision;
        }

        public void setVision(Vision vision)
        {
            this.vision = vision;
        }

        public Transcribe getTranscribe()
        {
            return transcribe == null ? new Transcribe() : transcribe;
        }

        public void setTranscribe(Transcribe transcribe)
        {
            this.transcribe = transcribe;
        }

        /** 归一化后的 provider：只有明确写 openai / openai-compatible 才走兼容端点，其余按 ollama */
        public String getEffectiveProvider()
        {
            String v = provider == null ? "" : provider.trim().toLowerCase();
            if (v.startsWith("openai") || "compatible".equals(v))
            {
                return "openai";
            }
            return "ollama";
        }

        /** 实际请求地址：显式 baseUrl 优先，否则按 provider 取本机默认 */
        public String getEffectiveBaseUrl()
        {
            String v = baseUrl == null ? "" : baseUrl.trim();
            if (!v.isEmpty())
            {
                return v;
            }
            return "openai".equals(getEffectiveProvider())
                    ? "http://127.0.0.1:8080/v1/chat/completions"
                    : "http://127.0.0.1:11434/api/generate";
        }

        /** 实际模型名：空白时回落到默认小模型 */
        public String getEffectiveModel()
        {
            String v = model == null ? "" : model.trim();
            return v.isEmpty() ? "qwen2.5:1.5b" : v;
        }

        /** 实际读超时：小于 1000 按 1000 计（否则一个 0 会让 HttpClient 直接抛参数异常） */
        public int getEffectiveTimeoutMs()
        {
            return timeoutMs < 1000 ? 1000 : timeoutMs;
        }
    }

    /**
     * 画面理解配置（P5.53 追加）：{@code qtools.media.llm.vision.*}
     *
     * <p>库里一半以上的片子没有对白也没有字幕（ASMR / 桌面壁纸 / 写真 / 舞蹈），只靠字幕会覆盖不到。
     * 这一路用 ffmpeg 抽 4-6 帧，交给**视觉模型**逐帧写一句描述，再由文本模型合成梗概。</p>
     *
     * <table border="1">
     *   <caption>键（qtools.media.llm.vision.*）/ 默认值 / 语义</caption>
     *   <tr><th>键</th><th>默认</th><td>&nbsp;</td></tr>
     *   <tr><td>enabled</td><td>false</td><td>总开关；false 时无字幕的片子直接回 insufficient，不抽帧不调模型</td></tr>
     *   <tr><td>model</td><td>qwen2.5vl:3b</td><td>视觉模型名（ollama 里的多模态 tag；需先 {@code ollama pull}）</td></tr>
     *   <tr><td>frames</td><td>5</td><td>抽几帧（2-10 之间夹取；帧越多越费时间，5 帧覆盖开头/中段/结尾）</td></tr>
     *   <tr><td>width</td><td>512</td><td>缩放后的宽度（高度自适应；512 够看清人物/场景，单帧约 30-60KB）</td></tr>
     *   <tr><td>timeoutMs</td><td>60000</td><td>单帧一次视觉推理的读超时；3B 视觉模型在 8GB 显存上首帧要 20-30s</td></tr>
     * </table>
     */
    public static class Vision
    {
        /** 总开关：默认关闭（视觉模型需另外下载约 3GB，不该由一个按钮触发） */
        private boolean enabled = false;

        /** 视觉模型名 */
        private String model = "qwen2.5vl:3b";

        /** 抽帧数量 */
        private int frames = 5;

        /** 输出宽度（像素） */
        private int width = 512;

        /** 单帧推理读超时（毫秒） */
        private int timeoutMs = 60000;

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public int getFrames()
        {
            return frames;
        }

        public void setFrames(int frames)
        {
            this.frames = frames;
        }

        public int getWidth()
        {
            return width;
        }

        public void setWidth(int width)
        {
            this.width = width;
        }

        public int getTimeoutMs()
        {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs)
        {
            this.timeoutMs = timeoutMs;
        }

        /** 实际抽帧数：夹到 [2, 10]（1 帧看不出发展，10 帧以上对 3B 模型没有增益） */
        public int getEffectiveFrames()
        {
            if (frames < 2)
            {
                return 2;
            }
            return Math.min(frames, 10);
        }

        /** 实际宽度：夹到 [256, 1024] */
        public int getEffectiveWidth()
        {
            if (width < 256)
            {
                return 256;
            }
            return Math.min(width, 1024);
        }

        /** 实际读超时：小于 5000 按 5000 计（视觉首帧加载动辄 20s） */
        public int getEffectiveTimeoutMs()
        {
            return timeoutMs < 5000 ? 5000 : timeoutMs;
        }

        /** 实际模型名：空白时回落到默认 3B 视觉模型 */
        public String getEffectiveModel()
        {
            String v = model == null ? "" : model.trim();
            return v.isEmpty() ? "qwen2.5vl:3b" : v;
        }
    }

    /**
     * 音频转写配置（P5.53 交付 3）：{@code qtools.media.llm.transcribe.*}
     *
     * <p><b>本机未装 whisper.cpp（{@code where whisper-cli} 无结果），因此这一路只落地配置与设计，
     * 不实现调用</b>：默认 {@code enabled=false}，开启后服务层会把它当作「不可用来源」跳过，
     * 并在 insufficient 的 reason 里说明原因。</p>
     *
     * <table border="1">
     *   <caption>键（qtools.media.llm.transcribe.*）/ 默认值 / 语义</caption>
     *   <tr><td>enabled</td><td>false</td><td>总开关</td></tr>
     *   <tr><td>whisperPath</td><td>空</td><td>whisper.cpp 的 {@code whisper-cli} 可执行文件路径；空 = 用 PATH 里的</td></tr>
     *   <tr><td>model</td><td>ggml-base.bin</td><td>whisper.cpp 模型文件名 / 路径</td></tr>
     *   <tr><td>timeoutMs</td><td>120000</td><td>单条转写限时（默认 120s，超时即放弃这一条）</td></tr>
     * </table>
     */
    public static class Transcribe
    {
        /** 总开关：默认关闭（本机没有 whisper.cpp） */
        private boolean enabled = false;

        /** whisper-cli 路径；空 = 交给 PATH */
        private String whisperPath = "";

        /** whisper.cpp 模型 */
        private String model = "ggml-base.bin";

        /** 单条转写限时（毫秒） */
        private int timeoutMs = 120000;

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getWhisperPath()
        {
            return whisperPath;
        }

        public void setWhisperPath(String whisperPath)
        {
            this.whisperPath = whisperPath;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public int getTimeoutMs()
        {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs)
        {
            this.timeoutMs = timeoutMs;
        }

        /** 实际限时：夹到 [10000, 600000] */
        public int getEffectiveTimeoutMs()
        {
            if (timeoutMs < 10000)
            {
                return 10000;
            }
            return Math.min(timeoutMs, 600000);
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
