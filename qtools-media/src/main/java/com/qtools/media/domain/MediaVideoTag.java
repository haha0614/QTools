package com.qtools.media.domain;

/**
 * 视频标签 media_video_tag
 *
 * @author qtools
 */
public class MediaVideoTag
{
    private String videoId;

    /** facet（分类/类型/题材/…） */
    private String facet;

    private String name;

    public String getVideoId()
    {
        return videoId;
    }

    public void setVideoId(String videoId)
    {
        this.videoId = videoId;
    }

    public String getFacet()
    {
        return facet;
    }

    public void setFacet(String facet)
    {
        this.facet = facet;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }
}
