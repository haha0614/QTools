/**
 * normalize-tags.js 单测（P6.03）：映射表 schema 校验 + 原子解析 + 目标态计划
 *
 * 跑法：node --test QTools/doc/media-import/normalize-tags.test.js
 *
 * 为什么这些断言值得写：迁移脚本一旦把「非法映射」当成「没有映射」静默放过，
 * 写库后就是**标签丢失**（旧标签被删、新标签没建）。所以校验必须逐条报错，
 * 而且错误信息要指到具体的键 / 下标 / 实际值 —— 下面每条都断言了关键词。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { loadMap, atomResolver, buildPlan, describeOldTags, sqlStr, SCHEMA_ID } = require('./normalize-tags.js');

let seq = 0;
/** 把 JSON 内容落成临时文件再走 loadMap，保证测的是真实的「读文件 + 解析 + 校验」链路 */
function writeMap(obj) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tagmap-'));
  const file = path.join(dir, 'm' + (seq++) + '.json');
  fs.writeFileSync(file, typeof obj === 'string' ? obj : JSON.stringify(obj), 'utf8');
  return file;
}

function expectBad(obj, keyword) {
  assert.throws(() => loadMap(writeMap(obj)), (e) => {
    assert.match(e.message, /tag-map 校验失败/);
    assert.ok(e.message.includes(keyword), '错误信息应包含 ' + keyword + '，实际是：' + e.message);
    return true;
  });
}

/* ---------------------------------------------------------------- 1. schema 校验 */

test('1.1 扩展式映射表解析：mapping / facets / drop / keep 全部读出来', () => {
  const m = loadMap(writeMap({
    $schema: SCHEMA_ID,
    facets: { 'cos': '题材' },
    mapping: { 'cos热舞': ['cos', '热舞'] },
    drop: ['E2E验证'],
    keep: ['ASMR'],
  }));
  assert.equal(m.schema, SCHEMA_ID);
  assert.deepEqual(m.mapping['cos热舞'], ['cos', '热舞']);
  assert.deepEqual(m.drop, ['E2E验证']);
  assert.deepEqual(m.keep, ['ASMR']);
  assert.equal(m.facets['cos'], '题材');
});

test('1.2 裸映射写法也收（{ "cos热舞": ["cos","热舞"] }），$ 开头的键当注释跳过', () => {
  const m = loadMap(writeMap({ $schema: SCHEMA_ID, 'cos热舞': ['cos', '热舞'] }));
  assert.equal(m.schema, 'bare');
  assert.deepEqual(m.mapping['cos热舞'], ['cos', '热舞']);
  assert.ok(!('$schema' in m.mapping));
});

test('1.3 值不是数组 → 报错指名键与实际类型', () => {
  expectBad({ mapping: { 'cos热舞': 'cos,热舞' } }, 'cos热舞');
  assert.throws(() => loadMap(writeMap({ mapping: { a: 'b' } })), /值必须是字符串数组，实际是 string/);
});

test('1.4 空数组 → 报错并提示用 drop 表达删除', () => {
  assert.throws(() => loadMap(writeMap({ mapping: { 'cos热舞': [] } })), /数组为空.*drop/);
});

test('1.5 目标名超过 32 字符（media_tag.name 上限）→ 报错', () => {
  assert.throws(() => loadMap(writeMap({ mapping: { 'x': ['超'.repeat(33)] } })), /32 字符上限/);
});

test('1.6 目标项不是字符串 / 是空串 / 首尾有空格 → 逐个报错', () => {
  assert.throws(() => loadMap(writeMap({ mapping: { a: ['ok', 42] } })), /第 2 项必须是字符串/);
  assert.throws(() => loadMap(writeMap({ mapping: { a: ['  '] } })), /是空字符串/);
  assert.throws(() => loadMap(writeMap({ mapping: { a: [' b'] } })), /首尾有空格/);
});

