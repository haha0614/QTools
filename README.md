# QTools 后端（青兔在线工具箱）

> 基于若依 RuoYi-Vue-springboot3 3.9.2 二次开发的后端，为青兔在线工具箱的前台用户站（QTools-C）与后台管理端（QTools-M）提供接口。

## 简介

- 本仓库是「青兔在线工具箱」的后端服务，技术栈为 Java 17 + Spring Boot 3 + MyBatis + MySQL + Redis。
- 前台用户站 QTools-C 通过两个**匿名**接口读取工具列表、上报访问埋点；管理后台 QTools-M 使用若依自带的登录与系统管理接口（用户 / 角色 / 菜单 / 字典 / 定时任务 / 代码生成等），另有工具维护接口 `/api/qtool/info/**`（走若依后台鉴权，权限串 `qtool:admin:*`，**不是**匿名接口）。
- 相对上游若依，本仓库做了两件事：① 模块与包整体重命名（`ruoyi-*` → `qtools-*`，`com.ruoyi` → `com.qtools`）；② 新增业务模块 `qtools-tool`（前台工具列表 + 访问埋点 + 后台工具维护）。
- 工具的基础数据由 `sql/qtools/tool_init.sql` 初始化（JSON 格式化、文本字数统计、随机密码生成、媒体站共 4 条）。
- 管理端已有工具维护页面（`QTools-M/src/views/qtool/index.vue`、`view.vue`，接口封装在 `src/api/qtool/info.js`），对接后端 `/api/qtool/info/**`；仓库内**未附**这批页面的菜单/权限 SQL，菜单需在「系统管理 · 菜单管理」里自行配置后才会出现在侧边栏。

## 相关仓库

| 仓库 | 角色 | 技术栈 / 说明 | 地址 |
| :--- | :--- | :--- | :--- |
| **QTools** | 后端（本项目） | Spring Boot 3 / Java 17，默认端口 8080 | https://github.com/haha0614/QTools |
| **QTools-C** | 前台用户站 | Vue3 + Vite + Element Plus，开发端口 5173 | https://github.com/haha0614/QTools-C |
| **QTools-M** | 管理后台 | 若依 Vue3 前端，开发端口 80 | https://github.com/haha0614/QTools-M |

## 技术栈

| 类别 | 版本 | 说明 |
| :--- | :--- | :--- |
| Spring Boot | 3.5.16 | 父 POM `spring-boot.version`（`pom.xml:20`） |
| Java | 17 | 编译级别 `java.version=17`（`pom.xml:19`） |
| 构建 | Maven（多模块） | 父坐标 `com.qtools:qtools:3.9.2`（`pom.xml:7-9`） |
| 数据库 | MySQL 8 | 库名 `qtools`，JDBC 驱动 `com.mysql.cj.jdbc.Driver` |
| 缓存 | Redis | 默认 `localhost:6379`、db 0；口令项 `spring.data.redis.password` 由 `secret.yml` 提供（当前为空）（`application.yml:155-177`） |
| 连接池 | Druid 1.2.28 | 含监控台 `/druid/*` |
| 持久层 | MyBatis + PageHelper 2.1.1 | mapper XML 位于各模块 `resources/mapper/**` |
| 接口文档 | springdoc-openapi 2.9.0 | Swagger UI：`/swagger-ui.html` |
| 认证 | Spring Security + JWT（jjwt 0.9.1） | Token 请求头 `Authorization` |
| 验证码 | Kaptcha 2.3.3 | 类型由 `qtools.captchaType` 控制（默认 `math`） |
| 代码生成 | Velocity 2.3 | `/tool/gen` |

## 模块结构

