package com.qtools.media.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.media.domain.MediaPersonCover;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaPersonCoverMapper;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.service.IMediaPersonCoverService;

/**
 * 人物卡封面服务（P6.101）
 *
 * <p>只做三件事：校验值域、按优先级合成「每个视图的最终设置」、写/删一行。
 * 人物是否存在由调用方（控制器 / 人物服务）保证 —— 本层不反查人物表，
 * 免得一次人物墙渲染多出 N 次人物查询。</p>
 *
 * <p>值域与 P5.88 的前端 {@code personBgAdjust.js} 逐值一致：
 * scale 0.5–3（3 位小数）、dx/dy 是卡片宽高的比例 ±2（4 位小数）。</p>
 *
 * @author qtools
 */
@Service
public class MediaPersonCoverServiceImpl implements IMediaPersonCoverService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonCoverServiceImpl.class);

    /** 四个卡片视图（顺序 = 站端按钮顺序） */
    private static final List<String> VIEWS = List.of("poster", "hero", "masonry", "compact");

    /** 图源：auto = 沿用头像三档；photo = 名下照片；frame = 视频帧 */
    private static final List<String> KINDS = List.of("auto", "photo", "frame");

    private static final BigDecimal SCALE_MIN = new BigDecimal("0.5");

    private static final BigDecimal SCALE_MAX = new BigDecimal("3");

    private static final BigDecimal OFFSET_LIMIT = new BigDecimal("2");

    private static final BigDecimal SCALE_AUTO = BigDecimal.ONE;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** source_ref 上限（与 avatar_ref 同量级） */
    private static final int REF_MAX = 512;

    @Autowired
    private MediaPersonCoverMapper mediaPersonCoverMapper;

    /** 只为「人物不存在 → 404」这一条判定（站端与管理端共用，避免两处各写一遍） */
    @Autowired
    private MediaPersonMapper mediaPersonMapper;

    private void requirePerson(Long personId)
    {
        if (personId == null || mediaPersonMapper.selectPersonDetail(personId) == null)
        {
            throw MediaException.notFound("人物不存在: " + personId);
        }
    }

    @Override
    public List<String> views()
    {
        return VIEWS;
    }

    @Override
    public Map<String, Object> effectiveMap(Long personId)
    {
        if (personId == null)
        {
            return Map.of();
        }
        return effectiveMapByPersonIds(List.of(personId)).getOrDefault(personId, Map.of());
    }

    @Override
    public Map<Long, Map<String, Object>> effectiveMapByPersonIds(Collection<Long> personIds)
    {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (personIds == null || personIds.isEmpty())
        {
            return out;
        }
        List<Long> ids = new ArrayList<>(personIds.size());
        for (Long id : personIds)
        {
            if (id != null)
            {
                ids.add(id);
            }
        }
        if (ids.isEmpty())
        {
            return out;
        }
        // 一个人物每个视图最多两行（default + user）；按视图聚起来后 user 覆盖 default
        Map<Long, Map<String, Map<String, MediaPersonCover>>> rows = new LinkedHashMap<>();
        for (MediaPersonCover cover : mediaPersonCoverMapper.selectByPersonIds(ids))
        {
            rows.computeIfAbsent(cover.getPersonId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(cover.getView(), k -> new LinkedHashMap<>())
                    .put(cover.getScope(), cover);
        }
        for (String view : VIEWS)
        {
            for (Map.Entry<Long, Map<String, Map<String, MediaPersonCover>>> entry : rows.entrySet())
            {
                Map<String, MediaPersonCover> byScope = entry.getValue().get(view);
                if (byScope == null)
                {
                    continue;
                }
                MediaPersonCover picked = byScope.getOrDefault(SCOPE_USER, byScope.get(SCOPE_DEFAULT));
                if (picked == null)
                {
                    continue;
                }
                out.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>())
                        .put(view, toNode(picked));
            }
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long personId, String view, String scope, String kind, String ref,
            BigDecimal scale, BigDecimal dx, BigDecimal dy)
    {
        requirePerson(personId);
        String cleanView = requireView(view);
        String cleanScope = requireScope(scope);
        String cleanKind = requireKind(kind);
        String cleanRef = cleanRef(cleanKind, ref);
        BigDecimal cleanScale = clamp(scale, SCALE_AUTO, SCALE_MIN, SCALE_MAX, 3);
        BigDecimal cleanDx = clamp(dx, ZERO, OFFSET_LIMIT.negate(), OFFSET_LIMIT, 4);
        BigDecimal cleanDy = clamp(dy, ZERO, OFFSET_LIMIT.negate(), OFFSET_LIMIT, 4);
        if ("auto".equals(cleanKind) && isAutoGeometry(cleanScale, cleanDx, cleanDy))
        {
            // 等于「什么都没设」→ 删行，别留一条无意义的记录
            mediaPersonCoverMapper.deleteOne(personId, cleanView, cleanScope);
            log.info("人物封面回到自动，已清空 person={} view={} scope={}", personId, cleanView, cleanScope);
            return effectiveMap(personId);
        }
        MediaPersonCover cover = new MediaPersonCover();
        cover.setPersonId(personId);
        cover.setView(cleanView);
        cover.setScope(cleanScope);
        cover.setSourceKind(cleanKind);
        cover.setSourceRef(cleanRef);
        cover.setBgScale(cleanScale);
        cover.setBgDx(cleanDx);
        cover.setBgDy(cleanDy);
        mediaPersonCoverMapper.upsert(cover);
        log.info("保存人物封面 person={} view={} scope={} kind={} ref={} scale={} dx={} dy={}", personId, cleanView,
                cleanScope, cleanKind, cleanRef, cleanScale, cleanDx, cleanDy);
        return effectiveMap(personId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> clear(Long personId, String view, String scope)
    {
        requirePerson(personId);
        String cleanView = requireView(view);
        String cleanScope = requireScope(scope);
        mediaPersonCoverMapper.deleteOne(personId, cleanView, cleanScope);
        log.info("清除人物封面 person={} view={} scope={}", personId, cleanView, cleanScope);
        return effectiveMap(personId);
    }

    // ------------------------------------------------------------------ 校验 / 归一

    private static String requireView(String view)
    {
        String clean = view == null ? "" : view.trim().toLowerCase(java.util.Locale.ROOT);
        if (!VIEWS.contains(clean))
        {
            throw MediaException.badRequest("未知视图: " + view + "（可选 " + String.join(" / ", VIEWS) + "）");
        }
        return clean;
    }

    private static String requireScope(String scope)
    {
        String clean = scope == null ? "" : scope.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SCOPE_DEFAULT.equals(clean) && !SCOPE_USER.equals(clean))
        {
            throw MediaException.badRequest("未知作用域: " + scope + "（可选 default / user）");
        }
        return clean;
    }

    private static String requireKind(String kind)
    {
        String clean = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.isEmpty())
        {
            return "auto";
        }
        if (!KINDS.contains(clean))
        {
            throw MediaException.badRequest("未知图源: " + kind + "（可选 auto / photo / frame）");
        }
        return clean;
    }

    /** auto 不存 ref；photo / frame 必须有 ref（形状由前端与后端同一套解析器保证，这里只挡空值） */
    private static String cleanRef(String kind, String ref)
    {
        String clean = ref == null ? "" : ref.trim();
        if ("auto".equals(kind))
        {
            return null;
        }
        if (clean.isEmpty())
        {
            throw MediaException.badRequest("图源 " + kind + " 缺少 ref");
        }
        if (clean.length() > REF_MAX)
        {
            throw MediaException.badRequest("ref 过长（上限 " + REF_MAX + " 字符）");
        }
        return clean;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal fallback, BigDecimal min, BigDecimal max, int scale)
    {
        if (value == null)
        {
            return null;
        }
        BigDecimal v = value.setScale(scale, RoundingMode.HALF_UP);
        if (v.compareTo(min) < 0)
        {
            v = min;
        }
        if (v.compareTo(max) > 0)
        {
            v = max;
        }
        return v;
    }

    private static boolean isAutoGeometry(BigDecimal scale, BigDecimal dx, BigDecimal dy)
    {
        BigDecimal s = scale == null ? SCALE_AUTO : scale;
        BigDecimal x = dx == null ? ZERO : dx;
        BigDecimal y = dy == null ? ZERO : dy;
        return s.compareTo(SCALE_AUTO) == 0 && x.signum() == 0 && y.signum() == 0;
    }

    /** 一行 → 站端节点（数值统一成字符串，前端不用判类型） */
    private static Map<String, Object> toNode(MediaPersonCover cover)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", cover.getSourceKind() == null ? "auto" : cover.getSourceKind());
        node.put("ref", cover.getSourceRef());
        node.put("scale", cover.getBgScale() == null ? null : cover.getBgScale().toPlainString());
        node.put("dx", cover.getBgDx() == null ? null : cover.getBgDx().toPlainString());
        node.put("dy", cover.getBgDy() == null ? null : cover.getBgDy().toPlainString());
        node.put("source", SCOPE_DEFAULT.equals(cover.getScope()) ? SCOPE_DEFAULT : SCOPE_USER);
        return node;
    }
}