test('1.7 同名同时出现在 mapping 与 drop、keep 与 drop → 报错', () => {
  assert.throws(() => loadMap(writeMap({ mapping: { a: ['b'] }, drop: ['a'] })), /同时出现在 mapping 与 drop/);
  assert.throws(() => loadMap(writeMap({ keep: ['x'], drop: ['x'] })), /同时在 keep 与 drop/);
});

test('1.8 $schema 不是 qtools-tag-map/1 → 报错', () => {
  assert.throws(() => loadMap(writeMap({ $schema: 'v2', mapping: { a: ['b'] } })), /\$schema 必须是/);
});

test('1.9 JSON 语法错误 / 文件不存在 → 可读错误', () => {
  assert.throws(() => loadMap(writeMap('{ "a": ')), /解析失败/);
  assert.throws(() => loadMap(path.join(os.tmpdir(), 'no-such-tag-map-' + Date.now() + '.json')), /读不到/);
});

test('1.10 facets / keep / drop 类型非法 → 各自报错', () => {
  assert.throws(() => loadMap(writeMap({ mapping: {}, facets: [] })), /facets 必须是对象/);
  assert.throws(() => loadMap(writeMap({ mapping: {}, keep: 'ASMR' })), /keep 必须是字符串数组/);
  assert.throws(() => loadMap(writeMap({ mapping: {}, drop: [1] })), /drop 的第 1 项/);
});

/* ---------------------------------------------------------------- 2. 原子解析 */

test('2.1 组合标签拆成多个原子、保持顺序', () => {
  const map = loadMap(writeMap({ mapping: { 'cos热舞': ['cos', '热舞'] } }));
  assert.deepEqual(atomResolver(map)('cos热舞'), ['cos', '热舞']);
});

test('2.2 未被映射的名字就是它自己；drop 的名字解析成空数组', () => {
  const map = loadMap(writeMap({ mapping: { a: ['b'] }, drop: ['E2E验证'] }));
  const r = atomResolver(map);
  assert.deepEqual(r('黑丝'), ['黑丝']);
  assert.deepEqual(r('E2E验证'), []);
});

test('2.3 映射可以指向另一个映射键（传递解析），并检测环', () => {
  const chain = loadMap(writeMap({ mapping: { '黑丝高跟美腿': ['黑丝高跟'], '黑丝高跟': ['黑丝', '高跟'] } }));
  assert.deepEqual(atomResolver(chain)('黑丝高跟美腿'), ['黑丝', '高跟']);

  const cycle = atomResolver(loadMap(writeMap({ mapping: { a: ['b'], b: ['a'] } })));
  assert.throws(() => cycle('a'), /映射存在环：a -> b -> a/);
});

/* ---------------------------------------------------------------- 3. 目标态计划 */

/** 造一份最小现状：dict 只用于报告，plan 只吃 rows */
function stateOf(rows) {
  // 第 4 项是 tag_id（不给 = 历史 facet 行的 NULL）
  return { dict: [], rows: rows.map((r) => ({ videoId: r[0], facet: r[1], name: r[2], tagId: r[3] == null ? null : r[3] })), videoTotal: 1, albumTagReady: false };
}

test('3.1 组合标签：删掉旧行、补上两个原子行（facet 取映射表声明）', () => {
  const map = loadMap(writeMap({
    facets: { 'cos': '题材', '热舞': '题材' },
    mapping: { 'cos热舞': ['cos', '热舞'] },
  }));
  const state = stateOf([['v1', '标签', 'cos热舞', null]]);
  const plan = buildPlan(state, map, atomResolver(map));
  assert.deepEqual(plan.targetByVideo.get('v1'), [{ name: 'cos', facet: '题材' }, { name: '热舞', facet: '题材' }]);
  assert.deepEqual(plan.changedVideos, ['v1']);
  assert.equal(plan.deleteRows, 1);
  assert.equal(plan.insertRows, 2);
});

