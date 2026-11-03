"use strict";
const status = document.getElementById("status");
async function show() {
  const tabs = await browser.tabs.query({active: true, currentWindow: true});
  if (!tabs[0]) { status.textContent = "No active tab."; return; }
  const candidates = await browser.runtime.sendMessage({type: "candidates", tabId: tabs[0].id});
  status.textContent = candidates.length ? `${candidates.length} stream(s) found` : "No streams found yet. Start the video, then reopen this menu.";
  const container = document.getElementById("items");
  for (const item of candidates) {
    const button = document.createElement("button");
    const parsed = new URL(item.url);
    // Do not display query strings; they can carry access credentials.
    button.textContent = `${item.kind} — ${parsed.hostname}`;
    if (!/\.(m3u8|mpd|mp4|mkv|webm|mov|m4v|ts|mp3|aac|wav|ogg|flac|m4a)$/i.test(parsed.pathname)) {
      button.disabled = true;
      button.textContent += " (extensionless; not yet supported)";
    }
    button.addEventListener("click", async () => {
      if (!navigator.share) { status.textContent = "Android sharing unavailable in Firefox. Copying the link instead."; try { await navigator.clipboard.writeText(item.url); } catch (_) { status.textContent = "Sharing unavailable. Open the link with a compatible sharing method."; } return; }
      try { await navigator.share({url: item.url}); } catch (_) { status.textContent = "Share cancelled or unavailable."; }
    });
    container.append(button);
  }
}
show().catch(() => { status.textContent = "Unable to inspect the current tab."; });
