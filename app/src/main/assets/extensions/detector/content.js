"use strict";
// Bridge: background -> this tab's content script -> app (native messaging).
// Messages from a content script carry the GeckoSession, so the app knows which tab it is.

function forward(item) {
  item.title = document.title || "";
  browser.runtime.sendNativeMessage("browser", { type: "media", item }).catch(() => {});
}

browser.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === "media") forward(msg.item);
});

browser.runtime.sendNativeMessage("browser", { type: "reset", url: location.href }).catch(() => {});
browser.runtime.sendMessage({ type: "hello" }).then((items) => {
  for (const item of items || []) forward(item);
}).catch(() => {});