test('3.2 同一概念挂在多个 facet 下 → 收敛成一行（原子化：一个标签一种含义）', () => {
  const map = loadMap(writeMap({ facets: { ASMR: '题材' } }));
  const state = stateOf([['v1', '分类', 'ASMR', null], ['v1', '类型', 'ASMR', null], ['v1', '题材', 'ASMR', null]]);
  const plan = buildPlan(state, map, atomResolver(map));
  assert.deepEqual(plan.targetByVideo.get('v1'), [{ name: 'ASMR', facet: '题材' }]);
  assert.equal(plan.deleteRows, 3);
  assert.equal(plan.insertRows, 1);
});

test('3.3 目标态与现状一致 → 0 变更（幂等的根据）', () => {
  const map = loadMap(writeMap({ facets: { ASMR: '题材' } }));
  const state = stateOf([['v1', '题材', 'ASMR', 9]]);
  const plan = buildPlan(state, map, atomResolver(map));
  assert.deepEqual(plan.changedVideos, []);
  assert.equal(plan.deleteRows, 0);
  assert.equal(plan.insertRows, 0);
});

test('3.4 合并 / 拆分后的重复原子只保留一次（去重保序）', () => {
  const map = loadMap(writeMap({
    facets: { cos: '题材', 热舞: '题材' },
    mapping: { 'cos热舞': ['cos', '热舞'], 跳舞: ['热舞'] },
  }));
  const state = stateOf([['v1', '标签', 'cos热舞', null], ['v1', '题材', 'cos', null], ['v1', '分类', '跳舞', null]]);
  const plan = buildPlan(state, map, atomResolver(map));
  assert.deepEqual(plan.targetByVideo.get('v1'), [{ name: 'cos', facet: '题材' }, { name: '热舞', facet: '题材' }]);
});

test('3.5 drop 的标签清空后视频不带任何标签（不是「保留旧行」）', () => {
  const map = loadMap(writeMap({ mapping: {}, drop: ['E2E验证'] }));
  const state = stateOf([['v1', '标签', 'E2E验证', null]]);
  const plan = buildPlan(state, map, atomResolver(map));
  assert.deepEqual(plan.targetByVideo.get('v1'), []);
  assert.deepEqual(plan.changedVideos, ['v1']);
  assert.equal(plan.insertRows, 0);
});

test('3.6 标签行不用改、但字典里有 0 引用孤儿 → 仍判定有工作（否则孤儿永远删不掉）', () => {
  const map = loadMap(writeMap({ facets: { ASMR: '题材' }, keep: ['白名单'] }));
  const state = stateOf([['v1', '题材', 'ASMR', 9]]);
  state.dict = [
    { id: 9, name: 'ASMR', parentId: null, useCount: 1 },
    { id: 10, name: '孤儿', parentId: null, useCount: 0 },
    { id: 11, name: '白名单', parentId: null, useCount: 0 },
  ];
  const plan = buildPlan(state, map, atomResolver(map));
  assert.deepEqual(plan.changedVideos, []);
  assert.deepEqual(plan.deleteTags, ['孤儿']);
  assert.equal(plan.hasWork, true);
});

test('3.7 迁移后才会空掉的旧标签单列一项（预览里必须提前看到）', () => {
  const map = loadMap(writeMap({ facets: { cos: '题材', 热舞: '题材' }, mapping: { cos热舞: ['cos', '热舞'] } }));
  const state = stateOf([['v1', '标签', 'cos热舞', null]]);
  state.dict = [
    { id: 1, name: 'cos热舞', parentId: null, useCount: 1 },
    { id: 2, name: 'cos', parentId: null, useCount: 0 },
    { id: 3, name: '热舞', parentId: null, useCount: 0 },
  ];
  const plan = buildPlan(state, map, atomResolver(map));
  // 现在就 0 引用的是 cos / 热舞（它们马上会被重建出来的行引用，实际不会删）
  assert.deepEqual(plan.deleteTags, ['cos', '热舞']);
  // 「迁移后会空掉」的才是 cos热舞
  assert.deepEqual(plan.projectedDeleteTags, ['cos热舞']);
});

