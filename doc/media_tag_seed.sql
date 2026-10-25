-- =====================================================================
-- 媒体库标签字典回填（P5.3-A）
--
-- 背景（已确诊）：
--   media_tag 是站端「标签选择器」的唯一数据源（GET /api/media/tags →
--   MediaOrganizeServiceImpl#tagList），但这一版库里 **0 行**；
--   真正的标签数据全在 media_video_tag（facet 维度行，name 是标签值，tag_id 为 NULL
--   表示扫描脚本写的历史 facet 行）。字典为空 ⇒ 前端选择器「无匹配标签」。
--
-- 本文件做两件事，且**只**做这两件事：
--   (1) 把 media_video_tag 里 distinct 的 name 回填成 media_tag 字典行（同名跳过）
--   (2) 统一重算 media_tag.use_count（对回填行与既有行都重算，口径 = name 相同行数）
--
-- 故意不做的事（重要）：
--   * **不回填 media_video_tag.tag_id**：684 行历史 facet 行保持原样（tag_id = NULL）。
--     只有站端 / 管理端**新打**的标签才写 tag_id。理由与 media_organize.sql 的
--     「name-only 历史行永远不动」一致：既有的 684 行是扫描脚本的产物，
--     一次性改写 tag_id 一旦判断错就是不可逆的批量数据污染，而回填字典并不需要它。
--   * 不建 media_tag.parent_id（标签层级与本次回填无关）。
--
-- 已知口径差异（不是 bug，但必须知道）：
--   MediaOrganizeMapper.xml#recomputeUseCount 重算的是 **tag_id 口径**
--   （count(*) from media_video_tag where tag_id = t.id）。本文件回填的是 **name 口径**。
--   因此：某个标签被站端/管理端实际编辑一次后，它的 use_count 会被应用侧按 tag_id 口径
--   重算（历史 facet 行没有 tag_id ⇒ 该行 use_count 回落到「新打的绑定数」，通常 0）。
--   影响面仅限「标签选择器的排序优先级」，不影响标签是否存在、能否选中。
--   若要彻底统一，改动点在 MediaOrganizeMapper.xml#recomputeUseCount（改为 name 口径），
--   属于另一个决定，本文件不动应用代码。
--
-- 超长名字（必须先看再决定，本文件**不静默截断**）：
--   media_tag.name           VARCHAR(32)  NOT NULL
--   media_video_tag.name     VARCHAR(120) NOT NULL
--   长度 > 32 的名字无法进字典。策略 = **跳过并打印**：
--     步骤 0 先列出超长清单（执行前先看一眼），步骤 1 的 INSERT 带
--     CHAR_LENGTH(t.name) <= 32 过滤，步骤 4 再列出「被跳过的名字」做对账。
--   绝不截断：截断会把「某某（超长的完整标签值）」变成另一个标签，
--   字典里出现两个前缀相同的不同标签比缺一个更难收拾。
--   若确实需要收编超长名：先人工决定「改名 / 拆词 / 放宽 media_tag.name 列宽」，
--   不要在迁移里机器截断。
--
-- 幂等性：本文件可反复执行。
--   第二遍：步骤 1 插入 0 行（LEFT JOIN ... mt.id IS NULL 天然去重），
--           步骤 2 的 use_count 结果不变（同口径重算）。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -t -e "source media_tag_seed.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文标签名会按 GBK 落库；
--     -t 只是为了表格化输出便于看自检结果）
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 预检：超长 name 清单（> 32 字符，进不了字典的那些）
--    看到非空结果时先停下来决定：改名 / 拆词 / 放宽列宽，三选一，
--    再决定是否放行步骤 1（步骤 1 本身会跳过它们，不会报错）。
-- ---------------------------------------------------------------------
SELECT 'PRE-CHECK 超长 name（>32，将被跳过）' AS check_item;

SELECT t.name,
       CHAR_LENGTH(t.name) AS name_len,
       MIN(t.facet)        AS sample_facet,
       COUNT(*)            AS rows_cnt
