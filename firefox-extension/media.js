/* URL and MIME classifier shared by background and node tests. */
function classifyMedia(url, mime = "") {
  try {
    const parsed = new URL(url);
    if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password) return null;
    const type = mime.split(";")[0].trim().toLowerCase();
    const extension = parsed.pathname.toLowerCase().match(/\.(m3u8|mpd|mp4|mkv|webm|mov|m4v|mp3|aac|wav|ogg|flac|m4a)$/);
    if (extension) return extension[1].toUpperCase();
    if (type === "application/vnd.apple.mpegurl" || type === "application/x-mpegurl") return "HLS";
    if (type === "application/dash+xml") return "DASH";
    if (type.startsWith("video/") || type.startsWith("audio/")) return type;
  } catch (_) { /* Ignore invalid URLs. */ }
  return null;
}
if (typeof module !== "undefined") module.exports = { classifyMedia };
