/* Isolated-world bridge for chrome.storage and popup requests. No network I/O. */
(function () {
  "use strict";
  var defaults = { blockCanvasReadback: false, hideWebglDebugInfo: false };
  function sendSettings() {
    chrome.storage.local.get(defaults).then(function (settings) {
      window.postMessage({ source: "dola-fingerprint-privacy", type: "apply", settings: settings }, "*");
    });
  }
  chrome.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || message.type !== "fingerprint-report") return;
    var done = false;
    function finish(value) { if (!done) { done = true; sendResponse(value); } }
    function receive(event) {
      if (event.source !== window || !event.data || event.data.source !== "dola-fingerprint-privacy" || event.data.type !== "report-result") return;
      window.removeEventListener("message", receive);finish({ ok: true, report: event.data.report });
    }
    window.addEventListener("message", receive);
    window.postMessage({ source: "dola-fingerprint-privacy", type: "request-report" }, "*");
    setTimeout(function () { window.removeEventListener("message", receive);finish({ ok: false, error: "report timeout" }); }, 1200);
    return true;
  });
  window.addEventListener("message", function (event) {
    if (event.source !== window || !event.data || event.data.source !== "dola-fingerprint-privacy") return;
    if (event.data.type === "request-report") window.dispatchEvent(new Event("dola-fingerprint-report"));
    if (event.data.type === "report") window.postMessage({ source: "dola-fingerprint-privacy", type: "report-result", report: event.data.report }, "*");
    if (event.data.type === "apply") window.dispatchEvent(new CustomEvent("dola-fingerprint-apply", { detail: event.data.settings || defaults }));
  });
  window.addEventListener("dola-fingerprint-privacy-ready", sendSettings, { once: true });
  sendSettings();
})();
