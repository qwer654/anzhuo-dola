const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const root = path.resolve(__dirname, "../app/src/main/assets/dola30-original");
test("original Dola30 JS assets are present and match their source manifest", () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "SOURCE-MANIFEST.json"), "utf8"));
  assert.equal(manifest.preservedUnmodified, true);
  assert.ok(manifest.files.length >= 10);
  for (const entry of manifest.files) {
    const file = path.join(root, entry.path);
    const bytes = fs.readFileSync(file);
    assert.equal(bytes.length, entry.bytes, entry.path);
    assert.equal(crypto.createHash("sha256").update(bytes).digest("hex"), entry.sha256, entry.path);
  }
});

test("user-supplied Dola30 v1.6.9 scripts are preserved as a separate, non-executed source baseline", () => {
  const baseline = path.resolve(__dirname, "../app/src/main/assets/dola30-user-source-v1.6.9");
  const manifest = JSON.parse(fs.readFileSync(path.join(baseline, "SOURCE-MANIFEST.json"), "utf8"));
  const upstream = JSON.parse(fs.readFileSync(path.join(baseline, "manifest.json"), "utf8"));
  assert.equal(manifest.version, "1.6.9");
  assert.equal(manifest.preservedUnmodified, true);
  const declared = [...new Set([
    ...upstream.content_scripts.flatMap(script => script.js || []),
    upstream.background?.service_worker
  ].filter(Boolean))].sort();
  assert.deepEqual([...manifest.manifestDeclaredJavaScript].sort(), declared);
  assert.deepEqual(manifest.withheldFromPublicBundle, [upstream.background.service_worker]);
  const packaged = declared.filter(name => !manifest.withheldFromPublicBundle.includes(name));
  for (const name of ["manifest.json", ...packaged]) assert.ok(manifest.files.some(entry => entry.path === name), `missing hash record: ${name}`);
  assert.equal(manifest.files.some(entry => entry.path === upstream.background.service_worker), false);
  for (const entry of manifest.files) {
    const bytes = fs.readFileSync(path.join(baseline, entry.path));
    assert.equal(bytes.length, entry.bytes, `v1.6.9 ${entry.path}`);
    assert.equal(crypto.createHash("sha256").update(bytes).digest("hex"), entry.sha256, `v1.6.9 ${entry.path}`);
  }
});
