# QTools / 青兔在线工具箱 · 路线图与落地方案

> 版本 v1 ｜ 适用仓库：**QTools**（后端）、**QTools-C**（前台用户站）、**QTools-M**（管理端）
> 目标域：`qtools.vip`（`pom.xml` 与 `application.yml` 已预留）

---

## 0. 一句话结论

**先把"地基"和"上线能力"补齐，再做在线业务；商城是主菜但排在中段**，因为它依赖三样现在还没有的东西：**C 端用户体系、部署上线链路、分区化的隐私边界**。短链先行（2–3 周）能用最低风险把后端硬功夫和上线链路全部跑通，之后做商城的阻力会小很多。

---

## 1. 现状盘点（均已实测）

| 能力 | 现状 | 证据 |
|---|---|---|
| 后端框架 | 若依 RuoYi-Vue-springboot3 3.9.2，已重命名 `qtools-*` / `com.qtools` | 7 个 Maven 模块 BUILD SUCCESS |
| 业务模块 | `qtools-tool`：`tool_info` / `tool_visit` + 2 个匿名接口 | `GET /api/toolInfo/list` 200（3 条）、`POST /api/toolVisit/add` 入库 |
| 前台站点 | Vue3 + Vite5 + Element Plus，4 个页面（首页 + 3 个纯本地工具） | `npm run build` 通过，15 个产物 |
| 管理端 | 若依 Vue3 前端，端口 80，仅监听本机 | `dev` 启动 200，标题「青兔管理后台」 |
| 认证 | 仅**后台管理员**（`sys_user`），C 端无用户体系 | `TokenService` + `SecurityConfig` |
| 密钥 | 已外置（`application-secret.yml` / 环境变量） | 仓库内仅 `.example` 模板 |
| 测试 | **0 个测试、pom 无 test 依赖** | `test_dirs=0` |
| 部署 | **无任何部署产物**（无 Dockerfile / compose / nginx / CI） | 全仓扫描 0 命中 |
| 体积 | 前台主 chunk **1.2 MB（gzip 385 KB）**，Element Plus 全量引入 | 构建输出 |
| 域名 | `qtools.vip` / `www.qtools.vip` 已写入配置白名单 | `application.yml:136` |

---

## 2. 目标与原则

**产品原则**
1. 「本地工具 = 数据不上传」是信任基石，必须**可验证地**保持（断网可用、无网络请求）。
2. 在线服务必须**明示数据去向**，与本地工具分区展示、视觉上区分。
3. 不做实物交易：**模拟支付**，页面明确标注"演示环境，不产生真实交易"。

**工程原则**
1. 单人可交付：每阶段结束都必须处于"能给别人用"的状态。
2. 先地基后业务：分区结构、用户体系、部署链路先补。
3. 不引入无法长期维护的技术（不追新框架、不搞微服务）。
4. 密钥永不入库（已建立的规矩继续保持）。

---

## 3. 产品地图

### 3.1 本地工具区（数据不上传，前端注册表驱动）

| 分类 | 工具 | 优先级 |
|---|---|---|
| 编码 | JSON 格式化（已有）、Base64 / URL 编解码、Unicode 转义、HTML 实体 | M0/M1 |
| 文本 | 字数统计（已有）、大小写与命名风格转换、去重/排序、diff 对比、Markdown 预览 | M1 |
| 生成 | 随机密码（已有）、UUID/ULID 批量、二维码生成、Lorem 中文假文 | M1 |
| 时间 | 时间戳↔日期、时区换算、cron 表达式解析 | M1 |
| 安全 | 哈希（MD5/SHA-1/SHA-256）、JWT 解码、TOTP 验证码 | M2 |
| 中文专项（差异化） | 繁简转换、拼音标注、汉字笔画/部首、身份证/银行卡/统一社会信用代码校验 | M2 |
| 数据 | JSON↔YAML/CSV、CSV 转表格、颜色格式转换 | M2 |

### 3.2 在线服务区（数据上传，后端模块驱动）

| 服务 | 用到的后端硬功夫 | 优先级 |
|---|---|---|
| **短链 + 点击统计** | 号段发号器、Base62、多级缓存、布隆过滤器、异步批量落库、限流、SSRF 防护 | **M1** |
| **商城（模拟支付）** | 库存防超卖、下单幂等、订单状态机、支付回调幂等、超时关单、金额精度 | **M3** |
| 问卷/表单 | 动态 schema、防重复提交、聚合统计、Excel 流式导出 | M5 |
| 预约排班 | 时段库存并发扣减、分布式锁、日历冲突、通知 | M5 |
| 图片暂存/短时分享（可选） | 对象存储、签名 URL、过期清理、配额 | 待定 |

