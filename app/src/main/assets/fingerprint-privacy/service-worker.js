/* Clean mode network policy. Header reduction is declarative and stable. */
"use strict";

const CLEAN_RULE_IDS = [31001, 31002, 31003, 31004];
const GENERIC_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
const RESOURCE_TYPES = ["main_frame", "sub_frame", "xmlhttprequest", "script", "image", "media", "font", "stylesheet", "other"];
const REMOVE_HEADERS = [
  "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "sec-ch-ua-arch",
  "sec-ch-ua-bitness", "sec-ch-ua-full-version", "sec-ch-ua-full-version-list",
  "sec-ch-ua-model", "sec-ch-ua-platform-version", "device-memory", "dpr",
  "viewport-width", "width", "downlink", "ect", "rtt", "x-client-data"
];

function cleanRules() {
  return [
    {
      id: CLEAN_RULE_IDS[0], priority: 1, action: { type: "modifyHeaders", requestHeaders: [{ header: "user-agent", operation: "set", value: GENERIC_USER_AGENT }] },
      condition: { resourceTypes: RESOURCE_TYPES }
    },
    {
      id: CLEAN_RULE_IDS[1], priority: 1, action: { type: "modifyHeaders", requestHeaders: REMOVE_HEADERS.map(header => ({ header, operation: "remove" })) },
      condition: { resourceTypes: RESOURCE_TYPES }
    },
    {
      id: CLEAN_RULE_IDS[2], priority: 1, action: { type: "modifyHeaders", requestHeaders: [{ header: "accept-language", operation: "set", value: "zh-CN,zh;q=0.8,en;q=0.5" }] },
      condition: { resourceTypes: RESOURCE_TYPES }
    },
    {
      id: CLEAN_RULE_IDS[3], priority: 1, action: { type: "modifyHeaders", requestHeaders: [{ header: "sec-gpc", operation: "set", value: "1" }] },
      condition: { resourceTypes: RESOURCE_TYPES }
    }
  ];
}

async function cleanModeEnabled() {
  const settings = await chrome.storage.local.get({ cleanMode: false });
  return !!settings.cleanMode;
}

// Serialize storage reads and rule updates: an older enable must not finish after disable.
let pending = Promise.resolve();
function syncCleanRules() {
  const operation = pending.then(async () => {
    try {
      const enabled = await cleanModeEnabled();
      await chrome.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: CLEAN_RULE_IDS.slice(), addRules: enabled ? cleanRules() : []
      });
      const status = { ok: true, enabled };
      await chrome.storage.local.set({ cleanModeStatus: status });
      return status;
    } catch (error) {
      const status = { ok: false, enabled: null, error: String(error.message || error) };
      await chrome.storage.local.set({ cleanModeStatus: status }).catch(() => {});
      return status;
    }
  });
  pending = operation.catch(() => {});
  return operation;
}

chrome.runtime.onInstalled.addListener(() => { void syncCleanRules(); });
chrome.runtime.onStartup.addListener(() => { void syncCleanRules(); });
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.cleanMode) void syncCleanRules();
});
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  // Only extension UI may request acknowledgement, not content scripts/web pages.
  if (!message || message.type !== "sync-clean-mode" || sender.id !== chrome.runtime.id || sender.url !== chrome.runtime.getURL("popup.html")) return;
  syncCleanRules().then(respond);
  return true;
});
