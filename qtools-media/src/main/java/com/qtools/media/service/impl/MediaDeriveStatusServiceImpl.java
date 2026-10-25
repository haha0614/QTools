package com.qtools.media.service.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaAdminMapper;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.service.IMediaDeriveStatusService;
import com.qtools.media.support.MediaDeriveAlgoMarker;
import com.qtools.media.support.MediaDeriveIndex;
import com.qtools.media.support.MediaDeriveStatusSupport;

/**
 * 派生资源状态查询 服务实现
 *
 * <p>性能取舍（一页 200 行 / 全库 176 条视频的真实规模）：</p>
 * <ol>
 *   <li><b>目录只扫一次</b>：{@link MediaDeriveIndex#scan(File)} 把 thumbs/v、thumbs/s、thumbs/pv2
 *       的文件名与 mtime 读进内存 Map，之后逐行判断只是查表，不再逐行 exists；</li>
 *   <li><b>源 mtime 每行只 stat 一次</b>（三档共用），不是每档一次；</li>
 *   <li><b>预览片段帧数不给</b>：ffprobe 一页就是几十次子进程，{@code frames} 恒为 null；
 *       而且帧数判不了新旧算法（短片新算法也是 32 帧），新旧一律看 mtime（见
 *       {@link MediaDeriveAlgoMarker}）；</li>
 *   <li>kind 过滤只能在内存里做（数据库里没有派生文件），因此只在 kind≠all 时退化为
 *       「按 q / ids 取的整批行 + 内存过滤 + 分页」，{@code total} 是过滤后的行数。</li>
 * </ol>
 *
 * <p>多库根：{@code media_video.root_id} 非空时用 media_root 里那一行的 path 拼源路径，
 * 否则用配置的 {@code qtools.media.rootPath}——与 build-thumbs.js 的 --root 对齐。</p>
 *
 * @author qtools
 */
@Service
public class MediaDeriveStatusServiceImpl implements IMediaDeriveStatusService
{
    /** 每页默认条数 / 上限（上限按「一页最多 200 条，和扫描任务的 videoIds 上限对齐」） */
    private static final int DEFAULT_SIZE = 50;

    private static final int MAX_SIZE = 1000;

    /** ids 参数上限：与 MediaScanServiceImpl 的 videoIds 上限一致 */
    private static final int MAX_IDS = 1000;

    /** 调用方不传 size 时按默认值 */
    private static final String KIND_ALL = "all";

    private static final List<String> KINDS = List.of(KIND_ALL, MediaDeriveIndex.KIND_THUMB,
            MediaDeriveIndex.KIND_SPRITE, MediaDeriveIndex.KIND_PREVIEW);

    /** videoId 形状（库里是 varchar(16)）：只收字母数字与 _-，挡住任意文本进 SQL 的 IN (...) */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    @Autowired
    private MediaAdminMapper mediaAdminMapper;

    @Autowired
    private MediaRootMapper mediaRootMapper;

    @Autowired
    private MediaProperties mediaProperties;

    @Override
    public Map<String, Object> deriveStatus(String ids, String q, String kind, String state, int page, int size)
    {
        List<String> videoIds = parseIds(ids);
        String keyword = trimToNull(q);
        String mode = normalizeKind(kind);
        String st = normalizeState(state);
        int safePage = Math.max(1, page);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(MAX_SIZE, size);
        File thumbs = thumbsDir();
        MediaDeriveIndex index = MediaDeriveIndex.scan(thumbs);
        MediaDeriveAlgoMarker marker = MediaDeriveAlgoMarker.load(thumbs);
        Map<Integer, String> roots = rootPaths();
        // 派生文件不在库里：服务端筛选只能把候选集全取回来，算状态 → 计数 → 过滤 → 再分页
        List<MediaVideo> videos = mediaAdminMapper.selectDeriveVideos(videoIds, keyword);
        List<Map<String, Object>> matched = new ArrayList<>(videos.size());
        Map<String, Object> counts = MediaDeriveStatusSupport.newCounts();
        for (MediaVideo video : videos)
        {
            Map<String, Object> row = viewRow(video, index, roots, marker);
            MediaDeriveStatusSupport.addRowCounts(counts, row);
            if (MediaDeriveStatusSupport.matchState(row, mode, st))
            {
                matched.add(row);
            }
        }
        long total = matched.size();
        int from = Math.min(matched.size(), (safePage - 1) * safeSize);
        int to = Math.min(matched.size(), from + safeSize);
        List<Map<String, Object>> rows = new ArrayList<>(matched.subList(from, to));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("kind", mode);
        out.put("state", st);
        out.put("counts", counts);
        out.put("algoSince", marker.getSinceText());
        out.put("algoSinceSource", marker.getSource());
        return out;
    }

