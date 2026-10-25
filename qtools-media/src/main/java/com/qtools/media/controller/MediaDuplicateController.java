package com.qtools.media.controller;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.core.controller.BaseController;
import com.qtools.common.enums.BusinessType;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaDuplicateService;
import static com.qtools.media.support.MediaBodyParser.boolValue;
import static com.qtools.media.support.MediaBodyParser.intValue;
import static com.qtools.media.support.MediaBodyParser.parseObject;
import static com.qtools.media.support.MediaBodyParser.stringValue;

/**
 * 重复文件合并接口（前缀 {@code /api/media/admin/duplicates}，P4-A）
 *
 * <p>数据来自别名表 {@code media_video_alias} / {@code media_photo_alias}：扫描器把
 * 「同一内容出现在第二条路径」的副本沉淀在这里，主文件仍留在 {@code media_video} /
 * {@code media_photo}。本组接口是这份沉淀的唯一出口。</p>
 *
 * <table border="1">
 *   <caption>端点一览</caption>
 *   <tr><th>端点</th><th>权限</th><th>语义</th></tr>
 *   <tr><td>{@code GET /duplicates?kind=&page=&size=}</td><td>media:admin:list</td>
 *       <td>按 content_hash 分组的副本组，只列「别名非空且主行存在」的组</td></tr>
 *   <tr><td>{@code POST /duplicates/merge}</td><td>media:admin:edit</td>
 *       <td>删别名行；{@code deleteFiles=true} 时先把副本文件搬进回收站</td></tr>
 *   <tr><td>{@code POST /duplicates/keep}</td><td>media:admin:edit</td>
 *       <td>把指定别名提升为主行指向的文件（交换 rel_path，主行 id 不变）</td></tr>
 * </table>
 *
 * <p><b>风险提示（面板必须显式告知用户）</b>：{@code merge(deleteFiles=true)} 会把文件移进
 * 回收站（{@code _媒体站/trash/<kind>/<rootId>/}）。回收站里的别名条目在
 * {@code GET /admin/trash} 中显示为 {@code orphan:true}（别名表不存「原路径 → 恢复」映射），
 * 取回靠 {@code trashRel}，而不是 {@code POST /admin/trash/restore}。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/media/admin/duplicates")
public class MediaDuplicateController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(MediaDuplicateController.class);

    @Autowired
    private IMediaDuplicateService mediaDuplicateService;

    /**
     * 副本组列表：{@code GET /api/media/admin/duplicates?kind=video|photo&page=1&size=20}
     *
     * <p>返回 {@code {kind,page,size,total,rows:[{contentHash,primary:{id,relPath,size,rootId},
     * aliases:[{id,relPath,size,rootId}],count,bytesWasted}]}}。{@code count} = 该组别名行数，
     * {@code bytesWasted} = 别名文件字节数之和（= 合并后能省下的磁盘量）。</p>
     *
     * <p>用显式 {@code page/size} 而不是 PageHelper：分组来自别名表的 group by，
     * 与主行是否为 photo（同 hash 多行）无关，分页必须在 SQL 里做，不能靠内存截断。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size)
    {
        Map<String, Object> out = mediaDuplicateService.list(kind, page, size);
        log.debug("重复文件列表 kind={} page={} size={} total={}", kind, page, size, out.get("total"));
        return out;
    }

    /**
     * 合并一组：{@code POST /api/media/admin/duplicates/merge}
     *
     * <p>body {@code {kind:"video"|"photo", contentHash:"<16位hex>", deleteFiles:true|false}}。
     * {@code deleteFiles=true} → 副本文件先进回收站（按 {@code root_id} 分桶），再删别名行；
     * {@code false} → 只删别名行，磁盘不动（文件从此不再被索引，但仍留在原处）。</p>
     *
     * <p>返回 {@code {kind,contentHash,removedRows,deletedFiles,missingFiles,kept,movedToTrash:[...]}}。
     * <b>幂等</b>：组里没有别名行时返回全 0（{@code kept} 仍给出主行），不报 404。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-重复文件合并", businessType = BusinessType.DELETE)
    @PostMapping("/merge")
    public Map<String, Object> merge(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        String kind = stringValue(body.get("kind"));
        String contentHash = stringValue(body.get("contentHash"));
        Boolean deleteFiles = boolValue(body.get("deleteFiles"));
        return mediaDuplicateService.merge(kind, contentHash, Boolean.TRUE.equals(deleteFiles));
    }

    /**
     * 保留某个副本：{@code POST /api/media/admin/duplicates/keep}
     *
     * <p>body {@code {kind, contentHash, id}}（{@code id} = 别名行 id）。主行与别名行<b>交换
     * rel_path</b>：主行 id / 标题 / 标签 / 标记 / 评分全部原地保留，组内行数不变，磁盘不动。
     * 别名文件在磁盘上不存在时 400（提升完会变成 missing 行）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-重复文件保留", businessType = BusinessType.UPDATE)
    @PostMapping("/keep")
    public Map<String, Object> keep(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        String kind = stringValue(body.get("kind"));
        String contentHash = stringValue(body.get("contentHash"));
        String id = stringValue(body.get("id"));
        if (id == null)
        {
            // 兼容前端把别名 id 当数字传（photo 别名 id 是 BIGINT）
            Integer asInt = intValue(body.get("id"));
            id = asInt == null ? null : String.valueOf(asInt);
        }
        if (id == null)
        {
            throw MediaException.badRequest("id 必填（别名行 id）");
        }
        return mediaDuplicateService.keep(kind, contentHash, id);
    }
}
