-- =====================================================================
-- 人物封面：按「视图 × 作用域」各自保存（P6.101）
--
-- 背景：站端人物墙现在有四种卡片版式（海报 / 大图 / 瀑布 / 紧凑），用户要求
--   「每个视图的背景参数互不影响，可以选不同的图（头像 / 名下照片 / 视频帧），
--     也可以同一张图取不同位置；没设过就用管理端配的默认值」。
--
-- 键：(person_id, view, scope)
--   view  = poster | hero | masonry | compact
--   scope = default（管理端配，对所有访客生效）| user（站端用户自己调）
-- 取值优先级：user(view) → default(view) → 自动（沿用 avatarOf 的三级回退）
--
-- 三列几何与 media_person.bg_scale/bg_dx/bg_dy 同口径（P5.88）：
--   bg_scale 0.5–3（3 位小数），bg_dx / bg_dy 是卡片宽高的比例 ±2（4 位小数）。
-- 幂等：可反复执行。
-- =====================================================================
create table if not exists media_person_view (
  person_id   bigint       not null comment '人物 id（media_person.id）',
  view        varchar(16)  not null comment '视图：poster / hero / masonry / compact',
  scope       varchar(16)  not null comment '作用域：default（管理端默认）/ user（站端用户）',
  source_kind varchar(8)   not null default 'auto' comment '图源：auto（沿用头像三档）/ photo（名下照片）/ frame（视频帧）',
  source_ref  varchar(512) null     comment 'photo = "<albumId>:<seq>[#crop=…]"；frame = "frame:<videoId>:<sec>[#crop=…]"；auto 为空',
  bg_scale    decimal(5,3) null     comment '缩放 0.5–3，NULL = 自动',
  bg_dx       decimal(6,4) null     comment '水平偏移（卡片宽的比例，±2）',
  bg_dy       decimal(6,4) null     comment '垂直偏移（卡片高的比例，±2）',
  updated_at  datetime     not null default current_timestamp on update current_timestamp,
  primary key (person_id, view, scope),
  key idx_mpv_person (person_id)
) engine = InnoDB default charset = utf8mb4 comment = '人物卡封面：按视图保存的图源与几何';
