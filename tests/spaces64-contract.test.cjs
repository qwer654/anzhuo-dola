const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const javaDir = path.join(root, 'app/src/main/java/com/doulala/android');
const slots = fs.readFileSync(path.join(javaDir, 'SpaceSlots.java'), 'utf8');
const browser = fs.readFileSync(path.join(javaDir, 'BrowserActivity.java'), 'utf8');
const manifest = fs.readFileSync(path.join(root, 'app/src/main/AndroidManifest.xml'), 'utf8');

test('64 slots have stable private activity/process declarations', () => {
  assert.match(slots, /static\s+final\s+int\s+COUNT\s*=\s*64/);
  assert.match(slots, /slot\s*<\s*0\s*\|\|\s*slot\s*>=\s*COUNT/);
  assert.match(slots, /return\s+"space"\s*\+\s*slot/);
  const rows = [...manifest.matchAll(/BrowserActivity\$Space(\d+)[^>]*android:exported="false"[^>]*android:process=":space(\d+)"/g)];
  assert.equal(rows.length, 64);
  assert.deepEqual(rows.map(m => Number(m[1])), [...Array(64).keys()]);
  assert.deepEqual(rows.map(m => Number(m[2])), [...Array(64).keys()]);
});

test('nested activities and ergonomic browser actions are present', () => {
  const nested = [...browser.matchAll(/class\s+Space(\d+)\s+extends\s+BrowserActivity/g)].map(m => Number(m[1]));
  assert.equal(nested.length, 64);
  assert.deepEqual(nested, [...Array(64).keys()]);
  for (const label of ['回到空间列表', '当前IP', '导入CK', '管理标签', '浏览器菜单']) assert.match(browser, new RegExp(label));
  assert.match(browser, /本空间新脚本30s开关/);
  for (const oldLabel of ['隐私洁净模式：关闭', '旧 Dola30 JS：仅备份，不执行']) assert.doesNotMatch(browser, new RegExp(oldLabel));
  for (const menuLabel of ['30S 工具', '创作工作台', '新建标签', '回到首页', '添加当前页书签', '打开书签', '下载中心', 'Cookie \/ CK 导入导出', '登录与空间说明', '回到空间列表']) assert.match(browser, new RegExp(menuLabel));
});