### 3.3 C 端用户与运营（商城/问卷/预约的前置）

| 能力 | 说明 | 优先级 |
|---|---|---|
| C 端注册登录 | `app_user` 独立于 `sys_user`，JWT 区分身份来源 | **M2** |
| 我的（订单/短链/问卷） | 个人中心 | M3 |
| 本地收藏与最近使用 | 仅 localStorage，不破坏"无需注册" | M4 |
| 访问/点击统计看板 | 聚合表 + 管理端图表 | M4 |

### 3.4 管理端（QTools-M）菜单规划

工具管理（分类/排序/上下架）· 短链管理 · 商品与库存管理 · 订单与支付单管理 · 统计看板 · 用户管理（C 端）

---

## 4. 架构设计

### 4.1 仓库与站点

| 仓库 | 角色 | 部署形态 |
|---|---|---|
| `QTools` | 后端（若依多模块扩展） | jar + systemd/docker |
| `QTools-C` | 前台用户站（工具区 + 服务区 + 商城前台） | 静态站点（nginx） |
| `QTools-M` | 管理端 | 静态站点，**仅内网/IP 白名单** |

> 商城主场先放在 `QTools-C` 的 `/shop/*` 子路由（按需分包），体量大了再拆独立站点，避免过早增加运维面。

### 4.2 后端模块划分（在若依多模块上增补）

| 模块 | 职责 |
|---|---|
| `qtools-tool`（已有） | 工具元数据、分区、埋点 |
| `qtools-link`（新） | 短链生成/跳转/统计 |
| `qtools-user`（新） | C 端用户（`app_user`）、C 端认证 |
| `qtools-shop`（新） | 商品/SKU/购物车/订单/库存 |
| `qtools-pay`（新） | 支付抽象 + `MockPayChannel`（预留真实渠道实现） |
| `qtools-survey`（新，M5） | 问卷 |
| `qtools-booking`（新，M5） | 预约 |

依赖方向：`admin → 各业务模块 → common/framework`，业务模块之间不互相依赖（跨模块通过 service 接口或事件解耦）。

### 4.3 前端路由与分包

```
/                     首页：本地工具区 + 在线服务区（分区渲染）
/tool/{slug}          本地工具（前端注册表驱动，按需分包）
/s/{code}             短链跳转（后端 302，无需前端页面）
/link                 短链生成与管理（需登录）
/shop                  商城首页 / 商品列表
/shop/item/{id}        商品详情
/shop/cart             购物车
/shop/order/{no}       订单详情（含模拟收银台入口）
/login /register       C 端登录注册
/me                    个人中心
```
- 本地工具**不依赖任何接口**：后端不可用时仍完全可用（注册表 + 组件静态映射）
- 商城/服务区按路由分包 + Element Plus 按需引入，目标：首屏 gzip < 300 KB

### 4.4 双用户体系（关键设计）

| | 管理端 | C 端 |
|---|---|---|
| 用户表 | `sys_user`（若依自带） | **新建 `app_user`** |
| 登录入口 | `/login`（管理端页面） | `/api/app/auth/login` |
| Token | 若依 JWT（`token.secret`） | 同密钥，但 claim 增加 `userType=APP` |
| 鉴权 | `SecurityConfig` 现有规则 | 新增 `/api/app/**` 链路 + `@AppLogin` 注解 |
| 隔离要求 | 管理端不得放公网 | 商城/问卷接口一律走 C 端身份 |

> 这是本项目最值得深挖的安全设计点：两套身份、一套密钥、互不可越权。

### 4.5 统一接口约定（延续现状）

- 前缀：C 端 `/api/**`（匿名或 C 端登录），管理端沿用若依 `/system/**`、`/monitor/**`
- 返回体：`AjaxResult`（`code=200` 成功），前端 axios 已兼容包装体与裸数组
- 分页：PageHelper（管理端 `startPage()`），C 端列表用游标或简单分页
- 错误码：沿用若依 `code` 语义；业务错误统一 `code=500` + `msg`
- 幂等：写接口带 `Idempotency-Key` 请求头（前端 UUID），服务端唯一索引兜底

---

## 5. 数据模型（核心表）