test('3.8 收敛后的库：hasWork=false（连跑第二遍 0 变更的判定依据）', () => {
  const map = loadMap(writeMap({ facets: { ASMR: '题材' } }));
  const state = stateOf([['v1', '题材', 'ASMR', 9]]);
  state.dict = [{ id: 9, name: 'ASMR', parentId: null, useCount: 1 }];
  const plan = buildPlan(state, map, atomResolver(map));
  assert.equal(plan.hasWork, false);
  assert.equal(plan.backfillRows, 0);
  assert.deepEqual(plan.projectedDeleteTags, []);
});

test('3.9 历史行 tag_id 为空 → backfillRows 计入工作量（否则 use_count 永远算不准）', () => {
  const map = loadMap(writeMap({ facets: { ASMR: '题材' } }));
  const state = stateOf([['v1', '题材', 'ASMR', null]]);
  state.dict = [{ id: 9, name: 'ASMR', parentId: null, useCount: 1 }];
  const plan = buildPlan(state, map, atomResolver(map));
  assert.equal(plan.backfillRows, 1);
  assert.equal(plan.hasWork, true);
});

/* ---------------------------------------------------------------- 4. 报告与转义 */

test('4.1 处置分类：保留 / 合并 / 拆分 / 删除 + 影响面', () => {
  const map = loadMap(writeMap({
    facets: { cos: '题材', 热舞: '题材' },
    mapping: { 'cos热舞': ['cos', '热舞'], 'nana': ['娜娜'] },
    drop: ['E2E验证'],
  }));
  const resolve = atomResolver(map);
  const state = stateOf([
    ['v1', '标签', 'cos热舞', null],
    ['v2', '标签', 'cos热舞', null],
    ['v3', '分类', 'nana', null],
    ['v4', '标签', 'E2E验证', null],
    ['v5', '题材', '黑丝', null],
  ]);
  const plan = buildPlan(state, map, resolve);
  const byName = new Map(describeOldTags(state, map, resolve, plan.facetOf).map((x) => [x.name, x]));
  assert.equal(byName.get('cos热舞').disposition, 'split');
  assert.equal(byName.get('cos热舞').videos, 2);
  assert.deepEqual(byName.get('cos热舞').to, ['cos', '热舞']);
  assert.equal(byName.get('nana').disposition, 'merge');
  assert.equal(byName.get('E2E验证').disposition, 'drop');
  assert.equal(byName.get('黑丝').disposition, 'keep');
});

test('4.2 迁移完成后映射里的键在库里已不存在 → 标记 done（第二遍报告不误导）', () => {
  const map = loadMap(writeMap({ mapping: { 'cos热舞': ['cos'] } }));
  const resolve = atomResolver(map);
  const state = stateOf([['v1', '题材', 'cos', null]]);
  const plan = buildPlan(state, map, resolve);
  const rows = describeOldTags(state, map, resolve, plan.facetOf);
  const done = rows.find((x) => x.name === 'cos热舞');
  assert.equal(done.disposition, 'done');
  assert.equal(done.videos, 0);
});

test('5.1 sqlStr 转义单引号 / 反斜杠 / 换行，别让标签名把 SQL 打断', () => {
  const q = String.fromCharCode(39);
  const bs = String.fromCharCode(92);
  assert.equal(sqlStr('a' + q + 'b'), q + 'a' + bs + q + 'b' + q);
  assert.equal(sqlStr('c' + bs + 'd'), q + 'c' + bs + bs + 'd' + q);
  assert.equal(sqlStr('e' + String.fromCharCode(10) + 'f'), q + 'e' + bs + 'nf' + q);
  assert.equal(sqlStr(null), 'NULL');
});
