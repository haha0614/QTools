-- =====================================================================
-- 媒体库「素人」人物 + 无演员视频自动归置（P5.3-A 追加）
--
-- 用户需求原话：「素人设置为人物标签，所有没设置演员的默认归类到这个人物下，
--   还有，演员标签同一个视频应当可以设置多个，存在多人出演的情况」
--
-- 「素人」= 一条**真实存在的 media_person 行**（不是前端假造的分类、也不是特殊常量），
-- 好处是索引 / 人物页 / 筛选器 / 视频详情的读路径一行都不用改：
-- 它和别的演员走同一套 media_video_actor 绑定，因此「素人名下的视频」用现成的
-- GET /api/media/person/{id}/videos 就能列出来，写接口也不需要任何特例。
--
-- 本文件做两件事（幂等，可反复执行）：
--   (1) 确保 media_person 里有 name='素人' 的行：不存在才插入，已存在直接复用 id
--   (2) 把所有「完全没有人物绑定」的视频绑到素人：
--       判定条件必须是 a.person_id IS NOT NULL（name-only 的历史扫描行不算「有演员」），
--       否则会漏绑那些只有 name 没有 person_id 的旧行所属的视频
--
-- 配套的服务层规则（Java 侧，见 MediaPersonServiceImpl#replaceVideoPersons）：
--   提交的 personIds 里只要有「素人以外的任何人物」→ 自动剔除素人；
--   提交空 / 只有素人 → 落库就是素人（解绑全部 = 回到素人，而不是变成「无人物」）。
--   因此本文件只需要一次性把存量视频归位，之后由服务层持续维持这个不变量。
--
-- use_count：素人的 use_count 与其它人物一样按关联行数重算（写入时重算，不做 +1/-1）。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -t -e "source media_amateur_person.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文人物名会按 GBK 落库）
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 预检：现状（执行前后各看一次即可看出本次绑定了多少）
-- ---------------------------------------------------------------------
SELECT 'PRE-CHECK 现状' AS check_item,
       (SELECT COUNT(*) FROM media_video)                                          AS videos,
       (SELECT COUNT(DISTINCT video_id) FROM media_video_actor
         WHERE person_id IS NOT NULL)                                              AS videos_with_person,
       (SELECT COUNT(*) FROM media_video v WHERE NOT EXISTS
          (SELECT 1 FROM media_video_actor a WHERE a.video_id = v.id AND a.person_id IS NOT NULL))
                                                                                    AS videos_without_person,
       (SELECT COUNT(*) FROM media_person WHERE name = '素人')                      AS amateur_rows;

-- ---------------------------------------------------------------------
-- 1) 确保素人存在（不存在才插入；uk_person_name 保证不会重复）
--    只写 name：其余列都有默认值（use_count / sort / hidden / created_at）
-- ---------------------------------------------------------------------
INSERT INTO media_person (name)
SELECT '素人'
WHERE NOT EXISTS (SELECT 1 FROM media_person WHERE name = '素人');

-- 本次是否新建了素人行（0 = 已存在，复用了既有 id）
SELECT 'STEP-1 本次新建素人行数' AS check_item, ROW_COUNT() AS inserted_rows;

-- 素人 id（后续语句复用；不存在时为 NULL，步骤 2 会因此插入 0 行而不是报错）
SET @amateur_id = (SELECT id FROM media_person WHERE name = '素人' LIMIT 1);

SELECT 'STEP-1 素人 id' AS check_item, @amateur_id AS amateur_id;

-- ---------------------------------------------------------------------
-- 2) 无人物绑定的视频 → 素人
--    INSERT IGNORE + uk_actor_video_person(video_id, person_id) 保证重复执行不新增行；
--    NOT EXISTS 保证「已经有真实演员的视频」一个都不碰。
--    role = NULL（素人不该占「主演 / 配角」这类角色槽位）。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO media_video_actor (video_id, name, person_id, role)
SELECT v.id, p.name, p.id, NULL
FROM media_video v
INNER JOIN media_person p ON p.id = @amateur_id
WHERE NOT EXISTS (
    SELECT 1 FROM media_video_actor a
    WHERE a.video_id = v.id AND a.person_id IS NOT NULL
);

-- 本次新绑定的视频数（第二遍执行时应为 0）
SELECT 'STEP-2 本次新绑定视频数' AS check_item, ROW_COUNT() AS bound_rows;

-- ---------------------------------------------------------------------
-- 3) 素人 use_count 重算（与 MediaPersonMapper#recomputePersonUseCount 同口径）
-- ---------------------------------------------------------------------
UPDATE media_person p
SET p.use_count = (SELECT COUNT(*) FROM media_video_actor a WHERE a.person_id = p.id)
WHERE p.id = @amateur_id;

-- ---------------------------------------------------------------------
-- 4) 自检
--    仍无任何 person 绑定的视频数必须 = 0；
--    素人绑定数 = 视频总数 - 有真实演员的视频数。
-- ---------------------------------------------------------------------
SELECT 'SELF-CHECK 归置结果' AS check_item,
       @amateur_id                                                                 AS amateur_id,
       (SELECT COUNT(*) FROM media_video_actor a WHERE a.person_id = @amateur_id)   AS amateur_videos,
       (SELECT use_count FROM media_person WHERE id = @amateur_id)                  AS amateur_use_count,
       (SELECT COUNT(*) FROM media_video)                                          AS videos,
       (SELECT COUNT(DISTINCT video_id) FROM media_video_actor
         WHERE person_id IS NOT NULL AND person_id <> @amateur_id)                  AS videos_with_real_person,
       (SELECT COUNT(*) FROM media_video v WHERE NOT EXISTS
          (SELECT 1 FROM media_video_actor a WHERE a.video_id = v.id AND a.person_id IS NOT NULL))
                                                                                    AS videos_without_person_must_be_0;

-- 素人一行本身（确认只有一行、名字没被截断 / 乱码）
SELECT 'SELF-CHECK 素人行' AS check_item, id, name, alias, use_count, hidden
FROM media_person WHERE name = '素人';
