const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "../app/src/main/assets/fingerprint-privacy");
const read = name => fs.readFileSync(path.join(root, name), "utf8");

test("fingerprint privacy extension is an MV3 module with local-only permissions", () => {
  const manifest = JSON.parse(read("manifest.json"));
  assert.equal(manifest.manifest_version, 3);
  assert.deepEqual(manifest.permissions.sort(), ["storage", "tabs"]);
  assert.ok(manifest.content_scripts.some(item => item.js.includes("content-main.js") && item.world === "MAIN"));
  assert.ok(manifest.content_scripts.some(item => item.js.includes("bridge.js")));
});

test("fingerprint module sources parse and do not contain network interception", () => {
  for (const name of ["content-main.js", "bridge.js", "popup.js"]) {
    new Function(read(name));
    const source = read(name);
    assert.doesNotMatch(source, /(?:window\.)?fetch\s*=\s*/i, name);
    assert.doesNotMatch(source, /XMLHttpRequest\s*\.prototype/i, name);
    assert.doesNotMatch(source, /chrome\.(webRequest|debugger|declarativeNetRequest)/i, name);
  }
});

test("fingerprint module is explicit about local-only behavior", () => {
  assert.match(read("content-main.js"), /localOnly:\s*true/);
  assert.match(read("README.md"), /不上传网络/);
  assert.match(read("README.md"), /不伪造 User-Agent/);
});
