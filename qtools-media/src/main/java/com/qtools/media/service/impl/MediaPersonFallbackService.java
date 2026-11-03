package com.qtools.media.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * 「素人兜底」不变量（P6.27）：不允许存在没有人物归属的视频 / 相册。
 *
 * <p>用户口径：所有没有人物归属的资源，一律归到「素人」。以前站端只是把卡片文案兜成素人，
 * 数据里没有关系，导致人物页（按真实关系计数）和视频库（按文案兜底）对不上，
 * 相册列表里还多出一个「未归类」分组。</p>
 *
 * <p>本类只做一件事：把「还没归属的」补齐。幂等，可以在每次扫描入库之后、以及每次改人物之后调用。</p>
 *
 * @author qtools
 */
@Service
public class MediaPersonFallbackService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonFallbackService.class);

    private static final String FALLBACK_NAME = "素人";

    @Autowired
    private DataSource dataSource;

    /**
     * 补齐兜底归属：没有演员的视频挂素人；person_id 为空的相册挂素人并补关系。
     *
     * @return 本次补了多少行（视频 + 相册），0 表示本来就干净
     */
    public int ensureFallback()
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer personId = findOrCreateFallback(jdbc);
        if (personId == null)
        {
            log.warn("素人兜底跳过：media_person 里没有素人且创建失败");
            return 0;
        }
        int videos = jdbc.update(
                "insert into media_video_actor (video_id, name, person_id, role)"
                        + " select v.id, ?, ?, null from media_video v"
                        + " where not exists (select 1 from media_video_actor a where a.video_id = v.id)",
                FALLBACK_NAME, personId);
        int albums = jdbc.update("update media_album set person_id = ? where person_id is null", personId);
        jdbc.update("insert ignore into media_album_person (album_id, person_id)"
                + " select a.id, ? from media_album a where a.person_id = ?", personId, personId);
        if (videos > 0 || albums > 0)
        {
            log.info("素人兜底：{} 条视频、{} 本相册补上了归属", videos, albums);
        }
        return videos + albums;
    }

    private Integer findOrCreateFallback(JdbcTemplate jdbc)
    {
        try
        {
            Integer id = jdbc.queryForObject(
                    "select id from media_person where name = ? limit 1", Integer.class, FALLBACK_NAME);
            if (id != null)
            {
                return id;
            }
        }
        catch (RuntimeException ignore)
        {
            // 查不到就走创建
        }
        try
        {
            jdbc.update("insert into media_person (name, hidden) values (?, 0)", FALLBACK_NAME);
            return jdbc.queryForObject(
                    "select id from media_person where name = ? limit 1", Integer.class, FALLBACK_NAME);
        }
        catch (RuntimeException e)
        {
            log.warn("创建素人失败: {}", e.getMessage());
            return null;
        }
    }
}
