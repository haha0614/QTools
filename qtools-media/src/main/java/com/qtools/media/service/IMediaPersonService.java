package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.media.domain.dto.MediaPersonPatch;

/**
 * 媒体人物 服务层（P3-A）
 *
 * <p>人物是一等实体：{@code media_person} 是字典，{@code media_video_actor.person_id}
 * 与 {@code media_album_person} 是关联。所有写方法都带 {@code @Transactional}：
 * 一次全量替换要么全部生效、要么全部回滚。</p>
 *
 * <p><b>全量替换只动绑定行</b>：{@code PUT /admin/videos/{id}/persons} 清理的是
 * {@code person_id IS NOT NULL} 的行，扫描脚本写的 name-only 历史行（{@code person_id IS NULL}）
 * 不会被管理端的绑定操作清掉 —— 与 {@code media_video_tag.tag_id} 的处理完全对称。</p>
 *
 * <p>相册人物是 {@code media_album_person}（多对多）∪ {@code media_album.person_id}
 * （单选镜像列，= 多人物里的第一个）的并集：写入时两边同步，读的时候两个来源都算。</p>
 *
 * @author qtools
 */
public interface IMediaPersonService
{
    /**
     * 人物分页（管理端 {@code GET /api/media/admin/persons?q=&page=&size=}）
     *
     * @param q    name / alias 模糊匹配（可空）
     * @param page 1 起
     * @param size 默认 20，上限 200
     * @return {@code {rows:[{id,name,alias,note,gender,birthday,useCount,videoCount,albumCount}],total,page,size}}
     */
    Map<String, Object> personPage(String q, int page, int size);

    /**
     * 新建人物（{@code POST /api/media/admin/persons}）
     *
     * <p>name 去首尾空格后 1-64 字符且库内唯一：重名直接 <b>409</b>（不像标签那样静默复用 ——
     * 两个人的名字撞在一起时，静默复用会把绑定关系挂到另一个人身上）。</p>
     *
     * @return 新建的 {@code {id,name,alias,note,avatarPath,gender,birthday,useCount,videoCount,albumCount}}
     */
    Map<String, Object> createPerson(MediaPersonPatch patch);

    /**
     * 改人物（{@code PUT /api/media/admin/persons/{id}}）
     *
     * <p>字段出现即覆盖，{@code null} = 清空；改名撞别人 <b>409</b>。改完回最新整行。</p>
     */
    Map<String, Object> updatePerson(Long id, MediaPersonPatch patch);

    /**
     * 删人物（{@code DELETE /api/media/admin/persons/{id}}）
     *
     * <p>仍被视频 / 相册引用时 <b>400</b>，message 里带 videoCount / albumCount；
     * 先解绑再删（不做级联删除：一次误删会静默抹掉几百条绑定）。</p>
     *
     * @return {@code {deleted,id}}
     */
    Map<String, Object> deletePerson(Long id);

    /**
     * 设头像（{@code POST /api/media/admin/persons/{id}/avatar}，body {@code {path}}）
     *
     * <p>只存路径，不落二进制：上传沿用项目既有的 {@code POST /common/upload}，
     * 它返回的 {@code url}（{@code /profile/upload/2026/...jpg}）直接作为 path 回传即可；
     * {@code path} 为空 / {@code null} = 清空头像。</p>
     */
    Map<String, Object> updateAvatar(Long id, String path);

