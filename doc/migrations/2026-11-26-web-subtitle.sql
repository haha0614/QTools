use qtools;

-- 视频双语字幕（docs/视频字幕-实施交接.md §7.1）
--
-- 为什么加：站端播放器要挂 <track> 双语字幕（上日文 / 下中文）：whisper 转写片子的日语音轨，
--   再用 DeepSeek 翻成简体中文，成品落盘为 <qtools.home>/data/subtitles/{post_id}/{seg}.vtt。
--   本表只存「状态 + 元数据」，不存字幕原文（隐私，见文档 §12）。
-- 谁在用：QTools/qtools-tool/src/main/java/com/qtools/tool/web/subtitle/（WebSubtitleService /
--   WebSubtitleController）+ resources/mapper/tool/ToolWebSubtitleMapper.xml；
--   接口 /api/tool/web/subtitle/{postId}（§10）。
-- 幂等：create table if not exists —— 重复执行不报错，也不动既有数据。
create table if not exists `tool_web_subtitle`
(
    `id`         bigint       not null auto_increment comment '主键',
    `post_id`    bigint       not null comment 'tool_web_post.id',
    `seg`        int          not null default 0 comment '第几段视频（多视频帖按段生成）',
    `lang_src`   varchar(16)  not null default 'ja' comment '源语言',
    `lang_dst`   varchar(16)  not null default 'zh' comment '目标语言',
    `status`     tinyint      not null default 0 comment '0 待生成 / 1 生成中 / 2 成功 / 3 失败',
    `progress`   tinyint      not null default 0 comment '进度百分比',
    `dur_ms`     bigint       not null default 0 comment '字幕覆盖时长（毫秒）',
    `cue_count`  int          not null default 0 comment 'cue 条数',
    `engine`     varchar(64)  not null default '' comment '引擎标识（whisper 模型 + 翻译模型）',
    `file_path`  varchar(255) not null default '' comment 'vtt 绝对路径（相对 qtools.home 可重定位）',
    `err`        varchar(500) not null default '' comment '失败原因（不含字幕原文）',
    `started_at` datetime     null comment '本次开跑时间（超 2 小时视为僵死）',
    `created_at` datetime     not null default current_timestamp comment '创建时间',
    `updated_at` datetime     not null default current_timestamp on update current_timestamp comment '更新时间',
    primary key (`id`),
    unique key `uk_post_seg` (`post_id`, `seg`),
    key `idx_status` (`status`)
) engine = InnoDB
  default charset = utf8mb4
  collate = utf8mb4_0900_ai_ci comment = '视频双语字幕';
