# QTools 路线图与落地方案（v2）

> 版本 v2 ｜ 2026-10-21 修订 ｜ 适用仓库：**QTools**（后端）、**QTools-C**（前台/媒体站静态页）、**QTools-M**（管理端）＋ `E:\111` 便携包
> 目标域：`qtools.vip`（`pom.xml` 与 `application.yml` 已预留）
> 一句话：v1 计划里的"短链/商城"**一行代码都没开始**，实际优先完成了**媒体站 + 便携化落地**；本文不追认 v1 的时间表，按真实进度重排优先级。

---

## 0. 这次修订说明（v1 → v2 由什么触发）

v1（2026-10-15）写的是一条**教科书式**路线：M0 地基 → M1 短链 → M2 C 端用户 → M3 商城 → M4 看板 → M5 问卷/预约 → M6 硬化。

六天后（2026-10-21）的真实情况是：**M0 只做了部署这一半，M1 完全没动，M2 换了一种做法做完了，M3 一行没有；同时计划外的"媒体站"占了绝大部分工作量，还顺带把整套站点塞进了一块移动硬盘。**

具体偏离（按 `git log` 与文件实测，见 §1 证据列）：

| v1 里程碑 | v1 预期 | 实际 |
|---|---|---|
| M0 地基 | 工具分区化 + 部署脚手架 + 测试/CI | **只完成部署**（且形态从 docker-compose 变成了 Windows 便携包）；分区化未做；测试/CI 仍是 0 |
| M1 短链 | 2–3 周，第一个在线服务 | **未开始**（无 `qtools-link` 模块、无 `short_link` 表） |
| M2 C 端用户 | `qtools-user` + `app_user` + `@AppLogin` | **功能做完了，设计换了**：模块叫 `qtools-app`，表前缀 `app_*`，独立密钥 JWT（不用 `userType` claim），另加了邀请码、登录限流、认领 |
| M3 商城 | 主菜，4–6 周 | **未开始**（无 `qtools-shop` / `qtools-pay`） |
| M4 看板 | 聚合表 + 图表 | **未开始**（媒体访问/播放统计也没有） |
| M5 问卷/预约 | 各 2–3 周 | **未开始** |
| M6 硬化 | 持续 | **反向退化**：前台主 chunk 从 1.2 MB 涨到 2.7 MB（详见 §1.3） |
| —— | 计划外 | **媒体站（`qtools-media` + `public/media/` 静态站 + 管理端媒体页）+ 单端口静态托管 + 便携化部署包 + 媒体数据迁移工具** |

**触发这次重写的三件事**：① v1 的假设"先地基后业务"被现实打破——地基（测试/CI/分区化）没补，业务（媒体站）先跑了 6 天；② v1 的 M2 描述与现有代码不一致，继续留着会误导后续实现；③ 已落地的东西（媒体站、便携包）在 v1 里**完全不存在**，README 与代码已经跑在路线图前面，文档必须先追上代码。

**这次修订的诚实声明**：媒体站是一次**计划外扩张**，不是 v1 里任何一项的组成部分。它值不值、要不要继续，见 §2；本文不把它包装成"地基的一部分"。

---

## 1. 现状盘点

盘点基线：`E:\111\project\QTools` 下三个 git 仓库（后端 `QTools`、前台 `QTools-C`、管理端 `QTools-M`；`E:\111` 与 `E:\111\project\QTools` 本身**不是** git 仓库），加 `E:\111` 便携包。

### 1.1 已落地（可用、可演示）

