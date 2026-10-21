package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.media.domain.MediaOwnerSession;

/**
 * 用户数据（收藏 / 最近浏览 / 资料）服务
 *
 * <p>所有读写按 owner_key 隔离；输出结构对齐 Node 版 data/user.json 的条目形状
 * （video: {kind,id,title,thumb,t[,pos,dur]}；photo: {kind,album,i,seq,title,thumb,t}）。</p>
 *
 * @author qtools
 */
public interface IMediaUserService
{
    /** GET /user：完整对象 */
    Map<String, Object> selectUser(MediaOwnerSession session);

    /** POST /user：合并式更新（profile / favorites / recentVideos / recentPhotos，兼容 Node 的 fav / recent 单条形态） */
    Map<String, Object> mergeUser(MediaOwnerSession session, Map<String, Object> body);

    /** POST /fav：单一收藏，幂等 */
    Map<String, Object> addFavorite(MediaOwnerSession session, String kind, String id, String album, Integer seq);

    /** DELETE /fav：取消收藏，幂等 */
    Map<String, Object> removeFavorite(MediaOwnerSession session, String kind, String id, String album, Integer seq);

    /** GET /fav/list */
    List<Map<String, Object>> selectFavoriteList(MediaOwnerSession session);

    /** POST /recent：记录浏览（视频带 pos/dur） */
    Map<String, Object> addRecent(MediaOwnerSession session, String kind, String id, String album, Integer seq,
            Double pos, Double dur);

    /** GET /recent/list：kind 为 null 时两类都返回 */
    Map<String, Object> selectRecentList(MediaOwnerSession session, String kind);

    /** GET /me：身份 + 计数 */
    Map<String, Object> selectMe(MediaOwnerSession session);
}
