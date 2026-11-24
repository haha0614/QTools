-- 作品层：一个源站帖子 = 一个作品，含 N 段视频（多段帖在视频站不再是一堆孤立文件）
-- 为什么加：视频站实体是单个视频文件；多段帖（现在 5,393 帖）进库后无法归组、
--   也没有 post_id 映射，无法回跳源帖/判断哪边新。见 HANDOFF §24。
-- 谁在用：qtools-tool 的 WebMediaImportService.linkWork()（导入时写），
--   qtools-media 的 MediaWorkController（读），前端 WorkPage.vue。
-- 约定：media_video 不动（扫描器零改动）；段关联靠 content_hash 稳定的 id，文件改名不断线。

use qtools;

create table if not exists media_work (
  id bigint not null auto_increment comment '作品 id',
  post_id bigint null comment '源站帖子 id（tool_web_post.id），手动导入的作品为 NULL',
  source_id varchar(32) null comment '源站目录名（data/media/web/<source_id>），用于取源站封面',
  title varchar(300) not null comment '作品标题（导入时的帖子标题）',
  synopsis_blocks longtext null comment '正文图文混排 blocks JSON（图片引用 /web/<source_id>/pN.jpg）',
  cover_path varchar(500) null comment '封面相对路径，用源站封面 /web/<source_id>/cover.jpg',
  seg_count int not null default 0 comment '段数',
  total_dur decimal(12,2) not null default 0 comment '总时长（秒），由 enrich-meta 回填',
  state varchar(16) not null default 'ready' comment 'ready/partial/failed',
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp,
  primary key (id),
  unique key uk_work_post (post_id),
  key idx_work_created (created_at)
) engine=InnoDB default charset=utf8mb4 comment='视频站作品（一帖=一个作品）';

create table if not exists media_work_segment (
  work_id bigint not null comment 'media_work.id',
  seq int not null comment '段序，从 0 开始',
  video_id varchar(16) not null comment 'media_video.id（sha1(rel_path)[:12]，改名不断线）',
  primary key (work_id, seq),
  key idx_wseg_video (video_id)
) engine=InnoDB default charset=utf8mb4 comment='作品与视频段的对应关系';
