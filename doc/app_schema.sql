-- 青兔工具站 · 用户端账号体系（M5）
-- 库：qtools   字符集：utf8mb4
-- 与若依后台（sys_user / /login / 后台 JWT）完全隔离：独立表、独立密钥、独立过滤器、独立接口前缀 /api/app/**
--
-- ⚠️ 执行必须指定客户端字符集，否则中文注释会按 GBK 存进数据字典（变乱码）：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source app_schema.sql"

SET NAMES utf8mb4;

-- 用户端账号
CREATE TABLE IF NOT EXISTS app_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
  username      VARCHAR(64)  NOT NULL                           COMMENT '登录名（站内唯一，创建后不可改）',
  nickname      VARCHAR(64)  NOT NULL                           COMMENT '昵称（展示用，可改）',
  avatar        MEDIUMTEXT   NULL                               COMMENT '头像（data:image/...;base64 或 URL，上限 512KB）',
  password_hash VARCHAR(100) NULL                               COMMENT '密码哈希（BCrypt cost=10）；仅验证码登录的账号可为空',
  status        CHAR(1)      NOT NULL DEFAULT '0'               COMMENT '状态：0 正常 / 1 停用',
  invite_code   VARCHAR(32)  NULL                               COMMENT '注册时使用的邀请码（开放注册时为空）',
  register_ip   VARCHAR(64)  NULL                               COMMENT '注册来源 IP',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  last_login_at DATETIME     NULL                               COMMENT '最近登录时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户端-账号（独立于 sys_user）';

-- 多身份绑定（用户名/邮箱/手机号，任一身份可作登录凭证）
CREATE TABLE IF NOT EXISTS app_user_identity (
  id         BIGINT       NOT NULL AUTO_INCREMENT               COMMENT '主键',
  user_id    BIGINT       NOT NULL                              COMMENT '所属账号，关联 app_user.id',
  type       VARCHAR(16)  NOT NULL                              COMMENT '身份类型：username 用户名 / email 邮箱 / phone 手机号',
  identifier VARCHAR(128) NOT NULL                              COMMENT '身份值（用户名 / 邮箱地址 / 手机号）',
  verified   TINYINT(1)   NOT NULL DEFAULT 0                    COMMENT '是否已验证：0 未验证 / 1 已验证（用户名注册即 1）',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP    COMMENT '绑定时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity (type, identifier),
  KEY idx_identity_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户端-登录身份（一账号可多绑）';

-- 验证码（邮箱/短信共用；当前阶段表先行、通道未启用）
CREATE TABLE IF NOT EXISTS app_user_verify_code (
  id         BIGINT       NOT NULL AUTO_INCREMENT               COMMENT '主键',
  channel    VARCHAR(8)   NOT NULL                              COMMENT '发送通道：email 邮件 / sms 短信',
  target     VARCHAR(128) NOT NULL                              COMMENT '接收目标：邮箱地址或手机号',
  purpose    VARCHAR(16)  NOT NULL                              COMMENT '用途：register 注册 / login 登录 / bind 绑定 / reset 重置密码',
  code_hash  VARCHAR(100) NOT NULL                              COMMENT '验证码哈希（不存明文）',
  attempts   INT          NOT NULL DEFAULT 0                    COMMENT '已尝试验证次数',
  used       TINYINT(1)   NOT NULL DEFAULT 0                    COMMENT '是否已使用：0 未用 / 1 已用',
  expires_at DATETIME     NOT NULL                              COMMENT '过期时间',
  ip         VARCHAR(64)  NULL                                  COMMENT '请求来源 IP',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_code_target (channel, target, purpose, used)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户端-验证码（邮箱/短信）';

-- 登录会话（可撤销、可“退出所有设备”）
CREATE TABLE IF NOT EXISTS app_user_token (
  id         BIGINT       NOT NULL AUTO_INCREMENT               COMMENT '主键',
  user_id    BIGINT       NOT NULL                              COMMENT '所属账号，关联 app_user.id',
  token_id   CHAR(36)     NOT NULL                              COMMENT 'JWT 的 jti（会话标识）',
  user_agent VARCHAR(255) NULL                                  COMMENT '登录时客户端 User-Agent',
  ip         VARCHAR(64)  NULL                                  COMMENT '登录来源 IP',
  expires_at DATETIME     NOT NULL                              COMMENT '会话过期时间',
  revoked    TINYINT(1)   NOT NULL DEFAULT 0                    COMMENT '是否已撤销：0 有效 / 1 已撤销（登出或强制下线）',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_token_jti (token_id),
  KEY idx_app_token_user (user_id, revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户端-登录会话';

-- 邀请码（register.mode=invite 时使用）
CREATE TABLE IF NOT EXISTS app_invite_code (
  id         BIGINT      NOT NULL AUTO_INCREMENT                COMMENT '主键',
  code       VARCHAR(32) NOT NULL                               COMMENT '邀请码明文（大写字母数字）',
  max_uses   INT         NOT NULL DEFAULT 1                     COMMENT '最大可使用次数',
  used_count INT         NOT NULL DEFAULT 0                     COMMENT '已使用次数',
  expires_at DATETIME    NULL                                   COMMENT '过期时间（空=永久有效）',
  created_by VARCHAR(64) NULL                                   COMMENT '创建人（admin 或后台账号）',
  note       VARCHAR(200) NULL                                  COMMENT '备注（发给谁、用途）',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP      COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_invite_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户端-邀请码';
