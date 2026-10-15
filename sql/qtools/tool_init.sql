-- ----------------------------
-- QTools 工具模块建表 + 初始化脚本
-- 目标库：qtools（MySQL 8.0，InnoDB / utf8mb4）
-- 幂等说明：
--   1) 建表统一使用 create table if not exists，已存在时直接跳过；
--   2) 初始化数据使用 insert ... on duplicate key update（以主键 id 为幂等键），
--      重复执行只会把这三条记录刷回初始内容，不会产生重复数据，也无需先 delete。
--      注：MySQL 8.0.20+ 中 values(col) 写法被标记为 deprecated（仅告警，功能正常），
--      这里为兼容更广泛的 8.0.x 小版本仍采用该写法。
--   3) 初始化数据的 id 显式写为 1 / 2 / 3，与前端兜底常量对齐，保证埋点统计不错位。
-- ----------------------------

-- ----------------------------
-- 1、工具信息表
-- ----------------------------
create table if not exists tool_info (
  id          bigint       not null auto_increment comment '工具ID',
  tool_name   varchar(64)  not null                comment '工具名称',
  tool_desc   varchar(255) default null            comment '工具描述',
  tool_route  varchar(128) not null                comment '前端访问路由',
  icon        varchar(64)  default null            comment '图标名称（前端 Element Plus 图标组件名）',
  sort        int          default 0               comment '显示顺序（升序）',
  status      char(1)      default '0'             comment '状态（0正常 1停用）',
  create_time datetime     default null            comment '创建时间',
  update_time datetime     default null            comment '更新时间',
  primary key (id)
) engine=innodb auto_increment=4 default charset=utf8mb4 collate=utf8mb4_general_ci comment = '工具信息表';

-- ----------------------------
-- 2、工具访问埋点表
-- ----------------------------
create table if not exists tool_visit (
  id          bigint       not null auto_increment comment '访问记录ID',
  tool_id     bigint       not null                comment '工具ID（对应 tool_info.id，不做外键约束，允许前端兜底 id）',
  ip          varchar(64)  default null            comment '访问者IP',
  user_agent  varchar(512) default null            comment '浏览器 User-Agent（最长512字符）',
  create_time datetime     default null            comment '访问时间',
  primary key (id),
  key idx_tool_visit_tool_id (tool_id)
) engine=innodb auto_increment=1 default charset=utf8mb4 collate=utf8mb4_general_ci comment = '工具访问埋点表';

-- ----------------------------
-- 3、工具信息初始化数据（id 固定 1/2/3，与前端兜底常量一致）
-- ----------------------------
insert into tool_info (id, tool_name, tool_desc, tool_route, icon, sort, status, create_time, update_time) values
(1, 'JSON格式化工具', 'JSON格式化、压缩、语法校验', '/tool/json',       'Document', 1, '0', now(), now()),
(2, '文本字数统计',   '统计汉字、字符、行数，可清除空格', '/tool/text-count', 'EditPen',  2, '0', now(), now()),
(3, '随机密码生成',   '自定义长度，支持大小写、数字、符号', '/tool/pwd-gen',  'Key',      3, '0', now(), now())
on duplicate key update
  tool_name   = values(tool_name),
  tool_desc   = values(tool_desc),
  tool_route  = values(tool_route),
  icon        = values(icon),
  sort        = values(sort),
  status      = values(status),
  update_time = values(update_time);
