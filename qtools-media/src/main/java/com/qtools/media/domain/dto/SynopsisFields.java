package com.qtools.media.domain.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 作品简介自动生成的**结构化输入**（P5.47）
 *
 * <p>模板生成与本地模型生成共用同一份素材：只有这些字段可以进提示词 / 进模板，
 * 因此「不编造剧情」这条约束在数据结构层面就成立（没有剧情字段可填）。</p>
 *
 * <p>所有字符串字段都可能是 null / 空串（老库常见缺人物、缺标签），渲染侧必须自动省略，
 * 绝不留「无」「暂无」这类占位词。</p>
 *
 * @author qtools
 */
public class SynopsisFields
{
    /** 视频 id（回包用，不参与渲染） */
    private String videoId;

    /** 标题（media_video.title） */
    private String title;

    /** 一级目录 / 分类（media_video.folder） */
    private String folder;

    /** 时长（秒）；null = 未探测 */
    private BigDecimal dur;

    /** 相对媒体根的路径（文件名里的主题词从这里来） */
    private String relPath;

    /** 人物 / 演员名（已去重，顺序与索引一致） */
    private List<String> persons = new ArrayList<>();

    /** 标签名（字典标签 + 扫描 facet 标签，已去重） */
    private List<String> tags = new ArrayList<>();

    /** 工作室名 */
    private List<String> studios = new ArrayList<>();

    /** 时间点标记条数（0 = 没有标记） */
    private int markerCount;

    public String getVideoId()
    {
        return videoId;
    }

    public void setVideoId(String videoId)
    {
        this.videoId = videoId;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getFolder()
    {
        return folder;
    }

    public void setFolder(String folder)
    {
        this.folder = folder;
    }

    public BigDecimal getDur()
    {
        return dur;
    }

    public void setDur(BigDecimal dur)
    {
        this.dur = dur;
    }

    public String getRelPath()
    {
        return relPath;
    }

    public void setRelPath(String relPath)
    {
        this.relPath = relPath;
    }

    public List<String> getPersons()
    {
        return persons;
    }

    public void setPersons(List<String> persons)
    {
        this.persons = persons == null ? new ArrayList<>() : persons;
    }

    public List<String> getTags()
    {
        return tags;
    }

    public void setTags(List<String> tags)
    {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }

    public List<String> getStudios()
    {
        return studios;
    }

    public void setStudios(List<String> studios)
    {
        this.studios = studios == null ? new ArrayList<>() : studios;
    }

    public int getMarkerCount()
    {
        return markerCount;
    }

    public void setMarkerCount(int markerCount)
    {
        this.markerCount = markerCount;
    }
}
