package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.qtools.common.security.IAppTokenVerifier;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaGuestMapper;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaGuestAccessService;
import com.qtools.media.support.BearerTokens;

/**
 * 未登录可见性判定实现（P5.78）
 *
 * <p>三类资源的「在哪个根下」全部走 {@link MediaGuestMapper} 的点查；根清单、隐藏根集合、
 * 根的 realpath 缓存在 {@link Snapshot} 里（{@link IMediaGuestAccessService#TTL_MS}），
 * 因此每个请求最多多出<b>一次</b>主键 / 索引点查，隐藏根集合为空时（默认状态）一次都没有。</p>
 *
 * <p><b>失败方向：放行</b>。{@code guest_visible} 列还没迁移、库连不上、根路径解析异常，
 * 一律当成「没有收紧的根」——媒体站匿名必须永远可用，判定组件坏了不能把整站变成 404。</p>
 *
 * @author qtools
 */
@Service
public class MediaGuestAccessServiceImpl implements IMediaGuestAccessService
{
    private static final Logger log = LoggerFactory.getLogger(MediaGuestAccessServiceImpl.class);

    /** 路由模板上的 {@code /api} 前缀：Spring 的 addPathPrefix 会把它加进 pattern 里 */
    private static final String API_PREFIX = "/api";

    /** 站端路由的公共前缀（{@code /media/admin/**} 是管理端，由 Security 管，不受本判定影响） */
    private static final String STATION_PREFIX = "/media/";

    private static final String ADMIN_PREFIX = "/media/admin";

    @Autowired
    private MediaGuestMapper mediaGuestMapper;

    @Autowired
    private MediaRootMapper mediaRootMapper;

    /** 只用来按相对路径定位磁盘文件（{@code /media/file|stream|sub} 的 {@code p} 参数） */
    @Autowired
    private IMediaFileService mediaFileService;

    /** token 校验（qtools-app 提供）：媒体模块单独部署 / 单测时没有该 bean，一切按未登录处理 */
    @Autowired
    private ObjectProvider<IAppTokenVerifier> appTokenVerifier;

    /** 根缓存；{@code null} = 还没建过，下次访问时建 */
    private volatile Snapshot snapshot;

    /** 上一次告警的时间（列未迁移 / 库读不到时不要每个请求刷一行日志） */
    private volatile long lastWarnAt;

    // ------------------------------------------------------------------ 登录态