| 模块 | 职责 |
| :--- | :--- |
| `qtools-admin` | 启动模块 + Web 层（`com.qtools.web.controller.*`），产物 `qtools-admin/target/qtools-admin.jar` |
| `qtools-common` | 通用工具、常量、注解、异常、Redis / 文件 / HTTP 等 |
| `qtools-framework` | 安全（Spring Security + JWT）、多数据源与 Druid、拦截器、AOP |
| `qtools-system` | 若依系统管理业务（用户 / 角色 / 菜单 / 部门 / 岗位 / 字典 / 参数 / 通知公告） |
| `qtools-quartz` | 定时任务 |
| `qtools-generator` | 代码生成（`/tool/gen`） |
| `qtools-tool` | **本项目新增的业务模块**：工具列表与访问埋点（`com.qtools.tool.*`，mapper XML 在 `resources/mapper/tool/`） |

关键目录（只列接手时最需要看的）：

```text
QTools/
├── pom.xml                                  # 父 POM：com.qtools:qtools:3.9.2，java.version=17
├── qtools-admin/                            # 启动模块 + Web 层
│   └── src/main/
│       ├── java/com/qtools/
│       │   ├── QToolsApplication.java        # 启动类
│       │   └── web/controller/              # system / monitor / common / tool 四组控制器
│       └── resources/
│           ├── application.yml               # 端口、Redis、token、springdoc、xss 等
│           ├── application-druid.yml         # 数据源、连接池、Druid 监控台
│           ├── secret.yml.example            # 私密配置模板（真实值不入库，复制为 secret.yml）
│           ├── logback.xml                   # 控制台彩色 + 文件日志（logs/）
│           ├── banner.txt                    # 启动横幅（应用版本 / Spring Boot 版本）
│           └── mybatis/mybatis-config.xml
├── qtools-common/                           # 通用工具 / 常量 / 注解 / 异常 / Redis、文件、HTTP
├── qtools-framework/                        # 安全、数据源、拦截器、AOP
│   └── src/main/java/com/qtools/framework/config/
│       ├── SecurityConfig.java              # Spring Security 放行规则
│       └── properties/PermitAllUrlProperties.java  # 扫描 @Anonymous 自动加入白名单
├── qtools-system/                           # 若依系统管理业务
├── qtools-quartz/                           # 定时任务
├── qtools-generator/                        # 代码生成
├── qtools-tool/                            # 本项目新增业务模块
│   └── src/main/
│       ├── java/com/qtools/tool/
│       │   ├── controller/                  # ToolInfoController、ToolVisitController（均 @Anonymous）+ ToolAdminController（后台维护，需鉴权）
│       │   ├── service/ + service/impl/     # IToolInfoService / IToolVisitService / IToolAdminService 及其实现
│       │   ├── mapper/                      # ToolInfoMapper、ToolVisitMapper、ToolAdminMapper
│       │   └── domain/                      # ToolInfo、ToolVisit、dto/ToolVisitAddDTO
│       └── resources/mapper/tool/           # ToolInfoMapper.xml、ToolVisitMapper.xml、ToolAdminMapper.xml
├── sql/
│   ├── ry_20260417.sql                      # 若依基础表 + 菜单/字典初始数据
│   ├── quartz.sql                           # 定时任务表
│   └── qtools/tool_init.sql                 # 本项目：tool_info / tool_visit + 4 条工具数据
├── ry.bat / ry.sh                           # 若依自带的启停脚本（注意下方说明）
├── bin/                                     # 若依自带的 clean/package/run 批处理
├── doc/                                     # 若依环境使用手册
└── LICENSE                                  # MIT
```

## 接口说明

统一响应约定：`code = 200` 表示成功，`msg` 为提示信息；业务数据放在 `data` 中。出错时由全局异常处理器返回 `code = 500` 与错误提示。

### 1. 查询工具列表（匿名）

- 方法与路径：`GET /api/toolInfo/list`
- 控制器：`com.qtools.tool.controller.ToolInfoController`（`qtools-tool/src/main/java/com/qtools/tool/controller/ToolInfoController.java`）
- 匿名放行：控制器类上标注 `@Anonymous`，由 `qtools-framework` 的 `PermitAllUrlProperties` 自动把该类下所有接口加入 Spring Security 的 `permitAll` 白名单，无需改动 `SecurityConfig`。
- 查询规则：只返回 `status = '0'`（正常）的记录，按 `sort asc, id asc` 排序（`ToolInfoMapper.xml:31-35`）。

