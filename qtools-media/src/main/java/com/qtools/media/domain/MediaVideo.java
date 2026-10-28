package com.qtools.media.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 媒体视频 media_video
 *
 * @author qtools
 */
public class MediaVideo
{
    /** sha1(rel)[:12]（历史身份，保持不变：前端 URL / 统计 target_key / 缩略图路径都还在用它） */
    private String id;

    /**
     * 内容哈希（oshash）—— 改名 / 移动 / 换盘都不变，是收藏与播放进度的稳定身份。
     *
     * <p>老库数据可能为 null（扫描脚本回填之前），此时调用方必须回退旧的 {@link #id}；
     * 详见 {@code doc/媒体库可移植性方案.md}。</p>
     */
    private String contentHash;

    /** 所属库根 media_root.id；null 表示默认根（多库根预留，当前只用一个根） */
    private Integer rootId;

    /** 一级目录（分类） */
    private String folder;

    /** 相对媒体根的路径 */
    private String relPath;

    private String title;

    private Long size;

    /** 时长（秒） */
    private BigDecimal dur;

    private Integer w;

    private Integer h;

    private String vcodec;

    /**
     * 平均帧率（帧/秒）—— P5.16 帧级 seek 用；<b>null = 未探测</b>（老库 / ffprobe 失败）。
     *
     * <p>由 {@code doc/media-import/enrich-meta.js} 用 ffprobe 的 avg_frame_rate（缺失时退
     * r_frame_rate）回填，列是 {@code media_video.fps DECIMAL(8,3)}（见 doc/media_fps.sql）。
     * 前端拿到 null 时不吸帧、不显示帧号，行为退回 P5.15。</p>
     */
    private BigDecimal fps;

    /** 浏览器能否直接播放（1/0） */
    private Integer browserOk;

    /** 相对 media 根的海报路径，如 thumbs/v/xxx.jpg */
    private String posterPath;

    /**
     * 封面来源（P6.00，{@code doc/media_video_poster_source.sql}）：
     * {@code frame} 自动截帧（默认 / 历史行为）· {@code file} 同目录自带封面 · {@code manual} 手动指定
     *
     * <p>{@code posterPath} 始终是<b>派生产物</b>路径（thumbs/v/&lt;key&gt;.jpg，后端按内容寻址落盘文件），
     * 与这一列无关地保持可用；{@code file} 时 {@link #posterFile} 指向源封面文件。</p>
     */
    private String posterSource;

    /**
     * 自带封面文件（相对媒体根、正斜杠；仅 {@code posterSource=file} 时有值）
     *
     * <p>{@code doc/media-import/build-thumbs.js} 拿它直接派生封面缩略图，不再跑 ffmpeg 截帧；
     * 文件被删掉时自动退回截帧。手工设置的 {@code manual} 不会被重扫覆盖。</p>
     */
    private String posterFile;

    /** 相对 media 根的文档路径 */
    private String docPath;

    /** 相对 media 根的字幕路径 */
    private String subPath;

    private Date mtime;

    /** 是否隐藏（1/0）；仅管理端查询会填充 */
    private Integer hidden;

    /**
     * 文件是否已消失（1=缺失 / 0=正常）
     *
     * <p>由 {@code doc/media_missing.sql} 加的列，{@code doc/media-import/scan-media.js} 在每次
     * 真实扫描的收尾阶段维护；{@code --prune-missing --yes} 才会真删除这些行。
     * 列表/索引默认仍带出它们（{@code includeMissing=true}），由调用方自行过滤。</p>
     */
    private Integer missing;

    /** 最后一次在磁盘上看到该文件的扫描时间（缺失行即「最后 seen」） */
    private Date lastSeen;

    /**
     * 人工评分 0-100（100 最好；<b>null = 未评分</b>，0 是有效的最低分）
     *
     * <p>P5-A 起值域由 0-10 扩到 0-100（列类型 tinyint → smallint，存量值已 ×10）；字段名不变。</p>
     *
     * <p>由 {@code media_video.rating} 承载，只在 M3-A 组织能力接口里写入；
     * 扫描脚本从不写这一列，因此重建索引不会冲掉人工评分。</p>
     */
    private Integer rating;

    /**
     * 内容分级（P5.99）：{@code general} 大众 / {@code guidance} 家长指导 / {@code restricted} 限制级
     *
     * <p>由 {@code media_video.content_rating}（VARCHAR(16) NOT NULL DEFAULT 'general'）承载，
     * 值域与校验集中在 {@link com.qtools.media.support.MediaRating}；与 {@link #rating}
     * （人工评分 0-100）是<b>两个不同的东西</b>，接口出参里分级恒叫 {@code contentRating}。</p>
     *
     * <p>未登录访客能否看到这一条，取决于「它在不在收紧的库根里」<b>且</b>「它的分级在
     * {@code media_rating_policy} 里是不是 guest_visible=0」——两者都通过才可见。</p>
     */
    private String contentRating;

