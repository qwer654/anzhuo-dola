(function () {
  "use strict";

  const INSTALL_KEY = "__watermarkFreeDuration30Installed";
  const DURATION_KEY = "intl_doubao_enable_30s_v1";
  const TARGET_DURATION_KEY = "intl_doubao_target_duration_v1";
  const STATE_ATTRIBUTE = "data-watermark-free-duration-30";
  const STATE_EVENT = "watermark-free-duration-30-change";
  const MENU_MARK = "data-watermark-free-duration-option";
  const NATIVE_BOUND_MARK = "data-watermark-free-native-duration-bound";
  const MODEL_SELECTOR = "[data-input-engine-actionbar-control-key=\"video-model\"], [data-input-engine-actionbar-control-key=\"model\"]";
  const VIDEO_COMPLETION_PATH = "/chat/completion";
  const VIDEO_ABILITY_TYPE = 17;
  const SEEDANCE_25_MODEL = "seedance_v2.5";
  const TIMELINE_GUARD_MARK = "【单片生成控制】";
  const DIRECT_GENERATION_GUARD_MARK = "【直接生成控制】";
  const OLD_DIRECT_GENERATION_GUARD = /【直接生成控制】用户已经明确选定视频模型、比例和时长，参数以本请求的 chat_ability \/ ability_param 为准。禁止询问用户确认，禁止要求用户回复 A\/B\/C 或选择时长，禁止再次解释可选规格；立即调用一次视频生成能力，只回复已经受理并开始生成的确认结果。\s*/g;
  const PREVIOUS_DIRECT_GENERATION_GUARD = /【直接生成控制】用户已在页面选定视频参数，以本次请求携带的能力参数为准。直接调用一次视频生成能力，不要提问、复述比例、时长、参考图或画面描述。仅在生成能力成功受理后回复：任务已受理，正在生成。不要添加其他文字；调用失败时简要说明原因。\s*/g;
  const TIMELINE_DURATION_SECONDS = 30;
  const PROMPT_FIELD_NAMES = new Set([
    "content", "text", "prompt", "query", "input", "input_text", "message"
  ]);

  if (globalThis[INSTALL_KEY]) {
    return;
  }
  globalThis[INSTALL_KEY] = true;

  const host = location.hostname.replace(/^www\./i, "").toLowerCase();
  if (!(host === "dola.com" || host.endsWith(".dola.com") || host === "doubao.com" || host.endsWith(".doubao.com"))) {
    return;
  }

  let lastKnownModelTarget = 0;
  let native30Observed = false;

  function textOf(element) {
    return String(
      element?.textContent ||
      element?.getAttribute?.("aria-label") ||
      element?.getAttribute?.("title") ||
      ""
    ).replace(/\s+/g, " ").trim();
  }

  function visible(element) {
    try {
      const rect = element?.getBoundingClientRect();
      const style = element && getComputedStyle(element);
      return Boolean(
        element &&
        element.isConnected &&
        rect.width > 1 &&
        rect.height > 1 &&
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        Number(style.opacity || 1) > 0.01
      );
    } catch {
      return false;
    }
  }

  function durationText(value) {
    return String(value || "").replace(/\s+/g, "").replace(/[✓✔√]/g, "").trim();
  }

  function selectedModelText() {
    return durationText(textOf(document.querySelector(MODEL_SELECTOR)));
  }

  function modelTargetDuration() {
    // 开启 30 秒模式后，以扩展状态为准，不再依赖 Dola 易变的按钮文案。
    // 这样页面重绘或平台把模型文案暂时恢复成 Fast 时，也不会丢失 30 秒入口。
    if (duration30Enabled()) {
      lastKnownModelTarget = 30;
      return 30;
    }
    const text = selectedModelText();
    if (/2\.5|seedance[^\d]*2[^\d]*5/i.test(text)) {
      lastKnownModelTarget = 30;
    } else if (/2\.0|seedance[^\d]*2[^\d]*0|seedance[^\d]*fast/i.test(text)) {
      lastKnownModelTarget = 0;
    }
    return lastKnownModelTarget;
  }

  function duration30Enabled() {
    try {
      return localStorage.getItem(DURATION_KEY) === "1";
    } catch {
      return false;
    }
  }

  function selectedDuration() {
    try {
      const value = Number(localStorage.getItem(TARGET_DURATION_KEY));
      if (value === 15 || value === 30) return value;
    } catch {
      // Fall through to the legacy switch.
    }
    return duration30Enabled() ? 30 : 30;
  }

  function saveTargetDuration(seconds) {
    const value = Number(seconds) === 15 ? 15 : 30;
    try {
      localStorage.setItem(TARGET_DURATION_KEY, String(value));
      localStorage.setItem(DURATION_KEY, value === 30 ? "1" : "0");
    } catch {
      return;
    }
    publishDurationState();
  }

  function publishDurationState() {
    const root = document.documentElement;
    if (!root) {
      return;
    }
    root.setAttribute(STATE_ATTRIBUTE, duration30Enabled() ? "1" : "0");
    document.dispatchEvent(new Event(STATE_EVENT));
  }

  function saveDuration30(enabled) {
    try {
      localStorage.setItem(DURATION_KEY, enabled ? "1" : "0");
    } catch {
      return;
    }
    publishDurationState();
  }

  document.addEventListener(STATE_EVENT, () => {
    const value = document.documentElement?.getAttribute(STATE_ATTRIBUTE);
    if (value !== "0" && value !== "1") {
      return;
    }
    try {
      localStorage.setItem(DURATION_KEY, value);
    } catch {
      return;
    }
    enhanceDurationOptions();
  });

  function getRequestUrl(input) {
    return typeof input === "string" ? input : (input && (input.url || input.href)) || String(input || "");
  }

  function isSupportedGenerationHost(rawUrl) {
    try {
      const requestHost = new URL(rawUrl, location.href).hostname.replace(/^www\./i, "").toLowerCase();
      return requestHost === "dola.com" || requestHost.endsWith(".dola.com") ||
        requestHost === "doubao.com" || requestHost.endsWith(".doubao.com");
    } catch {
      return false;
    }
  }

  function isVideoCompletionUrl(rawUrl) {
    if (!isSupportedGenerationHost(rawUrl || location.href)) {
      return false;
    }
    try {
      // 兼容 Dola/豆包给接口增加 /api、版本前缀或末尾斜杠。
      return new URL(rawUrl, location.href).pathname.replace(/\/$/, "").endsWith(VIDEO_COMPLETION_PATH);
    } catch {
      return false;
    }
  }

  function forceVideoAbility(ability) {
    if (!ability || Number(ability.ability_type) !== VIDEO_ABILITY_TYPE) {
      return false;
    }

    const wasString = typeof ability.ability_param === "string";
    let abilityParam = ability.ability_param;
    if (wasString) {
      try {
        abilityParam = JSON.parse(abilityParam);
      } catch {
        return false;
      }
    }
    if (!abilityParam || typeof abilityParam !== "object") {
      return false;
    }

    let changed = false;
    if (abilityParam.model !== SEEDANCE_25_MODEL) {
      abilityParam.model = SEEDANCE_25_MODEL;
      changed = true;
    }
    if (Number(abilityParam.duration) !== 30) {
      abilityParam.duration = 30;
      changed = true;
    }
    if (changed) {
      ability.ability_param = wasString ? JSON.stringify(abilityParam) : abilityParam;
    }
    return changed;
  }

  function forceGenerationPayload(value, seen = new Set()) {
    if (!value || typeof value !== "object" || seen.has(value)) {
      return false;
    }
    seen.add(value);
    let changed = forceVideoAbility(value);
    for (const child of Object.values(value)) {
      if (child && typeof child === "object") {
        changed = forceGenerationPayload(child, seen) || changed;
      }
    }
    return changed;
  }

  function timelinePercent(seconds) {
    const value = Math.max(0, seconds / TIMELINE_DURATION_SECONDS * 100);
    return Number(value.toFixed(1)).toString();
  }

  function timestampSeconds(hours, minutes, seconds) {
    return Number(hours || 0) * 3600 + Number(minutes || 0) * 60 + Number(seconds || 0);
  }

  function parseChineseSeconds(raw) {
    const text = String(raw || "");
    if (text === "半") {
      return 0.5;
    }
    const digits = { 零: 0, "〇": 0, 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 };
    const [integerText, decimalText = ""] = text.split("点");
    let integer = 0;
    if (integerText.includes("十")) {
      const [tensText, onesText] = integerText.split("十");
      integer = (tensText ? digits[tensText] : 1) * 10 + (onesText ? digits[onesText] : 0);
    } else if (integerText.length === 1 && Object.hasOwn(digits, integerText)) {
      integer = digits[integerText];
    } else {
      return NaN;
    }
    if (!decimalText) {
      return integer;
    }
    if (![...decimalText].every((character) => Object.hasOwn(digits, character))) {
      return NaN;
    }
    return Number(`${integer}.${[...decimalText].map((character) => digits[character]).join("")}`);
  }

  function secondsAsProgress(seconds, source, offset, explicitPosition = false) {
    if (!Number.isFinite(seconds) || seconds < 0) {
      return null;
    }
    const prefix = source.slice(Math.max(0, offset - 10), offset);
    const positionContext = /(?:在|于|到|至|截至|接近)$/u.test(prefix);
    if (!explicitPosition && !positionContext) {
      return `约占全片 ${timelinePercent(seconds)}% 的时长`;
    }
    return `全片进度约 ${timelinePercent(seconds)}% 处`;
  }

  function rewriteTimelinePrompt(text) {
    const original = String(text || "");
    if (!original) {
      return original;
    }

    let changed = false;
    // 旧版本已写进编辑框的控制语句也需升级，避免阻止新指令生效。
    let rewritten = original.replace(OLD_DIRECT_GENERATION_GUARD, "")
      .replace(PREVIOUS_DIRECT_GENERATION_GUARD, "");
    // 整片时长由页面选项决定，不把“十五秒视频”误解为片中时间点。
    const secondsToken = String.raw`(?:\d+(?:\.\d+)?|半|[零〇一二两三四五六七八九十]{1,3}(?:点[零〇一二两三四五六七八九]+)?)\s*(?:秒(?:钟)?|seconds?|secs?|s)(?![\w])`;
    rewritten = rewritten.replace(new RegExp(`(?:总时长|视频时长|成片时长|时长|片长)\\s*(?:为|是|约)?\\s*${secondsToken}`, "gi"), () => {
      changed = true;
      return "时长按页面设置";
    });
    rewritten = rewritten.replace(new RegExp(`${secondsToken}\\s*(?:的)?(时长|连续成片|视频|短片|影片|成片)`, "gi"), (match, kind) => {
      changed = true;
      return kind === "时长" ? "按页面设置的时长" : kind;
    });
    // 将 00:00-00:05、00 00 至 00 05 一类时间范围改为进度百分比。
    rewritten = rewritten.replace(
      /(^|[^\d])(?:(\d{1,2})\s*(?::|：|\s+)\s*)?([0-5]?\d)\s*(?::|：|\s+)\s*([0-5]\d)\s*(?:-|–|—|~|～|至|到|→)\s*(?:(\d{1,2})\s*(?::|：|\s+)\s*)?([0-5]?\d)\s*(?::|：|\s+)\s*([0-5]\d)(?=$|[^\d])/g,
      (match, prefix, startHours, startMinutes, startSeconds, endHours, endMinutes, endSeconds) => {
        const start = timestampSeconds(startHours, startMinutes, startSeconds);
        const end = timestampSeconds(endHours, endMinutes, endSeconds);
        if (end <= start || start < 0) {
          return match;
        }
        changed = true;
        return `${prefix}全片进度 ${timelinePercent(start)}%–${timelinePercent(end)}%`;
      }
    );

    // 同一提示中残留的单点时间码也同步换算，避免再次触发秒数识别。
    rewritten = rewritten.replace(
      /(^|[^\d])(?:(\d{1,2})\s*[:：]\s*)?([0-5]?\d)\s*[:：]\s*([0-5]\d)(?=$|[^\d])/g,
      (match, prefix, hours, minutes, seconds) => {
        const point = timestampSeconds(hours, minutes, seconds);
        if (point < 0) {
          return match;
        }
        changed = true;
        return `${prefix}全片进度约 ${timelinePercent(point)}%`;
      }
    );

    // 处理 29.2秒、第3秒、持续0.5秒等阿拉伯数字写法。
    rewritten = rewritten.replace(/(第)?(\d+(?:\.\d+)?)\s*秒(?:钟)?/g, (match, ordinal, numberText, offset, source) => {
      const replacement = secondsAsProgress(Number(numberText), source, offset, Boolean(ordinal));
      if (!replacement) {
        return match;
      }
      changed = true;
      return replacement;
    });

    // 英文秒数同样不留在发送的提示词中，例如 15s、15 sec、15 seconds。
    rewritten = rewritten.replace(/(\d+(?:\.\d+)?)\s*(?:seconds?|secs?|s)(?![\w])/gi, (match, numberText, offset, source) => {
      const replacement = secondsAsProgress(Number(numberText), source, offset);
      if (!replacement) return match;
      changed = true;
      return replacement;
    });

    // 处理“半秒”“三秒”“二十九点五秒”等中文数字写法。
    rewritten = rewritten.replace(
      /(第)?(半|[零〇一二两三四五六七八九十]{1,3}(?:点[零〇一二两三四五六七八九]+)?)\s*秒(?:钟)?/g,
      (match, ordinal, numberText, offset, source) => {
        const replacement = secondsAsProgress(parseChineseSeconds(numberText), source, offset, Boolean(ordinal));
        if (!replacement) {
          return match;
        }
        changed = true;
        return replacement;
      }
    );

    if (changed && !rewritten.includes(TIMELINE_GUARD_MARK)) {
      const timelineGuard = [
        TIMELINE_GUARD_MARK,
        "这是一个单次、连续的视频生成任务。下方的“全片进度”只表示同一条成片内部的镜头节奏，",
        "不代表多个视频任务；不得拆分生成，不得改为多次任务。"
      ].join("");
      rewritten = `${timelineGuard}\n\n${rewritten}`;
    }

    // 参数由页面的能力选项传递；成功调用后只保留一句简短确认。
    if (!rewritten.includes(DIRECT_GENERATION_GUARD_MARK)) {
      const directGenerationGuard = [
        DIRECT_GENERATION_GUARD_MARK,
        "用户已在页面选定视频参数，以本次请求携带的能力参数为准。",
        "直接调用一次视频生成能力，不要提问、复述比例、时长、参考图或画面描述。",
        "若当前账号的生成能力明确提供快速通道或优先队列，优先选用；否则立即正常提交，不要因此等待、询问或重复提交。",
        "仅在生成能力成功受理后回复：任务已受理，正在生成。不要添加其他文字；调用失败时简要说明原因。"
      ].join("");
      rewritten = `${directGenerationGuard}\n\n${rewritten}`;
    }
    return rewritten;
  }

  function editorText(editor) {
    if (!editor) return "";
    if (editor instanceof HTMLTextAreaElement || editor instanceof HTMLInputElement) {
      return String(editor.value || "");
    }
    return String(editor.innerText || editor.textContent || "");
  }

  function writeEditorText(editor, value) {
    if (!editor) return false;
    if (editor instanceof HTMLTextAreaElement || editor instanceof HTMLInputElement) {
      const prototype = editor instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype
        : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
      if (setter) setter.call(editor, value);
      else editor.value = value;
    } else {
      editor.focus();
      const selection = getSelection();
      const range = document.createRange();
      range.selectNodeContents(editor);
      selection?.removeAllRanges();
      selection?.addRange(range);
      let inserted = false;
      try {
        inserted = document.execCommand("insertText", false, value);
      } catch {
        inserted = false;
      }
      if (!inserted) editor.textContent = value;
      selection?.removeAllRanges();
    }
    editor.dispatchEvent(new InputEvent("input", {
      bubbles: true,
      composed: true,
      inputType: "insertText",
      data: null
    }));
    editor.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }

  function visiblePromptEditors(root = document) {
    return Array.from(root.querySelectorAll(
      "textarea:not([disabled]), input[type=\"text\"]:not([disabled]), [contenteditable=\"true\"], [role=\"textbox\"]"
    )).filter((editor) => visible(editor) && editorText(editor).trim());
  }

  function editorForSendButton(button) {
    let container = button?.parentElement || null;
    for (let depth = 0; container && depth < 9; depth += 1, container = container.parentElement) {
      const editors = visiblePromptEditors(container);
      if (editors.length) return editors[editors.length - 1];
    }
    const editors = visiblePromptEditors();
    return editors[editors.length - 1] || null;
  }

  function isLikelySendButton(button, editor) {
    if (!button || !editor || button.disabled || button.getAttribute("aria-haspopup")) return false;
    if (button.closest("[data-input-engine-actionbar-control-key]")) return false;
    const label = textOf(button);
    if (/模型|比例|时长|图片|视频|seedance|\b(?:5|10|15|30)s\b/i.test(label)) return false;
    if (button.type === "submit" || /发送|提交|生成|send|submit/i.test(label)) return true;
    const buttonRect = button.getBoundingClientRect();
    const editorRect = editor.getBoundingClientRect();
    return buttonRect.left >= editorRect.right - 90 &&
      buttonRect.top >= editorRect.top - 20 && buttonRect.bottom <= editorRect.bottom + 80;
  }

  function rewriteEditorBeforeSend(editor) {
    const current = editorText(editor);
    const rewritten = rewriteTimelinePrompt(current);
    if (!current.trim() || rewritten === current) return false;
    return writeEditorText(editor, rewritten);
  }

  function installSafePromptRewrite() {
    // 在页面自身组装、签名请求之前改写输入框；不接触 fetch/XHR。
    document.addEventListener("click", (event) => {
      const button = event.target?.closest?.("button, [role=\"button\"]");
      if (!button || button.dataset.dolaTimelineResend === "1") return;
      const editor = editorForSendButton(button);
      if (!isLikelySendButton(button, editor) || !rewriteEditorBeforeSend(editor)) return;

      event.preventDefault();
      event.stopImmediatePropagation();
      setTimeout(() => {
        button.dataset.dolaTimelineResend = "1";
        try {
          button.click();
        } finally {
          delete button.dataset.dolaTimelineResend;
        }
      }, 0);
    }, true);

    document.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
      const editor = event.target?.closest?.(
        "textarea, input[type=\"text\"], [contenteditable=\"true\"], [role=\"textbox\"]"
      );
      if (!editor || !rewriteEditorBeforeSend(editor)) return;
      // 输入法/编辑器先接收 input；随后由其原生 Enter 路径或发送按钮继续提交。
      event.preventDefault();
      event.stopImmediatePropagation();
      const container = editor.parentElement;
      const sendButton = Array.from(container?.querySelectorAll?.("button") || [])
        .filter(visible)
        .find((button) => button.type === "submit" || /发送|send/i.test(textOf(button))) || null;
      if (sendButton) setTimeout(() => sendButton.click(), 0);
    }, true);
  }

  function roleOf(value, inheritedRole = "") {
    const rawRole = value?.role || value?.sender_role || value?.sender_type || value?.author?.role || inheritedRole;
    return String(rawRole || "").toLowerCase();
  }

  function patchUserTimelinePrompts(value, inheritedRole = "", seen = new Set()) {
    if (!value || typeof value !== "object" || seen.has(value)) {
      return false;
    }
    seen.add(value);

    const currentRole = roleOf(value, inheritedRole);
    const blockedRole = /(?:system|assistant|bot|tool)/i.test(currentRole);
    let changed = false;

    if (Array.isArray(value)) {
      for (const item of value) {
        changed = patchUserTimelinePrompts(item, currentRole, seen) || changed;
      }
      return changed;
    }

    for (const [key, child] of Object.entries(value)) {
      const normalizedKey = key.toLowerCase();
      if (typeof child === "string" && PROMPT_FIELD_NAMES.has(normalizedKey) && !blockedRole) {
        const rewritten = rewriteTimelinePrompt(child);
        if (rewritten !== child) {
          value[key] = rewritten;
          changed = true;
        }
      } else if (child && typeof child === "object") {
        changed = patchUserTimelinePrompts(child, currentRole, seen) || changed;
      }
    }
    return changed;
  }

  function patchBody(body, requestUrl = "") {
    if (!duration30Enabled() || typeof body !== "string" || !body.trim() || !isVideoCompletionUrl(requestUrl)) {
      return body;
    }

    let payload;
    try {
      payload = JSON.parse(body);
    } catch {
      return body;
    }

    // 先把用户时间线改写为单片进度标记，阻止 Dola 在能力调用前把任务拆成 10/15 秒片段。
    const timelineChanged = patchUserTimelinePrompts(payload);
    // 请求层做最终固化：不管 Dola UI 当前回落成哪个模型/时长，提交时都强制 2.5 + 30s。
    const abilityChanged = forceGenerationPayload(payload);
    return timelineChanged || abilityChanged ? JSON.stringify(payload) : body;
  }

  function patchRequests() {
    const originalFetch = window.fetch;
    if (typeof originalFetch === "function") {
      window.fetch = function patchedFetch(input, init = {}) {
        if (init && typeof init.body === "string") {
          const requestUrl = getRequestUrl(input);
          const body = patchBody(init.body, requestUrl);
          if (body !== init.body) {
            init = { ...init, body };
          }
        }
        // 兼容 fetch(new Request(...))。旧版只处理 init.body，这正是部分 Dola 页面失效的来源。
        if (typeof Request !== "undefined" && input instanceof Request && !init.body && !/^(GET|HEAD)$/i.test(input.method)) {
          const receiver = this;
          return input.clone().text().then((requestBody) => {
            const patched = patchBody(requestBody, input.url);
            if (patched === requestBody) {
              return originalFetch.call(receiver, input, init);
            }
            return originalFetch.call(receiver, new Request(input, { ...init, body: patched }));
          }, () => originalFetch.call(receiver, input, init));
        }
        return originalFetch.call(this, input, init);
      };
    }

    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    if (typeof originalOpen === "function") {
      XMLHttpRequest.prototype.open = function patchedOpen(method, url) {
        this.__watermarkFreeRequestUrl = getRequestUrl(url);
        return originalOpen.apply(this, arguments);
      };
    }
    if (typeof originalSend === "function") {
      XMLHttpRequest.prototype.send = function patchedSend(body) {
        return originalSend.call(this, patchBody(body, this.__watermarkFreeRequestUrl || location.href));
      };
    }
  }

  function exactDuration(element) {
    const match = durationText(textOf(element)).match(/^(5|10|15|30)(s|秒)$/i);
    return match ? Number(match[1]) : 0;
  }

  function findDurationMenuRoot() {
    const candidates = Array.from(document.querySelectorAll(
      "[role=\"menu\"], [role=\"listbox\"], [data-slot*=\"dropdown-menu\"], [class*=\"popover\"], [class*=\"dropdown\"], div"
    ))
      .filter((element) => visible(element))
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        if (rect.width < 70 || rect.width > 520 || rect.height < 40 || rect.height > 520) {
          return false;
        }
        const text = durationText(textOf(element));
        const hasNativeDurations = /5(s|秒)/i.test(text) && /10(s|秒)/i.test(text);
        const looksLikeWholeToolbar = /Seedance|比例|参考图|模型|Model|Fast/i.test(text) && rect.width > 360;
        return hasNativeDurations && !looksLikeWholeToolbar;
      })
      .sort((first, second) => {
        const firstRect = first.getBoundingClientRect();
        const secondRect = second.getBoundingClientRect();
        return firstRect.width * firstRect.height - secondRect.width * secondRect.height;
      });
    return candidates[0] || null;
  }

  function replaceDurationLabel(element, target) {
    try {
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
      let changed = false;
      while (walker.nextNode()) {
        const next = String(walker.currentNode.nodeValue || "").replace(/(?:5|10|15|30)(s|秒)/ig, `${target}s`);
        if (next !== walker.currentNode.nodeValue) {
          walker.currentNode.nodeValue = next;
          changed = true;
        }
      }
      if (changed) {
        return;
      }
    } catch {
      // Fall through to replacing the whole label.
    }
    element.textContent = `${target}s`;
  }

  function durationCheckMarks(item) {
    return Array.from(item?.querySelectorAll?.("svg") || []);
  }

  function setDurationMenuSelected(item, selected) {
    item?.setAttribute?.("aria-selected", selected ? "true" : "false");
    if (selected) {
      item?.setAttribute?.("data-state", "checked");
    } else {
      item?.removeAttribute?.("data-state");
    }
    for (const mark of durationCheckMarks(item)) {
      mark.style.visibility = selected ? "visible" : "hidden";
    }
  }

  function syncDurationMenuSelection(root) {
    if (!root) {
      return;
    }
    const targetDuration = selectedDuration();
    const options = Array.from(root.querySelectorAll("[role=\"menuitem\"], [role=\"option\"], li, button, div"))
      .filter((element) => element !== root && visible(element) && exactDuration(element) > 0);
    for (const option of options) {
      const value = exactDuration(option);
      if (targetDuration === 15 || targetDuration === 30) {
        setDurationMenuSelected(option, value === targetDuration);
      } else if (option.getAttribute(MENU_MARK) === "30") {
        setDurationMenuSelected(option, false);
      }
    }
  }

  function updateToolbarDurationText(seconds) {
    if (!document.body) {
      return;
    }
    const menuRoot = findDurationMenuRoot();
    const isInMenu = (element) => Boolean(
      element && (menuRoot?.contains?.(element) || element.closest?.("[role=\"menu\"], [role=\"listbox\"], [data-slot=\"dropdown-menu-content\"]"))
    );
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (!/^\s*(5|10|15|30)s\s*$/i.test(node.nodeValue || "")) {
        continue;
      }
      const element = node.parentElement;
      if (!visible(element) || isInMenu(element)) {
        continue;
      }
      const trigger = element.closest?.("button, [role=\"button\"], [aria-haspopup], [aria-expanded], [data-slot*=\"trigger\"]") || element;
      if (visible(trigger) && !isInMenu(trigger)) {
        node.nodeValue = `${seconds}s`;
      }
    }
  }

  function holdToolbarDurationText(seconds) {
    for (const delay of [0, 50, 120, 300, 800, 1500, 3000]) {
      setTimeout(() => updateToolbarDurationText(seconds), delay);
    }
  }

  function stripRedundantDurationTalk(text) {
    let result = String(text || "");
    // Dola 在已经接收 30 秒请求后，偶尔仍会输出旧能力提示。只删提示句，不碰生成确认和结果。
    result = result.replace(
      /视频生成目前支持\s*\d+\s*(?:到|至|[-~～—])\s*\d+\s*秒[。.!！]?\s*/g,
      ""
    );
    result = result.replace(
      /我将按照默认\s*(?:5|10|15)\s*秒生成[。.!！]?\s*/g,
      ""
    );
    result = result.replace(
      /请(?:你)?(?:选择|选取|从中选择)[^。.!！\n]{0,40}(?:5\s*秒)[^。.!！\n]{0,20}(?:10\s*秒)[^。.!！\n]{0,20}(?:15\s*秒)[。.!！]?\s*/g,
      ""
    );
    return result;
  }

  function cleanRedundantDurationTalk(root = document.body) {
    if (!duration30Enabled() || host !== "dola.com" && !host.endsWith(".dola.com") || !root) {
      return;
    }
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      const parent = node.parentElement;
      if (!parent || parent.closest?.("script, style, textarea, input, [contenteditable=\"true\"]")) {
        continue;
      }
      const current = String(node.nodeValue || "");
      const cleaned = stripRedundantDurationTalk(current);
      if (cleaned !== current) {
        node.nodeValue = cleaned;
      }
    }
  }

  function installDolaQuietMode() {
    if (typeof MutationObserver === "undefined" || !document.body) {
      return;
    }
    cleanRedundantDurationTalk();
    let queued = false;
    const observer = new MutationObserver(() => {
      if (queued) {
        return;
      }
      queued = true;
      queueMicrotask(() => {
        queued = false;
        cleanRedundantDurationTalk();
      });
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  function injectDurationMenuOption() {
    const root = findDurationMenuRoot();
    if (!root) {
      return false;
    }

    const target = modelTargetDuration();
    let options = Array.from(root.querySelectorAll("[role=\"menuitem\"], [role=\"option\"], li, button, div"))
      .filter((element) => element !== root && visible(element) && exactDuration(element) > 0);

    for (const option of options) {
      if (option.getAttribute(MENU_MARK) === "30" && target !== 30) {
        option.remove();
      }
    }

    options = Array.from(root.querySelectorAll("[role=\"menuitem\"], [role=\"option\"], li, button, div"))
      .filter((element) => element !== root && visible(element) && exactDuration(element) > 0);

    // 15/30 必须来自能力配置形成的原生 React 选项，不再克隆 DOM 菜单。
    // rules.json 会在页面初始化阶段提供完整 5/10/15/30 配置。

    options = Array.from(root.querySelectorAll("[role=\"menuitem\"], [role=\"option\"], li, button, div"))
      .filter((element) => element !== root && visible(element) && exactDuration(element) > 0);
    for (const option of options) {
      const value = exactDuration(option);
      if (value === 30 && !option.hasAttribute(MENU_MARK)) native30Observed = true;
      if ((value === 5 || value === 10 || value === 15 || value === 30) && !option.hasAttribute(NATIVE_BOUND_MARK)) {
        option.setAttribute(NATIVE_BOUND_MARK, String(value));
        option.addEventListener("click", () => {
          if (value === 15 || value === 30) {
            saveTargetDuration(value);
          } else {
            try { localStorage.removeItem(TARGET_DURATION_KEY); } catch {}
            saveDuration30(false);
          }
        }, true);
      }
    }

    syncDurationMenuSelection(root);
    return true;
  }

  function enhanceDurationOptions() {
    injectDurationMenuOption();
  }

  // 1.4.6 起不再在 fetch/XHR 最后一刻改写 /chat/completion body。
  // 请求 URL 已包含 a_bogus/msToken/fp 等风控字段，签名完成后再改 body
  // 可能触发 710022002。30 秒能力由 document_start 的 skill-pack-main.js
  // 在页面初始化阶段注入，让 Dola 自己构造并签名最终请求。
  publishDurationState();
  installSafePromptRewrite();
  enhanceDurationOptions();
  setInterval(enhanceDurationOptions, 1000);
})();
