(function () {
  if (!globalThis.chrome?.runtime?.id) {
    console.warn("[dola30] content-panel: 缺少扩展上下文，无法挂载哆啦助手面板");
    return;
  }

  const PANEL_ID = "watermark-free-media-panel";
  const INSTANCE_KEY = "__watermarkFreeMediaPanelActive";
  const DURATION_KEY = "intl_doubao_enable_30s_v1";
  const TARGET_DURATION_KEY = "intl_doubao_target_duration_v1";
  const DURATION_STATE_ATTRIBUTE = "data-watermark-free-duration-30";
  const DURATION_STATE_EVENT = "watermark-free-duration-30-change";
  const MEDIA_APIS_ATTRIBUTE = "data-dola-media-fallback-apis";
  const MEDIA_APIS_EVENT = "dola-media-fallback-apis";
  const MINIMIZED_KEY = "intl_doubao_media_panel_minimized_v1";
  const SHELL_BOX_KEY = "intl_doubao_media_panel_shell_box_v1";
  const PANEL_POS_KEY = "intl_doubao_media_panel_pos_v3";
  const PANEL_BOTTOM_PX = 72;
  const PANEL_RIGHT_MARGIN_PX = 16;
  /** 最小化时哆啦图标在 shell 内的 left / 宽度，用于把图标锚到页面右下角 */
  const PANEL_ICON_SHELL_LEFT_PX = 200;
  const PANEL_ICON_WIDTH_PX = 76;
  const PARSE_PANEL_KEY = "intl_doubao_media_panel_parse_v1";
  const PROMPT_GUARD_INJECT_KEY = "intl_doubao_prompt_guard_inject_v1";
  const TIMELINE_REWRITE_KEY = "intl_doubao_timeline_rewrite_v1";
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
  shadow.innerHTML = `
    <style>
      :host {
        all: initial;
        position: fixed;
        top: auto;
        left: auto;
        right: 16px;
        bottom: 72px;
        z-index: 2147483647;
        font-family: "Microsoft YaHei UI", "Microsoft YaHei", Arial, sans-serif;
      }

      .shell {
        position: relative;
        display: flex;
        flex-direction: row;
        align-items: stretch;
        gap: 8px;
      }

      .panel {
        width: 145px;
        flex: 0 0 150px;
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        color: #34304B;
        background: #FFFFFF;
        border: 2px solid rgba(122, 92, 240, 0.16);
        border-radius: 14px;
        overflow: hidden;
      }

      /* ===== 品牌头部：哆啦助手渐变 ===== */
      .header {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 10px 8px 12px;
        background: linear-gradient(100deg, #7A5CF0 0%, #B46FE0 55%, #5AA9F0 100%);
        cursor: grab;
        touch-action: none;
        user-select: none;
      }

      .header:active {
        cursor: grabbing;
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

      .panel-toggle {
        display: none;
      }

      .doraemon-toggle {
        display: block;
        position: absolute;
        top: -50px;
        left: 80px;
        width: 70px;
        height: 92px;
        padding: 0;
        border: 0;
        background: transparent;
        cursor: pointer;
        z-index: 4;
      }

      .doraemon-toggle img {
        width: 100%;
        height: 100%;
        object-fit: contain;
        object-position: center bottom;
        display: block;
        pointer-events: none;
        filter: drop-shadow(0 6px 10px rgba(52, 48, 75, 0.18));
      }

      /* ===== 开关行：左横向开关，右文字 ===== */
      .switch-rows,
      .parse-block {
        padding: 8px 12px;
        background: #F8F5FE;
        border-bottom: 1px solid #EFE8FB;
      }

      .switch-rows {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }

      .switch-row {
        display: flex;
        flex-direction: row;
        align-items: center;
        gap: 10px;
        min-height: 32px;
      }

      .switch-label {
        flex: 1;
        min-width: 0;
        font-size: 12px;
        line-height: 16px;
        font-weight: 600;
        color: #6E6A88;
        user-select: none;
      }

      .switch-label.on {
        color: #2E9E5B;
      }

      .switch {
        position: relative;
        display: inline-block;
        flex-shrink: 0;
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

      .parse-block {
        padding-top: 6px;
        padding-bottom: 8px;
      }

      .list-panel {
        display: none;
        flex: 0 0 280px;
        width: 280px;
        box-sizing: border-box;
        flex-direction: column;
        min-height: 0;
        color: #34304B;
        background: #FFFFFF;
        border: 1px solid rgba(122, 92, 240, 0.16);
        border-radius: 14px;
        box-shadow: 0 16px 44px rgba(122, 92, 240, 0.28);
        overflow: hidden;
      }

      .list-panel[data-open="1"] {
        display: flex;
      }

      /* ===== 资源列表：在控制面板右侧展开 ===== */
      .list {
        flex: 1 1 auto;
        min-height: 0;
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
        grid-template-columns: auto 1fr auto;
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

      .media-thumb {
        width: 56px;
        height: 42px;
        border-radius: 8px;
        object-fit: cover;
        background: #EFE8FB;
        border: 1px solid #EAE3F7;
        display: block;
        flex-shrink: 0;
      }

      .media-thumb-fallback {
        width: 56px;
        height: 42px;
        border-radius: 8px;
        background: linear-gradient(135deg, #EFE8FB, #E2D9F8);
        border: 1px solid #EAE3F7;
        color: #9A7BF4;
        font-size: 10px;
        font-weight: 700;
        display: flex;
        align-items: center;
        justify-content: center;
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

      /* ===== 收起：只留哆啦图标，面板和标题条都不显示 ===== */
      :host([data-minimized="1"]) .panel,
      :host([data-minimized="1"]) .list-panel {
        display: none;
      }

      :host([data-minimized="1"]) .shell {
        gap: 0;
        pointer-events: none;
      }

      :host([data-minimized="1"]) .doraemon-toggle {
        pointer-events: auto;
        cursor: grab;
        touch-action: none;
      }

      :host([data-minimized="1"]) .doraemon-toggle:active {
        cursor: grabbing;
      }
    </style>
    <div class="shell">
      <section class="panel" aria-label="哆啦助手 无水印资源面板">
        <div class="header">
          <div class="title">哆啦助手</div>
          <button class="panel-toggle" type="button" aria-label="最小化资源面板" title="最小化资源面板">
            <span class="panel-toggle-icon" aria-hidden="true">−</span>
          </button>
        </div>
        <div class="switch-rows">
          <div class="switch-row">
            <label class="switch" title="SD2.5+30秒">
              <input class="duration-toggle" type="checkbox">
              <span class="track"><span class="thumb"></span></span>
            </label>
            <span class="switch-label switch-label-duration">SD2.5+30秒</span>
          </div>
          <div class="switch-row">
            <label class="switch" title="开启 Debugger 深度嗅探">
              <input class="sniff-deep-toggle" type="checkbox">
              <span class="track"><span class="thumb"></span></span>
            </label>
            <span class="switch-label switch-label-deep">深度嗅探</span>
          </div>
          <div class="switch-row">
            <label class="switch" title="自动 Debugger 嗅探">
              <input class="sniff-auto" type="checkbox" checked>
              <span class="track"><span class="thumb"></span></span>
            </label>
            <span class="switch-label switch-label-auto">自动嗅探</span>
          </div>
          <div class="switch-row">
            <label class="switch" title="发送时在正文前注入【直接生成控制】等指令（关闭则不附加控制段）">
              <input class="prompt-guard-toggle" type="checkbox">
              <span class="track"><span class="thumb"></span></span>
            </label>
            <span class="switch-label switch-label-guard">控制指令</span>
          </div>
          <div class="switch-row">
            <label class="switch" title="把提示词里的秒数/时间码换成全片进度百分比（关闭后原文发送）">
              <input class="timeline-rewrite-toggle" type="checkbox" checked>
              <span class="track"><span class="thumb"></span></span>
            </label>
            <span class="switch-label switch-label-timeline">秒数换算</span>
          </div>
          <div class="switch-row">
            <label class="switch" title="在右侧展开资源解析列表">
              <input class="parse-panel-toggle" type="checkbox">
              <span class="track"><span class="thumb"></span></span>
            </label>
            <span class="switch-label switch-label-parse">解析列表</span>
          </div>
        </div>
      </section>
      <div class="list-panel" data-open="0">
        <div class="list">
          <div class="empty">等待捕获资源…</div>
        </div>
      </div>
      <button class="doraemon-toggle" type="button" aria-label="展开资源面板" title="展开资源面板">
        <img class="doraemon-img" alt="">
      </button>
    </div>
  `;

  const list = shadow.querySelector(".list");
  const listPanel = shadow.querySelector(".list-panel");
  const refreshParseButton = shadow.querySelector(".refresh-parse");
  const copyVideoButton = shadow.querySelector(".copy-video-url");
  const channelPop = shadow.querySelector(".channel-pop");
  let lastFallbackUrls = [];
  const parsePanelToggle = shadow.querySelector(".parse-panel-toggle");
  const parseLabel = shadow.querySelector(".switch-label-parse");
  const durationToggle = shadow.querySelector(".duration-toggle");
  const durationLabel = shadow.querySelector(".switch-label-duration");
  const switchRows = shadow.querySelector(".switch-rows");
  const sniffDeepToggle = shadow.querySelector(".sniff-deep-toggle");
  const sniffAuto = shadow.querySelector(".sniff-auto");
  const sniffDeepLabel = shadow.querySelector(".switch-label-deep");
  const sniffAutoLabel = shadow.querySelector(".switch-label-auto");
  const promptGuardToggle = shadow.querySelector(".prompt-guard-toggle");
  const promptGuardLabel = shadow.querySelector(".switch-label-guard");
  const timelineRewriteToggle = shadow.querySelector(".timeline-rewrite-toggle");
  const timelineRewriteLabel = shadow.querySelector(".switch-label-timeline");
  const panelToggle = shadow.querySelector(".panel-toggle");
  const doraemonToggle = shadow.querySelector(".doraemon-toggle");
  const doraemonImg = shadow.querySelector(".doraemon-img");
  if (doraemonImg) doraemonImg.src = chrome.runtime.getURL("icons/doraemon.png");
  const durationControlSupported = /(^|\.)(doubao|dola)\.com$/i.test(location.hostname);
  const sniffControlSupported = durationControlSupported;
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

  function lockShellBox() {
    const shell = shadow.querySelector(".shell");
    if (!shell) return;
    const rect = shell.getBoundingClientRect();
    let w = rect.width;
    let h = rect.height;
    if (!(w > 1 && h > 1)) {
      try {
        const parsed = JSON.parse(localStorage.getItem(SHELL_BOX_KEY) || "");
        w = parsed?.w;
        h = parsed?.h;
      } catch {
        w = 0;
        h = 0;
      }
    }
    if (!(w > 1 && h > 1)) return;
    shell.style.width = `${w}px`;
    shell.style.height = `${h}px`;
    try {
      localStorage.setItem(SHELL_BOX_KEY, JSON.stringify({ w, h }));
    } catch {
      // ignore
    }
  }

  function unlockShellBox() {
    const shell = shadow.querySelector(".shell");
    if (!shell) return;
    shell.style.width = "";
    shell.style.height = "";
  }

  function setMinimizedState(nextValue, persist = true) {
    const next = Boolean(nextValue);
    if (next && !minimized) lockShellBox();
    minimized = next;
    if (minimized) {
      host.setAttribute("data-minimized", "1");
      host.style.setProperty("pointer-events", "none", "important");
    } else {
      host.removeAttribute("data-minimized");
      host.style.setProperty("pointer-events", "auto", "important");
      unlockShellBox();
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
    doraemonToggle?.setAttribute("aria-label", label);
    doraemonToggle?.setAttribute("title", minimized ? "按住拖动，点击展开" : label);
  }

  setMinimizedState(readMinimizedState(), false);
  panelToggle.addEventListener("click", (event) => {
    event.stopPropagation();
    setMinimizedState(!minimized);
    queueKeepPanelInView();
  });
  let iconDragged = false;
  doraemonToggle?.addEventListener("click", (event) => {
    event.stopPropagation();
    if (iconDragged) {
      iconDragged = false;
      event.preventDefault();
      return;
    }
    setMinimizedState(!minimized);
    queueKeepPanelInView();
  });

  const panelHeader = shadow.querySelector(".header");
  let dragSession = null;

  function defaultPanelLeft() {
    return window.innerWidth - PANEL_RIGHT_MARGIN_PX - PANEL_ICON_SHELL_LEFT_PX - PANEL_ICON_WIDTH_PX;
  }

  function readPanelOffset() {
    const left = parseFloat(host.style.getPropertyValue("left"));
    const bottom = parseFloat(host.style.getPropertyValue("bottom"));
    return {
      left: Number.isFinite(left) ? left : defaultPanelLeft(),
      bottom: Number.isFinite(bottom) ? bottom : PANEL_BOTTOM_PX
    };
  }

  function viewBox() {
    const pad = 8;
    const vv = window.visualViewport;
    if (vv && vv.width > 0 && vv.height > 0) {
      return {
        left: vv.offsetLeft + pad,
        top: vv.offsetTop + pad,
        right: vv.offsetLeft + vv.width - pad,
        bottom: vv.offsetTop + vv.height - pad
      };
    }
    return {
      left: pad,
      top: pad,
      right: Math.max(pad + 1, window.innerWidth - pad),
      bottom: Math.max(pad + 1, window.innerHeight - pad)
    };
  }

  function hostUnionRect() {
    const icon = doraemonToggle?.getBoundingClientRect();
    if (minimized && icon && icon.width > 1 && icon.height > 1) {
      return { left: icon.left, top: icon.top, right: icon.right, bottom: icon.bottom };
    }
    const hr = host.getBoundingClientRect();
    if (!icon || icon.width < 1) {
      return { left: hr.left, top: hr.top, right: hr.right, bottom: hr.bottom };
    }
    return {
      left: Math.min(hr.left, icon.left),
      top: Math.min(hr.top, icon.top),
      right: Math.max(hr.right, icon.right),
      bottom: Math.max(hr.bottom, icon.bottom)
    };
  }

  function placePanel(left, bottom) {
    host.style.setProperty("left", `${left}px`, "important");
    host.style.setProperty("bottom", `${bottom}px`, "important");
    host.style.setProperty("right", "auto", "important");
    host.style.setProperty("top", "auto", "important");
  }

  function applyPanelOffset(left, bottom) {
    placePanel(left, bottom);
    const box = viewBox();
    let l = left;
    let b = bottom;
    for (let i = 0; i < 2; i += 1) {
      const u = hostUnionRect();
      if (!(u.right > u.left) || !(u.bottom > u.top)) break;
      if (u.right > box.right) l -= u.right - box.right;
      if (u.left < box.left) l += box.left - u.left;
      if (u.bottom > box.bottom) b += u.bottom - box.bottom;
      if (u.top < box.top) b -= box.top - u.top;
      l = Math.max(8, l);
      b = Math.max(8, b);
      placePanel(l, b);
    }
    return { left: l, bottom: b };
  }

  let keepInViewTimer = 0;
  function queueKeepPanelInView() {
    if (keepInViewTimer) cancelAnimationFrame(keepInViewTimer);
    keepInViewTimer = requestAnimationFrame(() => {
      keepInViewTimer = 0;
      const { left, bottom } = readPanelOffset();
      applyPanelOffset(left, bottom);
    });
  }

  function applyDefaultPanelOffset() {
    // 最小化图标贴页面右下角（对齐豆包底栏右侧区域）
    applyPanelOffset(defaultPanelLeft(), PANEL_BOTTOM_PX);
  }

  function restorePanelOffset() {
    try {
      const raw = localStorage.getItem(PANEL_POS_KEY);
      if (!raw) {
        applyDefaultPanelOffset();
        return;
      }
      const parsed = JSON.parse(raw);
      if (typeof parsed?.left === "number" && typeof parsed?.bottom === "number") {
        applyPanelOffset(parsed.left, parsed.bottom);
        return;
      }
    } catch {
      // fall through
    }
    applyDefaultPanelOffset();
  }

  function persistPanelOffset() {
    const { left, bottom } = readPanelOffset();
    try {
      localStorage.setItem(PANEL_POS_KEY, JSON.stringify({ left, bottom }));
    } catch {
      // ignore
    }
  }

  restorePanelOffset();
  window.addEventListener("resize", queueKeepPanelInView);
  window.visualViewport?.addEventListener("resize", queueKeepPanelInView);
  window.visualViewport?.addEventListener("scroll", queueKeepPanelInView);

  function beginDrag(event, captureEl) {
    if (event.button !== 0) return;
    const offset = readPanelOffset();
    dragSession = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startLeft: offset.left,
      startBottom: offset.bottom,
      captureEl,
      moved: false
    };
    captureEl.setPointerCapture(event.pointerId);
  }

  function moveDrag(event) {
    if (!dragSession || event.pointerId !== dragSession.pointerId) return;
    const dx = event.clientX - dragSession.startX;
    const dy = event.clientY - dragSession.startY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) dragSession.moved = true;
    if (!dragSession.moved) return;
    applyPanelOffset(dragSession.startLeft + dx, dragSession.startBottom - dy);
  }

  function finishDrag(event) {
    if (!dragSession || event.pointerId !== dragSession.pointerId) return;
    const moved = dragSession.moved;
    const captureEl = dragSession.captureEl;
    const fromIcon = captureEl === doraemonToggle;
    dragSession = null;
    try {
      captureEl?.releasePointerCapture(event.pointerId);
    } catch {
      // ignore
    }
    if (moved) persistPanelOffset();
    if (fromIcon) iconDragged = moved;
  }

  panelHeader?.addEventListener("pointerdown", (event) => {
    if (event.target.closest(".panel-toggle")) return;
    beginDrag(event, panelHeader);
    if (dragSession) event.preventDefault();
  });
  panelHeader?.addEventListener("pointermove", moveDrag);
  panelHeader?.addEventListener("pointerup", finishDrag);
  panelHeader?.addEventListener("pointercancel", finishDrag);

  doraemonToggle?.addEventListener("pointerdown", (event) => {
    if (!minimized) return;
    beginDrag(event, doraemonToggle);
  });
  doraemonToggle?.addEventListener("pointermove", moveDrag);
  doraemonToggle?.addEventListener("pointerup", finishDrag);
  doraemonToggle?.addEventListener("pointercancel", (event) => {
    finishDrag(event);
    iconDragged = false;
  });

  function readParsePanelState() {
    try {
      const v = localStorage.getItem(PARSE_PANEL_KEY);
      if (v === null || v === "") return false;
      return v === "1";
    } catch {
      return false;
    }
  }

  function setParsePanelOpen(open, persist = true) {
    const on = Boolean(open);
    if (parsePanelToggle) parsePanelToggle.checked = on;
    if (listPanel) listPanel.setAttribute("data-open", on ? "1" : "0");
    if (parseLabel) parseLabel.classList.toggle("on", on);
    if (persist) {
      try {
        localStorage.setItem(PARSE_PANEL_KEY, on ? "1" : "0");
      } catch {
        // ignore
      }
    }
    if (on) render();
    queueKeepPanelInView();
  }

  setParsePanelOpen(readParsePanelState(), false);
  parsePanelToggle?.addEventListener("change", () => {
    setParsePanelOpen(!!parsePanelToggle.checked);
  });

  if (durationControlSupported) {
    syncDurationControl();
    durationToggle.addEventListener("change", () => {
      setDuration30Enabled(durationToggle.checked);
    });
    document.addEventListener(DURATION_STATE_EVENT, syncDurationControl);
  } else if (switchRows) {
    switchRows.remove();
  }

  if (sniffControlSupported) {
    chrome.runtime.sendMessage({ type: "GET_AUTO_DEBUGGER_SNIFF" }, (response) => {
      if (!chrome.runtime.lastError && response?.ok && sniffAuto) {
        sniffAuto.checked = !!response.enabled;
        syncSniffAutoLabel();
      }
    });
    sniffDeepToggle?.addEventListener("change", () => {
      if (!sniffDeepToggle.checked) {
        syncSniffDeepLabel();
        return;
      }
      sniffDeepToggle.disabled = true;
      chrome.runtime.sendMessage({ type: "ENABLE_DEBUGGER_SNIFF" }, (response) => {
        sniffDeepToggle.disabled = false;
        if (!response?.ok) {
          sniffDeepToggle.checked = false;
        }
        syncSniffDeepLabel();
      });
    });
    sniffAuto?.addEventListener("change", () => {
      chrome.runtime.sendMessage({
        type: "SET_AUTO_DEBUGGER_SNIFF",
        enabled: !!sniffAuto.checked
      });
      syncSniffAutoLabel();
    });
    syncSniffDeepLabel();
    syncSniffAutoLabel();
  } else if (switchRows && !durationControlSupported) {
    switchRows.remove();
  }

  function readPromptGuardInjectState() {
    try {
      return localStorage.getItem(PROMPT_GUARD_INJECT_KEY) === "1";
    } catch {
      return false;
    }
  }

  function setPromptGuardInject(enabled, persist = true) {
    const on = Boolean(enabled);
    if (promptGuardToggle) promptGuardToggle.checked = on;
    if (promptGuardLabel) promptGuardLabel.classList.toggle("on", on);
    if (persist) {
      try {
        localStorage.setItem(PROMPT_GUARD_INJECT_KEY, on ? "1" : "0");
      } catch {
        // ignore
      }
    }
  }

  if (durationControlSupported && promptGuardToggle) {
    setPromptGuardInject(readPromptGuardInjectState(), false);
    promptGuardToggle.addEventListener("change", () => {
      setPromptGuardInject(!!promptGuardToggle.checked);
    });
  }

  function readTimelineRewriteState() {
    try {
      return localStorage.getItem(TIMELINE_REWRITE_KEY) !== "0";
    } catch {
      return true;
    }
  }

  function setTimelineRewrite(enabled, persist = true) {
    const on = Boolean(enabled);
    if (timelineRewriteToggle) timelineRewriteToggle.checked = on;
    if (timelineRewriteLabel) timelineRewriteLabel.classList.toggle("on", on);
    if (persist) {
      try {
        localStorage.setItem(TIMELINE_REWRITE_KEY, on ? "1" : "0");
      } catch {
        // ignore
      }
    }
  }

  if (durationControlSupported && timelineRewriteToggle) {
    setTimelineRewrite(readTimelineRewriteState(), false);
    timelineRewriteToggle.addEventListener("change", () => {
      setTimelineRewrite(!!timelineRewriteToggle.checked);
    });
  }

  function syncSniffDeepLabel() {
    if (!sniffDeepLabel || !sniffDeepToggle) return;
    sniffDeepLabel.classList.toggle("on", sniffDeepToggle.checked);
  }

  function syncSniffAutoLabel() {
    if (!sniffAutoLabel || !sniffAuto) return;
    sniffAutoLabel.classList.toggle("on", sniffAuto.checked);
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
      rememberFallbackUrls(message.fallbackApis);
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
    if (listPanel?.getAttribute("data-open") !== "1") return;
    const raw = document.documentElement?.getAttribute(MEDIA_APIS_ATTRIBUTE) || "";
    if (!raw) return;
    document.documentElement?.removeAttribute(MEDIA_APIS_ATTRIBUTE);
    try {
      const urls = JSON.parse(raw);
      if (!Array.isArray(urls) || !urls.length) return;
      rememberFallbackUrls(urls);
      statusText = "正在解析无水印资源…";
      render();
      const requestStartedAt = Date.now();
      chrome.runtime.sendMessage({ type: "RESOLVE_FALLBACK_APIS", urls: lastFallbackUrls }, (response) => {
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

  refreshParseButton?.addEventListener("click", (event) => {
    event.stopPropagation();
    closeChannelPop();
    refreshParsedVideos();
  });

  copyVideoButton?.addEventListener("click", (event) => {
    event.stopPropagation();
    if (channelPop.classList.contains("open")) {
      closeChannelPop();
      return;
    }
    renderChannelPop();
    channelPop.classList.add("open");
  });

  shadow.addEventListener("pointerdown", (event) => {
    if (!event.target.closest(".copy-wrap, .channel-pop")) closeChannelPop();
  });

  function rememberFallbackUrls(urls) {
    if (!Array.isArray(urls)) return;
    const next = urls.filter((url) => typeof url === "string" && url);
    if (next.length) lastFallbackUrls = next;
  }

  function closeChannelPop() {
    channelPop?.classList.remove("open");
  }

  function collectChannelUrls() {
    for (const item of items.values()) {
      if (item.type !== "video") continue;
      const list = item.channels && item.channels.length ? item.channels : [item.url];
      return list.filter((url) => isHttpUrl(url)).slice(0, 2);
    }
    return [];
  }

  function renderChannelPop() {
    if (!channelPop) return;
    channelPop.textContent = "";
    const urls = collectChannelUrls();
    for (let index = 0; index < 2; index += 1) {
      const url = urls[index] || "";
      const row = document.createElement("div");
      row.className = "channel-row";
      const name = document.createElement("span");
      name.className = "channel-name";
      name.textContent = `渠道${index + 1}`;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "channel-copy";
      button.textContent = url ? "复制" : "暂无";
      button.disabled = !url;
      button.addEventListener("click", (event) => {
        event.stopPropagation();
        if (url) copyChannelAndOpen(url, button);
      });
      row.append(name, button);
      channelPop.appendChild(row);
    }
  }

  function copyChannelAndOpen(url, button) {
    const copied = copyText(url);
    button.textContent = copied ? "已复制" : "复制失败";
    chrome.runtime.sendMessage({ type: "OPEN_MEDIA_URL", url });
    setTimeout(() => {
      if (button.isConnected) button.textContent = "复制";
    }, 1200);
  }

  function copyText(url) {
    const area = document.createElement("textarea");
    area.value = url;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.left = "-9999px";
    document.documentElement.appendChild(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch {
      ok = false;
    }
    area.remove();
    if (!ok && navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(url).catch(() => {});
      return true;
    }
    return ok;
  }

  function refreshParsedVideos() {
    for (const [key, item] of items) {
      if (item.type === "video") items.delete(key);
    }
    statusText = "正在刷新解析…";
    render();
    if (refreshParseButton) refreshParseButton.disabled = true;
    chrome.runtime.sendMessage({
      type: "REFRESH_PARSED_VIDEOS",
      urls: lastFallbackUrls
    }, (response) => {
      if (refreshParseButton) refreshParseButton.disabled = false;
      if (chrome.runtime.lastError || response?.ok === false) {
        statusText = "刷新解析失败";
        render();
      }
    });
  }

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
    if (durationLabel) {
      durationLabel.textContent = "SD2.5+30秒";
      durationLabel.classList.toggle("on", enabled);
      durationLabel.title = enabled ? `2.5 · ${selected}s 已开启` : `当前 ${selected}s，开启为 2.5 · 30s`;
    }
    const durSwitch = durationToggle.closest(".switch");
    if (durSwitch) {
      durSwitch.title = enabled ? `Seedance 2.5 · ${selected}s` : "开启 Seedance 2.5 · 30s";
    }
  }

  function createMediaThumb(item) {
    if (item.type === "image") {
      const img = document.createElement("img");
      img.className = "media-thumb";
      img.src = item.url;
      img.alt = "";
      img.loading = "lazy";
      img.decoding = "async";
      img.referrerPolicy = "no-referrer";
      img.addEventListener("error", () => {
        img.replaceWith(createThumbFallback("图片"));
      }, { once: true });
      return img;
    }

    const video = document.createElement("video");
    video.className = "media-thumb";
    video.src = item.url;
    video.muted = true;
    video.playsInline = true;
    video.preload = "metadata";
    video.setAttribute("playsinline", "");
    video.setAttribute("webkit-playsinline", "");
    video.addEventListener("loadeddata", () => {
      try {
        if (video.currentTime < 0.01) video.currentTime = 0.05;
      } catch {
        // ignore seek errors
      }
    }, { once: true });
    video.addEventListener("error", () => {
      video.replaceWith(createThumbFallback("视频"));
    }, { once: true });
    return video;
  }

  function createThumbFallback(kind) {
    const box = document.createElement("div");
    box.className = "media-thumb-fallback";
    box.textContent = kind;
    box.title = kind;
    return box;
  }

  function render() {
    if (!listPanel || listPanel.getAttribute("data-open") !== "1") {
      return;
    }
    list.textContent = "";

    if (!items.size) {
      const empty = document.createElement("div");
      empty.className = "empty";
      empty.textContent = statusText || "等待捕获资源…";
      list.appendChild(empty);
      return;
    }

    Array.from(items.values()).forEach((item, index) => {
      const row = document.createElement("div");
      row.className = "item";

      const thumb = createMediaThumb(item);
      thumb.title = item.url;

      const label = document.createElement("div");
      label.className = "label";
      label.title = item.url;

      const indexText = document.createElement("span");
      indexText.className = "index";
      indexText.textContent = String(index + 1);

      label.append(indexText);

      const button = document.createElement("button");
      button.type = "button";
      button.className = "dl";
      button.textContent = "下载";
      button.addEventListener("click", () => {
        chrome.runtime.sendMessage({ type: "DOWNLOAD_MEDIA", url: item.url, mediaType: item.type });
      });

      row.append(thumb, label, button);
      list.appendChild(row);
    });
  }

  function addItems(nextItems) {
    for (const item of nextItems) {
      if (!item || typeof item.url !== "string" || !isHttpUrl(item.url)) {
        continue;
      }
      const type = item.type === "image" ? "image" : "video";
      const fingerprint = mediaFingerprint(item.url);
      const channels = Array.isArray(item.channels)
        ? item.channels.filter((url) => typeof url === "string" && isHttpUrl(url)).slice(0, 2)
        : [];
      items.set(`${type}:${fingerprint}`, {
        type,
        url: item.url,
        channels
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
      width: "auto",
      height: "auto",
      visibility: "visible",
      opacity: "1",
      "pointer-events": "auto",
      "z-index": "2147483647",
      top: "auto",
      left: "auto",
      bottom: `${PANEL_BOTTOM_PX}px`,
      right: `${PANEL_RIGHT_MARGIN_PX}px`
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
