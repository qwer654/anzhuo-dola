(function () {
  "use strict";
  var canvas = document.getElementById("canvas"), webgl = document.getElementById("webgl"), audio = document.getElementById("audio"), webrtc = document.getElementById("webrtc"), devices = document.getElementById("devices"), output = document.getElementById("output");
  var defaults = { blockCanvasReadback: false, hideWebglDebugInfo: true, blockAudioFingerprint: false, blockWebRTC: false, blockDeviceEnumeration: false };
  chrome.storage.local.get(defaults).then(function (s) { canvas.checked = !!s.blockCanvasReadback; webgl.checked = !!s.hideWebglDebugInfo; audio.checked = !!s.blockAudioFingerprint; webrtc.checked = !!s.blockWebRTC; devices.checked = !!s.blockDeviceEnumeration; });
  function settings() { return { blockCanvasReadback: canvas.checked, hideWebglDebugInfo: webgl.checked, blockAudioFingerprint: audio.checked, blockWebRTC: webrtc.checked, blockDeviceEnumeration: devices.checked }; }
  document.getElementById("save").addEventListener("click", function () {
    chrome.storage.local.set(settings()).then(function () { output.textContent = "设置已保存；新页面或刷新后生效。"; });
  });
  document.getElementById("strict").addEventListener("click", function () {
    canvas.checked = audio.checked = webrtc.checked = devices.checked = true; webgl.checked = true;
    chrome.storage.local.set(settings()).then(function () { output.textContent = "严格隐私已开启；部分绘图、音频、通话和设备选择页面可能不可用。"; });
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
