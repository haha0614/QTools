package com.qtools.media.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaPlaylist;
import com.qtools.media.domain.MediaPlaylistItem;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaPlaylistItemMapper;
import com.qtools.media.mapper.MediaPlaylistMapper;
import com.qtools.media.service.IMediaPlaylistService;

/**
 * 播放列表 服务实现（P5-B）
 *
 * <p>四条硬规则：</p>
 * <ol>
 *   <li><b>同名同 owner 复用</b>：先按 {@code (name, owner_key)} 查，命中直接返回既有行
 *       （{@code created=false}）。不同设备可以各有一个「稍后看」，互不影响；</li>
 *   <li><b>站端只碰自己的</b>：任何写操作先 {@code selectById} + owner 比对，对不上就 404，
 *       连「这条存在但不是你的」都不透露；全局列表（{@code owner_key IS NULL}）站端一律不可写，
 *       清理走管理端；</li>
 *   <li><b>顺序即语义</b>：全量替换 = 清空后按数组顺序插 0..n-1；删除单条后把后面的 seq 前移，
 *       保证「同一列表内 seq 从 0 连续」。视频重复由 {@code (playlist_id, video_id)} 唯一键兜底，
 *       本层再去一次重（保序去重，防止一次请求撞唯一键）；</li>
 *   <li><b>条目容许指向已消失的视频</b>：详情 left join media_video，视频不在了 title / dur 为 null，
 *       条目保留（删不删由用户决定，扫描 / 软删不该悄悄改别人的列表）。</li>
 * </ol>
 *
 * @author qtools
 */
