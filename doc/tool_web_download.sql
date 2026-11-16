create table if not exists tool_web_download (
  id          bigint      not null auto_increment,
  post_id     bigint      not null comment 'tool_web_post.id',
  mode        varchar(16) not null comment 'local=保存到本地 / media=保存到媒体站',
  state       varchar(16) not null comment 'queued/preparing/ready/done/error/pending_import',
  percent     int         not null default 0,
  size_bytes  bigint      null,
  params      text        null comment '存入媒体站时的参数（分级/人物/相册）JSON',
  error       varchar(255) null,
  created_at  datetime    not null default current_timestamp,
  finished_at datetime    null,
  primary key (id),
  key idx_web_dl_post (post_id),
  key idx_web_dl_created (created_at)
) engine = InnoDB default charset = utf8mb4 comment = '内容聚合-下载记录';