"use strict";
// Candidate URLs remain in memory only. Never write them to logs or storage.
const byTab = new Map();
function remember(details, mime = "") {
  if (details.tabId < 0) return;
  const kind = classifyMedia(details.url, mime);
  if (!kind) return;
  const items = byTab.get(details.tabId) || [];
  if (!items.some(item => item.url === details.url)) {
    items.push({ url: details.url, kind });
    if (items.length > 100) items.shift();
    byTab.set(details.tabId, items);
  }
}
browser.webRequest.onBeforeRequest.addListener(remember, {urls: ["<all_urls>"]});
browser.webRequest.onHeadersReceived.addListener(details => {
  const header = (details.responseHeaders || []).find(h => h.name.toLowerCase() === "content-type");
  remember(details, header?.value || "");
}, {urls: ["<all_urls>"]}, ["responseHeaders"]);
browser.webNavigation.onCommitted.addListener(details => {
  if (details.frameId === 0) byTab.delete(details.tabId);
});
browser.tabs.onRemoved.addListener(tabId => byTab.delete(tabId));
browser.runtime.onMessage.addListener(message => {
  if (message?.type === "candidates" && Number.isInteger(message.tabId)) return Promise.resolve(byTab.get(message.tabId) || []);
});
