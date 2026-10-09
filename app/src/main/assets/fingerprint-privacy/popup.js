(function () {
  "use strict";
  var canvas = document.getElementById("canvas"), webgl = document.getElementById("webgl"), output = document.getElementById("output");
  chrome.storage.local.get({ blockCanvasReadback: false, hideWebglDebugInfo: false }).then(function (s) { canvas.checked = !!s.blockCanvasReadback; webgl.checked = !!s.hideWebglDebugInfo; });
  document.getElementById("save").addEventListener("click", function () {
    chrome.storage.local.set({ blockCanvasReadback: canvas.checked, hideWebglDebugInfo: webgl.checked }).then(function () { output.textContent = "设置已保存；新页面或刷新后生效。"; });
  });
  document.getElementById("report").addEventListener("click", function () {
    output.textContent = "读取中…";
    chrome.tabs.query({ active: true, currentWindow: true }).then(function (tabs) {
      if (!tabs[0]) throw new Error("没有活动标签");
      return chrome.tabs.sendMessage(tabs[0].id, { type: "fingerprint-report" });
    }).then(function (result) {
      output.textContent = result && result.ok ? JSON.stringify(result.report, null, 2) : "当前页面未返回报告。";
    }).catch(function (error) { output.textContent = "无法读取当前页面：" + error.message; });
  });
})();
