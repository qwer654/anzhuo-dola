(function () {
  "use strict";
  var clean = document.getElementById("clean"), canvas = document.getElementById("canvas"), webgl = document.getElementById("webgl"), audio = document.getElementById("audio"), webrtc = document.getElementById("webrtc"), devices = document.getElementById("devices"), output = document.getElementById("output");
  var defaults = { cleanMode: false, blockCanvasReadback: false, hideWebglDebugInfo: true, blockAudioFingerprint: false, blockWebRTC: false, blockDeviceEnumeration: false };
  var controls = Array.from(document.querySelectorAll("input, button"));
  function busy(value) { controls.forEach(function (control) { control.disabled = value; }); }
  busy(true);
  chrome.storage.local.get(defaults).then(function (s) {
    clean.checked = !!s.cleanMode; canvas.checked = !!s.blockCanvasReadback; webgl.checked = !!s.hideWebglDebugInfo; audio.checked = !!s.blockAudioFingerprint; webrtc.checked = !!s.blockWebRTC; devices.checked = !!s.blockDeviceEnumeration;
    busy(false);
  }).catch(function (error) { output.textContent = "设置读取失败，请重新打开弹窗：" + error.message; });
  function settings() { return { cleanMode: clean.checked, blockCanvasReadback: canvas.checked, hideWebglDebugInfo: webgl.checked, blockAudioFingerprint: audio.checked, blockWebRTC: webrtc.checked, blockDeviceEnumeration: devices.checked }; }
  function save() {
    busy(true);
    output.textContent = "正在保存并应用请求头规则…";
    return chrome.storage.local.set(settings()).then(function () {
      return chrome.runtime.sendMessage({ type: "sync-clean-mode" });
    }).then(function (status) {
      if (!status || !status.ok) throw new Error(status && status.error || "浏览器未确认规则应用");
      output.textContent = "已保存；洁净模式请求头规则" + (status.enabled ? "已启用" : "已移除") + "。请刷新已有页面以应用或恢复页面属性。严格限制可能影响绘图、音频和通话；这不是匿名或无广告保证。";
    }).catch(function (error) {
      output.textContent = "未确认完整生效（可能已保存设置）：" + error.message + "。请重试；关闭失败时旧请求头规则可能仍存在。";
    }).finally(function () { busy(false); });
  }
  document.getElementById("save").addEventListener("click", save);
  document.getElementById("strict").addEventListener("click", function () {
    clean.checked = true; canvas.checked = audio.checked = webrtc.checked = devices.checked = true; webgl.checked = true;
    save();
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
