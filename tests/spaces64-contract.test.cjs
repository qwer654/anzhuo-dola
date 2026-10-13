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
  for (const label of ['回到空间列表', '当前IP', '导入CK', '清理空间', '浏览器菜单']) assert.match(browser, new RegExp(label));
  assert.match(browser, /本空间新脚本30s开关/);
  for (const oldLabel of ['隐私洁净模式：关闭', '旧 Dola30 JS：仅备份，不执行']) assert.doesNotMatch(browser, new RegExp(oldLabel));
  for (const menuLabel of ['30S 工具', '创作工作台', '新建标签', '回到首页', '添加当前页书签', '打开书签', '下载中心', 'Cookie \/ CK 导入导出', '登录与空间说明', '回到空间列表']) assert.match(browser, new RegExp(menuLabel));
});

test('native login opens Dola official flow, never a standalone Google account page',()=>{ assert.match(browser,/Dola 官网登录/); assert.match(browser,/Dola登录/); assert.match(browser,/officialLoginUrl\(\)/); assert.doesNotMatch(browser,/load\("https:\/\/accounts\.google\.com/); });

test('CK import safety precedes writes and targeted reset releases only its binding',()=>{
  const transfer=fs.readFileSync(path.join(javaDir,'CookieTransfer.java'),'utf8');
  const guard=fs.readFileSync(path.join(javaDir,'CkImportBindings.java'),'utf8');
  const reset=fs.readFileSync(path.join(javaDir,'SpaceReset.java'),'utf8');
  assert.match(browser,/new CookieTransfer\(this,[^;]+spaceSlot\(\)\)/);
  assert.match(transfer,/bindings\.reserve\(spaceSlot,pairs\)/);
  assert.match(transfer,/拒绝跨空间重复导入 CK/);
  assert.match(transfer,/安卓根因与固定目录能否解决尚未证实/);
  assert.match(guard,/HmacSHA256/);assert.match(guard,/ATOMIC_MOVE/);assert.match(guard,/tryLock\(\)/);
  assert.match(transfer,/getNoBackupFilesDir\(\)/);assert.match(reset,/ck-import-bindings[^;]+release\(slot\)/);
  assert.doesNotMatch(guard,/samantha_web_web_id|s_v_web_id|localStorage|device_id|CookieManager/);
});
