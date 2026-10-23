package com.qtools.media.config;

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
 *   <tr><td>allowOrigins</td><td>*</td><td>-</td><td>QTOOLS_MEDIA_ALLOWORIGINS</td></tr>
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

    /**
     * 允许的跨域来源，逗号分隔；* 表示不限（本地开发）
     *
     * <p>注意：实际生效的跨域放行来自 framework 的全局 CorsFilter（来源不限、放行全部请求头），
     * 它在 Security 过滤链内先执行，本模块无法在其之后再收紧。此处保留配置项，供 M4
     * 需要按模块收紧或补 Access-Control-Expose-Headers 时改 framework 一并使用。</p>
     */
    private String allowOrigins = "*";

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
}