| 能力 | 量化事实 | 证据 |
|---|---|---|
| 后端骨架 | 若依 RuoYi-Vue-springboot3 **3.9.2**，Java 17，重命名 `qtools-*` / `com.qtools` | `QTools/pom.xml` `<modules>` 与 `<java.version>17</java.version>` |
| Maven 模块 | **9 个**：`qtools-admin` `qtools-framework` `qtools-system` `qtools-quartz` `qtools-generator` `qtools-common` `qtools-tool` `qtools-media` `qtools-app` | 同上；`E:\111\README.md` 也列出同样 9 个 |
| 工具站最小集 | `qtools-tool`：**3 个 Controller** —— 前台 2 个匿名（`ToolInfoController`/`ToolVisitController`，`/api/toolInfo/list`、`/api/toolVisit/add`）+ 后台 1 个需鉴权（`ToolAdminController`，`/api/qtool/info/**`，权限串 `qtool:admin:*`）；表 `tool_info`(3 行，`auto_increment=4`) / `tool_visit` | `sql/qtools/tool_init.sql`；`qtools-tool/src/main/java/com/qtools/tool/controller/ToolAdminController.java:31-33` |
| **媒体库后端** `qtools-media` | `src` 下 **51 个文件**；**5 个业务 Controller**（`MediaAdmin`/`MediaFile`/`MediaIndex`/`MediaStream`/`MediaUser`）+ 1 个全局 `MediaExceptionHandler`；**7 个 Service 实现**；**9 个 Mapper 接口 + 9 个 mapper XML**；11 个 domain | `qtools-media/src/**`；`grep -c @RestController` = 6（含异常处理器，实为 5 个控制器）；`*ServiceImpl.java` 行数：`MediaAdminServiceImpl` **1168**、`MediaUserServiceImpl` 571、`MediaFileServiceImpl` 523、`MediaScanServiceImpl` 420、`MediaStreamServiceImpl` 388、`MediaIndexServiceImpl` 359、`MediaOwnerServiceImpl` 236 |
| 媒体能力：流式播放 | 字节级 Range（`ResourceRegion` + `HttpRange`），200/206/416/If-Range/HEAD 全覆盖；暴露 `Accept-Ranges, Content-Range, Content-Length, ETag` | `MediaFileController.java:24,40,61` |
| 媒体能力：实时转码 | 并发上限默认 **2**（`maxTranscode: 2`），名额在启动 ffmpeg **前**占用，超出立刻 **429** | `MediaStreamServiceImpl.java:38`；`application.yml:34`；`MediaException.java:54` |
| 媒体能力：软删可恢复 | 删除一律 MOVED 到 `_媒体站/trash`（`trashPath`），**代码里没有任何真删路径**；另有 `/restore/{kind}/{id}` 恢复 | `MediaProperties.java:27-28`；`MediaAdminController.java:38,156,168,171` |
| 媒体能力：索引/缩略图增量扫描 | 后端调 Node 脚本：全量/增量索引复用 `doc/media-import/import.js`，缩略图走 `_媒体站/tools/build_thumbs.js`（日志形态 `total=10359 concurrency=6`）；扫描超时 10 分钟 | `MediaScanServiceImpl.java:76`；`application.yml:38-47` |
| 媒体能力：收藏 / 最近浏览 | `media_favorite` / `media_recent` 表 + Mapper + Service | `doc/media_schema.sql`（共 **10 张** `media_*` 表） |
| **用户端账号** `qtools-app` | `src` 下 **29 个文件**（23 个 java + 6 个 mapper XML）；5 张表 `app_user` / `app_user_identity` / `app_user_verify_code` / `app_user_token` / `app_invite_code` | `doc/app_schema.sql`；`qtools-app/src/**` |
| 账号设计：与后台完全隔离 | 独立 JWT，`issuer: qtools-app`；密钥写 `secret.yml`，**必须 ≠ `token.secret`，缺失或相同则启动失败**；注释原文"与若依后台（sys_user + token.secret + /login）完全隔离：两套身份互不承认" | `application.yml:54-59` |
| 账号设计：登录限流 | 同 IP + 标识，**10 分钟 5 次** 后 429（`loginMaxAttempts: 5` / `loginWindowMinutes: 10`） | `application.yml:79-80` |
| 账号能力 | 邀请码注册、认领（`AppClaimServiceImpl`）、验证码通道预留（`app_user_verify_code` + `AppVerifyCodeMapper`） | 同上 |
| 前台 QTools-C：媒体站 | `public/media/` **10 个手写静态文件**：`index.html`(5.7K) `app.js`(53.2K) `app.css`(19.8K) `photos.html`(4.4K) `photo.js`(66.7K) `photo.css`(16.6K) `profile.js`(24.2K) `profile.css`(10.1K) `me.html`(1.3K) `me.js`(4.6K) | `QTools-C/public/media/` 实测 |
| 前台 QTools-C：账号页 | `/login` `/register` `/account`（`requiresAuth` + 路由守卫回跳 `/login?redirect=`）；`composables/useAppAuth.js` 单例 + `api/appAuth.js` | `QTools-C/src/router/index.js:40-56,79-80` |
| 前台 QTools-C：工具页 | 3 个现有工具路由：`/tool/json`、`/tool/text-count`、`/tool/pwd-gen`（**硬编码在 router，无注册表**） | `QTools-C/src/router/index.js:21-36` |
| 管理端 QTools-M：媒体 | `views/media/index.vue` **42.3 KB** + `api/media.js` 2.8 KB + 菜单 SQL：`menu_id 2001`（菜单）+ `2002~2006`（按钮：查询/编辑/删除/重建索引…），固定 id、可重复执行 | `QTools-M/src/views/media/index.vue`；`QTools/doc/media-menu.sql`（6 条 insert） |
| 管理端 QTools-M：工具维护 | `views/qtool/index.vue`（**316 行**：列表/搜索/新增/修改/删除/导出/详情入口）+ `view.vue`（**90 行**：详情抽屉）+ `api/qtool/info.js`（**44 行**：6 个请求方法），对接后端 `/api/qtool/info/**` | `QTools-M/src/views/qtool/`、`QTools-M/src/api/qtool/info.js`；`ToolAdminController.java:41-104` |
| **单端口静态托管（M4-A）** | `WebSpaConfig`（151 行）把 `userDist` 挂到 `/media/**` `/assets/**` `/static/**` `/**`，把 `adminDist` 挂到 `/admin-ui/**`；`SpaEntryController`（67 行）接管 `GET /`（forward `/index.html`）、`/media` `/media/`（redirect `index.html`）、`/login` `/register`（forward）、`/admin-ui` `/admin-ui/`；`spring.web.resources.add-mappings=false` | `qtools-admin/.../web/config/WebSpaConfig.java`；`.../web/controller/common/SpaEntryController.java`；`application.yml` |
| 鉴权规则调整 | `/api/media/admin/**` `/system/**` `/monitor/**` `/common/**` `/quartz/**` `/tool/gen/**` `/test/**` → `authenticated()`；`GET /**` → `permitAll()`；非 GET `/api/**` → `authenticated()`；`anyRequest` → `authenticated()`；`SysIndexController` 让位到 `/welcome` | `qtools-framework/.../config/SecurityConfig.java:103-115`；`SysIndexController.java` |
| 实时 gzip | `server.compression` 开；前端不再预压缩（`vite-plugin-compression 0.5.1` 与 Vite 6 路径不兼容） | `application.yml:94-96` |
| **便携化部署包** | `E:\111` 自包含：`tools\` 含 `jdk` `maven` `mysql` `redis` `nginx` `node` `easytier`；`data\{mysql,media,logs}`；启动/停止/首次初始化/放行 80/信任根证书/迁移 EasyTier/清理残留 7 个 `.bat`；全部路径由 `-Dqtools.home=E:/111` 推导 | `E:\111\README.md`、`便携说明.md`、`*.bat`；`application.yml:2-47` |
| 端口形态 | MySQL **3307**（非默认 3306）、Redis **6379**、nginx **80 + 443**（自签 CA，`ssl/server.crt`）、后端 8080 | `tools/mysql/my.ini`、`tools/redis/*.conf`、`tools/nginx/conf/nginx.conf` |
| 备份 | `backup/qtools-20261021-112442.sql`（1087.4 KB）+ 若依原始 zip 存档 | `E:\111\backup\` |
| **媒体数据迁移工具** | `doc/media-import/`：零依赖 Node（仅 `node:` 内置模块），`index.json` → MySQL，幂等 upsert + 只读快照；密码只经子进程 `MYSQL_PWD` 传递，**不落盘不打印** | `doc/media-import/{import.js,import-user.js,README.md,report.md,run1-truncate.log,run2-upsert.log}` |
| 迁移数据规模 | **176 视频 / 156 相册 / 9628 图 / 10 人物 / 684 标签 / 17 演员**；视频总字节 111,647,487,347 与源 `index.json` 完全一致；相册总字节 29,757,383,201 与磁盘实际字节一致 | `doc/media-import/run2-upsert.log`（逐项 `[ok]`） |
| 迁移校验强度 | 条数 / 字节 / 关系 / 抽样 / 全量磁盘存在性等 **20 项断言全 `[ok]`**：`photos=9628 == sum(album.photo_count)`、9628 条 `rel_path` 全存在 0 缺失、176 条视频 size 逐条等于磁盘大小、每本相册 bytes 等于该册图片磁盘字节 | 同上 |

> 口径说明（避免与口头记录冲突）：媒体模块业务 Controller 实测为 **5 个**（另有 1 个全局异常处理器）；Service 实现 **7 个**、Mapper **9 个**。`report.md`（`--truncate` 那次）记 9627 图 / 29,756,299,110 字节，`run2-upsert.log`（幂等 upsert 那次）记 9628 图 / 29,757,383,201 字节——**以最新一次 upsert 的 9628 为准**，差异来自源里删掉的孤图被清理。

### 1.2 半成品（做了壳/做了一部分）

| 项 | 已做的部分 | 缺的部分 | 证据 |
|---|---|---|---|
| 部署 | 便携包**已能跑**：`启动站点.bat` → nginx 80/443 → 三份前端 + 8080 后端 | 磁盘级单点：没有异地异盘备份演练；`E:\111` 整体**不在 git**（无版本、无历史、无 diff）；换机只验过 x64 Windows | `E:\111\README.md`；三仓库外无 `.git` |
| 日志 | 有 logback、有 `data\logs` 目录 | **应用日志没落 `data\logs`**：`log.path` 仍是相对值 `logs`，实际写到 `QTools\logs\`；仓根还躺着 `qtools-admin.out.log`(34.3 KB) / `err.log` | `logback.xml:4` `<property name="log.path" value="logs" />` |
| 媒体回收站 | 软删 → trash → 恢复，链路完整 | `视频\_媒体站\trash` 缺**硬校验**：没有"trash 里的文件与 `media_*` 表 hidden 标记是否一一对应"的巡检工具，长期会漂；也没有清理/容量策略 | `MediaProperties.java:28`；trash 下已有 `album/ photo/ video/` 三个目录 |
| 构建清理 | 构建能跑（esbuild EPERM 已规避） | `.build-tmp3` 残留：`hsperfdata_sdn`、`node-compile-cache`、`tomcat-docbase.8080.*`、`tomcat.8080.*`、两个 `esbuild-*`；仓根另有 `dump.rdb` | `E:\111\project\QTools\.build-tmp3` 实测；`QTools/dump.rdb` |
| 统计 | `tool_visit` 埋点表在写；媒体有收藏/最近浏览 | **没有看板**：媒体访问/播放/转码失败率都无聚合表，管理端无图表 | `doc/media_schema.sql` 无统计表 |
| C 端账号与媒体联动 | 两套东西都在 | **没有联动**：媒体收藏/最近浏览走的是自己的 owner 体系，C 端账号尚未接上"我的收藏"这一层语义（是否复用 `app_user` 是待决问题） | `qtools-media` 有 `MediaOwnerServiceImpl`；`qtools-app` 有 `AppOwnerMapper`——两处 owner 语义重叠 |
| 验证码 | 表、Mapper、字段齐全 | 通道**仅预留**，未接短信/邮件 | `app_user_verify_code` + `AppVerifyCodeMapper` |
| 迁移工具 | 能跑、有报告、幂等 upsert | 只在 Windows + 盘上 MySQL 3307 实测；无自动化回归（每次靠看日志 `[ok]`） | `report.md` / `run2-upsert.log` |

### 1.3 未开始（v1 计划里彻底没动的）

| 项 | 事实 | 证据 |
|---|---|---|
| 工具分区化 | `tool_info` 的 DDL 只有原有 9 列（id / 工具名称 / 工具描述 / 前端访问路由 / icon / sort / status / create_time / update_time）；**没有** `category` / `data_policy` / `requires_login`（后台增删改查已落地，见 §1.4；分区字段与分区 UI 都还没做） | `sql/qtools/tool_init.sql:16-27` 建表语句 |
| 前端工具注册表 | 工具仍是 router 里手写的 3 条静态路由；首页仍是「拉接口 + 兜底数组」：`FALLBACK_TOOLS`（4 条，接口不可用时回退）按 `route` 与接口结果合并。无 slug 注册表、无按需分包 | `QTools-C/src/router/index.js:21-36`；`QTools-C/src/views/Home.vue:20,72-85,106` |
| 短链服务 | 无 `qtools-link` 模块、无 `short_link` / `link_stats_daily` 表、无 `/s/{code}` | `pom.xml` 模块列表；`sql/` 只有 `ry_*.sql` `quartz.sql` `qtools/tool_init.sql` |
| 商城 + 模拟支付 | 无 `qtools-shop` / `qtools-pay`；无商品/订单/支付单任何表 | 同上 |
| 问卷 / 预约 | 无对应模块与页面 | 同上 |
| 运营看板 | 无聚合表、无图表、无慢查询面板 | 同上 |
| **测试体系** | **后端子模块 0 个 `src/test`，全仓 `pom.xml` 无 `spring-boot-starter-test` / `junit` / `mockito` 任何一条依赖**；两个前端 `package.json` 无 `vitest` / `jest`，`scripts` 只有 `dev`/`build`/`preview` | `Get-ChildItem -Recurse -Filter pom.xml \| Select-String 'spring-boot-starter-test\|junit\|mockito'` → 0 命中；`src/test` 目录数 = 0；`QTools-C/package.json`、`QTools-M/package.json` |
| **CI** | `.github/` 里**只有 `FUNDING.yml`**，没有 workflow | `QTools/.github/` 实测 |
| 前端体积优化 | 主 chunk **2744.3 KB（gzip 607.1 KB）**，CSS 358.3 KB（gzip 48.7 KB）；Element Plus **全量引入**（`app.use(ElementPlus)` + 遍历全部图标注册） | `QTools-C/dist/assets/index-MTbHYY0o.js`、`index-DVQYZ6wp.css` 实测（node `zlib.gzipSync` level 9）；`QTools-C/src/main.js` |

> 体积口径修正：v1 记的是"1.2 MB / gzip 385 KB"，那是 10-15 的构建产物。当前 `dist` 实测 **2744.3 KB / gzip 607.1 KB**，涨了约 1.3 倍——媒体站静态页不进这个 chunk（在 `public/media/` 里单独拷贝），所以这是**工具站自己的 SPA 变胖**，不是媒体站带来的。这一项从"待优化"升级为"已退化"。

---

### 1.4 进展记录

#### 2026-10-21 · 工具维护后台 CRUD 落地（N1 交付物 4，已落地；N1 交付物 1 未落地）

| 已落地 | 证据 |
|---|---|
| 后端新增 `ToolAdminController`（类级 `@RequestMapping("/api/qtool/info")`）：`GET /list`(`qtool:admin:list`)、`POST /export`(`:export`)、`GET /{id}`(`:query`)、`POST ""`(`:add`)、`PUT ""`(`:edit`)、`DELETE /{ids}`(`:remove`)。**每个方法都带 `@PreAuthorize`，与 `/api/toolInfo/list`、`/api/toolVisit/add` 的 `@Anonymous` 性质相反**，走若依后台鉴权 | `qtools-tool/src/main/java/com/qtools/tool/controller/ToolAdminController.java:31-104` |
| 配套 `ToolAdminMapper` + `ToolAdminMapper.xml`（6 个语句）、`IToolAdminService` / `ToolAdminServiceImpl` | `qtools-tool/src/main/resources/mapper/tool/ToolAdminMapper.xml:23-85`；`.../service/impl/ToolAdminServiceImpl.java` |
| 管理端页面：`views/qtool/index.vue`(316 行) / `view.vue`(90 行) + `api/qtool/info.js`(44 行) | `QTools-M/src/views/qtool/`、`QTools-M/src/api/qtool/info.js` |
| `ToolInfo` 的 JSON 字段名统一为**驼峰**（`id`/`toolName`/`toolDesc`/`toolRoute`/`icon`/`sort`/`status`），前台 `Home.vue` 的 `FALLBACK_TOOLS` 与合并映射同步改用驼峰 | `ToolInfo.java:19-44`；`QTools-C/src/views/Home.vue:20,72-76` |
| 私密配置文件名 `application-secret.yml` → `secret.yml`，模板 → `secret.yml.example`；`application.yml` 的 `spring.config.import` 改为 `optional:classpath:secret.yml`；`.gitignore` 同时排除新旧两个名字；`secret.yml` 的键为 `spring.datasource.druid.master.password` / `spring.data.redis.password`（当前为空）/ `token.secret` / `qtools.app.jwt.secret` | `application.yml:132`；`.gitignore:52,63`；`qtools-admin/src/main/resources/secret.yml.example` |

| 仍未落地 | 事实 | 证据 |
|---|---|---|
| `tool_info` 的分类字段 `category` / `data_policy` / `requires_login`（N1 交付物 1） | DDL 仍是原有 9 列，`ToolAdminMapper.xml` 的语句也只涉及这 9 列 | `sql/qtools/tool_init.sql:16-27` |
| 前端「工具注册表」与首页两分区（N1 交付物 2/3） | 首页仍是「拉接口 + `FALLBACK_TOOLS` 兜底数组」，路由仍是手写 3 条 | `QTools-C/src/views/Home.vue:20,106`；`QTools-C/src/router/index.js:21-36` |
| 管理端菜单/权限数据 | 仓库内没有 `qtool` 的菜单 SQL（`sql/` 与 `doc/` 只有 `ry_*` / `quartz` / `tool_init` / `app_*` / `media_*`），页面不会自动出现在侧边栏，需在「菜单管理」里手工配置 | `sql/`、`doc/*.sql` 实测 |
| 管理端页面与后端的三处不一致（待修） | ① 导出按钮请求 `tool/info/export`，后端是 `/api/qtool/info/export`（其余 5 个接口用的是 `/api/qtool/info/**`）；② 页面 `v-hasPermi` 用 `tool:info:*`，后端校验 `qtool:admin:*`；③ 新增/修改对话框只有名称/描述/路由/图标/顺序 5 个输入，`status` 无输入控件（仅列表列与查询条件） | `QTools-M/src/views/qtool/index.vue:100,125-148,187,309`；`QTools-M/src/api/qtool/info.js:6,15,23,32,41`；`ToolAdminController.java:41-104` |

> 口径：N1 因此**不能整体勾选完成**——后台 CRUD（交付物 4 的增删改查；导出接口后端已就绪，但管理端按钮路径没对上）已落地，分类/上下架（依赖 `category` 等字段）与前端注册表仍未落地。

---

## 2. 计划 vs 实际：偏移分析与判断

**判断一：媒体站不是"跑偏"，而是把最难啃的硬骨头提前啃了。** 文件管理、流式播放、转码排队、软删可恢复、增量索引、导入幂等与 20 项校验——这些都是"文件/流/权限/一致性"类问题，跟商城要用的能力**高度同构**：库存就是"有限资源 + 并发扣减 + 可回滚"，软删就是"状态而非物理删除"，迁移校验就是"对账思维"。这 6 天不是白花的，它产出的是**可迁移的工程资产**，不只是几个页面。

**判断二：但媒体站没有、也不该被算作商城的"完成度"。** 商城缺的是 v1 说得很准的那条 **C 端交易链路**：购物车 → 下单 → 预占库存 → 支付单 → 回调 → 幂等入账 → 超时关单 → 订单状态机 → 库存流水对账。这条链路上**目前一行代码都没有**，媒体站的经验可以迁移但**不能替代**。把媒体站完成度当成商城进度，是本轮最大的自欺风险。

**判断三：真正被牺牲的是"地基"，而且它已经在咬人。** 0 测试 + 0 CI + 无分区化，意味着现在的每一个"能跑"都是**手工验证过一次**的能跑。`MediaAdminServiceImpl` 已经 1168 行、`MediaUserServiceImpl` 571 行，再往上叠商城而不补测试，返工成本会指数上升。这是本文把"测试与 CI 从 0 到 1"放进 N0（而不是 v1 的 M0 里"顺手做"）的唯一理由。

**判断四：M2 换了实现，而且是更好的实现，v1 的写法要作废。** v1 写的是"同密钥 + `userType=APP` claim"。实际是**独立 issuer + 独立密钥 + 启动即校验两者不同**——隔离强度明显更高（后台密钥泄露不会直接放大成 C 端越权）。v1 的"一套密钥"是错的，本文按代码改。

**判断五：单人节奏下，6 天做出 5 个 Controller + 1168 行 Service + 便携包，代价是"没有任何自动化验证"。** 这不是效率问题，是**风险定价问题**：现在每一次改动的确认成本都由人肉承担。所以 v2 的第一优先级不是"接着做功能"，而是"把确认成本降下来"。

**结论**：媒体站**值得**，但要**封顶**（N2 只做体验与运营，不再扩功能）；商城**必须重估工期**（它现在不是"从 0 到 1"，而是"从 0 到 1，且没有测试网"）；**短链降级为可选项**（它原本是"用最低风险跑通在线服务范式"的教学项目，而这个范式已经被媒体站用另一种方式跑通了）。

---

## 3. 原则（沿用 v1，按现状修订）

**产品原则**
1. **本地工具 = 数据不上传**是信任基石，必须**可验证地**保持（断网可用、零网络请求）。**这条没有被这次扩张破坏**——3 个工具仍是纯前端运算。分区化（N1）就是为了让这条在 UI 上**看得见**。
2. 在线服务必须**明示数据去向**：媒体站接管的是**私有媒体**，比工具站更敏感，页面必须写清"数据在你自己的盘上/库里"。
3. 不做实物交易：**模拟支付，不产生任何真实资金流**；页面与 README 必须显式标注"演示环境，非真实交易"。**这条底线不变。**
4. **（v2 新增）不为了做完而做完**：媒体站收口优先于开新战场。

**工程原则**
1. 单人可交付：每个阶段结束都必须处于"能给别人用"的状态。
2. **先有验证，再加功能**（v2 修订，取代 v1 的"先地基后业务"）：地基里"部署"已经完成，"测试/CI"成了唯一还欠着的、且会持续放大成本的欠账。
3. 不引入无法长期维护的技术（不追新框架、不搞微服务）。**多一个 Maven 模块要能说清它对谁负责**——`qtools-media`/`qtools-app` 已经证明这条规则有用（边界清楚），以后不要再往 admin 里塞业务。
4. 密钥永不入库、永不入仓、永不入文档：本地写 `secret.yml`，部署走环境变量，脚本 **只经子进程环境变量**传递（`media-import` 已按此实现）。
5. **（v2 新增）软删优先**：任何"删除"用户可见资产的功能，先设计回收站与恢复路径（媒体站已建立范式，商城/短链沿用）。
6. **（v2 新增）路径与配置单一来源**：`-Dqtools.home` 推导一切，不在代码里硬编码盘符。

---

## 4. 架构现状图

### 4.1 三层总览

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 便携包 E:\111 （自包含，换机只插盘）                                          │
│  tools\  jdk · maven · mysql(3307) · redis(6379) · nginx(80/443 自签)          │
│          · node · easytier                                                    │
│  data\   mysql\ · media\{thumbs,tools,runtime,logs} · logs\                    │
│  backup\ qtools-20261021-112442.sql 等                                        │
│  视频\   _媒体站\{data\index.json, tools\build_thumbs.js, trash\} …（私有资产）  │
│  *.bat   启动站点 / 停止站点 / 首次初始化 / 放行80 / 信任根证书 / 迁移 / 清理     │
│  所有路径 ← -Dqtools.home=E:/111 推导                                          │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │  nginx:80 / nginx:443
                                ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 三仓库                                                                        │
│  QTools   (后端, 9 模块, 8080)   QTools-C (前台/媒体静态)    QTools-M (管理端)  │
│  /api/**  → Spring Boot          /            → SPA(工具站)   /admin-ui/ → SPA │
│  /media/** → 静态 + /api/media   /media/**    → 手写媒体站    base=/admin-ui   │
│                                  /login /register /account                    │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 后端 9 模块（依赖方向：admin → 业务模块 → framework/common）                    │
│                                                                              │
│  qtools-admin       启动 + 静态托管(WebSpaConfig/SpaEntryController) + 配置    │
│  qtools-tool        工具元数据 + 埋点          (3 行 tool_info / tool_visit)   │
│  qtools-media       媒体索引/扫描/转码/流式/回收站/收藏/最近/后台  (51 文件)     │
│  qtools-app         用户端账号：独立 JWT/邀请码/限流/认领/验证码预留 (29 文件)  │
│  qtools-framework   安全(SecurityConfig)/拦截/配置                             │
│  qtools-system      若依系统管理（用户/角色/菜单/字典）                          │
│  qtools-quartz      定时任务                                                  │
│  qtools-generator   代码生成                                                 │
│  qtools-common      工具类/常量                                              │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                ▼
                    MySQL qtools (3307)      Redis (6379)
                    ├ sys_* / app_* / tool_*   └ 若依缓存
                    └ media_*（10 张）
```

### 4.2 两个 SPA + 一份手写静态页（前端三份产物如何共处一个端口）

| 入口 | 谁提供 | 内容 | 实现 |
|---|---|---|---|
| `GET /` `/login` `/register` | `SpaEntryController` forward → `userDist/index.html` | 工具站 SPA（Vue3 SPA 模式路由） | 路由守卫：`/account` 需登录，否则 `/login?redirect=` |
| `GET /media` `/media/` | `SpaEntryController` redirect → `/media/index.html` | **手写媒体站**（纯静态，非 SPA，无框架） | `public/media/` 10 个文件；`/media/**` 由 `WebSpaConfig` 挂 `userDist/media`，**不做 SPA 回落**（找不到就 404） |
| `GET /admin-ui/` | `SpaEntryController` forward → `adminDist/index.html` | 若依管理端 SPA（`base=/admin-ui/`） | `/admin-ui/static/**` `/admin-ui/**` 资源映射 |
| `GET /welcome` | `SysIndexController` | 若依原首页（让位后的落点） | 避免与 `/` 抢路由 |
| `*` | 前端路由兜底 | —— | `spring.web.resources.add-mappings=false`，**一切静态都由代码显式映射** |

> 注意 `WebSpaConfig` 的 `API_PREFIXES` 白名单（`api` `system` `monitor` `common` `quartz` `tool/gen` `test` `profile` `druid` `v3` `swagger-ui` `media` `actuator`）：`/**` 映射到 `userDist` 时不吞这些前缀。**新增接口前缀必须同步加进这个数组**，否则会被静态处理器抢走——这是后续加 `qtools-shop`/`qtools-link` 时最容易踩的坑，本文把它列为 §6 难点之一。

### 4.3 身份边界（现状，v1 的写法作废）

| | 管理端 | C 端（`qtools-app`） |
|---|---|---|
| 用户表 | `sys_user`（若依自带） | `app_user` + `app_user_identity` |
| 登录入口 | `/login`（管理端 SPA，若依 `/login` 接口） | `/api/app/auth/*` |
| Token | 若依 JWT（`token.secret`） | **独立 JWT：`issuer: qtools-app` + 独立密钥（`qtools.app.jwt.secret`）** |
| 隔离保证 | 密钥缺失/与后台相同 → **启动即失败** | 两套过滤器各认各的，互不承认 |
| 附加防护 | —— | 邀请码注册、登录限流（10 分钟 5 次 → 429）、认领、验证码表预留 |
| 越权测试 | **尚无用例**（0 测试） | **尚无用例** |

---

## 5. 新的路线图

工期仍按"业余每天 2–3 小时"估算；每个 N 结束都必须"可演示、可上线、可回滚"。
排序理由：**先止血（N0）→ 再补 v1 真正缺的地基（N1）→ 兑现已投入的资产（N2）→ 才开新战场（N3/N4）**。

### N0 · 收口与体检（1 周）

- **目标**：把 6 天扩张留下的尾巴收干净，并把"测试与 CI"从 0 拉到 1——让后续所有改动的确认成本从"人肉"变成"一条命令"。
- **交付物**
  1. **媒体站遗留收尾**
     - `视频\_媒体站\trash` **硬校验**：写一个巡检脚本（零依赖 Node，与 `media-import` 同风格），对账「trash 里的文件」↔「`media_*` 表 `hidden=1` 的记录」，输出可对账报告（多/少/孤儿），支持 `--fix` 只做安全的单向修正。
     - **日志落 `data\logs`**：`logback.xml` 的 `log.path` 从相对 `logs` 改为由 `-Dqtools.home` 推导的 `${qtools.home}/data/logs`；清掉仓根的 `qtools-admin.out.log` / `err.log`（改由启动脚本重定向到 `data\logs`）。
     - **`.build-tmp3` 清理**：删掉 `hsperfdata_*` / `node-compile-cache` / `tomcat-docbase.8080.*` / `tomcat.8080.*` / `esbuild-*`；仓根 `dump.rdb` 移出或进 `.gitignore`；把该目录加入 `.gitignore` 与"清理残留.bat"。
     - 备份脚本补一条：`mysqldump` 到 `data\backup\`（当前只有手工的 `backup/qtools-20261021-112442.sql`）。
  2. **测试与 CI 从 0 到 1**（本次最硬的交付物）
     - 后端：父 `pom.xml` 加 `spring-boot-starter-test`（`test` scope），先给**纯函数/纯逻辑**开单测，第一个必须覆盖的靶子是 `MediaFileController` 的 Range 解析（`RANGE_NONE/PARTIAL/UNSATISFIABLE`）与 `MediaStream` 并发名额占用。
     - 前端：`QTools-C` 加 `vitest`，先测 `npm run build` 之外能纯函数验证的东西（工具站的 3 个工具核心逻辑、`useAppAuth` 状态机）。
     - CI：`QTools/.github/workflows/ci.yml`——`mvn -B test` + `QTools-C: npm ci && npm run build` + `QTools-M: npm ci && npm run build`；三仓库各自一份或统一一份，明确跑在哪。
     - **迁移工具回归**：把 `media-import` 的 20 项校验抽成可重复执行的 `--verify-only`，纳入 CI 的"可选任务"（无 MySQL 时跳过，不阻塞主流程）。
  3. **README / ROADMAP 与代码同步**：三仓库 README 的模块清单、端口、路由与 `工具站-便携包 README` 一致；本文档勾掉完成项。
- **验收标准**
  - `mvn -B test` 本地绿、CI 绿；后端至少 **5 个**测试方法、覆盖 Range 三分支与转码名额上限；前端至少 **3 个**单测通过。
  - `data\logs\` 下出现当次启动的应用日志，`QTools\logs\` 与仓根 `.log` 消失。
  - trash 巡检脚本跑一次输出报告：不一致条目 = 0（或每一条都有解释）。
  - `.build-tmp3` 只剩构建期临时物且被 `.gitignore` 覆盖；`git status` 干净。
  - 三份 README 与代码事实一致（模块 9 个、端口 3307/6379/80/443/8080、路由 5 条入口）。
- **依赖**：无（可立即开工）。
- **预估**：5–7 天（测试与 CI 占一半）。

### N1 · 工具分区化 + 前端工具注册表（1–2 周）

- **目标**：把"本地运算 / 需上传"的隐私边界**做成可见的 UI 事实**，并把工具从硬编码路由改成注册表驱动。这才是 v1 里真正没做的 M0。
- **现状（2026-10-21）**：交付物 4 的**增删改查**已落地（后端 `/api/qtool/info/**` + 管理端 `views/qtool/*`）；交付物 1（分类字段）、2（首页两分区）、3（前端注册表）**均未开始**；交付物 4 的「分类 / 上下架」与页面路径、权限串未对齐。逐条证据见 §1.4。
- **交付物**
  1. `tool_info` 加 `category`（`local`/`online`）+ `data_policy`（`local`/`server`）+ `requires_login`，写升级 SQL（`sql/qtools/` 新增 `tool_partition.sql`，可重复执行）；现有 3 条标注为 `local`，补 1–2 条 `online` 示例（可用媒体站入口）。——**未落地**：`tool_info` 仍是原 9 列（§1.4）。
  2. 前台首页两分区（本地工具 / 在线服务），卡片带「本地运算·数据不上传」/「需上传」徽标。
  3. **前端工具注册表**：`src/tools/registry.js` 声明 `{ slug, name, category, policy, loader }`，路由由注册表生成，`/tool/{slug}` 按需 `import()` 分包；本地工具**零接口依赖**。
  4. 管理端「工具管理」页（用若依 `/tool/gen` 生成后微调）：增删改查 + 分类 + 上下架 + 导出。——**增删改查已落地**（`/api/qtool/info` 6 个接口 + `QTools-M/src/views/qtool/*`，见 §1.4）；分类与上下架仍缺（依赖交付物 1），且页面存在三处与后端不一致（导出路径、权限串、`status` 无输入控件），需一并修。
- **验收标准**
  - 接口返回含 `category`/`data_policy`；首页两分区渲染正确。
  - **停掉后端，本地工具区仍完全可用**（断网打开 `/tool/json` 能用），且在 DevTools Network 里 **0 个请求**。
  - 本地工具不进主 chunk：新增工具后主 chunk 体积**不增长**（`dist` 对比）。
  - 管理端能改分类并即时反映到前台。
- **依赖**：N0（要 CI 兜住回归）。
- **预估**：7–10 天。

### N2 · 媒体站体验与运营（2–3 周）

- **目标**：把已投入的媒体资产**兑现成可用产品**，并**封顶**——只做体验与运营，不再扩功能。
- **交付物**
  1. **C 端账号与媒体联动**：明确 owner 语义收敛（`qtools-app` 的 `AppOwnerMapper` 与 `qtools-media` 的 `MediaOwnerServiceImpl` 二选一作为唯一真相），实现"我的收藏 / 最近浏览 / 可见性授权"。
  2. **访问与播放统计看板**：新增聚合表（`media_play_daily` / `media_view_daily`），管理端图表；转码成功率、429 次数、慢播放占比可见。
  3. **转码与缩略图策略**：`maxTranscode=2` 之外的排队与友好提示（429 要给"当前繁忙，N 秒后重试"），缩略图增量扫描改为可调度（quartz）+ 失败重试；转码产物落 `data\media\runtime`，加容量与清理策略。
  4. **移动端播放体验**：手写媒体站在小屏的布局、手势、横竖屏、iOS 内联播放（`playsinline`）、首帧时间。
- **验收标准**
  - 收藏/最近浏览在 C 端登录态下跨设备一致（换浏览器仍能看到）；未登录访问按策略拒绝或降级只读。
  - 看板数字与库内聚合 SQL 结果一致；看板查询不出现全表 `count(*)`。
  - 并发 4 路播放时第 3 路得到明确 429 语义提示而不是白屏。
  - 移动端首帧 < 2s（同一局域网、同一视频、冷缓存），暂停/拖动可用。
- **依赖**：N0（日志与 trash 校验）、N1（分区/注册表不冲突，媒体入口挂到在线服务区）。
- **预估**：12–18 天。

### N3 · 商城 MVP（模拟支付，4–6 周）

- **目标**：补上 v1 说准的那条 **C 端交易链路**，复用已有身份体系，不新建账号。
- **范围（**冻结**，超出即砍）**：商品列表/详情、购物车、下单、模拟收银台、回调幂等、超时关单、我的订单、后台商品与订单管理、库存防超卖与流水。
- **不做**：真实支付、物流、发票、多租户、优惠券体系、评价、退款流程（后续）。
- **交付物**
  1. `qtools-shop`（商品/SKU/购物车/订单/库存流水）+ `qtools-pay`（`PayChannel` 接口 + `MockPayChannel`，预留真实渠道实现位）。
  2. 表：`shop_item` `shop_sku` `shop_cart` `shop_order` `shop_order_item` `shop_pay` `shop_stock_log`（金额一律**整数分**；`order_no`/`idem_key`/`pay_no` 唯一索引；`sku.version` 乐观锁）。
  3. 接口：`POST /api/shop/order/create`（带 `Idempotency-Key`）、`POST /api/pay/mock/create`、`GET /pay/cashier`（模拟收银台）、`POST /api/pay/notify/mock`（HMAC 签名）、`GET /api/shop/order/{orderNo}`。
  4. 后台：商品/库存/订单/支付单管理（复用 `sys_menu` 固定 id 的写法）。
  5. **`WebSpaConfig.API_PREFIXES` 必须加 `shop` / `pay`**（否则会被 `/**` 静态映射吞掉）。
- **验收标准（沿用 v1，不放水）**
  1. **100 并发抢 10 件库存，不超卖**（压测脚本 + 数据证明 + `shop_stock_log` 对账平衡）。
  2. 同一 `Idempotency-Key` 重复下单**只产生一张订单**。
  3. 支付回调**重复投递 5 次不重复入账**（`pay_no` 唯一 + 状态机拦截 + 事务）。
  4. 超时订单自动关闭并**释放预占库存**（`@Scheduled` 每分钟扫）。
  5. 全链路演示：加购 → 下单 → 模拟收银台 → 回调 → `PAID` → 库存流水可追溯。
  6. **页面显式标注"演示环境，不产生真实交易"**（合规硬要求）。
  7. 越权：C 端 token 访问管理端接口 403，且看不到他人订单（这条要有自动化用例）。
- **依赖**：N0（测试/CI，否则以上验收无法自动化）、N1（front registry 分区，商城的商品页走在线服务区）。
- **预估**：25–40 天。**注意**：这是"从 0 到 1 且没有测试网"的工期，N0 若不完成，实际会翻倍。

### N4 · 短链（2–3 周）

- **目标**：跑通"在线服务范式"的完整样板（缓存/异步统计/限流/安全）。
- **重要修订**：v1 把短链当"M1 第一个在线服务"是因为要**用它来练手上线链路**；这个目的**已经被媒体站用另一种方式达成了**。所以短链现在是**可选项**，与 N3 无依赖，可互换顺序，也可以在 N3 之后按"想不想讲这个面试故事"决定做不做。
- **交付物**：`qtools-link`（号段发号器 + Base62 + 多级缓存 + 布隆过滤器 + 异步批量落库 + 限流 + SSRF/开放重定向防护）；`short_link` / `link_stats_daily`；`GET /s/{code}` 302；前台 `/link` 页；管理端短链管理；压测报告。
- **验收标准**：生成→跳转→统计闭环；缓存命中率有数据；随机不存在短码**不落 SQL**（布隆 + 空值缓存）；限流生效；恶意目标 URL 用例全部被拒。
- **依赖**：N0（CI/缓存压测脚本）；与 N3 互不依赖。
- **预估**：12–18 天。

### N5 · 问卷 / 预约（各 2–3 周，按兴趣）

- **问卷**：动态 schema（JSON 表单定义）、防重复提交（token + 唯一键）、聚合统计、Excel 流式导出（`poi-ooxml` 已在依赖里）。
- **预约**：时段库存并发扣减、分布式锁、日历冲突检测、通知（先落到站内消息，不接短信）。
- **验收标准**：各自独立闭环可用；问卷 Excel 导出 1 万行不 OOM；预约同一时段并发 50 人抢 5 个名额不超约。
- **依赖**：N0 + N1（问卷/预约都属于"在线服务区"，需要分区与注册表）。
- **预估**：各 12–18 天。

### N6 · 长期硬化（持续）

- **性能**：缓存穿透/雪崩防护（空值缓存 + 随机 TTL）、慢 SQL（EXPLAIN + 索引）、连接池/线程池、媒体列表分页（9628 张图必须有键集分页）。
- **可观测**：结构化日志（落 `data\logs`）、慢接口告警、媒体扫描/转码任务看板、`/actuator/health` 外部探测。
- **安全**：限流全覆盖（目前只有登录限流）、**越权自动化用例**（两套身份 × 所有管理接口）、SQL 注入/敏感信息扫描、依赖漏洞扫描、Druid 控制台关闭。
- **前端体积**：Element Plus **按需引入**（`unplugin-vue-components`）+ 图标按需 + 媒体静态页不混入 SPA。**目标：首屏 gzip < 300 KB**（当前 607.1 KB，先到 450 KB 再到 300 KB）。
- **备份演练**：`mysqldump` 每日 + **异地异盘一份**；**恢复演练**（在干净机器上从 `backup/*.sql` + `视频\` 恢复出可用站点），演练记录写进 README。
- **拆域（可选）**：`s.qtools.vip`（短链）、`shop.qtools.vip`（商城）。

---

## 6. 关键难点清单

| 难点 | 现状 | 验证方式 |
|---|---|---|
| 流媒体字节级 Range（200/206/416/If-Range/HEAD） | **已解决**（`MediaFileController`：`ResourceRegion` + `HttpRange` + `EXPOSE_HEADERS`） | 用 `curl -H "Range: bytes=..."` 断言 206 与 `Content-Range`；越界断言 416；`If-Range` 命中返回 200 全量 |
| 转码并发控制与 429 语义 | **已解决**（`maxTranscode=2`，名额在启动 ffmpeg **前**占用，超出立即 429） | 并发 4 路请求播放，断言第 3/4 路 429；断言名额释放（结束一路后能再起一路）；无 ffmpeg 进程泄漏 |
| 软删可恢复（绝不真删） | **已解决**（MOVED 到 `_媒体站/trash` + `/restore/{kind}/{id}`，代码无真删路径） | trash 巡检：文件集合 ↔ `hidden=1` 记录一一对应；删除后源路径不存在、trash 存在；恢复后反向成立 |
| 媒体导入幂等与多重校验 | **已解决**（`--truncate` / 幂等 upsert；20 项断言：条数/字节/关系/抽样/全量磁盘存在性/每册字节/每片 size） | 连续跑两次 upsert，第二次条数不变、字节不变、校验全 `[ok]`（`run2-upsert.log` 已证） |
| 数据库口令不外泄 | **已解决**（从 `secret.yml` 读，只经子进程 `MYSQL_PWD` 传递，不落盘不打印） | 检查 `import.js` 无 `console.log(password)`；`--help` 与异常栈不含口令；进程列表与脚本文件不含明文 |
| 单端口多前端路由互不吞 | **已解决**（`SpaEntryController` + `WebSpaConfig.API_PREFIXES` 白名单 + `add-mappings=false`，`/media/**` 无 SPA 回落） | 逐一请求 `/` `/login` `/register` `/media` `/media/nope` `/admin-ui/` `/api/media/**`，断言各自响应体与状态码（`/media/nope` 必须 404 而不是 index.html） |
| 两套身份互不越权 | **部分解决**（独立 issuer/密钥，启动校验密钥不同） | **待做**：自动化越权用例——C 端 token 打 `/system/**`、`/api/media/admin/**` 必须 401/403；后台 token 打 `/api/app/auth/me` 必须拒绝 |
| **库存防超卖** | **待做**（无 `shop_sku`） | 100 并发抢 10 件：成功 10、失败 90、`stock>=0`、`shop_stock_log` 汇总 = 初始 - 剩余 |
| **支付回调幂等 / 重复入账** | **待做** | 同一 `pay_no` 回调投递 5 次：`shop_pay` 1 行、`shop_order` 状态只变一次、库存流水只增一次 |
| **下单幂等** | **待做** | 同 `Idempotency-Key` 连发 10 次：`shop_order` 只 1 行，返回同一 `order_no` |
| **超时关单释放库存** | **待做** | 造 `expire_time` 已过的单，等定时任务：订单 `CLOSED`、库存回到下单前、流水有释放记录 |
| **缓存穿透/雪崩**（短链/商品） | **待做** | 随机不存在短码 1 万次：SQL 计数 ≈ 0（布隆挡住）；空值缓存 TTL 抖动生效 |
| **限流**（除登录外） | **部分**（仅登录 10 分钟 5 次 → 429） | 补写/播放/下单接口限流，压测断言 429 比例与恢复时间 |
| **越权测试体系** | **待做（0 测试）** | CI 里跑越权矩阵：2 身份 × N 管理接口，全绿才算过 |
| 统计不靠全表扫描 | **待做**（无聚合表） | 看板接口 `EXPLAIN` 不出现 `type=ALL`；与直接聚合 SQL 数字一致 |
| 9628 张图的分页与索引 | **部分**（有 Mapper，未验） | 深翻页（第 500 页）响应 < 200ms；键集分页不随页号劣化 |
| 前台体积 | **退化中**（2744.3 KB / gzip 607.1 KB，Element Plus 全量） | 按需引入前后 `dist` 对比；首屏 gzip 目标 < 300 KB |
| 单盘单点 | **缓解中**（已推 GitHub；“媒体库是否入库”未定） | 恢复演练：干净机器用 `backup/*.sql` + `视频\` 重建出可用站点 |

---

## 7. 风险与止损

| 风险 | 现状评估 | 应对 |
|---|---|---|
| **单盘单点（硬盘坏 = 全部没了）** | 已**部分缓解**：三个代码仓库已推 GitHub。但 `E:\111` 整体（`tools\` `data\` `视频\` `backup\`）**不在 git、没有异地副本**，而 `视频\` 是**不可再生的私有资产** | ① 代码继续推远程（已完成，保持）；② `backup/*.sql` + 媒体导入脚本进可重建路径；③ **`视频\` 必须有一份物理异地副本**（第二块盘或 NAS）；④ 季度恢复演练，记录进 README |
| **媒体库是私有数据，不能只靠"应该没事"** | 现在只有手工一份 `qtools-20261021-112442.sql`（1087.4 KB），**无自动备份、无恢复演练** | N0 加 `mysqldump` 脚本；N6 做恢复演练；把"trash + 缩略图可重建、原片不可再生"写进口径 |
| **商城范围膨胀** | v1 已冻结范围，但目前还没开工，诱惑最大 | 范围冻结在 N3 正文里；任何新需求进 N5/N6 或"明确不做"；每周检查点 |
| **单人节奏断档** | 6 天做出大量功能，说明爆发力够，但**没有可持续验证机制** | 每个 N 结束必须"可上线可回滚"；N0 的 CI 是防断档的基础设施（隔一周回来也能靠 CI 判断状态） |
| **测试债**（最大技术风险） | 后端 0 测试、0 CI、两个前端无测试框架；`MediaAdminServiceImpl` 1168 行 | N0 必须先落地，**N3 开工的前置条件**；测试只补"会坏的地方"（Range、并发、幂等、金额），不追覆盖率数字 |
| **媒体站功能蔓生** | 能力已经很多（索引/扫描/转码/回收站/收藏/最近/后台），再加就会拖住商城 | N2 **封顶**：只做体验与运营，不做新功能；新需求进 backlog |
| **计划外扩张再次发生** | 这次就是先例：6 天全投在一个 v1 里不存在的方向上 | 规则化：**任何路线图外的新方向，先写一行"为什么值得、挤掉哪个 N"，再开工** |
| **真实支付诱惑** | 无企业资质 | 无资质前**不做**真实资金流；`PayChannel` 只留实现位 |
| **技术债型控制器膨胀** | `MediaAdminController` 单点已经很重（Service 1168 行） | N2 拆分只按"读/写/转码任务"三块拆，不做无谓重构 |

**明确不做**：真实资金流、实物交易/物流、多租户、微服务拆分、账号云同步（破坏"无需注册"卖点）、媒体站上云（与"私有资产留在盘上"冲突）。

---

## 8. 立即可派发的任务（任务书级别）

> 全部取自 **N0**，因为 N0 是后续一切的前置。每个任务可独立派发、独立验收；共 8 个（T1–T8）。

| 编号 | 任务 | 目标 | 边界（只动这些） | 验收标准 |
|---|---|---|---|---|
| **T1** | trash 硬校验脚本 | 让"软删可恢复"从口头保证变成可对账事实 | 只新增 `QTtools/doc/media-import/verify-trash.js`（或同目录新文件）+ README 一段；不改后端 Java | 零依赖 Node；输出「trash 文件数 / `hidden=1` 记录数 / 孤儿文件 / 孤儿记录」四项；对现有 trash 跑通并给出报告；`--fix` 只做单向安全修正且默认关闭；退出码可用于 CI |
| **T2** | 日志落 `data\logs` | 日志统一进盘上数据目录，仓里不再躺日志 | 只改 `logback.xml` 的 `log.path` + 启动脚本重定向 + `.gitignore`；不动业务代码 | 启动后 `E:\111\data\logs\` 出现当日日志；`QTools\logs\` 与仓根 `*.log` 不再新增；`-Dqtools.home` 缺省时也不写到 CWD |
| **T3** | 构建残留清理与忽略 | 仓根干净、`git status` 无噪音 | 只改 `.gitignore` + 清理脚本 + README 故障排查一段；不删任何源码/数据 | `.build-tmp3` 内的 `hsperfdata_*`/`node-compile-cache`/`tomcat.*`/`esbuild-*` 被清理且已忽略；`dump.rdb` 已忽略或移出；`git status --porcelain` 只剩预期改动 |
| **T4** | 后端测试基建（从 0 到 1） | 让 `mvn -B test` 有东西可跑 | 只改父 `pom.xml`（加 `spring-boot-starter-test`，`test` scope）+ 新增 `qtools-media/src/test/**`；不改业务逻辑 | `mvn -B test` 绿；至少 5 个用例，**必须覆盖** Range 的三分支（none/partial/unsatisfiable）与 416；不引入除 starter-test 之外的新依赖 |
| **T5** | 转码并发名额单测 | 把 `maxTranscode=2` + 429 语义钉死 | 只新增 `qtools-media/src/test/**`（引 T4 的依赖）；不改 `MediaStreamServiceImpl` 逻辑 | 模拟 4 路并发：2 路拿到名额、2 路得到 429 语义；名额释放后可再取；用例不依赖真实 ffmpeg（用假实现/桩） |
| **T6** | 前端测试框架 + 首批单测 | 前端也有 `npm test` | 只改 `QTools-C/package.json`（加 `vitest` + script）+ 新增 `QTools-C/tests/**`；不动 `public/media/` | `npm test` 绿；至少 3 个用例（3 个工具的核心纯函数或 `useAppAuth` 状态机）；`npm run build` 仍通过、产物文件名与大小无异常变化 |
| **T7** | CI 从 0 到 1 | 四个真话：后端能测、两个前端能构建 | 只新增 `.github/workflows/ci.yml`（+ 必要时三仓库各一份）；不改源码 | PR/push 触发；`mvn -B test`、`QTools-C npm ci && npm run build`、`QTools-M npm ci && npm run build` 三步全绿；失败时能看出是哪一步；**密钥不注入 CI** |
| **T8** | README / ROADMAP 与代码同步 | 文档不再落后于代码 | 三仓库 README + 本文档（勾进度）；不动代码 | 四个 README 与本文的模块数（9）、端口（3307/6379/80/443/8080）、入口路由（`/` `/login` `/register` `/media` `/admin-ui/` `/welcome`）、测试与 CI 状态**完全一致**；本文 N0 条目勾选 |

> 每个任务做完都要：`mvn -B test` 或 `npm run build` 通过 + 关键行为实测（给出命令与输出）+ 文档同步（README / 本路线图勾选进度）。
> **派发顺序建议**：T3 → T2 → T4 → T5 → T6 → T7 → T1 → T8（T7 依赖 T4/T6 有测试可跑；T1 与云无关可最后做）。

---

## 附录 A · v1 里程碑 → v2 映射

| v1 | 处置 | 去向 |
|---|---|---|
| M0 地基（分区化 + 脚手架 + 测试/CI） | **拆开** | 部署部分**已完成**（形态改为便携包）；分区化 → **N1**；测试/CI → **N0** |
| M1 短链 | **降级为可选** | **N4**（与 N3 无依赖，可互换或不做） |
| M2 C 端用户体系 | **已落地（实现方式不同）** | 已完成：`qtools-app`，独立 JWT/issuer，不采用 `userType` claim 方案 |
| M3 商城 + 模拟支付 | **保留，工期重估** | **N3**（需 N0 前置，否则工期翻倍） |
| M4 运营看板 | **并入** | 媒体侧 → **N2**；通用 → **N6** |
| M5 问卷/预约 | **保留** | **N5** |
| M6 硬化 | **保留并加重** | **N6**（新增：前端体积从"待优化"变"已退化"） |
| —— | 新增 | **N0**（收口与体检）、**N2**（媒体站体验与运营） |

## 附录 B · 本文事实的核对方式（可复现）

```powershell
# 9 个模块
Select-String QTools\pom.xml -Pattern '<module>'
# 0 测试：无 test 依赖、无 src/test
Get-ChildItem QTools -Recurse -Filter pom.xml | Select-String 'spring-boot-starter-test|junit|mockito'
Get-ChildItem QTools -Recurse -Directory | Where-Object FullName -match 'src\\test'
# 0 CI
Get-ChildItem QTools\.github -Recurse -File
# 媒体模块规模
Get-ChildItem QTools\qtools-media\src -Recurse -File | Measure-Object
Get-ChildItem QTools\qtools-media\src -Recurse -Filter *ServiceImpl.java |
  ForEach-Object { "$($_.Name) $((Get-Content $_.FullName | Measure-Object -Line).Lines)" }
# 用户端账号模块
Get-ChildItem QTools\qtools-app\src -Recurse -File | Measure-Object
Select-String QTools\doc\app_schema.sql -Pattern 'CREATE TABLE'
# tool_info 无分区字段
Select-String QTools\sql\qtools\tool_init.sql -Pattern 'category|data_policy'
# 工具后台维护：6 个接口与权限串（对比前台匿名接口）
Select-String QTools\qtools-tool\src\main\java\com\qtools\tool\controller\ToolAdminController.java -Pattern 'Mapping|hasPermi'
Select-String QTools\qtools-tool\src\main\java\com\qtools\tool\controller\ToolInfoController.java -Pattern 'Anonymous'
Select-String QTools\qtools-tool\src\main\resources\mapper\tool\ToolAdminMapper.xml -Pattern '<select|<insert|<update|<delete'
# 私密配置：文件名、加载方式、被忽略的新旧两个名字
Select-String QTools\qtools-admin\src\main\resources\application.yml -Pattern 'config.import|secret.yml'
Get-ChildItem QTools\qtools-admin\src\main\resources\secret.yml*
Select-String QTools\.gitignore -Pattern 'secret'
# 前台体积（raw / gzip）
node -e "const z=require('zlib'),fs=require('fs');const b=fs.readFileSync('QTools-C/dist/assets/index-MTbHYY0o.js');console.log(b.length, z.gzipSync(b,{level:9}).length)"
# 迁移数据规模与校验
Get-Content QTools\doc\media-import\run2-upsert.log -Tail 25
# 便携包运行时与端口
Get-ChildItem E:\111\tools -Directory
Select-String E:\111\tools\mysql\my.ini -Pattern 'port'
```

> 口径纪律：本文所有数字均来自上述命令在 **2026-10-21** 的实测输出；若与口头记录不一致（如媒体图片数 9627 vs 9628），**以最新一次 `run2-upsert.log` 的 9628 为准**，并在 §1 保留差异说明。
