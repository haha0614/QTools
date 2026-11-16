-- =====================================================================
-- 「外部站点镜像」聚合表（P6.110）：把第三方站点的条目索引到本站
--
-- 目的：内容仍然是人家的（我们不做转存），但**承载页是我们自己的** ——
-- 去广告、按我们自己的分类/标签/收藏/播放器来组织。
--
-- 为什么只存元数据：视频是 HLS 签名流（auth_key 带时效），URL 存下来第二天就失效；
-- 播放时由 sidecar 现取当次地址（见 qtools-tool/web 下的代理），这里只留定位信息。
-- =====================================================================
create table if not exists tool_web_post
(
  id           bigint       not null auto_increment comment '主键',
  source       varchar(32)  not null default 'mrds' comment '来源标识（多站点聚合时区分）',
  source_id    varchar(32)  not null comment '源站条目 id（URL 里的 /archives/<id>/）',
  title        varchar(300) not null comment '标题',
  category     varchar(64)  null comment '源站分类（主题大赛 / 投稿原创 …）',
  tags         varchar(512) null comment '标签，逗号分隔',
  cover        varchar(512) null comment '封面图 URL',
  published_at varchar(32)  null comment '源站发布日期（原文，含中文，先不解析）',
  url          varchar(512) not null comment '源站详情页完整 URL',
  source_rank  int          not null default 0 comment '抓取时在源站列表里的位次（同日多条按它还原源站先后）',
  has_video    tinyint      not null default 0 comment '该条目是否带播放器',
  created_at   datetime     not null default current_timestamp,
  updated_at   datetime     not null default current_timestamp on update current_timestamp,
  primary key (id),
  unique key uk_web_post (source, source_id),
  key idx_web_post_cat (source, category),
  key idx_web_post_created (source, id),
  key idx_web_post_rank (source, source_rank),
  -- 源站日期是中文原文（'2026 年 11 月 15 日'），排序用这个自动算出来的标准日期
  published_ts date generated always as (
    str_to_date(replace(replace(replace(published_at, ' 年 ', '-'), ' 月 ', '-'), ' 日', ''), '%Y-%m-%d')
  ) stored,
  key idx_web_post_ts (source, published_ts)
) engine = InnoDB
  default charset = utf8mb4 comment = '外部站点条目索引（本站只做承载页）';
