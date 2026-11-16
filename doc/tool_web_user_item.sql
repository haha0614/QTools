create table if not exists tool_web_user_item (
  id        bigint      not null auto_increment comment '主键',
  owner_key varchar(64) not null comment '归属键：u:<userId> 账号态 / 裸设备键 匿名态',
  post_id   bigint      not null comment 'tool_web_post.id',
  kind      varchar(8)  not null comment 'fav 收藏 / seen 看过',
  created_at datetime   not null default current_timestamp,
  primary key (id),
  unique key uk_web_user_item (owner_key, post_id, kind),
  key idx_web_user_item (owner_key, kind, created_at)
) engine = InnoDB default charset = utf8mb4 comment = '内容聚合-收藏与观看状态';