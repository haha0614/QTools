package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaDuplicateMapper;
import com.qtools.media.service.IMediaDuplicateService;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.support.MediaTrashSupport;

/**
 * 重复文件合并 服务实现（P4-A）
 *
 * <p>三条铁律，与 {@code MediaAdminServiceImpl} 的软删同一口径：</p>
 * <ol>
 *   <li>路径先过 {@link IMediaFileService} 的根内校验（越界 403），控制器不拼路径；</li>
 *   <li>删文件只搬进 {@code _媒体站/trash/<kind>/<rootId>/}，条目名由
 *       {@link MediaTrashSupport#uniqueTarget} 统一生成——回收站列表认得出、取回得了；</li>
 *   <li>先搬文件再删行：搬失败（跨盘 / 占用）整组回滚，绝不出现「行删了、文件还在原地」
 *       这种下一次扫描又会把副本登记回来的半成品。</li>
 * </ol>
 *
 * <p><b>回收站里的别名条目是 orphan</b>：别名表里没有「原路径 → 恢复」的记录（主表才存 rel_path），
 * 所以 {@code GET /admin/trash} 会把它们标成 {@code orphan:true}，按 {@code trashRel} 取回。
 * 这是刻意取舍：给别名建一条「恢复时写回原路径」的路径，等于把刚合并掉的副本原样恢复成重复文件。</p>
 *
 * @author qtools
 */
@Service
public class MediaDuplicateServiceImpl implements IMediaDuplicateService
{
    private static final Logger log = LoggerFactory.getLogger(MediaDuplicateServiceImpl.class);

    private static final String KIND_VIDEO = "video";

    private static final String KIND_PHOTO = "photo";

    /** 分页上限：合并页是「逐组人点」的操作面，一次给太多组没有意义 */
    private static final int SIZE_MAX = 100;

    /** content_hash 是 CHAR(16) 的十六进制串；短串放行以便老库/手写探针也能定位 */
    private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{1,16}$");

    @Autowired
    private MediaDuplicateMapper mediaDuplicateMapper;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private IMediaIndexService mediaIndexService;