    @Override
    public boolean isGuest(HttpServletRequest request)
    {
        if (request == null)
        {
            return false;
        }
        IAppTokenVerifier verifier = appTokenVerifier == null ? null : appTokenVerifier.getIfAvailable();
        if (verifier == null)
        {
            // 没有校验组件 = 本部署不存在登录态，谈不上收紧
            return false;
        }
        String token = BearerTokens.of(request);
        if (token == null)
        {
            return true;
        }
        try
        {
            return verifier.verifyUserId(token) == null;
        }
        catch (RuntimeException e)
        {
            // 失效 / 篡改 / 过期：与 MediaOwnerServiceImpl 同口径 —— 按未登录处理
            log.debug("app token 校验异常，按未登录处理: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public boolean isGuest()
    {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs))
        {
            // 无请求上下文（定时任务 / 单测）：不收紧
            return false;
        }
        return isGuest(servletAttrs.getRequest());
    }

    // ------------------------------------------------------------------ 根集合

    @Override
    public Set<Integer> hiddenRootIds()
    {
        return snapshot().hidden;
    }

    @Override
    public boolean isHidden(Integer rootId)
    {
        if (rootId == null)
        {
            return false;
        }
        return hiddenRootIds().contains(rootId);
    }

    @Override
    public void invalidateRoots()
    {
        snapshot = null;
    }

    @Override
    public Integer rootIdOfPath(Path absolutePath)
    {
        if (absolutePath == null)
        {
            return null;
        }
        Path real = realOf(absolutePath);
        Snapshot current = snapshot();
        for (RootBase base : current.roots)
        {
            if (real.startsWith(base.real))
            {
                return base.id;
            }
        }
        return null;
    }

    @Override
    public void requireVisible(Integer rootId)
    {
        if (isHidden(rootId) && isGuest())
        {
            throw MediaException.notFound();
        }
    }

    // ------------------------------------------------------------------ 拦截判定

    @Override
    public boolean isBlocked(HttpServletRequest request, String pattern, Map<String, String> vars)
    {
        if (request == null || StringUtils.isEmpty(pattern))
        {
            return false;
        }
        String path = normalizePattern(pattern);
        if (!path.startsWith(STATION_PREFIX) || path.startsWith(ADMIN_PREFIX))
        {
            return false;
        }
        Set<Integer> hidden = hiddenRootIds();
        if (hidden.isEmpty())
        {
            // 没有任何根收紧（默认状态）：连点查都不做
            return false;
        }
        String pathVar = vars == null ? null : vars.getOrDefault("id", null);
        String albumId = vars == null ? null : vars.getOrDefault("albumId", null);
        String index = vars == null ? null : vars.getOrDefault("i", null);
        try
        {
            switch (path)
            {
                // ---- 视频：详情 / 元信息 / 封面 / 精灵图 / 预览片段 / 实时取帧
                case "/media/video/{id}":
                case "/media/video/{id}/meta":
                case "/media/thumb/v/{id}":
                case "/media/sprite/{id}":
                case "/media/preview/{id}":
                case "/media/frame/{id}":
                    return isRootHidden(mediaGuestMapper.selectRootIdOfVideo(pathVar));
                // ---- 相册：整册详情
                case "/media/album/{id}":
                    return albumBlocked(pathVar);
                // ---- 图片：内容寻址（hash）与老序号 URL
                case "/media/thumb/p/{i}":
                case "/media/preview/p/{i}":
                    return photoHashBlocked(index);
                case "/media/thumb/p/{albumId}/{i}":
                case "/media/preview/p/{albumId}/{i}":
                    return photoSeqBlocked(albumId, index);
                // ---- P5.90 人物卡背景预渲染：出口是「头像原图」的像素，必须与它的来源根同口径
                case "/media/person/{id}/card-bg":
                    return personBgBlocked(pathVar);
                // ---- 原文件 / 转码流 / 字幕：按相对路径 + 可选根 id
                case "/media/file":
                case "/media/stream":
                case "/media/sub":
                    return pathBlocked(request);
                default:
                    // /media/index 由索引构建时筛除（不是 404）；tags / persons / user 等不按根定位
                    return false;
            }
        }
        catch (RuntimeException e)
        {
            warnOnce("未登录可见性判定失败（按放行处理）: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 各类目标

    /**
     * 人物卡背景（P5.90）：按<b>头像来源</b>判，不按人物判
     *
     * <p>人物行本身不分根（索引里对访客也照常列出名字与计数），真正带内容的是头像：
     * photo 档指向某本相册的某一页、frame 档指向某个视频 —— 两者都能落到 {@code root_id}。
     * 所以这里复用相册序号 / 视频根两条既有判定，口径与 {@code /thumb/p/<album>/<seq>}、
     * {@code /frame/<id>} 完全一致：<b>头像取不到时，预渲染出来的图也必须取不到</b>，
     * 否则这条新接口就成了「登录可见库根的图片外带通道」。</p>
     */
    private boolean personBgBlocked(String personId)
    {
        if (StringUtils.isEmpty(personId))
        {
            return false;
        }
        long id;
        try
        {
            id = Long.parseLong(personId.trim());
        }
        catch (NumberFormatException e)
        {
            return false;
        }
        Map<String, Object> row = mediaGuestMapper.selectAvatarOfPerson(id);
        if (row == null)
        {
            return false;
        }
        String kind = row.get("kind") == null ? "" : String.valueOf(row.get("kind")).trim().toLowerCase(Locale.ROOT);
        String ref = row.get("ref") == null ? "" : String.valueOf(row.get("ref")).trim();
        int hash = ref.indexOf('#');
        String body = hash >= 0 ? ref.substring(0, hash).trim() : ref;
        if ("photo".equals(kind))
        {
            int colon = body.indexOf(':');
            if (colon <= 0)
            {
                return false;
            }
            return photoSeqBlocked(body.substring(0, colon).trim(), body.substring(colon + 1).trim());
        }
        if ("frame".equals(kind))
        {
            String[] parts = body.split(":");
            if (parts.length < 3)
            {
                return false;
            }
            return isRootHidden(mediaGuestMapper.selectRootIdOfVideo(parts[1].trim()));
        }
        return false;
    }

    /** 相册对未登录访客是否隐藏：它的图片里<b>有任何一张</b>落在收紧的根下就整册藏（保守方向） */
    private boolean albumBlocked(String albumId)
    {
        if (StringUtils.isEmpty(albumId))
        {
            return false;
        }
        for (Integer rootId : mediaGuestMapper.selectRootIdsOfAlbum(albumId))
        {
            if (isRootHidden(rootId))
            {
                return true;
            }
        }
        return false;
    }

    /** 内容寻址缩略图：同一份内容只有在<b>所有</b>副本都落在收紧的根下时才藏（多根同一份文件是正常的） */
    private boolean photoHashBlocked(String hash)
    {
        if (StringUtils.isEmpty(hash))
        {
            return false;
        }
        List<Integer> roots = mediaGuestMapper.selectRootIdsOfPhotoByHash(hash);
        if (roots == null || roots.isEmpty())
        {
            return false;
        }
        for (Integer rootId : roots)
        {
            if (!isRootHidden(rootId))
            {
                return false;
            }
        }
        return true;
    }

    /** 老序号 URL：{@code /thumb/p/<albumId>/<seq>}，序号定位到具体一行 */
    private boolean photoSeqBlocked(String albumId, String index)
    {
        if (StringUtils.isEmpty(albumId) || StringUtils.isEmpty(index))
        {
            return false;
        }
        int seq;
        try
        {
            seq = Integer.parseInt(index.trim());
        }
        catch (NumberFormatException e)
        {
            // 序号位传的是内容哈希（老 HTML 两种写法都有）：退回按哈希判定
            return photoHashBlocked(index);
        }
        return isRootHidden(mediaGuestMapper.selectRootIdOfPhotoBySeq(albumId, seq));
    }

    /** {@code /media/file|stream|sub?p=&r=}：显式 root id 直接判，否则把 p 解析成真实路径再反查根 */
    private boolean pathBlocked(HttpServletRequest request)
    {
        Integer rootId = intParam(request.getParameter("r"));
        if (rootId != null)
        {
            return isRootHidden(rootId);
        }
        String rel = request.getParameter("p");
        if (StringUtils.isEmpty(rel))
        {
            return false;
        }
        try
        {
            Path resolved = mediaFileService.resolveMedia(rel, null);
            return isRootHidden(rootIdOfPath(resolved));
        }
        catch (MediaException e)
        {
            // 404 / 403 是 controller 本来就会给的结果，这里不重复判定
            return false;
        }
    }

    private boolean isRootHidden(Integer rootId)
    {
        return rootId != null && hiddenRootIds().contains(rootId);
    }

    // ------------------------------------------------------------------ 快照

    private Snapshot snapshot()
    {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now - current.builtAt < TTL_MS)
        {
            return current;
        }
        synchronized (this)
        {
            current = snapshot;
            if (current != null && now - current.builtAt < TTL_MS)
            {
                return current;
            }
            Snapshot fresh = load();
            snapshot = fresh;
            return fresh;
        }
    }

    private Snapshot load()
    {
        Set<Integer> hidden = new HashSet<>();
        List<RootBase> bases = new ArrayList<>();
        try
        {
            for (Integer id : mediaGuestMapper.selectGuestHiddenRootIds())
            {
                if (id != null)
                {
                    hidden.add(id);
                }
            }
            for (MediaRoot root : mediaRootMapper.selectRootList())
            {
                if (root == null || root.getId() == null || !"root".equalsIgnoreCase(root.getKind()))
                {
                    continue;
                }
                Path real = realOf(toPath(root.getPath()));
                if (real != null)
                {
                    bases.add(new RootBase(root.getId(), real));
                }
            }
        }
        catch (RuntimeException e)
        {
            // 列还没迁移 / 库读不到：当成「没有收紧的根」，站点照旧可用
            warnOnce("读取库根可见性失败（按全部可见处理）: {}", e.getMessage());
            hidden = new HashSet<>();
            bases = new ArrayList<>();
        }
        return new Snapshot(System.currentTimeMillis(), Collections.unmodifiableSet(hidden), bases);
    }

    /** 一次告警最多每 60 秒一行，避免列未迁移时把日志刷满 */
    private void warnOnce(String format, Object arg)
    {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L)
        {
            return;
        }
        lastWarnAt = now;
        log.warn(format, arg);
    }

    private static Path toPath(String path)
    {
        if (StringUtils.isEmpty(path))
        {
            return null;
        }
        try
        {
            return Paths.get(path);
        }
        catch (InvalidPathException e)
        {
            return null;
        }
    }

    /** 绝对 + 规范化 + 尽量取 realpath（根没挂盘时退回规范化路径，前缀比较仍然成立） */
    private static Path realOf(Path path)
    {
        if (path == null)
        {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute))
        {
            return absolute;
        }
        try
        {
            return absolute.toRealPath();
        }
        catch (IOException | RuntimeException e)
        {
            return absolute;
        }
    }

    private static String normalizePattern(String pattern)
    {
        String text = pattern.trim();
        if (text.startsWith(API_PREFIX + "/"))
        {
            text = text.substring(API_PREFIX.length());
        }
        while (text.length() > 1 && text.endsWith("/"))
        {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static Integer intParam(String value)
    {
        if (StringUtils.isEmpty(value))
        {
            return null;
        }
        try
        {
            return Integer.valueOf(value.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** 一个媒体根的 id + 已解析的 realpath */
    private static final class RootBase
    {
        private final Integer id;

        private final Path real;

        private RootBase(Integer id, Path real)
        {
            this.id = id;
            this.real = real;
        }
    }

    /** 根缓存快照（不可变；换新值靠整体替换） */
    private static final class Snapshot
    {
        private final long builtAt;

        private final Set<Integer> hidden;

        private final List<RootBase> roots;

        private Snapshot(long builtAt, Set<Integer> hidden, List<RootBase> roots)
        {
            this.builtAt = builtAt;
            this.hidden = hidden;
            this.roots = roots;
        }
    }
}
