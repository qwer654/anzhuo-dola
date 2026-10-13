const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const policy = JSON.parse(fs.readFileSync(path.join(assets, 'RUNTIME-SOURCE-POLICY.json'), 'utf8'));
const baseline = 'dola30-source-v1.6.14';
test('new attachment is a complete byte-exact source baseline with exactly two active scripts', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(assets, baseline, 'SOURCE-MANIFEST.json'), 'utf8'));
  assert.equal(manifest.version, '1.6.14');assert.equal(manifest.preservedUnmodified, true);
  assert.deepEqual(policy.activeDocumentStartScripts, [baseline+'/skill-pack-main.js', baseline+'/media-upload-main.js']);
  assert.deepEqual(manifest.activeDocumentStartScripts, ['skill-pack-main.js','media-upload-main.js']);
  assert.deepEqual(manifest.notInjected, ['content-panel.js','service-worker.js']);
  assert.deepEqual(policy.activeDocumentIdleScripts,[baseline+'/duration-main.js']);
  assert.deepEqual(manifest.activeDocumentIdleScripts,['duration-main.js']);
  for(const entry of policy.nativePageAdapterFiles){const bytes=fs.readFileSync(path.join(assets,entry.path));assert.equal(bytes.length,entry.bytes);assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),entry.sha256);}
  for (const entry of manifest.files) {
    const bytes=fs.readFileSync(path.join(assets,baseline,entry.path));
    assert.equal(bytes.length,entry.bytes);assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),entry.sha256);
  }
});
test('all inactive JS remains available as hash-checked backup, without executing it', () => {
  assert.ok(policy.retiredAndNonInjectedJavaScript.length > 10);
  for (const entry of policy.retiredAndNonInjectedJavaScript) {
    assert.equal(entry.runtime, 'backup-only');assert.ok(!policy.activeDocumentStartScripts.includes(entry.path));
    const bytes=fs.readFileSync(path.join(assets,entry.path));
    assert.equal(bytes.length,entry.bytes);assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),entry.sha256);
  }
});
test('production has one new-only document-start chain and no legacy JS execution path', () => {
  const java=fs.readFileSync(path.join(root,'app/src/main/java/com/doulala/android/BrowserActivity.java'),'utf8');
  const helper=fs.readFileSync(path.join(root,'app/src/main/java/com/doulala/android/DolaPageScripts.java'),'utf8');
  assert.equal((java.match(/WebViewCompat\.addDocumentStartJavaScript\(/g)||[]).length,1);
  assert.match(java,/readAsset\(DolaPageScripts.SKILL_ASSET\)/);assert.match(java,/readAsset\(DolaPageScripts.UPLOAD_ASSET\)/);
  assert.doesNotMatch(java,/readAsset\("/);
  assert.doesNotMatch(java,/window\.(?:__dolaAndroidDrain|__dolaAndroidScan|dola30Android|setDola30s|rewriteDolaTimeline)/);
  assert.doesNotMatch(helper,/dola30-user-source-v1\.6\.9|dola30-original|content-panel\.js|service-worker\.js/);
  assert.match(helper,/dola30NewPageScripts/);assert.match(helper,/dola30-source-v1\.6\.14\/skill-pack-main\.js/);
  assert.doesNotMatch(java,/旧 Dola30 JS：仅备份，不执行/); // No retired-script control in current UI.
});
test('active scripts require no Chrome background API and Chrome capabilities remain unavailable', () => {
  for (const file of policy.activeDocumentStartScripts) assert.doesNotMatch(fs.readFileSync(path.join(assets,file),'utf8'),/\bchrome\s*\./);
  assert.equal(policy.chromeDebugger,'not-supported');assert.equal(policy.extensionPanel,'not-injected');
});
