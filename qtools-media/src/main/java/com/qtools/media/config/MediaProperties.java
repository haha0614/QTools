package com.qtools.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体库本地配置（前缀 qtools.media）
 *
 * @author qtools
 */
@Component
@ConfigurationProperties(prefix = "qtools.media")
public class MediaProperties
{
    /** 媒体根目录（只读，例如 E:\111\视频） */
    private String rootPath;

    /** 缩略图根目录（含 v/p/pv 三个子目录） */
    private String thumbsPath;

    /** ffmpeg 可执行文件路径 */
    private String ffmpeg;

    /** 实时转码并发上限 */
    private int maxTranscode = 2;

    /** 回收站根目录，默认 {@code <rootPath>\_媒体站\trash}（软删只移动、绝不真删） */
    private String trashPath;

    /** Node 可执行文件（重建索引任务用） */
    private String nodePath = "D:\\Tools\\Node\\node.exe";

    /** 重建索引脚本：复用 doc/media-import/import.js */
    private String scanScript = "E:\\111\\project\\QTools\\QTools\\doc\\media-import\\import.js";

    /** 扫描产物目录（import.sql / report.md），与仓库隔离 */
    private String scanOutPath = "E:\\111\\project\\QTools\\uploadPath\\media-scan";

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
     * 缩略图增量脚本（{@code type=thumb} 扫描任务执行它）
     *
     * <p>脚本自带增量判定（按源文件 mtime 与产物对比）：默认参数即为增量模式，
     * 绝不能带 {@code --force}（那会重建全部 1 万多张缩略图）。</p>
     */
    private String thumbScript = "E:\\111\\视频\\_媒体站\\tools\\build_thumbs.js";

    /** 缩略图扫描附加参数；空 = 增量全量跑一遍（videos + photos + previews 三档，已存在的跳过） */
    private String thumbArgs = "";

    /** 缩略图扫描超时（分钟）：1 万条约 8-10 分钟，留足余量 */
    private int thumbTimeoutMinutes = 30;

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
}
