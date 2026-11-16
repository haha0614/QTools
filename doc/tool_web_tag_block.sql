create table if not exists tool_web_tag_block (
  id        bigint      not null auto_increment comment '主键',
  owner_key varchar(64) not null comment '归属键：u:<userId> 或设备键',
  tag       varchar(64) not null comment '被屏蔽的标签',
  created_at datetime   not null default current_timestamp,
  primary key (id),
  unique key uk_web_tag_block (owner_key, tag),
  key idx_web_tag_block (owner_key)
) engine = InnoDB default charset = utf8mb4 comment = '内容聚合-标签黑名单';