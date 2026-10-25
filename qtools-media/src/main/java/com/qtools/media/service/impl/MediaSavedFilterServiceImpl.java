package com.qtools.media.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaSavedFilter;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaSavedFilterMapper;
import com.qtools.media.service.IMediaSavedFilterService;

/**
 * 保存的筛选条件 服务实现
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li><b>只存原始 JSON</b>：服务层只判断「是不是合法 JSON」「够不够短」，不解释字段——
 *       前端加一个筛选维度不必改后端；</li>
 *   <li><b>同名同 owner 覆盖</b>：先按 {@code (name, owner_key)} 查，命中就只更新 params；
 *       因此既不会 409，也不会把别人的条目改掉（不同 owner 各有各的同名条目）；</li>
 *   <li><b>站端只删自己的</b>：{@code DELETE} 带 {@code owner_key} 条件，0 行一律 404，
 *       连「这条存在但不是你的」都不透露；清理全局/他人的条目走管理端接口。</li>
 * </ol>
 *
 * @author qtools
 */
@Service
public class MediaSavedFilterServiceImpl implements IMediaSavedFilterService
{
    private static final Logger log = LoggerFactory.getLogger(MediaSavedFilterServiceImpl.class);

    /** name 上限（与 media_saved_filter.name VARCHAR(64) 一致） */
    private static final int NAME_MAX = 64;

    /** params 上限：4KB（UTF-8 字节，不是字符数——中文条件按字节算才挡得住 TEXT 膨胀） */
    private static final int PARAMS_MAX_BYTES = 4096;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 只用于 params 的合法性校验与反序列化，不参与响应序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MediaSavedFilterMapper mediaSavedFilterMapper;

    @Override
    public List<Map<String, Object>> list(String ownerKey)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaSavedFilter filter : mediaSavedFilterMapper.selectVisibleList(normalizeOwner(ownerKey)))
        {
            out.add(toView(filter, false));
        }
        return out;
    }

    @Override
    public Map<String, Object> save(String ownerKey, String name, String paramsJson)
    {
        String nameText = requireName(name);
        String paramsText = requireParams(paramsJson);
        String owner = normalizeOwner(ownerKey);
        MediaSavedFilter existing = mediaSavedFilterMapper.selectByNameAndOwner(nameText, owner);
        boolean created = existing == null;
        Long id;
        if (created)
        {
            MediaSavedFilter filter = new MediaSavedFilter();
            filter.setName(nameText);
            filter.setParams(paramsText);
            filter.setOwnerKey(owner);
            mediaSavedFilterMapper.insert(filter);
            id = filter.getId();
        }
        else
        {
            mediaSavedFilterMapper.updateParamsById(existing.getId(), paramsText);
            id = existing.getId();
        }
        MediaSavedFilter stored = id == null ? null : mediaSavedFilterMapper.selectById(id);
        Map<String, Object> view = stored == null ? new LinkedHashMap<>() : toView(stored, false);
        view.put("created", created);
        log.info("保存筛选 owner={} name={} created={} bytes={}", owner, nameText, created,
                paramsText.getBytes(StandardCharsets.UTF_8).length);
        return view;
    }

    @Override
    public Map<String, Object> delete(String ownerKey, Long id)
    {
        if (id == null || id.longValue() <= 0L)
        {
            throw MediaException.badRequest("id 不合法");
        }
        int rows = mediaSavedFilterMapper.deleteByIdAndOwner(id, normalizeOwner(ownerKey));
        if (rows == 0)
        {
            // 不存在 / 不属于自己 / 是全局条目：对外统一 404，不泄露归属
            throw MediaException.notFound("filter not found: " + id);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("deleted", rows);
        return view;
    }

    @Override
    public List<Map<String, Object>> adminList()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaSavedFilter filter : mediaSavedFilterMapper.selectAll())
        {
            out.add(toView(filter, true));
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
        MediaSavedFilter existing = mediaSavedFilterMapper.selectById(id);
        if (existing == null)
        {
            throw MediaException.notFound("filter not found: " + id);
        }
        mediaSavedFilterMapper.deleteById(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("name", existing.getName());
        view.put("deleted", 1);
        return view;
    }

    // ------------------------------------------------------------------ 校验 / 视图

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

    /** params：必须合法 JSON 且 &lt; 4KB；原样返回（保留前端的键序与空白） */
    private static String requireParams(String paramsJson)
    {
        String text = paramsJson == null ? null : paramsJson.trim();
        if (text == null || text.isEmpty())
        {
            throw MediaException.badRequest("params 不能为空（要保存的是筛选条件的 JSON）");
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PARAMS_MAX_BYTES)
        {
            throw MediaException.badRequest("params 过长：" + bytes + " 字节（上限 " + PARAMS_MAX_BYTES + "）");
        }
        try
        {
            MAPPER.readTree(text);
        }
        catch (Exception e)
        {
            throw MediaException.badRequest("params 不是合法 JSON");
        }
        return text;
    }

    /** 空白 owner 一律按 null（全局）处理；站端 resolve 出来的 owner 永远非空 */
    private static String normalizeOwner(String ownerKey)
    {
        return StringUtils.isEmpty(ownerKey) ? null : ownerKey.trim();
    }

    private static Map<String, Object> toView(MediaSavedFilter filter, boolean admin)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", filter.getId());
        node.put("name", filter.getName());
        node.put("params", parseParams(filter.getParams()));
        if (admin)
        {
            node.put("ownerKey", filter.getOwnerKey());
            node.put("global", filter.getOwnerKey() == null);
            node.put("createTime", formatDate(filter.getCreateTime()));
            node.put("updateTime", formatDate(filter.getUpdateTime()));
        }
        return node;
    }

    /** 库里是原始 JSON 文本，出参按 JSON 值返回（解析不了的原样回字符串，绝不因此 500） */
    private static Object parseParams(String paramsJson)
    {
        if (StringUtils.isEmpty(paramsJson))
        {
            return null;
        }
        try
        {
            return MAPPER.readTree(paramsJson);
        }
        catch (Exception e)
        {
            log.warn("已保存的筛选 params 不是合法 JSON（原样返回） id 见调用方", e);
            return paramsJson;
        }
    }

    private static String formatDate(Date date)
    {
        return date == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, date);
    }
}
