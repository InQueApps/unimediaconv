"""
YouTube proxy for AnyConvert: resolves a YouTube watch URL to a direct stream URL
using yt-dlp so the Android app can download the video/audio.

Run: pip install -r requirements.txt && python app.py
Then set YOUTUBE_PROXY_BASE_URL in the app to http://YOUR_IP:5000 (or your public URL).

Endpoints:
  GET /resolve?url=YOUTUBE_URL  -> JSON { "url": "...", "mimeType": "...", "title": "..." }
  GET /health                   -> 200 OK (for liveness)
"""

import json
import logging
import urllib.parse

import yt_dlp
from flask import Flask, request, jsonify

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Options for yt-dlp: prefer formats with direct URL, no download
YDL_OPTS = {
    "quiet": True,
    "no_warnings": True,
    "extract_flat": False,
    "format": "best[ext=mp4]/best[ext=webm]/best",  # prefer mp4 then webm then best
}


def extract_stream_url(video_url: str) -> dict | None:
    """Use yt-dlp to get a direct stream URL. Returns dict with url, mimeType, title or None."""
    out = None

    def _progress(d):
        if d.get("status") == "finished":
            nonlocal out
            # info_dict is set when post_extraction runs; we need the chosen format URL
            pass

    opts = {
        **YDL_OPTS,
        "progress_hooks": [_progress],
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(video_url, download=False)
            if not info:
                return None
            # Prefer direct URL from selected format (when format is a single stream)
            direct_url = info.get("url")
            ext = (info.get("ext") or "mp4").lower()
            mime_map = {
                "mp4": "video/mp4",
                "webm": "video/webm",
                "m4a": "audio/mp4",
                "webm_audio": "audio/webm",
            }
            mime_type = mime_map.get(ext, "video/mp4")
            title = (info.get("title") or "video").replace("/", "-")[:200]
            if direct_url and not direct_url.startswith("mhtml:"):
                return {"url": direct_url, "mimeType": mime_type, "title": title}
            # Fallback: pick best format with a direct url from formats list
            formats = info.get("formats") or []
            for f in formats:
                u = f.get("url")
                if u and not u.startswith("mhtml:"):
                    ext = (f.get("ext") or "mp4").lower()
                    mt = mime_map.get(ext, "video/mp4")
                    return {"url": u, "mimeType": mt, "title": title}
            return None
    except Exception as e:
        logger.exception("yt-dlp extract failed: %s", e)
        return None


@app.route("/health")
def health():
    return "", 200


@app.route("/resolve")
def resolve():
    raw = request.args.get("url")
    if not raw:
        return jsonify({"error": "missing url"}), 400
    try:
        url = urllib.parse.unquote(raw.strip())
    except Exception:
        return jsonify({"error": "invalid url"}), 400
    if "youtube.com" not in url and "youtu.be" not in url:
        return jsonify({"error": "not a youtube url"}), 400
    result = extract_stream_url(url)
    if not result:
        return jsonify({"error": "could not resolve stream"}), 404
    return jsonify(result)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