| 表 | 关键字段 | 备注 |
|---|---|---|
| `tool_info`（扩展） | + `category`(local/online)、`data_policy`(local/server)、`requires_login` | 分区与隐私标注 |
| `short_link` | `id, code, target_url, domain_id, create_by, expire_time, status, click_count` | `code` 唯一索引 |
| `link_stats_daily` | `link_id, stat_date, clicks, uniq_visitors` | 唯一键 `(link_id, stat_date)` |
| `app_user` | `id, username, password(bcrypt), nickname, phone, status, create_time` | C 端用户 |
| `shop_item` | `id, title, sub_title, cover, price_cent, status, stock_mode` | **金额用分（bigint/int）** |
| `shop_sku` | `id, item_id, spec_json, price_cent, stock, version` | 库存 + 乐观锁 version |
| `shop_cart` | `id, user_id, sku_id, quantity, create_time` | 唯一键 `(user_id, sku_id)` |
| `shop_order` | `id, order_no, user_id, total_cent, status, expire_time, idem_key, pay_time` | `order_no`/`idem_key` 唯一 |
| `shop_order_item` | `id, order_no, sku_id, title, price_cent, quantity` | 下单快照（防商品改价影响历史单） |
| `shop_pay` | `id, pay_no, order_no, channel, amount_cent, status, notify_time, raw_notify` | 支付单 |
| `shop_stock_log` | `id, sku_id, change, type, ref_no, create_time` | 库存流水（可追溯） |

约定：所有表带 `create_time`/`update_time`；删除用 `status` 或 `del_flag`；金额一律整数分。

---

## 6. 模拟支付设计（商城核心，重点）

**设计原则：支付渠道是模拟的，但"下单 → 支付 → 回调 → 订单状态"的链路是真的。** 这样将来接真实支付只需**新增一个渠道实现**，不改业务代码。

### 6.1 状态机

```
订单：CREATED(待支付) ──支付成功──> PAID(已支付) ──(虚拟商品)──> COMPLETED(已完成)
                        └─超时/取消─> CLOSED(已关闭)
支付单：INIT ──> SUCCESS / FAILED
```

### 6.2 接口

| 接口 | 说明 |
|---|---|
| `POST /api/shop/order/create` | 下单：校验库存 → 预占 → 生成订单（带 `Idempotency-Key`） |
| `POST /api/pay/mock/create` | 创建支付单，返回模拟收银台地址 `/pay/cashier?payNo=xxx` |
| `GET /pay/cashier` | 模拟收银台页面（前端）：显示金额与【支付成功】/【支付失败】按钮 |
| `POST /api/pay/notify/mock` | **模拟渠道回调**（服务端内部调用亦可用），带 HMAC 签名 |
| `GET /api/shop/order/{orderNo}` | 订单详情（轮询支付结果） |

### 6.3 关键机制

1. **幂等**：`order_no`/`idem_key`/`pay_no` 建唯一索引；重复回调直接返回成功。
2. **验签**：mock 渠道签名 = `HMAC-SHA256(secret, 原始报文)`；验签失败一律拒绝并记录。
3. **回调处理**：查支付单 → 状态已是 SUCCESS 直接返回 → 否则事务内更新支付单 + 订单 + 库存流水。
4. **超时关单**：`@Scheduled` 每分钟扫 `CREATED && expire_time < now()`，释放预占库存。
5. **金额**：全程整数分，禁止 `double`。
6. **换真实支付**（未来）：实现 `PayChannel` 接口（`WechatPayChannel`/`AlipayChannel`），配置 `qtools.pay.channel=wechat` 切换；业务层无改动。

> **合规声明**：商城为演示环境，模拟支付不产生真实资金流；上线页面与 README 必须显式标注，避免误导。

---

## 7. 路线图

> 工期按业余每天 2–3 小时估算；每个里程碑都必须"可演示、可上线"。

### M0 · 地基（1–2 周）
- **目标**：分区结构落地 + 上线链路从 0 到 1 + 测试基建
- **交付物**
  1. `tool_info` 加 `category`/`data_policy`/`requires_login`（升级 SQL）
  2. 前台首页两分区渲染 + 卡片「本地运算 / 需上传」徽标；本地工具断网可用
  3. 管理端「工具管理」页面（用**若依代码生成器** `/tool/gen` 生成后微调）
  4. 部署脚手架：`docker-compose`（nginx + app + mysql + redis）、`.env` 注入密钥、`/actuator` 探活
  5. 后端接入 `spring-boot-starter-test`，CI 跑 `mvn test` + 前端 `npm run build`
