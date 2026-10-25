package com.qtools.media.controller;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.service.IMediaOwnerService;
import com.qtools.media.service.IMediaPlaylistService;
import static com.qtools.media.support.MediaBodyParser.parseObject;
import static com.qtools.media.support.MediaBodyParser.stringValue;
import static com.qtools.media.support.MediaBodyParser.stringsValue;

/**
 * 媒体站 播放列表接口（{@code /api/media/playlists}，P5-B）
 *
 * <p>与收藏 / 保存的筛选（{@code /api/media/filters}）同一套身形：类上 {@link Anonymous} 免登录，
 * 数据按 {@code owner_key} 隔离（读 = 自己的 + 全局的），写操作进业务前先调
 * {@link IMediaEditPolicyService#requireWritable()}——编辑策略为 off / local 时 403。</p>
 *
 * <p>顺序：{@code PUT \/playlists/{id}} 的 {@code videoIds} <b>数组顺序即播放顺序</b>；
 * 省略该字段 = 只改名，传空数组 = 清空条目（两者的区别由 {@code null} / {@code []} 表达）。</p>
 *
 * <p><b>路径别名（P5-C 前端已按单数实现）</b>：列表集合只有复数 {@code /playlists}，
 * 单条资源同时挂 <b>复数与单数</b> 两套前缀（{@code /playlists/{id}} 与 {@code /playlist/{id}}），
 * 条目子资源同时挂 {@code /items} 与 {@code /videos}——4 种组合行为完全一致（同一个方法、同一套
 * 策略闸门与校验），前端调用哪套都行。复数是规范路径，单数是兼容路径。</p>
 *
 * <p>body 一律手工解析（{@code @RequestBody String} + Jackson 树）：非法 JSON 400，
 * 不落到 500，与站端其它写接口口径一致。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaPlaylistController
{
    @Autowired
    private IMediaOwnerService mediaOwnerService;

    @Autowired
    private IMediaPlaylistService mediaPlaylistService;

    @Autowired
    private IMediaEditPolicyService mediaEditPolicyService;

    // ------------------------------------------------------------------ 读

    /** 当前 owner 可见的列表：{@code [{id,name,count}]} */
    @GetMapping("/playlists")
    public List<Map<String, Object>> list(HttpServletRequest request, HttpServletResponse response)
    {
        return mediaPlaylistService.list(mediaOwnerService.resolve(request, response).getOwnerKey());
    }

    /**
     * 列表详情：{@code {id,name,count,items:[{videoId,title,dur,seq}],videos:[同 items]}}
     *
     * <p>{@code items} 与 {@code videos} 内容完全相同：前端（P5-C）自带一层归一化，认
     * {@code videos / items / rows / videoIds / ids} 五种形状，后端两个键都给，省掉一次「形状不对」的排查。</p>
     */
    @GetMapping({ "/playlists/{id}", "/playlist/{id}" })
    public Map<String, Object> detail(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id)
    {
        return mediaPlaylistService.detail(mediaOwnerService.resolve(request, response).getOwnerKey(), longId(id));
    }

    // ------------------------------------------------------------------ 写（策略 = full 才放行）

    /** 新建：body {@code {name}}；同名同 owner 复用（{@code created=false}，不是 409） */
    @PostMapping("/playlists")
    public Map<String, Object> create(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaPlaylistService.create(mediaOwnerService.resolve(request, response).getOwnerKey(),
                stringValue(body.get("name")));
    }

    /**
     * 改名 / 全量替换条目：body {@code {name?, videoIds?}}
     *
     * <p>{@code videoIds} 省略 = 条目不动；{@code []} = 清空；有值 = 按数组顺序整体替换（保序、去重）。</p>
     */
    @PutMapping({ "/playlists/{id}", "/playlist/{id}" })
    public Map<String, Object> update(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id, @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaPlaylistService.update(mediaOwnerService.resolve(request, response).getOwnerKey(), longId(id),
                stringValue(body.get("name")), stringsValue(body.get("videoIds"), "videoIds"));
    }

    /** 追加一条：body {@code {videoId}}；已在列表里就 no-op（{@code added=false}） */
    @PostMapping({ "/playlists/{id}/items", "/playlists/{id}/videos", "/playlist/{id}/items",
            "/playlist/{id}/videos" })
    public Map<String, Object> addItem(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id, @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaPlaylistService.addItem(mediaOwnerService.resolve(request, response).getOwnerKey(), longId(id),
                stringValue(body.get("videoId")));
    }

    /**
     * 删除一条（后面的条目自动前移）
     *
     * <p><b>幂等</b>：该视频本来就不在列表里时返回 200 + {@code removed:0}，不报 404——
     * 前端在单条删除失败后会退回「整集合 PUT」，用 200 表达「结果已经是你要的样子」比 404 更安全
     * （404 会让前端把「另一个标签页已经删过」当成真失败）。</p>
     */
    @DeleteMapping({ "/playlists/{id}/items/{videoId}", "/playlists/{id}/videos/{videoId}",
            "/playlist/{id}/items/{videoId}", "/playlist/{id}/videos/{videoId}" })
    public Map<String, Object> removeItem(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id, @PathVariable("videoId") String videoId)
    {
        mediaEditPolicyService.requireWritable();
        return mediaPlaylistService.removeItem(mediaOwnerService.resolve(request, response).getOwnerKey(),
                longId(id), videoId);
    }

    /** 删除整个列表：{@code {id,name,deleted}}（不存在 / 不是自己的 404） */
    @DeleteMapping({ "/playlists/{id}", "/playlist/{id}" })
    public Map<String, Object> delete(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id)
    {
        mediaEditPolicyService.requireWritable();
        return mediaPlaylistService.delete(mediaOwnerService.resolve(request, response).getOwnerKey(), longId(id));
    }

    private static Long longId(String id)
    {
        try
        {
            return Long.valueOf(id);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("id 必须是数字");
        }
    }
}
