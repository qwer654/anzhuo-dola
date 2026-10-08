/* Pure, opt-in prompt text utility; does not inject into requests or change model capabilities. */
(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else if (root) root.DolaPromptTools = api;
})(typeof window === "undefined" ? null : window, function () {
  "use strict";
  function parseChineseNumber(raw) {
    var text = String(raw || "");
    if (text === "半") return 0.5;
    var digits = { 零: 0, "〇": 0, 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 };
    var parts = text.split("点");
    if (parts.length > 2 || !parts[0]) return NaN;
    var integerText = parts[0], decimal = parts[1] || "", integer = 0, pending = 0, lastUnit = 10000;
    for (var i = 0; i < integerText.length; i++) {
      var c = integerText[i];
      if (Object.prototype.hasOwnProperty.call(digits, c)) pending = pending * 10 + digits[c];
      else {
        var unit = c === "十" ? 10 : c === "百" ? 100 : c === "千" ? 1000 : 0;
        if (!unit || unit >= lastUnit) return NaN;
        integer += (pending || 1) * unit; pending = 0; lastUnit = unit;
      }
    }
    integer += pending;
    var decimalDigits = "";
    for (var j = 0; j < decimal.length; j++) {
      if (!Object.prototype.hasOwnProperty.call(digits, decimal[j])) return NaN;
      decimalDigits += digits[decimal[j]];
    }
    if (parts.length === 2 && !decimalDigits) return NaN;
    return Number(String(integer) + (decimalDigits ? "." + decimalDigits : ""));
  }
  function rewriteTimeline(text, totalSeconds) {
    var total = Number(totalSeconds);
    if (!Number.isFinite(total) || total <= 0 || total > 86400) throw new RangeError("totalSeconds must be 0 < duration <= 86400");
    var input = String(text || "");
    if (input.length > 100000) throw new RangeError("Prompt is too large (maximum 100000 characters)");
    var percent = function (seconds) { return String(Number((seconds / total * 100).toFixed(1))); };
    var range = function (a, b) { return "全片进度 " + percent(a) + "%–" + percent(b) + "%"; };
    var point = function (n, duration) { return duration ? "约占全片 " + percent(n) + "% 的时长" : "全片进度约 " + percent(n) + "% 处"; };
    var timestamp = function (raw) {
      var values = raw.split(/[:：]/).map(Number);
      if (values.length < 2 || values.slice(1).some(function (n) { return n >= 60; })) return NaN;
      return values.reduce(function (result, n) { return result * 60 + n; }, 0);
    };
    var stamp = "\\d{1,3}[:：]\\d{2}(?:[:：]\\d{2}(?:\\.\\d+)?)?";
    var separator = "(?:[-–—－―~～→]|至|到|to)";
    var rewritten = input.replace(new RegExp("(^|[^\\d.:：-])(" + stamp + ")\\s*" + separator + "\\s*(" + stamp + ")(?![\\d.:：])", "gi"), function (match, prefix, a, b) {
      var start = timestamp(a), end = timestamp(b);
      return Number.isFinite(start) && Number.isFinite(end) && end > start ? prefix + range(start, end) : match;
    });
    rewritten = rewritten.replace(new RegExp("(^|[^\\d.:：-])(" + stamp + ")(?![\\d.:：])", "g"), function (match, prefix, raw) {
      var n = timestamp(raw); return Number.isFinite(n) ? prefix + point(n, false) : match;
    });
    var chinese = "(?:半|[零〇一二两三四五六七八九十百千]+(?:点[零〇一二两三四五六七八九]+)?)";
    var number = "(?:\\d+(?:\\.\\d+)?|" + chinese + ")";
    var unit = "(?:秒(?:钟)?|seconds?\\b|secs?\\b|s\\b)";
    var numeric = function (value) { return /^\d/.test(value) ? Number(value) : parseChineseNumber(value); };
    rewritten = rewritten.replace(new RegExp("(^|[^\\d.\\-])(" + number + ")\\s*(?:" + unit + ")?\\s*" + separator + "\\s*(" + number + ")\\s*" + unit, "gi"), function (match, prefix, a, b) {
      var start = numeric(a), end = numeric(b);
      return Number.isFinite(start) && Number.isFinite(end) && end > start ? prefix + range(start, end) : match;
    });
    rewritten = rewritten.replace(new RegExp("(^|[^\\d.\\-])(第)?(" + number + ")\\s*" + unit, "gi"), function (match, prefix, ordinal, raw, offset, source) {
      var n = numeric(raw);
      if (!Number.isFinite(n) || n < 0) return match;
      var previous = source.slice(Math.max(0, offset - 20), offset) + prefix;
      var duration = !ordinal && /(?:持续|延续|保持|停留|时长|等待|for\s*|lasting\s*)$/i.test(previous);
      return prefix + point(n, duration);
    });
    return rewritten;
  }
  return Object.freeze({ parseChineseNumber: parseChineseNumber, rewriteTimeline: rewriteTimeline });
});
