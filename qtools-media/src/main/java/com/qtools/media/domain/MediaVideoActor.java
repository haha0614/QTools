package com.qtools.media.domain;

/**
 * 视频演员 media_video_actor
 *
 * <p>表里有两类行（P3-A 起）：</p>
 * <ul>
 *   <li>扫描脚本写的 name-only 历史行：{@code personId == null}，没有角色</li>
 *   <li>管理端绑定的人物行：{@code personId = media_person.id}，{@code name} 冗余人物名，
 *       {@code role} 为角色（如 主演 / 配角），可空</li>
 * </ul>
 *
 * <p>与 {@code media_video_tag.tag_id} 同风格：所有「全量替换」只动 {@code person_id IS NOT NULL} 的行。</p>
 *
 * @author qtools
 */
public class MediaVideoActor
{
    private String videoId;

    private String name;

    /** 人物 id（NULL = 扫描脚本写的 name-only 历史行） */
    private Long personId;

    /** 角色（NULL = 未指定） */
    private String role;

    public String getVideoId()
    {
        return videoId;
    }

    public void setVideoId(String videoId)
    {
        this.videoId = videoId;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Long getPersonId()
    {
        return personId;
    }

    public void setPersonId(Long personId)
    {
        this.personId = personId;
    }

    public String getRole()
    {
        return role;
    }

    public void setRole(String role)
    {
        this.role = role;
    }
}
