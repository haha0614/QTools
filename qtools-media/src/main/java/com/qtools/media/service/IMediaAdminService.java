package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.common.core.page.TableDataInfo;

/**
 * 管理端媒体库 服务层
 *
 * <p>分页由调用方先用 PageHelper 开启（控制器继承 BaseController），本层负责查询、数据整形，
 * 并在整形之前用 {@link com.github.pagehelper.PageInfo} 取出真实 total —— 行整形会把 PageHelper
 * 返回的 Page 换成普通 List，若在控制器里再 getDataTable 就只能拿到「当前页条数」当作 total。</p>
 * <p>软删一律「移动到回收站 + hidden=1」，任何方法都不得真正删除文件。</p>
 *
 * @author qtools
 */
public interface IMediaAdminService
{
    /**
     * 视频分页；hidden 传 null 表示不过滤
     *
     * @param tagId 标签层级筛选：服务端把它展开成「自身 + 全部后代标签」再过滤
     *              （选中父标签必须能命中挂在子标签下的视频）；null = 不按标签过滤
     */
    TableDataInfo selectVideoPage(String keyword, String folder, String person, String vcodec, String hidden,
            String sort, Long tagId, String ids);

    /** 相册分页 */
    TableDataInfo selectAlbumPage(String keyword, Long personId, String hidden, String sort);

    /** 某册图片分页 */
    TableDataInfo selectPhotoPage(String albumId);

    /**
     * 某册全部图片（<b>只读库</b>，不列磁盘）
     *
     * @param albumId 相册 id
     * @return [{id,seq,relPath,missing,bytes,w,h,thumbUrl,previewUrl,thumbVersion,contentHash,lastSeen}]
     *         —— thumbUrl / previewUrl 有 content_hash 时是内容寻址 URL（{@code /api/media/thumb/p/<hash>}），
     *         否则回退老命名 {@code /api/media/thumb/p/<albumId>/<%04d seq>}；thumbVersion 恒非空，
     *         供前端将来拼 {@code ?v=} 做 cache-busting。缺失行排在最后。
     */
    List<Map<String, Object>> albumPhotos(String albumId);

    /**
     * 缺失记录分页（missing = 1 的行）
     *
     * @param kind    photo / video / all（默认 photo）
     * @param albumId photo 用：只查该册（可空）
     * @return {rows:[{kind,id,albumId,seq,relPath,title,bytes,missing,lastSeen,missingSince}], total}
     */
    Map<String, Object> missingList(String kind, String albumId, int pageNum, int pageSize);

    /**
     * 清理缺失记录（真删库里的行；磁盘上本来就没文件）
     *
     * @param kind photo / video / all
     * @param ids  精确清理的 id（photo 用数字串，video 用 video id）
     * @param all  true = 清空该类型所有缺失行（与 ids 互斥，优先 all）
     * @return {deleted, kind, all}
     */
    Map<String, Object> pruneMissing(String kind, List<String> ids, boolean all);

    /** 详情：kind ∈ video/album/photo */
    Map<String, Object> selectDetail(String kind, String id);

    /**
     * 设置隐藏位
     *
     * @param kind    video/album/photo
     * @param id      video id / album id / photoId；photo 亦可传 {@code <albumId>:<seq>} 或非数字串 + albumId/seq
     * @param hidden  目标状态
     * @param albumId photo 用相册 id（可空）
     * @param seq     photo 用册内序号（可空）
     * @return {updated, kind, id, hidden}
     */
    Map<String, Object> updateHidden(String kind, String id, Boolean hidden, String albumId, Integer seq);

    /** 软删：移动到回收站并置 hidden=1 */
    Map<String, Object> delete(String kind, String id);

    /** 从回收站移回原路径并置 hidden=0 */
    Map<String, Object> restore(String kind, String id);

    /**
     * 回收站分页（以磁盘为准，不是查表）
     *
     * @param kind   all / video / album / photo
     * @param keyword 名称、原路径、id 模糊匹配
     * @return 每行 {kind,id,name,originalRel,orphan,trashRel,bytes,deletedAt,isDir,thumbUrl}
     */
    TableDataInfo selectTrashPage(String kind, String keyword, int pageNum, int pageSize);

    /** 回收站统计：{count,bytes,byKind:{video:{count,bytes},...}} */
    Map<String, Object> trashStats();

    /**
     * 彻底删除（唯一真删入口，只允许 trash 根下的对象）
     *
     * @param kind     video / album / photo
     * @param id       对象 id（trashRel 为空时按它定位最近一次软删的条目）
     * @param trashRel 相对媒体根的回收站路径，用于精确指定同 id 多份中的某一条
     * @return {purged:true, bytes, freed, ...}
     */
    Map<String, Object> purgeTrash(String kind, String id, String trashRel);

    /** 占用统计 */
    Map<String, Object> stats();

    /**
     * 配置自检（{@code GET /api/media/admin/config}）
     *
     * <p>逐项检查 {@code qtools.media.*} 的路径是否存在 / 可读 / 可写、ffmpeg 与 node 是否可执行、
     * 脚本是否就位，并汇总库内条数。本方法<b>不抛异常</b>：任何单项检查失败只往 warnings 里记一条可读文案。</p>
     *
     * @return {home, media:{rootPath,thumbsPath,trashPath,roots,ffmpeg,nodePath,scanScript,thumbScript,
     *         scanOutPath,maxTranscode,scanArgs}, counts:{videos,albums,photos,trashEntries}, warnings:[...]}
     */
    Map<String, Object> config();

    /** 人物列表（id/name/albumCount/photoCount） */
    List<Map<String, Object>> people();

    /** 改相册人物归属；personId 为 null 表示未归类 */
    Map<String, Object> updateAlbumPerson(String albumId, Long personId);
}
