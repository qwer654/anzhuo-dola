/* Real MV3 runtime test against our own loopback server, never a user's profile. */
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { chromium } = require("playwright");
const output = path.resolve(__dirname, "../.tooling/browser-test");
function record(result) {
  fs.mkdirSync(output, { recursive: true });
  fs.writeFileSync(path.join(output, "result.json"), JSON.stringify({ generatedAt: new Date().toISOString(), ...result }, null, 2));
}

async function main() {
  const extension = path.resolve(__dirname, "../app/src/main/assets/fingerprint-privacy");
  record({ ok: false, status: "running" });
  const profile = fs.mkdtempSync(path.join(output, "profile-"));
  const checks = [];
  const server = http.createServer((req, res) => {
    res.setHeader("Cache-Control", "no-store");
    if (req.url.startsWith("/echo")) {
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify(req.headers));
    } else {
      res.setHeader("Content-Type", "text/html; charset=utf-8");
      res.end('<!doctype html><title>Local privacy test</title><canvas id="canvas"></canvas><p>Loopback fixture only</p>');
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  let context;
  try {
    context = await chromium.launchPersistentContext(profile, {
      channel: "chromium", headless: true,
      ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}),
      args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`]
    });
    const worker = context.serviceWorkers()[0] || await context.waitForEvent("serviceworker", { timeout: 20000 });
    const id = new URL(worker.url()).host;
    const popup = await context.newPage();
    await popup.goto(`chrome-extension://${id}/popup.html`);
    const page = await context.newPage();
    async function setClean(enabled) {
      await popup.locator("#clean").setChecked(enabled);
      await popup.locator("#save").click();
      await popup.waitForFunction(() => !document.querySelector("#save").disabled);
      const message = await popup.locator("#output").innerText();
      assert.match(message, enabled ? /规则已启用/ : /规则已移除/);
    }
    async function signals(enabled) {
      await page.goto(origin);
      await page.waitForFunction(expected => window.__dolaFingerprintPrivacy?.getSettings().cleanMode === expected, enabled);
      return page.evaluate(async () => ({
        ua: navigator.userAgent, appVersion: navigator.appVersion,
        languages: Array.from(navigator.languages),
        headers: await (await fetch("/echo", { headers: { Authorization: "Bearer local-test-only", "X-Test-Marker": "retained" } })).json()
      }));
    }
    await setClean(false);
    const baseline = await signals(false);
    await context.addCookies([{ name: "test_session", value: "local-only", url: origin }]);
    for (let pass = 0; pass < 2; pass++) {
      await setClean(true);
      const clean = await signals(true);
      assert.match(clean.ua, /Android 13/);
      assert.equal(clean.headers["user-agent"], clean.ua);
      assert.equal(clean.appVersion, clean.ua.slice(8));
      assert.deepEqual(clean.headers["accept-language"].split(",").map(v => v.split(";")[0]), clean.languages);
      assert.equal(clean.headers["sec-ch-ua"], undefined);
      assert.equal(clean.headers["sec-ch-ua-platform"], undefined);
      assert.equal(clean.headers["sec-gpc"], "1");
      assert.equal(clean.headers.authorization, "Bearer local-test-only");
      assert.match(clean.headers.cookie, /test_session=local-only/);
      assert.equal(clean.headers["x-test-marker"], "retained");
      checks.push(`cycle ${pass + 1}: real network headers match page UA; hints removed; auth/cookie retained`);
      await setClean(false);
      const restored = await signals(false);
      assert.equal(restored.ua, baseline.ua);
      assert.equal(restored.headers["user-agent"], baseline.headers["user-agent"]);
      assert.equal(restored.headers["sec-ch-ua"], baseline.headers["sec-ch-ua"]);
      assert.equal(restored.headers["sec-gpc"], baseline.headers["sec-gpc"]);
      checks.push(`cycle ${pass + 1}: disable restores original page and network identity`);
    }
    await popup.locator("#strict").click();
    await popup.waitForFunction(() => !document.querySelector("#save").disabled);
    assert.match(await popup.locator("#output").innerText(), /规则已启用/);
    await signals(true);
    const canvasError = await page.evaluate(() => {
      try { document.querySelector("canvas").toDataURL(); return "none"; } catch (error) { return error.name; }
    });
    assert.equal(canvasError, "SecurityError");
    checks.push("strict mode actually blocks Canvas readback in page context");
    // The test profile is disposable; do not leave network spoofing on in it.
    await setClean(false);
    const result = { ok: true, browser: context.browser().version(), platform: process.platform, checks,
      notVerified: ["Android/Cobalt", "iframe/Worker coverage", "ad removal", "original Dola30 runtime equivalence"] };
    record(result);
    console.log(JSON.stringify(result, null, 2));
  } finally {
    if (context) await context.close();
    await new Promise(resolve => server.close(resolve));
  }
}
main().catch(error => { record({ ok: false, status: "failed", error: String(error) }); console.error(error); process.exitCode = 1; });