@Service
public class MediaPlaylistServiceImpl implements IMediaPlaylistService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPlaylistServiceImpl.class);

    /** name 上限（与 media_playlist.name VARCHAR(64) 一致） */
    private static final int NAME_MAX = 64;

    /** videoId 上限（与 media_video.id VARCHAR(16) 一致） */
    private static final int VIDEO_ID_MAX = 16;

    /**
     * 单个列表的条目上限
     *
     * <p>没有硬约束的话，一次 PUT 传 10 万条就能把库和响应都拖垮；5000 对「播放列表」这种
     * 人肉维护的对象已经远超实际（一个列表 5000 首 ≈ 300 小时）。</p>
     */
    private static final int MAX_ITEMS = 5000;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private MediaPlaylistMapper mediaPlaylistMapper;

    @Autowired
    private MediaPlaylistItemMapper mediaPlaylistItemMapper;

    @Override
    public List<Map<String, Object>> list(String ownerKey)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaPlaylist playlist : mediaPlaylistMapper.selectVisibleList(normalizeOwner(ownerKey)))
        {
            out.add(toView(playlist, false));
        }
        return out;
    }

    @Override
    public Map<String, Object> create(String ownerKey, String name)
    {
        String owner = requireOwner(ownerKey);
        String nameText = requireName(name);
        MediaPlaylist existing = mediaPlaylistMapper.selectByNameAndOwner(nameText, owner);
        if (existing != null)
        {
            Map<String, Object> view = toView(existing, false);
            view.put("created", false);
            return view;
        }
        MediaPlaylist playlist = new MediaPlaylist();
        playlist.setName(nameText);
        playlist.setOwnerKey(owner);
        mediaPlaylistMapper.insert(playlist);
        MediaPlaylist stored = mediaPlaylistMapper.selectById(playlist.getId());
        Map<String, Object> view = stored == null ? toView(playlist, false) : toView(stored, false);
        view.put("created", true);
        log.info("新建播放列表 owner={} name={} id={}", owner, nameText, playlist.getId());
        return view;
    }

    @Override
    public Map<String, Object> detail(String ownerKey, Long id)
    {
        return detailView(requireReadable(ownerKey, id));
    }

    @Override
    public Map<String, Object> update(String ownerKey, Long id, String name, List<String> videoIds)
    {
        MediaPlaylist playlist = requireReadable(ownerKey, id);
        if (name != null)
        {
            String nameText = requireName(name);
            if (!nameText.equals(playlist.getName()))
            {
                MediaPlaylist same = mediaPlaylistMapper.selectByNameAndOwner(nameText,
                        normalizeOwner(ownerKey));
                if (same != null && !same.getId().equals(playlist.getId()))
                {
                    throw MediaException.conflict("同名播放列表已存在：" + nameText);
                }
                mediaPlaylistMapper.updateNameById(playlist.getId(), nameText);
            }
        }
        if (videoIds != null)
        {
            List<String> ids = normalizeVideoIds(videoIds);
            mediaPlaylistItemMapper.deleteByPlaylistId(playlist.getId());
            int seq = 0;
            for (String videoId : ids)
            {
                insertItem(playlist.getId(), videoId, seq++);
            }
            mediaPlaylistMapper.touchById(playlist.getId());
            log.info("播放列表全量替换 id={} owner={} items={}", playlist.getId(),
                    normalizeOwner(ownerKey), ids.size());
        }
        // 重新读一遍：改名的结果在库里，手里的对象仍是旧名（否则 PUT 的响应会回显旧名字）
        return detailView(requireReadable(ownerKey, id));
    }

    @Override
    public Map<String, Object> addItem(String ownerKey, Long id, String videoId)
    {
        return addItems(ownerKey, id, java.util.Arrays.asList(videoId));
    }

    @Override
    public Map<String, Object> addItems(String ownerKey, Long id, List<String> videoIds)
    {
        MediaPlaylist playlist = requireReadable(ownerKey, id);
        // 保序去重：一次请求里给了同一个 id 两遍不该撞唯一键
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : videoIds)
        {
            unique.add(requireVideoId(raw));
        }
        List<String> addedIds = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        for (String video : unique)
        {
            if (mediaPlaylistItemMapper.countByPlaylistAndVideo(playlist.getId(), video) > 0)
            {
                // 幂等：同一视频重复加入是 no-op，不是 409（前端把它当「已加入」，不是失败）
                skippedIds.add(video);
                continue;
            }
            Integer maxSeq = mediaPlaylistItemMapper.selectMaxSeq(playlist.getId());
            int seq = maxSeq == null ? 0 : maxSeq.intValue() + 1;
            if (seq >= MAX_ITEMS)
            {
                throw MediaException.conflict("播放列表条目已达上限 " + MAX_ITEMS);
            }
            insertItem(playlist.getId(), video, seq);
            addedIds.add(video);
        }
        if (!addedIds.isEmpty())
        {
            mediaPlaylistMapper.touchById(playlist.getId());
        }
        Map<String, Object> view = detailView(playlist);
        view.put("added", !addedIds.isEmpty());
        view.put("addedIds", addedIds);
        view.put("skippedIds", skippedIds);
        return view;
    }

    @Override
    public Map<String, Object> removeItem(String ownerKey, Long id, String videoId)
    {
        MediaPlaylist playlist = requireReadable(ownerKey, id);
        String video = requireVideoId(videoId);
        int rows = mediaPlaylistItemMapper.deleteItem(playlist.getId(), video);
        if (rows > 0)
        {
            renumber(playlist.getId());
            mediaPlaylistMapper.touchById(playlist.getId());
        }
        // rows == 0 不报 404：幂等语义（前端单条删除失败会退回整集合 PUT，200 才是「结果已达成」）
        Map<String, Object> view = detailView(playlist);
        view.put("removed", rows);
        return view;
    }

    @Override
    public Map<String, Object> delete(String ownerKey, Long id)
    {
        MediaPlaylist playlist = requireReadable(ownerKey, id);
        mediaPlaylistItemMapper.deleteByPlaylistId(playlist.getId());
        int rows = mediaPlaylistMapper.deleteByIdAndOwner(playlist.getId(), normalizeOwner(ownerKey));
        if (rows == 0)
        {
            // 并发下被别人先删掉了：对外仍是 404，与「不存在」同口径
            throw MediaException.notFound("playlist not found: " + id);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", playlist.getId());
        view.put("name", playlist.getName());
        view.put("deleted", rows);
        log.info("删除播放列表 id={} owner={} name={}", playlist.getId(), normalizeOwner(ownerKey),
                playlist.getName());
        return view;
    }

    @Override
    public List<Map<String, Object>> adminList()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaPlaylist playlist : mediaPlaylistMapper.selectAll())
        {
            out.add(toView(playlist, true));
        }
        return out;
    }

    @Override
    public Map<String, Object> adminDelete(Long id)
    {
        if (id == null || id.longValue() <= 0L)
        {
            throw MediaException.badRequest("id 不合法");
        }
        MediaPlaylist existing = mediaPlaylistMapper.selectById(id);
        if (existing == null)
        {
            throw MediaException.notFound("playlist not found: " + id);
        }
        mediaPlaylistItemMapper.deleteByPlaylistId(id);
        mediaPlaylistMapper.deleteById(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("name", existing.getName());
        view.put("ownerKey", existing.getOwnerKey());
        view.put("deleted", 1);
        return view;
    }

    // ------------------------------------------------------------------ 内部

    /** 读：自己的 + 全局的；不存在 404 */
    private MediaPlaylist requireReadable(String ownerKey, Long id)
    {
        if (id == null || id.longValue() <= 0L)
        {
            throw MediaException.badRequest("id 不合法");
        }
        MediaPlaylist playlist = mediaPlaylistMapper.selectById(id);
        String owner = normalizeOwner(ownerKey);
        if (playlist == null || !belongsTo(playlist, owner))
        {
            throw MediaException.notFound("playlist not found: " + id);
        }
        return playlist;
    }

    /** 全局列表对所有访客可读，但只有自己的行可写 */
    private static boolean belongsTo(MediaPlaylist playlist, String ownerKey)
    {
        String stored = playlist.getOwnerKey();
        if (stored == null)
        {
            return true;
        }
        return stored.equals(ownerKey);
    }

    /** 写：必须先拿到自己的 owner（站端 resolve 永远非空，为空说明调用方漏了） */
    private static String requireOwner(String ownerKey)
    {
        String owner = normalizeOwner(ownerKey);
        if (owner == null)
        {
            throw MediaException.badRequest("缺少 owner（X-Ms-Owner 请求头 / ms_owner cookie）");
        }
        return owner;
    }

    /** 删除单条后把后面的 seq 前移：按 seq 升序处理，目标 seq 恒小于当前值，不会撞主键 */
    private void renumber(Long playlistId)
    {
        List<String> videoIds = mediaPlaylistItemMapper.selectVideoIds(playlistId);
        int seq = 0;
        for (String videoId : videoIds)
        {
            mediaPlaylistItemMapper.updateSeq(playlistId, videoId, seq++);
        }
    }

    private void insertItem(Long playlistId, String videoId, int seq)
    {
        MediaPlaylistItem item = new MediaPlaylistItem();
        item.setPlaylistId(playlistId);
        item.setVideoId(videoId);
        item.setSeq(seq);
        mediaPlaylistItemMapper.insertItem(item);
    }

    private Map<String, Object> detailView(MediaPlaylist playlist)
    {
        Map<String, Object> node = toView(playlist, false);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MediaPlaylistItem item : mediaPlaylistItemMapper.selectByPlaylistId(playlist.getId()))
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("videoId", item.getVideoId());
            entry.put("title", item.getTitle());
            entry.put("dur", item.getDur());
            entry.put("seq", item.getSeq());
            items.add(entry);
        }
        node.put("items", items);
        // 同一个列表再挂一次 videos：前端（P5-C）归一化认 videos，后端不必让它猜
        node.put("videos", items);
        node.put("count", items.size());
        return node;
    }

    /** name：去空格后 1-64 字符；空 / 超长一律 400 */
    private static String requireName(String name)
    {
        String text = name == null ? null : name.trim();
        if (text == null || text.isEmpty())
        {
            throw MediaException.badRequest("name 不能为空（1-" + NAME_MAX + " 个字符）");
        }
        if (text.length() > NAME_MAX)
        {
            throw MediaException.badRequest("name 过长：" + text.length() + " 个字符（上限 " + NAME_MAX + "）");
        }
        return text;
    }

    /** videoId：去空格后 1-16 字符（与 media_video.id 同宽）；只校验形状，不校验存在性 */
    private static String requireVideoId(String videoId)
    {
        String text = videoId == null ? null : videoId.trim();
        if (text == null || text.isEmpty())
        {
            throw MediaException.badRequest("videoId 不能为空");
        }
        if (text.length() > VIDEO_ID_MAX)
        {
            throw MediaException.badRequest("videoId 过长：" + text.length() + " 个字符（上限 " + VIDEO_ID_MAX + "）");
        }
        return text;
    }

    /** 全量替换前的整形：逐个校验 + 保序去重 + 条数上限（重复项会撞唯一键，先在这里吃掉） */
    private static List<String> normalizeVideoIds(List<String> videoIds)
    {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : videoIds)
        {
            unique.add(requireVideoId(raw));
        }
        if (unique.size() > MAX_ITEMS)
        {
            throw MediaException.badRequest("videoIds 过多：" + unique.size() + " 条（上限 " + MAX_ITEMS + "）");
        }
        return new ArrayList<>(unique);
    }

    /** 空白 owner 一律按 null（全局）处理 */
    private static String normalizeOwner(String ownerKey)
    {
        return StringUtils.isEmpty(ownerKey) ? null : ownerKey.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> toView(MediaPlaylist playlist, boolean admin)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", playlist.getId());
        node.put("name", playlist.getName());
        node.put("count", playlist.getItemCount() == null ? 0 : playlist.getItemCount());
        if (admin)
        {
            node.put("ownerKey", playlist.getOwnerKey());
            node.put("global", playlist.getOwnerKey() == null);
            node.put("note", playlist.getNote());
            node.put("createTime", formatDate(playlist.getCreateTime()));
            node.put("updateTime", formatDate(playlist.getUpdateTime()));
        }
        return node;
    }

    private static String formatDate(Date date)
    {
        return date == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, date);
    }
}