请求：

```bash
curl http://localhost:8080/api/toolInfo/list
```

响应示例：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "id": 1,
      "toolName": "JSON格式化工具",
      "toolDesc": "JSON格式化、压缩、语法校验",
      "toolRoute": "/tool/json",
      "icon": "Document",
      "sort": 1,
      "status": "0"
    }
  ]
}
```

字段说明（JSON 字段名为**驼峰**，由 `ToolInfo` 上的 `@JsonProperty` 显式指定，与 Java 字段同名；`tool_info` 表的物理列名仍是下划线风格，由 `ToolInfoMapper.xml` 的 `resultMap` 映射）：

| JSON 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | number | 工具 ID |
| `toolName` | string | 工具名称 |
| `toolDesc` | string | 工具描述 |
| `toolRoute` | string | 前台访问路由，如 `/tool/json` |
| `icon` | string | 前端 Element Plus 图标组件名 |
| `sort` | number | 显示顺序（升序） |
| `status` | string | 状态：`0` 正常、`1` 停用（接口只返回 `0`） |

### 2. 记录工具访问埋点（匿名）

- 方法与路径：`POST /api/toolVisit/add`
- 控制器：`com.qtools.tool.controller.ToolVisitController`（`qtools-tool/src/main/java/com/qtools/tool/controller/ToolVisitController.java`）
- 匿名放行：同上，类上标注 `@Anonymous`。
- 请求体：`{"toolId": 1}`；`toolId` 为必填（`ToolVisitAddDTO` 上 `@NotNull(message = "toolId不能为空")`）。
- 落库：写入 `tool_visit` 表（`tool_id`、`ip`、`user_agent`、`create_time`）。**不校验 `toolId` 是否真实存在于 `tool_info`**。

成功：

```bash
curl -X POST http://localhost:8080/api/toolVisit/add \
  -H "Content-Type: application/json" \
  -d '{"toolId":1}'