- **验收**：本地起 compose 一键跑起来；首页两分区正常；CI 绿灯；本地工具在停掉后端后仍可用

### M1 · 第一个在线服务：短链 + 点击统计（2–3 周）
- **目标**：跑通"在线服务"的完整范式（缓存 / 异步统计 / 限流 / 安全）
- **交付物**：`qtools-link` 模块（生成/跳转/统计）、前台 `/link` 页面、管理端短链管理、压测报告
- **验收**：生成→跳转→统计闭环；缓存命中率数据；布隆过滤器挡住不存在短码；限流生效；SSRF/开放重定向用例通过

### M2 · C 端用户体系（1–2 周）
- **目标**：为商城/问卷/预约铺前置
- **交付物**：`app_user` 表 + `qtools-user` 模块 + `/api/app/auth/{register,login,me}` + `@AppLogin` 注解 + 前台登录/注册页
- **验收**：C 端可注册登录；C 端 token 无法访问管理端接口（越权测试）；管理端用户与 C 端用户互不影响

### M3 · 商城 MVP + 模拟支付（4–6 周，主菜）
- **范围（严格冻结，超出即砍）**：商品列表/详情、购物车、下单、模拟支付、我的订单、管理端商品与订单管理、库存防超卖与超时关单
- **不做**：真实支付、物流、发票、多租户、优惠券体系（后续）
- **交付物**：`qtools-shop` + `qtools-pay` 模块、前台 `/shop/*`、模拟收银台页面、管理端菜单
- **验收**：
  1. 100 并发抢 10 件，**不超卖**（压测脚本 + 数据证明）
  2. 同一 `Idempotency-Key` 重复下单只产生一张订单
  3. 支付回调重复投递不产生重复入账（幂等）
  4. 超时订单自动关闭并释放库存
  5. 全链路演示：加购→下单→模拟支付→订单变 PAID→库存流水可追溯

### M4 · 运营与数据（1–2 周）
- **交付物**：访问/点击/销量看板（聚合表 + 管理端图表）、本地收藏与最近使用（localStorage）、工具搜索
- **验收**：看板数据与数据库聚合结果一致；统计不依赖 `count(*)` 全表扫描

### M5 · 更多在线服务（各 2–3 周，按兴趣选做）
- 问卷/表单：动态 schema + 防重复提交 + 聚合统计 + Excel 导出
- 预约排班：时段库存并发扣减 + 分布式锁 + 日历冲突 + 通知
- **验收**：各自独立闭环可用

### M6 · 进阶与硬化（持续）
- 性能：缓存穿透/雪崩防护、慢 SQL 优化（EXPLAIN + 索引）、连接池与线程池调优
- 可观测：结构化日志、慢接口告警、简单 APM（可选）
- 安全：限流全覆盖、越权测试用例、依赖漏洞扫描
- 体积：前台按需引入，首屏 gzip < 300 KB
- 拆域（可选）：商城拆 `shop.qtools.vip`，短链用 `s.qtools.vip`

---

## 8. 关键难点与验证方式（面试可讲的点）

| 难点 | 方案 | 如何验证 |
|---|---|---|
| 库存防超卖 | `update sku set stock=stock-#{n} where id=? and stock>=#{n}` + 预占/释放 | 并发压测：不超卖、库存流水对账平衡 |
| 下单幂等 | `Idempotency-Key` + 唯一索引 | 重复请求只落一张单 |
| 支付回调幂等 | `pay_no` 唯一 + 状态判断 + 事务 | 回调重复投递 5 次，结果一致 |
| 超时关单 | 定时扫描 + 释放库存（进阶：延迟队列） | 关单后库存恢复、状态正确 |
| 短链缓存穿透 | 布隆过滤器 + 空值缓存 | 随机不存在短码，DB 查询数为 0 |
| 点击统计热点写 | Redis `INCR` + 定时批量落库 | 改造前后 QPS/延迟对比 |
| 开放重定向/SSRF | 协议白名单 + 内网地址黑名单 | 恶意目标 URL 用例被拒 |
| 金额精度 | 整数分存储与计算 | 边界用例（0.01/999999.99） |
| 前后端分离体积 | 路由分包 + 按需引入 | 首屏 gzip 对比数据 |
| 双身份越权 | C 端 token 带 `userType`，管理接口强校验 | 越权测试用例全部 403 |

---

