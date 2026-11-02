"use strict";
// Detects media (HLS/DASH manifests and plain video/audio files) per tab and
// hands them to the tab's content script, which forwards them to the app.

const MIN_FILE_BYTES = 512 * 1024;
const KEEP_HEADERS = new Set([
  "cookie", "referer", "origin", "user-agent", "authorization", "accept-language"
]);

const pendingHeaders = new Map(); // requestId -> headers
const tabMedia = new Map();       // tabId -> Map(url -> item)

function mediaFor(tabId) {
  let m = tabMedia.get(tabId);
  if (!m) { m = new Map(); tabMedia.set(tabId, m); }
  return m;
}

browser.webRequest.onBeforeRequest.addListener((d) => {
  if (d.type === "main_frame" && d.tabId >= 0) tabMedia.delete(d.tabId);
}, { urls: ["<all_urls>"], types: ["main_frame"] });

browser.webRequest.onBeforeSendHeaders.addListener((d) => {
  if (d.tabId < 0) return;
  const h = {};
  for (const { name, value } of d.requestHeaders || []) {
    const n = name.toLowerCase();
    if (KEEP_HEADERS.has(n) || n.startsWith("x-")) h[name] = value;
  }
  pendingHeaders.set(d.requestId, h);
}, { urls: ["<all_urls>"] }, ["requestHeaders"]);

function header(list, name) {
  for (const h of list || []) if (h.name.toLowerCase() === name) return h.value || "";
  return "";
}

function classify(url, contentType, totalBytes) {
  const path = url.split("#")[0].split("?")[0].toLowerCase();
  const ct = contentType.split(";")[0].trim().toLowerCase();
  if (ct.includes("mpegurl") || path.endsWith(".m3u8")) return "hls";
  if (ct === "application/dash+xml" || path.endsWith(".mpd")) return "dash";
  // Stream segments: never list them as standalone videos.
  if (/\.(ts|m4s|m4f|cmfv|cmfa|aac|vtt|webvtt)$/.test(path) || ct === "video/mp2t" || ct === "video/iso.segment") return null;
  const fileExt = /\.(mp4|m4v|webm|mkv|mov|avi|flv|3gp|mp3|m4a|ogg|opus|wav|flac)$/.test(path);
  const isMedia = ct.startsWith("video/") || ct.startsWith("audio/") ||
                  (fileExt && (ct === "application/octet-stream" || ct === "binary/octet-stream" || ct === ""));
  if (!isMedia) return null;
  if (totalBytes > 0 && totalBytes < MIN_FILE_BYTES) return null;
  return ct.startsWith("audio/") || /\.(mp3|m4a|ogg|opus|wav|flac)$/.test(path) ? "audio" : "video";
}

browser.webRequest.onHeadersReceived.addListener((d) => {
  const headers = pendingHeaders.get(d.requestId) || {};
  pendingHeaders.delete(d.requestId);
  if (d.tabId < 0 || d.statusCode >= 400) return;
  if (d.url.startsWith("blob:") || d.url.startsWith("data:")) return;

  const ct = header(d.responseHeaders, "content-type");
  let total = parseInt(header(d.responseHeaders, "content-length"), 10) || 0;
  const range = header(d.responseHeaders, "content-range"); // bytes 0-99/12345
  const slash = range.lastIndexOf("/");
  if (slash > 0) total = parseInt(range.slice(slash + 1), 10) || total;

  const kind = classify(d.url, ct, total);
  if (!kind) return;

  const media = mediaFor(d.tabId);
  if (media.has(d.url)) return;
  const item = {
    url: d.url,
    kind,
    mime: ct.split(";")[0].trim(),
    size: total,
    headers,
    pageUrl: d.documentUrl || d.originUrl || "",
    time: Date.now()
  };
  media.set(d.url, item);
  browser.tabs.sendMessage(d.tabId, { type: "media", item }, { frameId: 0 }).catch(() => {});
}, { urls: ["<all_urls>"] }, ["responseHeaders"]);

browser.webRequest.onErrorOccurred.addListener((d) => pendingHeaders.delete(d.requestId), { urls: ["<all_urls>"] });
browser.tabs.onRemoved.addListener((tabId) => tabMedia.delete(tabId));

// Content script asks for whatever was detected before it loaded.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "hello" && sender.tab) {
    return Promise.resolve([...mediaFor(sender.tab.id).values()]);
  }
});
