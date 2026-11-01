package com.qtools.media.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.qtools.common.exception.ServiceException;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaIndexService;

/**
 * 照片「连文件一起删」的唯一实现（P6.51）。
 *
 * <p><b>为什么必须带文件</b>：处在媒体根里的文件下一次扫描会被重新导入——扫描器按目录遍历
 * （{@code scan-media.js}），只排除 {@code _媒体站} / {@code trash} 这类目录名，而回收站就在
 * 这个排除名单里。所以对一条照片行做「只删行」等于假删：文件还在原地，下一轮扫描原地复活。
 * 把「搬进回收站」和「删行」绑成一个动作，才是可恢复的真删除。</p>
 *
 * <p>调用方：按图改归属的「只保留一份」策略、重复文件页的「同内容多份」清理。</p>
 */
@Component
public class MediaPhotoRemover
{
    private static final Logger log = LoggerFactory.getLogger(MediaPhotoRemover.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private IMediaIndexService mediaIndexService;

    /**
     * 删除一条照片行：文件移入回收站（存在才搬）+ 删行 + 重算所属册 + 索引失效。
     *
     * <p>返回 {@code {id, albumId, relPath, trashed, trashRel, rootId}}：
     * {@code trashed=false} 表示文件本来就不在磁盘上（只清了行）。</p>
     */
    public Map<String, Object> remove(long photoId)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, album_id, rel_path, root_id, content_hash from media_photo where id = ?", photoId);
        if (rows.isEmpty())
        {
            throw new ServiceException("照片不存在（可能已被删除）：" + photoId);
        }
        Map<String, Object> row = rows.get(0);
        String rel = String.valueOf(row.get("rel_path"));
        String albumId = String.valueOf(row.get("album_id"));
        Object rawRoot = row.get("root_id");
        Integer rootId = rawRoot == null ? null : Integer.valueOf(String.valueOf(rawRoot));
        Object rawHash = row.get("content_hash");
        String hash = rawHash == null ? null : String.valueOf(rawHash);

        // P6.76：resolveMedia 对「文件已不在磁盘」是直接 404 抛出，而重复文件页里正好有这种行
        //（另一份是同一内容的副本，原件早被删/搬走），于是「清理这份」整条失败、永远清不掉。
        // 这里退化成「只删行」：没有文件可搬，就没有复活风险。
        Path source = null;
        try
        {
            source = mediaFileService.resolveMedia(rel, rootId);
        }
        catch (MediaException e)
        {
            log.info("照片删除：源文件解析不到（按文件缺失处理，只删行）id={} rel={} 原因={}", photoId, rel,
                    e.getMessage());
        }
        boolean trashed = false;
        String trashRel = null;
        if (source != null && Files.exists(source))
        {
            Path targetDir = mediaFileService.resolveTrashDir("photo", rootId);
            Path target = MediaTrashSupport.uniqueTarget(targetDir, String.valueOf(photoId),
                    source.getFileName().toString());
            try
            {
                MediaTrashSupport.moveOrCopy(source, target);
            }
            catch (IOException e)
            {
                log.error("重复文件清理：移入回收站失败 id={} {} -> {}", photoId, source, target, e);
                throw new MediaException(500, "移入回收站失败（跨盘或文件被占用）：" + e.getMessage());
            }
            // 这一行马上要被删掉：原路径必须随条目落盘，否则回收站里只剩「原记录已丢失」
            // 路径 + 内容哈希一起落盘：有了哈希，回收站里还能出封面和预览大图
            MediaTrashSupport.writeMeta(target, rel, hash);
            trashed = true;
            trashRel = mediaFileService.relativize(target);
        }

        jdbc.update("delete from media_photo where id = ?", photoId);
        recompute(jdbc, albumId);
        mediaIndexService.invalidateSnapshot();
        log.info("照片删除（含回收站）id={} album={} rel={} trashed={} trash={}", photoId, albumId, rel,
                trashed, trashRel);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", photoId);
        out.put("albumId", albumId);
        out.put("relPath", rel);
        out.put("rootId", rootId);
        out.put("trashed", trashed);
        out.put("trashRel", trashRel);
        return out;
    }

    private static void recompute(JdbcTemplate jdbc, String albumId)
    {
        jdbc.update("update media_album a set photo_count = (select count(*) from media_photo p where p.album_id = a.id),"
                + " bytes = (select coalesce(sum(p.bytes), 0) from media_photo p where p.album_id = a.id)"
                + " where a.id = ?", albumId);
    }
}