## 9. 部署落地方案（可上线）

**拓扑（单机起步）**
```
Internet → nginx:443
             ├── /             → QTools-C 静态文件
             ├── /admin        → QTools-M 静态文件（建议仅内网/IP 白名单）
             ├── /api, /s/xxx  → Spring Boot jar (8080, 仅内网)
             └── /actuator/health → 探活
mysql(3306) / redis(6379) 仅内网，不对外暴露
```

**docker-compose 服务清单**：`nginx`、`app`、`mysql`、`redis`（+ 可选 `certbot`）
**域名规划**：`qtools.vip`/`www`（前台）、`admin.qtools.vip`（管理端，白名单）、`s.qtools.vip`（短链，后期）

**运维基线**
| 项 | 做法 |
|---|---|
| HTTPS | Let's Encrypt + 自动续期 |
| 密钥 | 环境变量注入（沿用已外置的 `application-secret.yml` 方案，容器里用 env） |
| 备份 | `mysqldump` 每日 03:00 + 保留 7 天；Redis 仅作缓存可重建 |
| 日志 | 容器日志轮转 + 应用 `logs/` 保留 60 天（已配） |
| 探活 | `/actuator/health` + 简单外部探测（可加 Uptime Kuma） |
| 安全 | 改默认口令、关闭公网 3306/6379、**关掉 Druid 控制台**、管理端限源、C 端接口限流 |
| 成本 | 轻量 2C2G（活动价 ¥100–300/年）+ 域名（.vip ≈ ¥30–60/年） |
| 合规 | 国内服务器需 ICP 备案（1–3 周，需域名实名）；**模拟支付需在页面标注非真实交易** |

---

## 10. 工程基建

- **分支**：`main` 保护 + 特性分支 `feat/xxx`；提交信息 `feat/fix/docs/chore: 描述`
- **CI**（GitHub Actions）：后端 `mvn -B test`；两个前端 `npm ci && npm run build`
- **测试策略**：工具纯函数单测（Vitest）→ Service 单测（JUnit5 + Mockito）→ 关键链路集成测试（Testcontainers 可选）→ 手工冒烟清单
- **代码规范**：后端保持若依风格（Controller 瘦、Service 厚、Mapper 纯 SQL）；前端 `<script setup>` + 中文注释；接口改动必须同步 README 契约
- **审查清单**：幂等？权限？金额精度？SQL 索引？异常处理？密钥是否入库？

---

## 11. 风险与止损

| 风险 | 应对 |
|---|---|
| 商城范围膨胀 | M3 范围已冻结，超出的需求进 M5/M6；每周检查点 |
| 单人节奏断档 | 每阶段结束都必须是"可上线状态"，随时可停 |
| 真实支付诱惑 | 无企业资质前**不做**；模拟渠道已预留实现位 |
| 功能数量陷阱 | 用 `tool_visit` / 短链点击 / 商城订单数据决定下一步做什么 |
| 上线后无人用 | M4 看板先看数据，再决定加工具还是加服务 |

**明确不做**：真实资金流、实物交易/物流、多租户、微服务拆分、账号云同步（破坏"无需注册"卖点）。

---

## 12. 可以立即开始的任务（任务书级别）

| 编号 | 任务 | 边界 | 验收 |
|---|---|---|---|
| T1 | `tool_info` 分区字段 + 升级 SQL | 只改 `sql/qtools/` + `qtools-tool` | 3 条数据变为 `online`，新增 local 示例；接口返回含 category |
| T2 | 前台分区渲染 + 本地工具注册表 | 只改 `QTools-C/src/{views,config,router}` | 两分区展示；停后端后本地区仍可用；卡片有徽标 |
| T3 | 管理端工具管理页面 | 用 `/tool/gen` 生成后微调，只动 `QTools-M` | 增删改查 + 分类 + 上下架 + 导出，操作日志入库 |
| T4 | 部署脚手架 | 新增 `deploy/`（compose + nginx + 备份脚本） | 一条命令起全套；`/actuator/health` 200 |
| T5 | 测试与 CI 基建 | 加 test 依赖 + 1 个示例测试 + workflow | `mvn test` 通过；Actions 绿灯 |
| T6 | 短链模块骨架 | 新建 `qtools-link`：表 + 生成/跳转接口 | 生成→302 跳转；压测基线数据 |

> 每个任务做完都要：编译/构建通过 + 关键行为实测 + 文档同步（README/本路线图勾选进度）。