    /**
     * 库行 → 视图行：源 mtime 每行只 stat 一次（三档共用），派生状态全部查索引
     */
    private Map<String, Object> viewRow(MediaVideo video, MediaDeriveIndex index, Map<Integer, String> roots,
            MediaDeriveAlgoMarker marker)
    {
        String deriveName = MediaDeriveStatusSupport.deriveName(video.getContentHash(), video.getId());
        File src = sourceFile(video, roots);
        boolean srcExists = src != null && src.isFile();
        long srcMtime = srcExists ? src.lastModified() : 0L;
        return MediaDeriveStatusSupport.row(video.getId(), video.getTitle(), video.getDur(), deriveName,
                srcMtime, srcExists, index, marker);
    }

    /**
     * 源文件绝对路径：root_id 非空取 media_root 的 path，否则取配置的 rootPath
     *
     * <p>media_root 表没建 / 读库失败一律回落到默认根（派生状态是只读视图，不该因为多根配置挂掉）。</p>
     */
    private File sourceFile(MediaVideo video, Map<Integer, String> roots)
    {
        if (video == null || StringUtils.isEmpty(video.getRelPath()))
        {
            return null;
        }
        String root = null;
        if (video.getRootId() != null)
        {
            root = roots.get(video.getRootId());
        }
        if (StringUtils.isEmpty(root))
        {
            root = mediaProperties.getRootPath();
        }
        if (StringUtils.isEmpty(root))
        {
            return null;
        }
        String rel = video.getRelPath().replace('\\', '/');
        return new File(root, rel.replace('/', File.separatorChar));
    }

    /** 缩略图根（配置项的目录；不存在时索引为空，所有行报 exists=false） */
    private File thumbsDir()
    {
        String path = mediaProperties.getThumbsPath();
        return StringUtils.isEmpty(path) ? null : new File(path);
    }

    /** media_root 全表（id → path）：一次查询，行内多次用 */
    private Map<Integer, String> rootPaths()
    {
        Map<Integer, String> roots = new HashMap<>();
        try
        {
            List<MediaRoot> list = mediaRootMapper.selectRootList();
            if (list == null)
            {
                return roots;
            }
            for (MediaRoot root : list)
            {
                if (root != null && root.getId() != null && StringUtils.isNotEmpty(root.getPath()))
                {
                    roots.put(root.getId(), root.getPath());
                }
            }
        }
        catch (RuntimeException e)
        {
            // 没跑 media_portability.sql / 库不可用：多根不存在是常态，回落到配置的 rootPath
            return roots;
        }
        return roots;
    }

    /**
     * ids 参数：逗号分隔（也接受重复传参拼成的逗号串），≤1000 个
     *
     * @return 空表 = 不按 id 过滤
     */
    private static List<String> parseIds(String ids)
    {
        List<String> out = new ArrayList<>();
        String text = trimToNull(ids);
        if (text == null)
        {
            return out;
        }
        for (String part : text.split(","))
        {
            String id = part.trim();
            if (id.isEmpty() || out.contains(id))
            {
                continue;
            }
            if (!VIDEO_ID.matcher(id).matches())
            {
                throw MediaException.badRequest("ids 含非法 videoId：" + id + "（只允许字母数字与 _-，最长 32 字符）");
            }
            out.add(id);
        }
        if (out.size() > MAX_IDS)
        {
            throw MediaException.badRequest("ids 最多 " + MAX_IDS + " 个（收到 " + out.size() + "）");
        }
        return out;
    }

    private static String normalizeKind(String kind)
    {
        String text = trimToNull(kind);
        if (text == null)
        {
            return KIND_ALL;
        }
        String mode = text.toLowerCase(Locale.ROOT);
        if ("video".equals(mode))
        {
            // 该接口只服务视频派生产物：video 视同 all，免得调用方把「媒体种类」传成 kind 就 400
            return KIND_ALL;
        }
        if (!KINDS.contains(mode))
        {
            throw MediaException.badRequest("kind 只接受 " + String.join(" / ", KINDS) + "（收到：" + kind + "）");
        }
        return mode;
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    /** state 参数归一：all|ok|oldAlgo|stale|missing，非法值 400 */
    private static String normalizeState(String state)
    {
        if (StringUtils.isEmpty(state))
        {
            return MediaDeriveStatusSupport.STATE_ALL;
        }
        String v = state.trim();
        if (MediaDeriveStatusSupport.STATES.contains(v))
        {
            return v;
        }
        throw new MediaException(400, "state 不支持：" + state + "（可用 all / ok / oldAlgo / stale / missing）");
    }
}