```

```json
{ "code": 200, "msg": "操作成功" }
```

`toolId` 为空或缺失时，参数校验失败，由全局异常处理器返回：

```json
{ "code": 500, "msg": "toolId不能为空" }
```

### 3. 工具维护（后台，需鉴权）

- 控制器：`com.qtools.tool.controller.ToolAdminController`（`qtools-tool/src/main/java/com/qtools/tool/controller/ToolAdminController.java`），类级 `@RequestMapping("/api/qtool/info")`。
- **与上面两个接口性质不同**：本控制器**没有** `@Anonymous`，每个方法都带 `@PreAuthorize("@ss.hasPermi('...')")`，走若依后台鉴权链——未携带有效后台 token 或权限不足时，按若依默认拒绝（不会进入 `permitAll` 白名单）。写操作另带 `@Log`，记入若依操作日志。
- 响应字段与匿名列表接口一致（同一个 `ToolInfo` 实体，驼峰命名）。

| 方法 | 路径 | 权限串 | 说明 | 代码位置 |
| :--- | :--- | :--- | :--- | :--- |
| GET | `/api/qtool/info/list` | `qtool:admin:list` | 分页查询（`startPage()` → `TableDataInfo`）；过滤条件：`toolName` 模糊匹配，`toolDesc` / `toolRoute` / `icon` / `sort` / `status` 精确匹配 | `ToolAdminController.java:41-48` |
| POST | `/api/qtool/info/export` | `qtool:admin:export` | 导出 Excel（`ExcelUtil`，sheet 名「工具信息数据」） | `ToolAdminController.java:53-61` |
| GET | `/api/qtool/info/{id}` | `qtool:admin:query` | 按 id 查详情 | `ToolAdminController.java:66-71` |
| POST | `/api/qtool/info` | `qtool:admin:add` | 新增 | `ToolAdminController.java:76-82` |
| PUT | `/api/qtool/info` | `qtool:admin:edit` | 修改 | `ToolAdminController.java:87-93` |
| DELETE | `/api/qtool/info/{ids}` | `qtool:admin:remove` | 批量删除（路径参数为 id 数组） | `ToolAdminController.java:98-104` |

- 配套实现：`IToolAdminService` / `ToolAdminServiceImpl`；`ToolAdminMapper.java` + `resources/mapper/tool/ToolAdminMapper.xml`（6 个语句：`selectToolInfoList` / `selectToolInfoById` / `insertToolInfo` / `updateToolInfo` / `deleteToolInfoById` / `deleteToolInfoByIds`）。
- 管理端页面：`QTools-M/src/views/qtool/index.vue`（列表 + 搜索 + 新增 / 修改 / 删除 / 导出按钮）、`view.vue`（详情抽屉），接口封装在 `QTools-M/src/api/qtool/info.js`。
- **已知不一致（待修）**：管理端导出按钮请求的是 `tool/info/export`（`index.vue:309`），与后端的 `/api/qtool/info/export` 不一致，点导出会打不到本接口；页面上的 `v-hasPermi` 用的是 `tool:info:*`，与后端校验的 `qtool:admin:*` 也不同，配菜单权限时以**后端**的 `qtool:admin:*` 为准。

> 前端字段交叉印证：`QTools-C/src/views/Home.vue` 的 `FALLBACK_TOOLS` 与合并逻辑用的是 `toolName` / `toolDesc` / `toolRoute` / `icon` / `sort`（驼峰），与后端序列化一致。

## 快速开始

### 1. 前置依赖

| 依赖 | 要求 |
| :--- | :--- |
| JDK | 17 |
| Maven | 3.8+（能访问 Maven 中央仓库或阿里云镜像，见 `pom.xml` 的 `repositories`） |
| MySQL | 8.x，库名 `qtools`，字符集 `utf8mb4` |
| Redis | `localhost:6379`、db 0、无密码（与 `application.yml` 默认一致） |

### 2. 建库与导入 SQL

`sql/` 下三个脚本的执行顺序**不能颠倒**：若依基础表在前，本项目建表脚本在后。

```bash
mysql -uroot -p -e "create database qtools default charset utf8mb4;"
mysql -uroot -p qtools < sql/ry_20260417.sql       # 若依基础表 + 菜单/字典初始数据
mysql -uroot -p qtools < sql/quartz.sql            # 定时任务表
mysql -uroot -p qtools < sql/qtools/tool_init.sql  # 本项目：tool_info / tool_visit + 4 条工具数据(id 1/2/3/4)
```

`tool_init.sql` 使用 `create table if not exists` 与 `insert ... on duplicate key update`，可重复执行。

### 3. 配置私密文件

数据库密码、Redis 口令、后台与用户端的 JWT 密钥都不入库，仓库里只有模板 `secret.yml.example`：

```bash
# Windows
copy qtools-admin\src\main\resources\secret.yml.example qtools-admin\src\main\resources\secret.yml
# Linux / macOS
cp qtools-admin/src/main/resources/secret.yml.example qtools-admin/src/main/resources/secret.yml
```

然后在 `secret.yml` 中填入以下键的真实值（占位符只是示例，请自行替换）：

| 键 | 用途 | 备注 |
| :--- | :--- | :--- |
| `spring.datasource.druid.master.password` | MySQL 密码 | 可用环境变量 `SPRING_DATASOURCE_DRUID_MASTER_PASSWORD` 覆盖 |
| `spring.data.redis.password` | Redis 口令 | 便携 Redis 默认无密码，**留空即可** |
| `token.secret` | 若依后台 JWT 签名密钥（`/login`、`sys_user`） | 模板附了随机生成命令；可用 `TOKEN_SECRET` 覆盖 |
| `qtools.app.jwt.secret` | 用户端（`/api/app/**`）独立 JWT 密钥 | **必须与 `token.secret` 不同**；缺失、短于 32 字节或与后台相同都会启动失败（`AppJwtService.java:48-66`） |

`secret.yml` 已被 `.gitignore` 排除（`.gitignore:63`；旧名 `application-secret.yml` 也仍在排除列表里，`.gitignore:52`），不会提交。部署到服务器时建议改用环境变量（见「配置说明」）。

### 4. 打包与启动

```bash
mvn -DskipTests -pl qtools-admin -am package     # 产物：qtools-admin/target/qtools-admin.jar
java -jar qtools-admin/target/qtools-admin.jar   # 默认 8080 端口
```

- `-am` 会连带构建 `qtools-admin` 依赖的其它模块；若只想构建单模块，需先把其它模块 `install` 到本地仓库。
- 若依自带的启停脚本也可使用：Linux/macOS 用 `./ry.sh start|stop|restart|status`，Windows 用 `ry.bat`（用法见文件内注释）。
  两个脚本的 `AppName` 已改为本项目产物名 `qtools-admin.jar`，把产物放到脚本同级目录即可。（脚本以 `java -jar qtools-admin.jar` 方式启动，jar 名由脚本内的 `AppName` 决定。）
- 默认管理员账号 **`admin / admin123`**（若依默认值），**首次登录后请立即修改密码**。

### 5. 验证

```bash
curl http://localhost:8080/api/toolInfo/list
curl -X POST http://localhost:8080/api/toolVisit/add -H "Content-Type: application/json" -d '{"toolId":1}'
curl http://localhost:8080/captchaImage
```

- 工具列表接口返回 `code: 200` 且 `data` 有 4 条数据，说明数据库与 `tool_info` 数据正常。
- 埋点接口返回 `code: 200` 后，可查 `tool_visit` 表确认记录写入。
- 接口文档：<http://localhost:8080/swagger-ui.html>；Druid 监控台：<http://localhost:8080/druid/>。

## 配置说明

### 配置文件清单

| 文件 | 作用 | 关键配置项 |
| :--- | :--- | :--- |
| `qtools-admin/src/main/resources/application.yml` | 主配置 | `server.port: 8080`、`spring.profiles.active: druid`、Redis（`localhost:6379` / db 0 / 无密码）、`token.header: Authorization`、`token.expireTime: 30`（分钟）、`springdoc`（`/swagger-ui.html`，扫描包 `com.qtools.web.controller.tool, com.qtools.tool.controller`）、`xss` 过滤（`urlPatterns: /system/*,/monitor/*,/tool/*`）、`qtools.profile`（上传目录） |
| `qtools-admin/src/main/resources/application-druid.yml` | 数据源与连接池 | 驱动 `com.mysql.cj.jdbc.Driver`、主库 `jdbc:mysql://localhost:${qtools.db.port:3307}/qtools?...`（**默认端口 3307，不是 3306**）、`initialSize: 5` / `minIdle: 10` / `maxActive: 20` / `maxWait: 60000`、慢 SQL 阈值 `slow-sql-millis: 1000`、Druid 监控台 `url-pattern: /druid/*`、`login-username: qtools` |
| `qtools-admin/src/main/resources/secret.yml`（本地创建，`.gitignore` 已排除，不入库） | 私密值 | MySQL 密码、Redis 口令（可为空）、`token.secret`、`qtools.app.jwt.secret` |
| `qtools-admin/src/main/resources/secret.yml.example` | 私密配置模板 | 上述四项的键名、占位符，以及 `token.secret` 的随机生成命令 |
| `qtools-admin/src/main/resources/logback.xml` | 日志 | 控制台彩色输出；文件日志写入相对目录 `logs/` |
| `qtools-admin/src/main/resources/banner.txt` | 启动横幅 | 显示应用版本（`qtools.version`）与 Spring Boot 版本 |

### 密钥外置与环境变量

主配置通过 `spring.config.import: optional:classpath:secret.yml`（`application.yml:132`）可选加载私密文件；文件缺失时不报错，但**缺少必填值会导致启动失败**（预期行为，避免误用默认密钥）。

部署时用环境变量替代私密文件（Spring Boot 宽松绑定：全部大写、`.` 换成 `_`、`-` 删除）：

| 配置项 | 本地（`secret.yml`） | 环境变量 |
| :--- | :--- | :--- |
| MySQL 密码 | `spring.datasource.druid.master.password` | `SPRING_DATASOURCE_DRUID_MASTER_PASSWORD` |
| Redis 口令 | `spring.data.redis.password` | `SPRING_DATA_REDIS_PASSWORD` |
| 后台 JWT 密钥 | `token.secret` | `TOKEN_SECRET` |
| 用户端 JWT 密钥 | `qtools.app.jwt.secret` | `QTOOLS_APP_JWT_SECRET` |

> Druid 监控台：`application-druid.yml` 只配置了 `login-username: qtools`（`application-druid.yml:50`），`secret.yml` 及模板里都**没有**控制台口令键——需要口令时请自行在 `application-druid.yml` 补 `statViewServlet.login-password` 并用环境变量注入；生产环境建议直接把 `statViewServlet.enabled` 关掉。

本仓库不保存任何真实密码：`application.yml` 与 `application-druid.yml` 中只有 URL、用户名与注释说明，密码一律由私密文件或环境变量注入。

### 日志与入口地址

- 日志：控制台带颜色（时间-绿、级别-高亮、线程-黄、类名-青）；文件日志写入相对工作目录的 `logs/`，为 `sys-info.log`、`sys-error.log`、`sys-user.log`（按天滚动，保留 60 天）。以 `java -jar` 启动时，日志目录即当前工作目录下的 `logs/`。
- 接口文档：`/swagger-ui.html`（OpenAPI JSON 在 `/v3/api-docs`）。
- Druid 监控台：`/druid/*`，用户名 `qtools`（`application-druid.yml:50`），**配置里没有设置 `login-password`**——需要口令时请自行补到 `application-druid.yml` 并用环境变量注入。
- 上述路径已在 `SecurityConfig` 中放行（`SecurityConfig.java:106`）。

## 换成你自己的内容

本项目出厂带的是「青兔在线工具箱」的站点名、工具列表与媒体内容。要换成自己的，按下面五件事走一遍即可，不用改代码逻辑：

| 想换什么 | 改哪里 | 是否要重建 |
| :--- | :--- | :--- |
| 站点名 / 标语 / 版权 | 前台 `QTools-C` 的 `src/utils/constants.js`（`APP_INFO`） | 要（前台） |
| 管理端标题 / 页脚 | `QTools-M` 的 `.env.*`（`VITE_APP_TITLE`）与 `src/settings.js`（`footerContent`） | 要（管理端） |
| 工具卡片 | 库表 `tool_info`（管理端「工具维护」增删改）；兜底数据在 `QTools-C/src/views/Home.vue` 的 `FALLBACK_TOOLS` | 改库不要，改兜底要 |
| 媒体内容 | 媒体根目录 + `doc/media-import/scan-media.js`（扫盘建库）+ `doc/media-import/build-thumbs.js`（缩略图） | 不要 |
| 私密值 / 部署根路径 | `secret.yml`（模板 `secret.yml.example`）、`-Dqtools.home` | 不要（重启生效） |

两个最容易踩的坑：

- 媒体内容用 `doc/media-import/scan-media.js`（扫盘 → 建库）与 `build-thumbs.js`（按库内记录出缩略图）两条命令，前者 `--truncate` 必须同时给 `--yes`，管理端「重建索引」会自动过滤掉 `--truncate`。
- 改完工具列表记得同步 `FALLBACK_TOOLS`，否则后端不可用时首页会显示**旧的**工具卡片。

字段级改造清单、媒体目录约定与两条导入命令、`qtools.media.*` 配置项对照表、`/api/media/admin/config` 自检、分步迁移 checklist，见 **[自定义与迁移指南](doc/自定义与迁移指南.md)**。

## 二次开发改动清单（相对若依）

相对上游 RuoYi-Vue-springboot3 3.9.2，本仓库的改动如下，接手时可据此判断哪些文件"和若依文档不一致"：

1. **模块与包重命名**：`ruoyi-*` → `qtools-*`，`com.ruoyi` → `com.qtools`（含各模块 `pom.xml` 的 `groupId`、`parent` 与源码目录）。父 POM 仍是 `com.qtools:qtools:3.9.2`。
2. **新增业务模块 `qtools-tool`**：工具列表与访问埋点两个匿名接口（`ToolInfoController` / `ToolVisitController`）、`tool_info` 与 `tool_visit` 两张表的建表脚本 `sql/qtools/tool_init.sql`。`qtools-admin` 已依赖该模块。
3. **修复重命名遗留**：验证码生成器实现类（`CaptchaConfig` 中的 `com.qtools.framework.config.KaptchaTextCreator`，`CaptchaConfig.java:66`）、`Constants` 的 JSON 白名单与定时任务白名单/违规黑名单（`Constants.java:161-172`）、`generator.yml` 的生成包名与作者（`generator.yml:4,6`）、`banner.txt` 的版本占位符（`banner.txt:1-2`）。
4. **日志**：控制台改为彩色输出；文件日志路径改为相对目录 `logs/`（`logback.xml:4`），不再使用上游以 `/` 开头的绝对路径。
5. **密钥外置**：数据库密码、Redis 口令、后台 `token.secret`、用户端 `qtools.app.jwt.secret` 从 `secret.yml`（`.gitignore` 已排除，旧名 `application-secret.yml` 也在排除列表）或环境变量注入，配置文件中不留真实值。私密配置文件名由 `application-secret.yml` 改为 `secret.yml`，模板同步改名为 `secret.yml.example`。
6. **启停脚本**：`ry.sh` / `ry.bat` 的 `AppName` 由上游的 `ruoyi-admin.jar` 改为本项目产物名 `qtools-admin.jar`，脚本可直接使用。

## 常见问题

1. **启动或首次访问数据库报 `Access denied for user 'root'@'localhost'`**
   没有 `secret.yml` 也没设环境变量，Druid 拿到的密码为空。按「快速开始 · 3」补齐数据库密码；确认 `application-druid.yml` 中的库名/用户名与本地一致。
2. **启动报 `Could not resolve placeholder 'token.secret'`**
   `TokenService` 用 `@Value("${token.secret}")` 且没有默认值（`TokenService.java:42`），密钥缺失时启动中断。在 `secret.yml` 里填 `token.secret`，或设置环境变量 `TOKEN_SECRET`。
3. **验证码 / 登录接口报 Redis 连接异常**
   本地没启动 Redis。默认连接 `localhost:6379`、db 0、口令为空（`application.yml:155-177`）；Redis 不在本机或改过端口时改这里。应用启动本身不依赖 Redis，两个匿名接口也不经过 Redis。
4. **Druid 监控台 `/druid/*` 安全**
   生产环境建议把 `spring.datasource.druid.statViewServlet.enabled` 设为 `false`，或至少配置 `allow` 白名单/限制来源 IP；默认用户名 `qtools`，配置中未设 `login-password`（生产环境更应直接关闭监控台）。
5. **新增公开接口在哪放行？**
   在控制器类或方法上标注 `@Anonymous` 即可，`PermitAllUrlProperties` 会自动把该 URL 加入 Spring Security 的 `permitAll` 白名单；不要为了单个业务接口放宽 `SecurityConfig` 的整体匹配规则。

## 许可与致谢

- 本项目基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)（springboot3 分支，v3.9.2）二次开发，遵循 **MIT License**（见仓库根目录 `LICENSE`，版权声明保留上游 `Copyright (c) 2018 RuoYi`）。
- 感谢若依（RuoYi）作者与社区提供的开源后台框架；使用本仓库时请同样保留上游的版权与许可声明。