    @Override
    public Map<String, Object> list(String kind, int page, int size)
    {
        String cleanKind = requireKind(kind);
        int pageNum = Math.max(1, page);
        int pageSize = size <= 0 ? 20 : Math.min(size, SIZE_MAX);

        int total = mediaDuplicateMapper.countAliasGroups(cleanKind);
        List<Map<String, Object>> raw = total == 0 ? List.of()
                : mediaDuplicateMapper.selectAliasGroups(cleanKind, pageSize, (pageNum - 1) * pageSize);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> group : raw)
        {
            String hash = str(group.get("contentHash"));
            Map<String, Object> primary = mediaDuplicateMapper.selectPrimaryByHash(cleanKind, hash);
            if (primary == null)
            {
                // 主行在这两次查询之间被删掉了：本组已无可合并对象，直接跳过（不影响 total 口径以外的语义）
                continue;
            }
            List<Map<String, Object>> aliases = new ArrayList<>();
            long wasted = 0;
            for (Map<String, Object> alias : mediaDuplicateMapper.selectAliasesByHash(cleanKind, hash))
            {
                aliases.add(node(alias));
                wasted += num(alias.get("size"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contentHash", hash);
            row.put("primary", node(primary));
            row.put("aliases", aliases);
            row.put("count", aliases.size());
            row.put("bytesWasted", wasted);
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", cleanKind);
        out.put("page", pageNum);
        out.put("size", pageSize);
        out.put("total", total);
        out.put("rows", rows);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> merge(String kind, String contentHash, boolean deleteFiles)
    {
        String cleanKind = requireKind(kind);
        String hash = requireHash(contentHash);

        List<Map<String, Object>> aliases = mediaDuplicateMapper.selectAliasesByHash(cleanKind, hash);
        Map<String, Object> primary = mediaDuplicateMapper.selectPrimaryByHash(cleanKind, hash);

        List<String> movedToTrash = new ArrayList<>();
        int deletedFiles = 0;
        int missingFiles = 0;
        if (deleteFiles)
        {
            for (Map<String, Object> alias : aliases)
            {
                String relPath = str(alias.get("relPath"));
                Integer rootId = integer(alias.get("rootId"));
                int outcome = moveAliasToTrash(cleanKind, rootId, relPath, str(alias.get("id")), movedToTrash);
                if (outcome > 0)
                {
                    deletedFiles++;
                }
                else
                {
                    missingFiles++;
                }
            }
        }

        int removedRows = mediaDuplicateMapper.deleteAliasesByHash(cleanKind, hash);
        if (removedRows > 0 || deletedFiles > 0)
        {
            mediaIndexService.invalidateSnapshot();
        }
        log.info("重复文件合并 kind={} hash={} deleteFiles={} removedRows={} deletedFiles={} missingFiles={}", cleanKind,
                hash, deleteFiles, removedRows, deletedFiles, missingFiles);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", cleanKind);
        out.put("contentHash", hash);
        out.put("removedRows", removedRows);
        out.put("deletedFiles", deletedFiles);
        out.put("missingFiles", missingFiles);
        out.put("kept", primary == null ? null : node(primary));
        out.put("movedToTrash", movedToTrash);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> keep(String kind, String contentHash, String id)
    {
        String cleanKind = requireKind(kind);
        String hash = requireHash(contentHash);
        String aliasId = StringUtils.isEmpty(id) ? null : id.trim();
        if (aliasId == null)
        {
            throw MediaException.badRequest("id 必填（别名行 id）");
        }

        Map<String, Object> primary = mediaDuplicateMapper.selectPrimaryByHash(cleanKind, hash);
        if (primary == null)
        {
            throw MediaException.notFound("该内容哈希没有主行: " + hash);
        }
        Map<String, Object> alias = mediaDuplicateMapper.selectAliasById(cleanKind, aliasId);
        if (alias == null)
        {
            throw MediaException.notFound("别名不存在: " + cleanKind + "#" + aliasId);
        }
        if (!hash.equalsIgnoreCase(str(alias.get("contentHash"))))
        {
            throw MediaException.badRequest("别名 " + aliasId + " 不属于内容哈希 " + hash);
        }

        String primaryRel = str(primary.get("relPath"));
        String aliasRel = str(alias.get("relPath"));
        Integer primaryRoot = integer(primary.get("rootId"));
        Integer aliasRoot = integer(alias.get("rootId"));
        long primarySize = num(primary.get("size"));
        long aliasSize = num(alias.get("size"));

        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("relPath", primaryRel);
        previous.put("size", primarySize);
        previous.put("rootId", primaryRoot);

        if (primaryRel != null && primaryRel.equals(aliasRel))
        {
            // 已经是同一份文件：幂等返回，不动任何行
            return keepOut(cleanKind, hash, node(primary), previous);
        }
        if (aliasRel == null)
        {
            throw MediaException.badRequest("别名 " + aliasId + " 没有 rel_path，无法提升");
        }
        // 别名文件必须真实存在：否则提升完主行就指向一个不存在的路径（列表里变成 missing）
        requireAliasFileExists(aliasRel, aliasRoot);
        if (mediaDuplicateMapper.countMainRowsByRelPath(cleanKind, aliasRel) > 0)
        {
            throw MediaException.conflict("路径已被主表其它记录占用，不能提升: " + aliasRel);
        }

        // 交换：主行改指别名文件（id 不变），别名行改指旧主路径（组内行数不变）
        mediaDuplicateMapper.updatePrimaryPath(cleanKind, str(primary.get("id")), aliasRel, aliasRoot, aliasSize,
                folderOf(aliasRel));
        mediaDuplicateMapper.updateAliasPath(cleanKind, aliasId, primaryRel, primaryRoot, primarySize);
        mediaIndexService.invalidateSnapshot();
        log.info("重复文件保留 kind={} hash={} aliasId={} primaryId={} {} -> {}", cleanKind, hash, aliasId,
                str(primary.get("id")), primaryRel, aliasRel);

        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("id", primary.get("id"));
        kept.put("relPath", aliasRel);
        kept.put("size", aliasSize);
        kept.put("rootId", aliasRoot);
        return keepOut(cleanKind, hash, kept, previous);
    }

    // ------------------------------------------------------------------ 内部

    /** 把一个别名文件搬进回收站：跳过（0）/ 已搬（1）；路径越界或 IO 失败直接抛 */
    private int moveAliasToTrash(String kind, Integer rootId, String relPath, String aliasId, List<String> movedToTrash)
    {
        if (StringUtils.isEmpty(relPath))
        {
            return 0;
        }
        Path source;
        try
        {
            source = mediaFileService.resolveMedia(relPath, rootId);
        }
        catch (MediaException e)
        {
            if (e.getStatus() == 404)
            {
                // 磁盘上已经没有这份副本了：行照删，文件无需搬
                log.warn("别名文件不存在，跳过搬移 kind={} rel={} root={}", kind, relPath, rootId);
                return 0;
            }
            throw e;
        }
        Path targetDir = mediaFileService.resolveTrashDir(kind, rootId);
        Path target = MediaTrashSupport.uniqueTarget(targetDir, aliasId, source.getFileName().toString());
        try
        {
            MediaTrashSupport.moveOrCopy(source, target);
        }
        catch (IOException e)
        {
            log.error("别名文件搬入回收站失败 kind={} {} -> {}", kind, source, target, e);
            throw new MediaException(500, "移动失败（跨盘或文件被占用）：" + e.getMessage());
        }
        movedToTrash.add(mediaFileService.relativize(target));
        return 1;
    }

    private void requireAliasFileExists(String relPath, Integer rootId)
    {
        try
        {
            mediaFileService.resolveMedia(relPath, rootId);
        }
        catch (MediaException e)
        {
            if (e.getStatus() == 404)
            {
                throw MediaException.badRequest("别名文件在磁盘上不存在，不能提升为主文件: " + relPath);
            }
            throw e;
        }
    }

    private static Map<String, Object> keepOut(String kind, String hash, Map<String, Object> kept,
            Map<String, Object> previous)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("contentHash", hash);
        out.put("kept", kept);
        out.put("previous", previous);
        return out;
    }

    /** 别名 / 主行对外节点：只看文件身份，不暴露别名字段 */
    private static Map<String, Object> node(Map<String, Object> row)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", row.get("id"));
        node.put("relPath", row.get("relPath"));
        node.put("size", num(row.get("size")));
        node.put("rootId", row.get("rootId"));
        return node;
    }

    private static String requireKind(String kind)
    {
        String clean = StringUtils.isEmpty(kind) ? null : kind.trim().toLowerCase();
        if (KIND_VIDEO.equals(clean) || KIND_PHOTO.equals(clean))
        {
            return clean;
        }
        throw MediaException.badRequest("kind 只能是 video / photo，收到: " + kind);
    }

    private static String requireHash(String contentHash)
    {
        String clean = StringUtils.isEmpty(contentHash) ? null : contentHash.trim();
        if (clean == null || !HASH.matcher(clean).matches())
        {
            throw MediaException.badRequest("contentHash 必须是 1-16 位十六进制（content_hash）: " + contentHash);
        }
        return clean.toLowerCase();
    }

    /** rel_path 的一级目录 = media_video.folder 冗余列（与扫描脚本同一口径） */
    private static String folderOf(String relPath)
    {
        if (relPath == null)
        {
            return null;
        }
        int cut = relPath.indexOf('/');
        return cut > 0 ? relPath.substring(0, cut) : "";
    }

    private static String str(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static long num(Object value)
    {
        if (value == null)
        {
            return 0L;
        }
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? 0L : Long.parseLong(text);
    }
}