    /**
     * 全量替换某视频的人物与角色
     * （管理端 {@code PUT /api/media/admin/videos/{id}/persons}、站端 {@code PUT /api/media/video/{id}/persons}）
     *
     * <p><b>多人出演</b>（P5.3-A）：一次可以绑任意多个（上限 50）人物，每人一个角色；
     * 库侧唯一键是 {@code uk_actor_video_person(video_id, person_id)}，只约束「同一视频同一人只一行」，
     * 不限制人数。返回的 {@code persons} 就是落库后的全部绑定，前端照原样渲染即可。</p>
     *
     * <p><b>素人占位规则</b>（P5.3-A）：{@code media_person} 里 name='素人' 的那一行承载
     * 「没有演员」的视频（见 {@code doc/media_amateur_person.sql}）。规则：</p>
     * <ul>
     *   <li>提交里出现<b>素人以外的任何人物</b> → 素人被自动剔除（真实演员优先，绝不与素人共存）</li>
     *   <li>提交为空表（解绑全部）→ <b>落库是素人</b>，不是「一个都没有」：
     *       站端「清空演员」的语义是「这部片子没有署名」，用素人承载才不会让人物页漏掉它</li>
     *   <li>提交里只有素人 → 原样落库</li>
     *   <li>库里没有素人行（迁移未执行）→ 整条规则跳过，退回「空表 = 无人物」的原始语义</li>
     * </ul>
     *
     * @param personIds 目标人物集合（去重前上限 50；未知 id 一律 400）；空表 = 解除全部绑定（→ 素人）
     * @param roles     {@code personId -> 角色}（可空；键必须在该人的 personIds 里，值 <= 32 字符；
     *                  被规则剔除的素人键会被静默丢弃，不算 400）
     * @return {@code {updated,id,persons:[{personId,name,alias,role}]}}
     */
    Map<String, Object> replaceVideoPersons(String videoId, List<Long> personIds, Map<Long, String> roles);

    /**
     * 全量替换某相册的人物（{@code PUT /api/media/admin/albums/{id}/persons}）
     *
     * <p>同时把 {@code media_album.person_id} 同步成第一个人物（无人物 = NULL），
     * 保证老读路径（索引 people[] / {@code /admin/albums?personId=}）继续正确。</p>
     *
     * @return {@code {updated,id,persons:[{id,name}]}}
     */
    Map<String, Object> replaceAlbumPersons(String albumId, List<Long> personIds);

    // ------------------------------------------------------------------ 站端（写）

    /**
     * 站端「现场新建演员」：{@code POST /api/media/persons}（P5.3-A）
     *
     * <p>与 {@link #createPerson(MediaPersonPatch)} 的<b>唯一语义差异</b>：同名<b>复用</b>而不 409，
     * 返回里带 {@code created} 供前端区分「新建成功」与「已存在」。理由：站端编辑视频时的
     * 「现场新建演员」是输入即提交（用户打一个名字，回车就要拿到 id 去绑定），
     * 撞名报错会让用户被迫先去管理端查一遍；而两个真实同名演员在自托管场景里几乎不存在，
     * 复用比阻塞更符合使用场景。管理端保持 409 不变（那里是「建字典」，需要绝对唯一）。</p>
     *
     * <p>复用时<b>不覆盖</b>既有 alias：站端一次输入只表达「我要这个人的 id」，
     * 不该顺带改掉管理端维护的别名。{@code created=false} 时 alias 参数被忽略。</p>
     *
     * @param name  去首尾空格后 1-64 字符（超长 / 空 400）
     * @param alias 可空，{@code created=true} 时才写，<= 128 字符
     * @return {@code {id,name,created}}
     */
    Map<String, Object> ensurePerson(String name, String alias);

    // ------------------------------------------------------------------ 站端（只读）

    /** 站端人物清单 {@code [{id,name,alias,useCount}]}，useCount 降序 */
    List<Map<String, Object>> personBriefList();

    /** 站端人物详情 {@code {id,name,alias,note,avatarPath,gender,birthday,useCount,videoCount,albumCount}} */
    Map<String, Object> personDetail(Long id);

    /** 站端某人物名下视频分页 {@code {rows,total,page,size}} */
    Map<String, Object> personVideos(Long id, int page, int size);

    /** 站端某人物名下相册分页 {@code {rows,total,page,size}} */
    Map<String, Object> personAlbums(Long id, int page, int size);
}
