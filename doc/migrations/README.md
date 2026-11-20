# 数据库迁移（doc/migrations）

## 为什么有这个目录

后端代码会引用新列（`video_ratios`、`orientation` …），但这些 DDL 原来**没随代码提交**。
另一台机器 pull 下来后，mapper 里一 `select` 就报
`Unknown column 'xxx' in 'field list'` —— 而且往往挂在 facets / 列表这类**一次返回整包数据**的接口上，
表现是整个页面的筛选、计数全空，不像是缺列，已经踩过两回：

- 2026-11-19 `video_ratios`：详情接口整体 500
- 2026-11-20 `orientation`：分类、已入库计数全部变空

所以：**凡是要动表结构，DDL 必须进这个目录，并和引用它的代码一起提交。**

## 规则

1. **文件名**：`YYYY-MM-DD-<做了什么>.sql`，按日期排序执行
2. **文件开头必须写 `use qtools;`** —— `create index` 这类语句需要默认库，否则报 `No database selected`
3. **必须幂等**：重复执行不报错（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，用 `information_schema` 判断后 `PREPARE`，见下面的例子）
4. **只加不删**：删列/改类型需要单独评审，不在这个目录的常规流程里
5. **每个文件顶部注释写清**：为什么加、谁在用、对应的代码位置

## 怎么执行

```powershell
# 密码从 secret.yml 取，不要写进脚本
$pw = (Select-String -Path 'D:\project\QTools-All\QTools\qtools-admin\src\main\resources\secret.yml' -Pattern 'password').Line
$env:MYSQL_PWD = '...'

foreach ($f in Get-ChildItem 'D:\project\QTools-All\QTools\doc\migrations' -Filter '*.sql' | Sort-Object Name) {
    & 'D:\project\QTools-All\tools\mysql\bin\mysql.exe' -h127.0.0.1 -P3307 -uroot --protocol=TCP --default-character-set=utf8mb4 -e "source $($f.FullName)"
}
```

## 怎么核对（pull 之后建议跑一次）

```sql
-- 看关键列在不在
select column_name, data_type
  from information_schema.columns
 where table_schema = 'qtools' and table_name = 'tool_web_post'
   and column_name in ('video_ratios', 'orientation');
```

两行都出来才算齐；少哪列就补跑对应的迁移文件。

## 幂等写法模板

```sql
set @ddl := (
  select if(count(*) = 0,
            'alter table tool_web_post add column 新列 类型 null after 某列',
            'do 0')
    from information_schema.columns
   where table_schema = 'qtools' and table_name = 'tool_web_post' and column_name = '新列'
);
prepare s from @ddl; execute s; deallocate prepare s;
```