    /**
     * 作品简介 / 剧情梗概（P5.38，长文本，保留换行；{@code null} = 没有简介）
     *
     * <p>由 {@code media_video.synopsis}（TEXT NULL，见 {@code doc/media_synopsis.sql}）承载。
     * 与 {@link #rating} 一样是<b>人工属性</b>：扫描脚本从不写这一列，重建索引不会冲掉；
     * 写入只走站端 {@code PUT /api/media/video/{id}/synopsis} 与管理端
     * {@code PUT /api/media/admin/videos/{id}/synopsis}（上限 8000 字符，纯空白归一化成 null）。</p>
     *
     * <p>为什么列表查询（{@link com.qtools.media.mapper.MediaVideoMapper#selectVideoList}）
     * <b>不取</b>这一列：索引体量按「全部视频」计，一条 8KB 正文乘几万 = 几十 MB，
     * 而索引里的条目从不输出 synopsis；只有单片详情 / 简介接口才按需读它。</p>
     */
    private String synopsis;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getContentHash()
    {
        return contentHash;
    }

    public void setContentHash(String contentHash)
    {
        this.contentHash = contentHash;
    }

    public Integer getRootId()
    {
        return rootId;
    }

    public void setRootId(Integer rootId)
    {
        this.rootId = rootId;
    }

    public String getFolder()
    {
        return folder;
    }

    public void setFolder(String folder)
    {
        this.folder = folder;
    }

    public String getRelPath()
    {
        return relPath;
    }

    public void setRelPath(String relPath)
    {
        this.relPath = relPath;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public Long getSize()
    {
        return size;
    }

    public void setSize(Long size)
    {
        this.size = size;
    }

    public BigDecimal getDur()
    {
        return dur;
    }

    public void setDur(BigDecimal dur)
    {
        this.dur = dur;
    }

    public Integer getW()
    {
        return w;
    }

    public void setW(Integer w)
    {
        this.w = w;
    }

    public Integer getH()
    {
        return h;
    }

    public void setH(Integer h)
    {
        this.h = h;
    }

    public String getVcodec()
    {
        return vcodec;
    }

    public void setVcodec(String vcodec)
    {
        this.vcodec = vcodec;
    }

    public BigDecimal getFps()
    {
        return fps;
    }

    public void setFps(BigDecimal fps)
    {
        this.fps = fps;
    }

    public Integer getBrowserOk()
    {
        return browserOk;
    }

    public void setBrowserOk(Integer browserOk)
    {
        this.browserOk = browserOk;
    }

    public String getPosterPath()
    {
        return posterPath;
    }

    public void setPosterPath(String posterPath)
    {
        this.posterPath = posterPath;
    }

    public String getPosterSource()
    {
        return posterSource;
    }

    public void setPosterSource(String posterSource)
    {
        this.posterSource = posterSource;
    }

    public String getPosterFile()
    {
        return posterFile;
    }

    public void setPosterFile(String posterFile)
    {
        this.posterFile = posterFile;
    }

    public String getDocPath()
    {
        return docPath;
    }

    public void setDocPath(String docPath)
    {
        this.docPath = docPath;
    }

    public String getSubPath()
    {
        return subPath;
    }

    public void setSubPath(String subPath)
    {
        this.subPath = subPath;
    }

    public Date getMtime()
    {
        return mtime;
    }

    public void setMtime(Date mtime)
    {
        this.mtime = mtime;
    }

    public Integer getHidden()
    {
        return hidden;
    }

    public void setHidden(Integer hidden)
    {
        this.hidden = hidden;
    }

    public Integer getRating()
    {
        return rating;
    }

    public void setRating(Integer rating)
    {
        this.rating = rating;
    }

    public String getContentRating()
    {
        return contentRating;
    }

    public void setContentRating(String contentRating)
    {
        this.contentRating = contentRating;
    }

    public String getSynopsis()
    {
        return synopsis;
    }

    public void setSynopsis(String synopsis)
    {
        this.synopsis = synopsis;
    }

    public Integer getMissing()
    {
        return missing;
    }

    public void setMissing(Integer missing)
    {
        this.missing = missing;
    }

    public Date getLastSeen()
    {
        return lastSeen;
    }

    public void setLastSeen(Date lastSeen)
    {
        this.lastSeen = lastSeen;
    }
}
