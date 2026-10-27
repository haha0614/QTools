package com.qtools.media.service;

import java.util.Map;

/**
 * 产物根（{@code media_root.kind='thumbs'}）子目录清单 服务（P5.80）
 *
 * <p>解决什么问题：一个产物根里同时躺着 7 类派生资源（p / v / s / pv / pv2 / frames / avcrop），
 * 清理缓存时「哪一类占了多少」只能靠人工 {@code du}。本服务把这件事做成一次带边界的扫描：</p>
 *
 * <ul>
 *   <li><b>只认产物根</b>：{@code kind} 不是 {@code thumbs} 的行一律拒绝（内容根本身不放派生资源）；</li>
 *   <li><b>路径只信库</b>：目录来自 {@code media_root.path}，客户端不能传任何路径片段；</li>
 *   <li><b>有边界</b>：最多 {@value com.qtools.media.service.impl.MediaRootArtifactServiceImpl#MAX_FILES} 个文件 /
 *       {@value com.qtools.media.service.impl.MediaRootArtifactServiceImpl#MAX_MILLIS} ms /
 *       {@value com.qtools.media.service.impl.MediaRootArtifactServiceImpl#MAX_DEPTH} 层深，超限返回
 *       {@code partial=true} 与已统计量，绝不让「几十万文件的目录」把请求线程拖住；</li>
 *   <li><b>缓存 60s</b>：{@code ?refresh=true} 强制重扫（管理端「重扫」按钮）。</li>
 * </ul>
 *
 * @author qtools
 */
public interface IMediaRootArtifactService
{
    /**
     * 一个产物根的子目录清单（文件数 / 占用 / 是否存在）
     *
     * @param rootId  {@code media_root.id}
     * @param refresh true = 跳过 60s 缓存，重新统计
     * @return {@code {rootId,kind,path,exists,dirs:[{name,label,files,bytes,exists}],unknown:[...],
     *         total:{files,bytes},looseFiles,looseBytes,partial,partialReason,cached,scannedAt,durationMs,note}}
     */
    Map<String, Object> artifacts(Integer rootId, boolean refresh);
}
