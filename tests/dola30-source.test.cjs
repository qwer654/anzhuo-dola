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
