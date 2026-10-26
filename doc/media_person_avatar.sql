-- =====================================================================
-- 媒体库「人物头像支持手动指定」（P5.48）
--
-- 交付内容：media_person 增加 2 个可空列：
--   avatar_kind VARCHAR(8)   NULL   手动头像来源类型：'photo' | 'url'
--   avatar_ref  VARCHAR(512) NULL   来源标识（**不存二进制**）：
--                                   photo → '<albumId>:<seq>'（如 'a164:0'）
--                                   url   → 'https://…' / 'data:image/…'
--
-- 为什么是「albumId + seq」而不是 media_photo.id（选择理由）：
--   1. (album_id, seq) 是 media_photo 的**自然唯一键** uk_photo_album_seq，跨重扫稳定；
--      media_photo.id 是自增代理键，一次重扫 = 删行 + 重插，id 会变，
--      头像会静默指向另一张图（用户看不到任何报错，只看到「头像自己换了」）。
--   2. 缩略图 URL 由 (albumId, seq) 直接拼出（/api/media/thumb/p/<albumId>/<pad4(seq)>），
--      读的时候零额外查询：人物列表 / 索引 / 详情三个出口都能在**同一行**上解析出最终 URL，
--      不需要 join media_photo（列表里 N 个人物就 N 次 join，成本白白多一倍）。
--   3. 校验仍然只看 media_photo（album_id, seq）存在 + 该相册属于这个人物，
--      一条 count 查询即可，不需要额外索引。
--
-- 为什么用 avatar_ref 字符串而不是再来一列 avatar_photo_id / avatar_url：
--   两列会让「哪一列才算数」变成第三条不变量（kind 与列必须互相一致），
--   kind + ref 只有一条不变量：kind 决定 ref 怎么解释。
--
-- 与既有 avatar_path（VARCHAR(400)，管理端 POST /admin/persons/{id}/avatar 用）的关系：
--   不动 avatar_path 一个字节 —— 老数据 / 老接口照旧可读写。
--   解析优先级（MediaPersonServiceImpl#avatarUrlOf）：avatar_kind+avatar_ref（手动）优先，
--   其次 avatar_path（历史），两者都没有 → avatarUrl 为 NULL，前端继续走三级自动回退
--   （相册首图 → 视频封面 → 首字占位）。
--
-- 幂等：所有 DDL 先查 information_schema，已存在则跳过；本文件可反复执行。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_person_avatar.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文备注会按 GBK 写进数据字典）
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS qtools_person_avatar_add_column;

DELIMITER $$

CREATE PROCEDURE qtools_person_avatar_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(500))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

CALL qtools_person_avatar_add_column('media_person', 'avatar_kind',
  'VARCHAR(8) NULL COMMENT ''手动头像来源类型：photo=人物名下相册图片 / url=外部图片地址（NULL = 未手动指定）''');

CALL qtools_person_avatar_add_column('media_person', 'avatar_ref',
  'VARCHAR(512) NULL COMMENT ''手动头像来源标识：photo=<albumId>:<seq>（如 a164:0）/ url=http(s) 或 data:image 地址（NULL = 未指定，不存二进制）''');

DROP PROCEDURE IF EXISTS qtools_person_avatar_add_column;

-- ---------------------------------------------------------------------
-- 自检（只读，可反复执行）
-- ---------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'media_person'
   AND column_name IN ('avatar_kind', 'avatar_ref')
 ORDER BY ordinal_position;

SELECT COUNT(*) AS persons,
       SUM(avatar_kind IS NOT NULL) AS with_manual_avatar,
       SUM(avatar_kind = 'photo')   AS photo_kind,
       SUM(avatar_kind = 'url')     AS url_kind
  FROM media_person;

-- 非法 kind 的自检：应恒为 0 行（应用侧只写 photo / url，这里防的是直连 SQL 写脏）
SELECT id, name, avatar_kind, avatar_ref
  FROM media_person
 WHERE avatar_kind IS NOT NULL AND avatar_kind NOT IN ('photo', 'url');
