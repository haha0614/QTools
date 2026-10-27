package com.qtools.media.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 媒体库日常维护 服务层（多库根 media_root + 便携包导出产物）
 *
 * <p>这两块都是「日常维护不用命令行」的落点：</p>
 * <ul>
 *   <li>多库根：把 {@code media_root} 的增删改搬到管理端，减少一次手写 SQL 的机会；删除前必须确认没有
 *       {@code media_video} / {@code media_photo} 的 {@code root_id} 还指着它；</li>
 *   <li>导出产物：只暴露 {@code <qtools.media.exportPath>} 之下、目录名形如 {@code yyyyMMdd-HHmmss} 的
 *       常规文件，任何越界（{@code ..}、绝对路径、软链接）一律 403。</li>
 * </ul>
 *
 * @author qtools
 */
public interface IMediaMaintenanceService
{
    /** 多库根清单：[{id,name,path,kind,guestVisible,note,exists,readable,videoCount,photoCount,isDefault}] */
    List<Map<String, Object>> listRoots();

    /**
     * 新增库根
     *
     * @param path 必须存在且是目录、不能与已有根重复（唯一键 uk_media_root_path）
     * @param kind root / thumbs / trash，缺省 root
     * @param guestVisible 未登录是否可见（P5.78），{@code null} = 默认 true（沿用改动前的行为）
     */
    Map<String, Object> createRoot(String name, String path, String kind, String note, Boolean guestVisible);

    /**
     * 改库根：只改传入的字段
     *
     * <p>提示：改 {@code path} <b>不会</b>自动重扫，{@code rel_path} / {@code root_id} 仍指向旧布局，
     * 需要再跑一次 {@code mode=scan}（或 {@code backfill-hash}）。</p>
     */
    Map<String, Object> updateRoot(Integer id, String name, String path, String note, Boolean guestVisible);

    /** 删除库根：{@code media_video} / {@code media_photo} 仍有引用时 400，并在 message 里给出引用数 */
    Map<String, Object> deleteRoot(Integer id);

    /** 最近 10 次导出目录 + 每目录文件清单与大小 */
    List<Map<String, Object>> exportList();

    /**
     * 校验下载目标并返回绝对路径
     *
     * @param dir  {@code yyyyMMdd-HHmmss}
     * @param file 文件名，扩展名必须是 sql / gz / json / zip
     * @throws com.qtools.media.exception.MediaException 400 名字不合法 / 403 越出导出根 / 404 文件不存在
     */
    Path resolveExportFile(String dir, String file);
}