FROM media_video_tag t
WHERE CHAR_LENGTH(t.name) > 32
GROUP BY t.name
ORDER BY name_len DESC, t.name ASC;

-- ---------------------------------------------------------------------
-- 1) 回填字典：distinct name → media_tag（同名跳过 = 幂等）
--    use_count 先按 name 口径给个初值，步骤 2 会再统一重算一次
--    （步骤 1 的 COUNT(*) 是「本次回填的那批行数」，与步骤 2 的口径一致，
--      重复计算一次是为了让「已有行」也被覆盖到）
-- ---------------------------------------------------------------------
INSERT INTO media_tag (name, color, use_count)
SELECT t.name, NULL, COUNT(*)
FROM media_video_tag t
LEFT JOIN media_tag mt ON mt.name = t.name
WHERE mt.id IS NULL
  AND CHAR_LENGTH(t.name) <= 32
GROUP BY t.name;

-- 步骤 1 实际插入行数（第二遍执行时应为 0）
SELECT 'STEP-1 本次回填插入行数' AS check_item, ROW_COUNT() AS inserted_rows;

-- ---------------------------------------------------------------------
-- 2) 统一重算 use_count（覆盖回填行 + 既有行）
--    口径：media_video_tag 中 name 相同的行数（含 tag_id 为 NULL 的历史 facet 行）
--    只读 media_video_tag，不触 media_tag 自身，MySQL 8 允许这种相关子查询更新。
-- ---------------------------------------------------------------------
UPDATE media_tag mt
SET mt.use_count = (SELECT COUNT(*) FROM media_video_tag x WHERE x.name = mt.name);

-- ---------------------------------------------------------------------
-- 3) 自检 1：字典行数 vs 库里 distinct name 数（看是否对齐）
-- ---------------------------------------------------------------------
SELECT 'SELF-CHECK 字典 / distinct-name 对比' AS check_item;

SELECT (SELECT COUNT(*) FROM media_tag)                                          AS dict_rows,
       (SELECT COUNT(DISTINCT name) FROM media_video_tag)                        AS distinct_names,
       (SELECT COUNT(DISTINCT name) FROM media_video_tag WHERE CHAR_LENGTH(name) > 32) AS skipped_long_names,
       (SELECT COUNT(*) FROM media_video_tag WHERE tag_id IS NULL)               AS facet_rows_tagid_null,
       (SELECT COUNT(*) FROM media_video_tag WHERE tag_id IS NOT NULL)           AS dict_rows_tagid_set;

-- ---------------------------------------------------------------------
-- 4) 自检 2：use_count 前 10（站端选择器的排序就是这个顺序）
-- ---------------------------------------------------------------------
SELECT 'SELF-CHECK use_count TOP10' AS check_item;

SELECT mt.id, mt.name, mt.color, mt.use_count
FROM media_tag mt
ORDER BY mt.use_count DESC, mt.name ASC
LIMIT 10;

-- ---------------------------------------------------------------------
-- 5) 自检 3：仍为 NULL tag_id 的行数（应为回填前的原值，本文件不改它）
--               + 被跳过的超长 name 清单（对账用，nothing silently dropped）
-- ---------------------------------------------------------------------
SELECT 'SELF-CHECK 未进字典的 name（超长，被跳过）' AS check_item;

SELECT t.name, CHAR_LENGTH(t.name) AS name_len, COUNT(*) AS rows_cnt
FROM media_video_tag t
LEFT JOIN media_tag mt ON mt.name = t.name
WHERE mt.id IS NULL
GROUP BY t.name
ORDER BY name_len DESC, t.name ASC;

-- 收尾：历史 facet 行一条都没被改写（与执行前对比这一条即可）
SELECT 'SELF-CHECK media_video_tag 未被改写' AS check_item,
       (SELECT COUNT(*) FROM media_video_tag WHERE tag_id IS NULL) AS facet_rows_tagid_null;
