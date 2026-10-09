const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

const file = "../app/src/main/assets/dola30-android-adapter.js";
const source = fs.readFileSync(require("node:path").join(__dirname, file), "utf8");

test("Android adapter exposes the local 30S and prompt-tool APIs", () => {
  assert.match(source, /root\.setDola30s\s*=\s*setDola30s/);
  assert.match(source, /root\.getDola30sStatus\s*=\s*getDola30sStatus/);
  assert.match(source, /root\.rewriteDolaTimeline\s*=\s*rewriteDolaTimeline/);
  assert.match(source, /androidWebView\s*:\s*true/);
});

test("Android adapter does not install request interception or Chrome APIs", () => {
  assert.doesNotMatch(source, /(?:window\.)?fetch\s*=\s*/i);
  assert.doesNotMatch(source, /XMLHttpRequest\s*\.prototype/i);
  assert.doesNotMatch(source, /chrome\s*\./i);
  assert.match(source, /networkRewrite\s*:\s*false/);
  assert.match(source, /autoSubmit\s*:\s*false/);
});
