# 短链服务 · 前后端接口契约（P6.264）

> 前端按此文档开发（前期用 mock），后端按此文档实现。
> 任何字段变动先改这里，再改两端。

## 1. 数据模型（已建表）

```sql
short_link(id, short_code, long_url, url_hash, user_id, password, expire_at, status, clicks, create_time)
short_link_stat(id, short_code, day, pv, uv_estimate)
```

**status 语义统一为：`'0' = 正常（启用）`，`'1' = 停用（禁用）`。**
字符串状态码，禁止当布尔用：前端比较 `status === '0'`，后端比较 `"0".equals(status)`。

## 2. 接口清单

| # | 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/short/create` | App 用户 | 创建短链（幂等） |
| 2 | GET | `/api/short/mine` | App 用户 | 我的短链（分页） |
| 3 | POST | `/api/short/{code}/status` | 本人 | 启用/停用 `{status:"0"|"1"}` |
| 4 | DELETE | `/api/short/{code}` | 本人 | 删除 |
| 5 | GET | `/s/{code}` | 公开 | 302 跳转（带密码时先给表单） |

统一响应：RuoYi `AjaxResult`，即 `{ code, msg, data }`；分页用 `{ code, msg, rows, total }`。

### 2.1 创建

```http
POST /api/short/create
Content-Type: application/json

{ "longUrl": "https://example.com/a?b=1",
  "alias": "promo",        // 可选：自定义短码（字母数字，3-16 位）
  "expireDays": 30,        // 可选：0 或省略 = 永久
  "password": "" }         // 可选：访问密码（明文传入，服务端存 sha256）
```

```json
{ "code": 200, "msg": "操作成功", "data": {
    "shortCode": "Ab3xK9", "shortUrl": "https://qtools.com/s/Ab3xK9",
    "longUrl": "https://example.com/a?b=1",
    "status": "0", "enabled": true,
    "expireAt": "2026-12-04 14:00:00", "hasPassword": false } }
```

| 错误 | code | msg |
|---|---|---|
| 链接不合法（非 http/https 或超过 2048） | 400 | 长链接不合法 |
| 别名被占用 / 格式不对 | 400 | 别名已被占用 / 别名只能是 3-16 位字母数字 |
| 频率超限 | 429 | 创建过于频繁，请稍后再试 |

**幂等**：同一用户、同一长链接重复提交，返回**同一个** `shortCode`（不新建行）。

### 2.2 列表

```http
GET /api/short/mine?pageNum=1&pageSize=10
```

```json
{ "code": 200, "total": 12, "rows": [
  { "shortCode": "Ab3xK9", "shortUrl": "https://qtools.com/s/Ab3xK9",
    "longUrl": "...", "clicks": 137, "status": "0", "enabled": true,
    "hasPassword": false, "expireAt": null, "createTime": "2026-11-04 14:00:00" } ] }
```

### 2.3 启用 / 停用

```http
POST /api/short/Ab3xK9/status   { "status": "1" }
→ { "code": 200 }
```

### 2.4 删除

```http
DELETE /api/short/Ab3xK9 → { "code": 200 }
```

### 2.5 公开跳转

```http
GET /s/Ab3xK9
→ 302 Location: <longUrl>      // 正常
→ 404  短码不存在
→ 410  已过期或已停用
→ 200  text/html               // 该链有密码：返回表单，POST /s/Ab3xK9 校验后 302
```

## 3. 前端约定

- 页面路径：`/s`（创建 + 我的列表同页，上下两块）
- 请求封装：`src/api/short.js`
- 前端只读 `enabled` 布尔，**不判断 `status` 字符串**
- 显示域名：`window.location.origin + '/s/' + shortCode`

## 4. nginx 约定（必须）

`/s/` **不能**落到 SPA 回退，否则短码会被当成前端路由：

```nginx
location ^~ /s/ { proxy_pass http://backend:8080; include /etc/nginx/snippets/qtools-proxy.conf; }
```
