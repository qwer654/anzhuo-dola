(function () {
  const PANEL_ID = "watermark-free-media-panel";
  const INSTANCE_KEY = "__watermarkFreeMediaPanelActive";
  const DURATION_KEY = "intl_doubao_enable_30s_v1";
  const TARGET_DURATION_KEY = "intl_doubao_target_duration_v1";
  const DURATION_STATE_ATTRIBUTE = "data-watermark-free-duration-30";
  const DURATION_STATE_EVENT = "watermark-free-duration-30-change";
  const MEDIA_APIS_ATTRIBUTE = "data-dola-media-fallback-apis";
  const MEDIA_APIS_EVENT = "dola-media-fallback-apis";
  const MINIMIZED_KEY = "intl_doubao_media_panel_minimized_v1";
  const items = new Map();

  if (globalThis[INSTANCE_KEY]) {
    return;
  }
  globalThis[INSTANCE_KEY] = true;

  const existingHost = document.getElementById(PANEL_ID);
  existingHost?.remove();

  const host = document.createElement("div");
  host.id = PANEL_ID;
  forceHostVisible();
  mountHost();

  const shadow = host.attachShadow({ mode: "open" });
  const LOGO_URL = chrome.runtime.getURL("icons/logo96.png");
  shadow.innerHTML = `
    <style>
      :host {
        all: initial;
        position: fixed;
        right: 16px;
        bottom: 16px;
        z-index: 2147483647;
        font-family: "Microsoft YaHei UI", "Microsoft YaHei", Arial, sans-serif;
      }

      .panel {
        width: 340px;
        max-height: 430px;
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        color: #34304B;
        background: #FFFFFF;
        border: 1px solid rgba(122, 92, 240, 0.16);
        border-radius: 14px;
        box-shadow: 0 16px 44px rgba(122, 92, 240, 0.28);
        overflow: hidden;
      }

      /* ===== 品牌头部：苏生阁渐变 ===== */
      .header {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 10px 8px 12px;
        background: linear-gradient(100deg, #7A5CF0 0%, #B46FE0 55%, #5AA9F0 100%);
      }

      .logo {
        width: 22px;
        height: 22px;
        min-width: 22px;
        border-radius: 6px;
        object-fit: contain;
        display: block;
      }

      .title {
        flex: 1;
        min-width: 0;
        color: #fff;
        font-size: 13px;
        line-height: 20px;
        font-weight: 700;
        letter-spacing: 0.2px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .count {
        min-width: 24px;
        height: 20px;
        padding: 0 7px;
        border-radius: 999px;
        background: rgba(255, 255, 255, 0.92);
        color: #6A4BD8;
        font-size: 12px;
        line-height: 20px;
        font-weight: 700;
        text-align: center;
      }

      .panel-toggle {
        width: 26px;
        min-width: 26px;
        height: 26px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        padding: 0;
        border: 0;
        border-radius: 8px;
        background: rgba(255, 255, 255, 0.22);
        color: #fff;
        font-size: 16px;
        line-height: 1;
        cursor: pointer;
        transition: background 0.15s ease;
      }

      .panel-toggle:hover {
        background: rgba(255, 255, 255, 0.38);
      }

      .panel-toggle-icon {
        display: block;
        line-height: 16px;
      }

      /* ===== 30 秒模式行：iOS 风格开关 ===== */
      .duration-control {
        min-height: 44px;
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 0 12px;
        background: #F8F5FE;
        border-bottom: 1px solid #EFE8FB;
        color: #34304B;
        font-size: 12px;
      }

      .dur-tag {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 999px;
        background: linear-gradient(100deg, #7A5CF0, #B46FE0);
        color: #fff;
        font-size: 11px;
        font-weight: 700;
        line-height: 16px;
        white-space: nowrap;
      }

      .dur-label {
        flex: 1;
        min-width: 0;
        color: #6E6A88;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .switch {
        position: relative;
        display: inline-block;
        width: 38px;
        height: 22px;
        min-width: 38px;
        cursor: pointer;
        -webkit-user-select: none;
        user-select: none;
      }

      .switch input {
        position: absolute;
        width: 0;
        height: 0;
        opacity: 0;
        margin: 0;
      }

      .switch .track {
        position: absolute;
        inset: 0;
        border-radius: 999px;
        background: #D8D2E8;
        transition: background 0.18s ease;
      }

      .switch .thumb {
        position: absolute;
        top: 2px;
        left: 2px;
        width: 18px;
        height: 18px;
        border-radius: 50%;
        background: #fff;
        box-shadow: 0 1px 3px rgba(52, 48, 75, 0.3);
        transition: transform 0.18s ease;
      }

      .switch input:checked + .track {
        background: linear-gradient(100deg, #3FBF7F, #2FBF71);
      }

      .switch input:checked + .track .thumb {
        transform: translateX(16px);
      }

      .duration-state {
        color: #8A86A3;
        font-size: 11px;
        font-variant-numeric: tabular-nums;
        white-space: nowrap;
      }

      .duration-state.enabled {
        color: #2E9E5B;
        font-weight: 700;
      }

      /* ===== 资源列表 ===== */
      .list {
        min-height: 78px;
        max-height: 356px;
        overflow: auto;
        padding: 8px;
        background: #FFFFFF;
      }

      .empty {
        padding: 20px 10px;
        color: #A79FBE;
        font-size: 13px;
        line-height: 20px;
        text-align: center;
      }

      .item {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto auto;
        gap: 8px;
        align-items: center;
        padding: 7px 8px;
        border: 1px solid #EAE3F7;
        border-radius: 10px;
        background: #FFFFFF;
      }

      .item + .item {
        margin-top: 6px;
      }

      .download-note {
        grid-column: 1 / -1;
        color: #6E6A88;
        font-size: 11px;
        line-height: 16px;
        overflow-wrap: anywhere;
      }

      .label {
        min-width: 0;
        color: #34304B;
        font-size: 13px;
        line-height: 18px;
        font-weight: 600;
        display: flex;
        align-items: center;
        gap: 6px;
      }

      .label .index {
        color: #B3AACB;
        font-size: 11px;
        font-weight: 400;
        font-variant-numeric: tabular-nums;
      }

      .tag {
        display: inline-block;
        min-width: 32px;
        padding: 1px 6px;
        border-radius: 999px;
        color: #fff;
        font-size: 11px;
        font-weight: 700;
        line-height: 16px;
        text-align: center;
      }

      .tag.video {
        background: linear-gradient(100deg, #7A5CF0, #9A7BF4);
      }

      .tag.image {
        background: linear-gradient(100deg, #14B8A6, #2BB3A3);
      }

      button.dl {
        height: 28px;
        padding: 0 14px;
        border: 0;
        border-radius: 8px;
        background: linear-gradient(100deg, #7A5CF0, #9A7BF4);
        color: #fff;
        font-size: 12px;
        font-weight: 600;
        line-height: 28px;
        cursor: pointer;
        transition: filter 0.15s ease;
      }

      button.dl:hover {
        filter: brightness(1.08);
      }

      button.dl:disabled {
        opacity: 0.65;
        cursor: wait;
      }

      button.save {
        height: 28px;
        padding: 0 7px;
        border: 1px solid #DCD2F5;
        border-radius: 8px;
        background: #fff;
        color: #6A4BD8;
        font-size: 11px;
        cursor: pointer;
      }

      button.save:disabled {
        opacity: 0.5;
        cursor: wait;
      }

      /* ===== 最小化 ===== */
      :host([data-minimized="1"]) {
        right: 0 !important;
        bottom: 0 !important;
      }

      :host([data-minimized="1"]) .panel {
        width: 40px;
        height: 40px;
        min-height: 40px;
        max-height: 40px;
        border-radius: 12px;
        border: 0;
        background: linear-gradient(120deg, #7A5CF0, #5AA9F0);
        box-shadow: 0 10px 26px rgba(122, 92, 240, 0.35);
      }

      :host([data-minimized="1"]) .header {
        width: 40px;
        height: 40px;
        padding: 0;
        background: transparent;
        border: 0;
        justify-content: center;
      }

      :host([data-minimized="1"]) .logo,
      :host([data-minimized="1"]) .title,
      :host([data-minimized="1"]) .count,
      :host([data-minimized="1"]) .duration-control,
      :host([data-minimized="1"]) .list {
        display: none;
      }

      :host([data-minimized="1"]) .panel-toggle {
        width: 40px;
        height: 40px;
        border-radius: 12px;
        background: transparent;
        color: #fff;
        font-size: 20px;
      }

      :host([data-minimized="1"]) .panel-toggle:hover {
        background: rgba(255, 255, 255, 0.2);
      }
    </style>
    <section class="panel" aria-label="苏生阁AI 无水印资源面板">
      <div class="header">
        <img class="logo" src="${LOGO_URL}" alt="苏生阁AI">
        <div class="title">苏生阁AI · 无水印助手</div>
        <div class="count">0</div>
        <button class="panel-toggle" type="button" aria-label="最小化资源面板" title="最小化资源面板">
          <span class="panel-toggle-icon" aria-hidden="true">−</span>
        </button>
      </div>
      <div class="duration-control">
        <span class="dur-tag">30秒</span>
        <span class="dur-label">Seedance 2.5 生成</span>
        <label class="switch">
          <input class="duration-toggle" type="checkbox">
          <span class="track"><span class="thumb"></span></span>
        </label>
        <span class="duration-state">未开启</span>
      </div>
      <div class="list">
        <div class="empty">等待捕获资源…</div>
      </div>
    </section>
  `;

  const list = shadow.querySelector(".list");
  const count = shadow.querySelector(".count");
  const durationToggle = shadow.querySelector(".duration-toggle");
  const durationState = shadow.querySelector(".duration-state");
  const durationControl = shadow.querySelector(".duration-control");
  const panelToggle = shadow.querySelector(".panel-toggle");
  const panelToggleIcon = shadow.querySelector(".panel-toggle-icon");
  const durationControlSupported = /(^|\.)(doubao|dola)\.com$/i.test(location.hostname);
  let currentSourceKey = "";
  let statusText = "等待捕获资源";
  let minimized = false;

  function readMinimizedState() {
    try {
      return localStorage.getItem(MINIMIZED_KEY) === "1";
    } catch {
      return false;
    }
  }

  function setMinimizedState(nextValue, persist = true) {
    minimized = Boolean(nextValue);
    if (minimized) {
      host.setAttribute("data-minimized", "1");
    } else {
      host.removeAttribute("data-minimized");
    }
    if (persist) {
      try {
        localStorage.setItem(MINIMIZED_KEY, minimized ? "1" : "0");
      } catch {
        // Keep the current visual state when storage is unavailable.
      }
    }
    const label = minimized ? "展开资源面板" : "最小化资源面板";
    panelToggle.setAttribute("aria-label", label);
    panelToggle.setAttribute("title", label);
    panelToggleIcon.textContent = minimized ? "＋" : "−";
  }

  setMinimizedState(readMinimizedState(), false);
  panelToggle.addEventListener("click", () => {
    setMinimizedState(!minimized);
  });
  if (durationControlSupported) {
    syncDurationControl();
    durationToggle.addEventListener("change", () => {
      setDuration30Enabled(durationToggle.checked);
    });
    document.addEventListener(DURATION_STATE_EVENT, syncDurationControl);
  } else {
    durationControl.remove();
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message) {
      return;
    }

    if (message.type === "PANEL_PING") {
      sendResponse({ ready: true });
      return;
    }

    if (message.type === "MEDIA_STATUS" && typeof message.text === "string") {
      resetForSource(message.sourceKey);
      if (!items.size) {
        statusText = message.text;
      }
      render();
      return;
    }

    if (message.type === "MEDIA_FOUND" && Array.isArray(message.items)) {
      resetForSource(message.sourceKey);
      addItems(message.items);
      statusText = items.size ? "" : "未提取到资源";
      render();
      return;
    }

    if (message.type === "MEDIA_RESET") {
      currentSourceKey = typeof message.sourceKey === "string" ? message.sourceKey : "";
      items.clear();
      statusText = "";
      render();
      return;
    }

  });

  function consumeFallbackApis() {
    const raw = document.documentElement?.getAttribute(MEDIA_APIS_ATTRIBUTE) || "";
    if (!raw) return;
    document.documentElement?.removeAttribute(MEDIA_APIS_ATTRIBUTE);
    try {
      const urls = JSON.parse(raw);
      if (!Array.isArray(urls) || !urls.length) return;
      statusText = "正在解析无水印资源…";
      render();
      const requestStartedAt = Date.now();
      chrome.runtime.sendMessage({ type: "RESOLVE_FALLBACK_APIS", urls }, (response) => {
        if (chrome.runtime.lastError) {
          if (!items.size) {
            statusText = "后台解析未完成，请切换会话后重试";
            render();
          }
          return;
        }
        if (response?.ok === false && !items.size) {
          statusText = "无水印资源解析失败";
          render();
        }
      });
      setTimeout(() => {
        if (!items.size && statusText === "正在解析无水印资源…" && Date.now() - requestStartedAt >= 12000) {
          statusText = "本次未解析到资源，请切换会话后重试";
          render();
        }
      }, 12000);
    } catch {
      // Ignore malformed cross-world payloads.
    }
  }

  document.addEventListener(MEDIA_APIS_EVENT, consumeFallbackApis);
  consumeFallbackApis();

  function resetForSource(sourceKey) {
    if (typeof sourceKey !== "string" || !sourceKey) {
      return;
    }

    if (sourceKey !== currentSourceKey) {
      currentSourceKey = sourceKey;
      items.clear();
      statusText = "";
    }
  }

  function duration30Enabled() {
    try {
      return localStorage.getItem(DURATION_KEY) === "1";
    } catch {
      return false;
    }
  }

  function setDuration30Enabled(enabled) {
    try {
      localStorage.setItem(DURATION_KEY, enabled ? "1" : "0");
      localStorage.setItem(TARGET_DURATION_KEY, enabled ? "30" : "15");
    } catch {
      return;
    }
    document.documentElement?.setAttribute(DURATION_STATE_ATTRIBUTE, enabled ? "1" : "0");
    document.dispatchEvent(new Event(DURATION_STATE_EVENT));
    syncDurationControl();
  }

  function syncDurationControl() {
    let selected = duration30Enabled() ? 30 : 15;
    try {
      const explicit = Number(localStorage.getItem(TARGET_DURATION_KEY));
      if (explicit === 15 || explicit === 30) selected = explicit;
    } catch {
      // Keep the legacy switch value.
    }
    const enabled = selected === 30;
    durationToggle.checked = enabled;
    durationState.textContent = `已选择 2.5 · ${selected}s`;
    durationState.classList.toggle("enabled", true);
  }

  function render() {
    count.textContent = String(items.size);
    list.textContent = "";

    if (!items.size) {
      const empty = document.createElement("div");
      empty.className = "empty";
      empty.textContent = statusText || "等待捕获资源…";
      list.appendChild(empty);
      return;
    }

    Array.from(items.entries()).forEach(([key, item], index) => {
      const row = document.createElement("div");
      row.className = "item";

      const label = document.createElement("div");
      label.className = "label";
      label.title = item.url;

      const tag = document.createElement("span");
      tag.className = `tag ${item.type}`;
      tag.textContent = item.type === "image" ? "图片" : "视频";

      const indexText = document.createElement("span");
      indexText.className = "index";
      indexText.textContent = String(index + 1);

      label.append(tag, indexText);

      const button = document.createElement("button");
      button.type = "button";
      button.className = "dl";
      const download = item.download;
      button.textContent = download?.state === "complete" ? "打开所在目录"
        : download?.state === "selecting" ? "等待选址…"
        : download?.state === "starting" || download?.state === "in_progress" ? "下载中…"
        : download?.state === "interrupted" || download?.state === "failed" ? "重试下载" : "下载";
      button.disabled = ["starting", "selecting", "in_progress"].includes(download?.state);
      button.addEventListener("click", () => {
        if (download?.state === "complete" && Number.isInteger(download.id)) {
          chrome.runtime.sendMessage({ type: "REVEAL_DOWNLOAD", id: download.id }, (response) => {
            if (chrome.runtime.lastError || !response?.ok) {
              item.download = { ...download, error: response?.error || chrome.runtime.lastError?.message || "无法打开目录" };
              render();
            }
          });
          return;
        }
        startDownload(key, false);
      });

      const saveButton = document.createElement("button");
      saveButton.type = "button";
      saveButton.className = "save";
      saveButton.textContent = "选择位置";
      saveButton.disabled = ["starting", "selecting", "in_progress"].includes(download?.state);
      saveButton.addEventListener("click", () => startDownload(key, true));

      row.append(label, button, saveButton);
      if (download) {
        const note = document.createElement("div");
        note.className = "download-note";
        note.textContent = download.state === "complete"
          ? `已保存：${download.filename || "请打开所在目录查看"}${download.error ? `（打开目录失败：${download.error}）` : ""}`
          : download.error ? `下载失败：${download.error}`
          : download.state === "selecting" ? "请选择保存位置；选定后会显示实际文件路径…"
          : "正在保存到当前浏览器的下载目录…";
        row.appendChild(note);
      }
      list.appendChild(row);
    });
  }

  function startDownload(key, saveAs) {
    const item = items.get(key);
    if (!item || ["starting", "selecting", "in_progress"].includes(item.download?.state)) return;
    item.download = { state: "starting" };
    render();
    chrome.runtime.sendMessage({ type: "DOWNLOAD_MEDIA", url: item.url, mediaType: item.type, saveAs }, (response) => {
      const current = items.get(key);
      if (!current) return;
      const error = chrome.runtime.lastError?.message || response?.error;
      if (error || !response?.ok || !Number.isInteger(response.id)) {
        if (!error && response?.ok && response.pending && Number.isFinite(response.startedAt)) {
          current.download = { state: "selecting", startedAt: response.startedAt };
          render();
          pollDownloadLookup(key, item.url, response.startedAt);
          return;
        }
        current.download = { state: "failed", error: error || "浏览器未创建下载任务" };
        render();
        return;
      }
      current.download = { state: "in_progress", id: response.id };
      render();
      pollDownload(key, response.id);
    });
  }

  function pollDownloadLookup(key, url, startedAt) {
    chrome.runtime.sendMessage({ type: "DOWNLOAD_LOOKUP", url, startedAt }, (response) => {
      const current = items.get(key);
      if (!current || current.download?.state !== "selecting" || current.download.startedAt !== startedAt) return;
      const error = chrome.runtime.lastError?.message || response?.error;
      if (error || !response?.ok) {
        current.download = { state: "failed", error: error || "无法查询浏览器下载记录" };
        render();
        return;
      }
      if (Number.isInteger(response.id)) {
        current.download = { state: "in_progress", id: response.id };
        render();
        pollDownload(key, response.id);
        return;
      }
      if (Date.now() - startedAt > 300000) {
        current.download = { state: "failed", error: "未找到下载记录，请检查浏览器下载栏或重试" };
        render();
        return;
      }
      setTimeout(() => pollDownloadLookup(key, url, startedAt), 1500);
    });
  }

  function pollDownload(key, id) {
    chrome.runtime.sendMessage({ type: "DOWNLOAD_STATUS", id }, (response) => {
      const current = items.get(key);
      if (!current || current.download?.id !== id) return;
      const error = chrome.runtime.lastError?.message || response?.error;
      if (error || !response?.ok) {
        current.download = { state: "failed", id, error: error || "无法读取下载状态" };
      } else {
        current.download = {
          state: response.state || "in_progress",
          id,
          filename: response.filename || "",
          error: response.state === "interrupted" ? response.error || "下载被中断" : ""
        };
      }
      render();
      if (current.download.state === "in_progress") {
        setTimeout(() => pollDownload(key, id), 1500);
      }
    });
  }

  function addItems(nextItems) {
    for (const item of nextItems) {
      if (!item || typeof item.url !== "string" || !isHttpUrl(item.url)) {
        continue;
      }
      const type = item.type === "image" ? "image" : "video";
      const fingerprint = mediaFingerprint(item.url);
      const key = `${type}:${fingerprint}`;
      items.set(key, {
        ...items.get(key),
        type,
        url: item.url
      });
    }
  }

  function isHttpUrl(url) {
    return /^https?:\/\//i.test(url);
  }

  function mediaFingerprint(rawUrl) {
    try {
      const url = new URL(rawUrl);
      url.hash = "";
      for (const key of Array.from(url.searchParams.keys())) {
        if (isVolatileMediaQueryKey(key)) {
          url.searchParams.delete(key);
        }
      }
      url.searchParams.sort();
      return url.toString();
    } catch {
      return String(rawUrl || "").split("#", 1)[0];
    }
  }

  function isVolatileMediaQueryKey(key) {
    const normalized = String(key || "").toLowerCase();
    return /^(?:x-amz-|x-oss-|x-cos-|x-qn-)/.test(normalized) ||
      /^(?:sign|signature|token|access_token|auth|auth_key|authorization|expires?|expire_at|timestamp|ts|wssecret|wstime|policy|key-pair-id)$/.test(normalized);
  }

  function forceHostVisible() {
    const properties = {
      all: "initial",
      display: "block",
      position: "fixed",
      right: "16px",
      bottom: "16px",
      width: "auto",
      height: "auto",
      visibility: "visible",
      opacity: "1",
      "pointer-events": "auto",
      "z-index": "2147483647"
    };
    for (const [name, value] of Object.entries(properties)) {
      host.style.setProperty(name, value, "important");
    }
  }

  function mountHost() {
    if (!host.isConnected && document.documentElement) {
      document.documentElement.appendChild(host);
    }
  }

  const hostObserver = new MutationObserver(() => {
    const currentHost = document.getElementById(PANEL_ID);
    if (currentHost && currentHost !== host) {
      hostObserver.disconnect();
      return;
    }
    mountHost();
  });
  hostObserver.observe(document.documentElement, { childList: true });
})();
