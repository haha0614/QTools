package com.qtools.media.service.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.media.domain.MediaOwnerSession;
import com.qtools.media.domain.MediaPlaylist;
import com.qtools.media.domain.MediaPlaylistItem;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaFavoriteMapper;
import com.qtools.media.mapper.MediaPlaylistItemMapper;
import com.qtools.media.mapper.MediaPlaylistMapper;
import com.qtools.media.mapper.MediaRecentMapper;
import com.qtools.media.mapper.MediaSavedFilterMapper;
import com.qtools.media.service.IMediaAccountSyncService;

/**
 * 设备态 → 账号态的数据接管实现（P5.74）
 *
 * <p><b>合并算法</b>（源 = 设备键，目标 = {@code u:<appUserId>}）：</p>
 * <ol>
 *   <li><b>播放列表</b>逐条处理：账号下没有同名 → 在账号下新建一条同名的，并把条目按原序整段复制过去；
 *       账号下已有同名 → 只把账号里还缺的条目按设备顺序追加到末尾（去重保序）。</li>
 *   <li><b>收藏 / 最近浏览</b>：一条 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 把账号里
 *       还缺的行补进去，判重口径与单条写入完全一致（content_hash 优先、老字段回退）。</li>
 *   <li><b>保存的筛选</b>：账号下没有同名的复制一份过去，同名的不动。</li>
 * </ol>
 *
 * <p><b>一律复制、绝不删除设备态</b>：登出之后设备态的播放列表 / 收藏 / 浏览 / 筛选原样还在
 * （撤账号不会连带撤数据）。</p>
 *
 * <p><b>幂等</b>：三条路径都只做「账号里还缺的部分」——播放列表判「同名 + videoId 是否存在」、
 * 收藏与浏览命中 {@code NOT EXISTS}、筛选判「同名是否存在」。所以第二次调用时账号里已无缺口，
 * 返回值里的 {@code playlistsAdded/playlistsMerged/playlistItemsAdded/favoritesAdded/recentsAdded/filtersAdded}
 * 全是 0（同名列表补进 0 条新条目时也不计入 {@code playlistsMerged}）。没有任何「已合并」标记位，
 * 重复登录不会累积任何东西，也不会重复弹 toast。</p>
 *
 * @author qtools
 */
@Service
public class MediaAccountSyncServiceImpl implements IMediaAccountSyncService
{
    private static final Logger log = LoggerFactory.getLogger(MediaAccountSyncServiceImpl.class);

    @Autowired
    private MediaPlaylistMapper mediaPlaylistMapper;

    @Autowired
    private MediaPlaylistItemMapper mediaPlaylistItemMapper;

    @Autowired
    private MediaFavoriteMapper mediaFavoriteMapper;

    @Autowired
    private MediaRecentMapper mediaRecentMapper;

    @Autowired
    private MediaSavedFilterMapper mediaSavedFilterMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncDeviceToAccount(MediaOwnerSession session)
    {
        if (session == null || !session.isAccount())
        {
            // 匿名请求没有目标账号，合并无从谈起；这不是「未授权访问数据」，所以是 400 而不是 401
            throw MediaException.badRequest("未登录：设备数据并入账号需要有效的 Authorization: Bearer <app token>");
        }
        String from = session.getDeviceKey();
        String to = session.getOwnerKey();

        int listsAdded = 0;
        int listsMerged = 0;
        int itemsAdded = 0;
        for (MediaPlaylist source : mediaPlaylistMapper.selectByOwnerKey(from))
        {
            MediaPlaylist target = mediaPlaylistMapper.selectByNameAndOwner(source.getName(), to);
            if (target == null)
            {
                // 账号下没有同名：整条复制一份到账号（设备态那条原地不动）
                MediaPlaylist copy = new MediaPlaylist();
                copy.setName(source.getName());
                copy.setOwnerKey(to);
                copy.setNote(source.getNote());
                if (mediaPlaylistMapper.insert(copy) <= 0)
                {
                    continue;
                }
                listsAdded++;
                itemsAdded += copyItems(source.getId(), copy.getId());
                continue;
            }
            // 同名：条目去重保序追加；一条都没新增就不算「合并了一个列表」（第二次登录统计恒 0）
            int added = copyItems(source.getId(), target.getId());
            itemsAdded += added;
            if (added > 0)
            {
                mediaPlaylistMapper.touchById(target.getId());
                listsMerged++;
            }
        }

        int favoritesAdded = mediaFavoriteMapper.copyMissingToOwner(from, to);
        int recentsAdded = mediaRecentMapper.copyMissingToOwner(from, to);
        int filtersAdded = mediaSavedFilterMapper.copyMissingToOwner(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("synced", true);
        result.put("deviceOwner", from);
        result.put("accountOwner", to);
        result.put("userId", session.getUserId());
        // 播放列表：「账号下新增 N 个」+「同名列表补进 M 个新条目」；前端 toast 的「列表 X 个」= N + M
        result.put("playlistsAdded", listsAdded);
        result.put("playlistsMerged", listsMerged);
        result.put("playlistItemsAdded", itemsAdded);
        result.put("favoritesAdded", favoritesAdded);
        result.put("recentsAdded", recentsAdded);
        result.put("filtersAdded", filtersAdded);
        log.info("设备数据并入账号 device={} account={} lists(+{} / merge {}) items+{} fav+{} recent+{} filter+{}",
                from, to, listsAdded, listsMerged, itemsAdded, favoritesAdded, recentsAdded, filtersAdded);
        return result;
    }

    /**
     * 把设备列表的有序条目补进账号列表：账号列表原有顺序在前，设备列表里账号没有的按设备顺序追加
     *
     * <p>去重靠「账号侧已有 videoId 集合」，因此设备列表内部的重复（理论上被唯一键挡住）也不会带进来；
     * {@code seq} 从账号列表当前最大值 +1 继续，主键 {@code (playlist_id, seq)} 不会撞。
     * 目标列表为空（刚复制出来的新列表）时 seq 从 0 开始，顺序与设备侧逐字一致。</p>
     *
     * @return 实际追加的条目数；0 表示账号里已经全都有（= 幂等的那一次）
     */
    private int copyItems(Long sourceId, Long targetId)
    {
        Set<String> existing = new LinkedHashSet<>(mediaPlaylistItemMapper.selectVideoIds(targetId));
        Integer maxSeq = mediaPlaylistItemMapper.selectMaxSeq(targetId);
        int seq = maxSeq == null ? 0 : maxSeq + 1;
        int added = 0;
        for (String videoId : mediaPlaylistItemMapper.selectVideoIds(sourceId))
        {
            if (!existing.add(videoId))
            {
                continue;
            }
            MediaPlaylistItem item = new MediaPlaylistItem();
            item.setPlaylistId(targetId);
            item.setVideoId(videoId);
            item.setSeq(seq + added);
            added += mediaPlaylistItemMapper.insertItem(item);
        }
        return added;
    }
}
