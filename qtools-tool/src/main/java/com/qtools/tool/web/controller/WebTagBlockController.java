package com.qtools.tool.web.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.tool.mapper.ToolWebPostMapper;
import com.qtools.tool.web.support.WebOwnerResolver;

/**
 * 标签黑名单（P6.114）
 *
 * <p>被屏蔽的标签在列表、计数、标签云三处同时生效（服务端过滤），而不是前端过滤——
 * 否则分页条数和"还有多少"都会失真。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/web")
public class WebTagBlockController
{
    @Autowired
    private ToolWebPostMapper toolWebPostMapper;

    @Autowired
    private WebOwnerResolver webOwnerResolver;

    /** 读取黑名单：{@code GET /api/tool/web/tag-blocks} */
    @GetMapping("/tag-blocks")
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "owner", required = false) String ownerParam)
    {
        String owner = webOwnerResolver.resolve(authorization, ownerHeader, ownerParam);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tags", owner == null ? List.of() : toolWebPostMapper.selectBlockedTags(owner));
        return out;
    }

    /** 屏蔽 / 取消屏蔽：{@code POST /api/tool/web/tag-blocks?tag=xxx&on=0} */
    @PostMapping("/tag-blocks")
    public Map<String, Object> toggle(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "owner", required = false) String ownerParam,
            @RequestParam("tag") String tag,
            @RequestParam(value = "on", required = false, defaultValue = "1") int on)
    {
        String owner = webOwnerResolver.resolve(authorization, ownerHeader, ownerParam);
        Map<String, Object> out = new LinkedHashMap<>();
        String cleanTag = tag == null ? "" : tag.trim();
        if (owner == null || cleanTag.isEmpty() || cleanTag.length() > 64)
        {
            out.put("ok", false);
            return out;
        }
        if (on > 0)
        {
            toolWebPostMapper.addBlockedTag(owner, cleanTag);
        }
        else
        {
            toolWebPostMapper.removeBlockedTag(owner, cleanTag);
        }
        out.put("ok", true);
        out.put("tags", toolWebPostMapper.selectBlockedTags(owner));
        return out;
    }
}
