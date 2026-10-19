#!/usr/bin/env python3
"""
Test script for the app's link-media conversion logic.

Simplest usage (capture stream + auto-download to out.mp4):
  pip install requests beautifulsoup4 playwright && playwright install chromium
  python test_link_conversion.py "https://page-with-video.com/..." --capture

  Uses a headless browser to load the page, captures the video/stream URL and
  cookies, then runs the download (ffmpeg for HLS, or requests for direct URLs).
  Requires ffmpeg on PATH for .m3u8 streams.

Other:
  -o out.mp4         Save to a different file (with --capture).
  --print-cmd        Print copy-paste curl/ffmpeg commands instead of auto-download.
  --out-cmd file.ps1 Also save the PowerShell command to a file.
  --capture-wait 20  Wait longer for stream URLs (default 12s).
"""

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urljoin, urlparse

try:
    import requests
except ImportError:
    print("Install: pip install requests")
    sys.exit(1)
try:
    from bs4 import BeautifulSoup
except ImportError:
    print("Install: pip install beautifulsoup4")
    sys.exit(1)


# Same as app: MainActivity.kt fetchPageHtml
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/91.0.4472.120 Mobile Safari/537.36"
)
USER_AGENT_DOWNLOAD = (
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Mobile Safari/537.36"
)
TIMEOUT_FETCH = 15
TIMEOUT_DOWNLOAD = 30


def fetch_page_html(url: str) -> str | None:
    """Same as app: fetchPageHtml."""
    try:
        r = requests.get(
            url,
            headers={"User-Agent": USER_AGENT},
            timeout=TIMEOUT_FETCH,
            allow_redirects=True,
        )
        r.raise_for_status()
        return r.text
    except Exception as e:
        print(f"fetchPageHtml failed: {e}")
        return None


def is_youtube_watch_url(url: str) -> bool:
    """Same as app: isYouTubeWatchUrl."""
    lower = url.lower()
    return "youtube.com/watch" in lower or "youtu.be/" in lower


def looks_like_direct_media_url(url: str) -> bool:
    """Same as app: looksLikeDirectMediaUrl."""
    lower = url.lower()
    if any(
        x in lower
        for x in (
            "youtube.com/watch",
            "youtu.be/",
            "youtube.com/embed",
            "vimeo.com/",
        )
    ):
        return False
    path = lower.split(".com", 1)[-1].split(".org", 1)[-1].split("?")[0]
    return any(
        path.endswith(ext)
        for ext in (
            ".mp4", ".webm", ".mp3", ".m4a", ".ogg", ".wav",
            ".flac", ".avi", ".mov", ".m3u8", ".m4s", ".ts",
        )
    )


def extract_balanced_json(html: str, start: int) -> str | None:
    """Same as app: extractBalancedJson. Returns JSON string from start until matching brace/bracket."""
    if start >= len(html):
        return None
    open_char = html[start]
    close_char = "}" if open_char == "{" else "]"
    if open_char not in "{[":
        return None
    depth = 1
    i = start + 1
    while i < len(html) and depth > 0:
        c = html[i]
        if c == open_char:
            depth += 1
        elif c == close_char:
            depth -= 1
        elif c == '"':
            i += 1
            while i < len(html):
                if html[i] == '"' and html[i - 1] != "\\":
                    break
                i += 1
        i += 1
    return html[start:i] if depth == 0 else None


def parse_streaming_data_from_json(json_str: str) -> tuple[str, str | None] | None:
    """Same as app: parseStreamingDataFromJson. Returns (url, mimeType) or None."""
    try:
        root = json.loads(json_str)
        streaming_data = root.get("streamingData")
        if not streaming_data:
            return None

        def from_format(fmt: dict) -> tuple[str, str | None] | None:
            url = (fmt.get("url") or "").strip()
            if not url:
                return None
            mime = (fmt.get("mimeType") or "").split(";")[0].strip()
            return (url, mime if mime else None)

        for arr_key in ("formats", "adaptiveFormats"):
            formats = streaming_data.get(arr_key)
            if isinstance(formats, list):
                for fmt in formats:
                    if isinstance(fmt, dict):
                        result = from_format(fmt)
                        if result:
                            return result
        return None
    except Exception:
        return None


def extract_youtube_stream_from_player_response(
    html: str, start_marker: str
) -> tuple[str, str | None] | None:
    """Same as app: extractYouTubeStreamFromPlayerResponse."""
    idx = html.find(start_marker)
    if idx < 0:
        return None
    after_marker = idx + len(start_marker)
    eq = html.find("=", after_marker)
    if eq >= 0:
        after_marker = eq + 1
    brace_start = html.find("{", after_marker)
    if brace_start < 0:
        return None
    json_str = extract_balanced_json(html, brace_start)
    if not json_str:
        return None
    return parse_streaming_data_from_json(json_str)


def extract_youtube_stream_from_json_object(
    html: str, key: str
) -> tuple[str, str | None] | None:
    """Same as app: extractYouTubeStreamFromJsonObject."""
    for pattern in [f'"{key}"', key]:
        idx = 0
        while True:
            pos = html.find(pattern, idx)
            if pos < 0:
                break
            idx = pos
            after_key = idx + len(pattern)
            colon = html.find(":", after_key)
            if colon >= 0 and colon - after_key <= 3:
                brace_start = html.find("{", colon)
                if brace_start >= 0:
                    json_str = extract_balanced_json(html, brace_start)
                    if json_str:
                        wrapped = '{"streamingData": ' + json_str + "}"
                        result = parse_streaming_data_from_json(wrapped)
                        if result:
                            return result
            idx += 1
    return None


def extract_youtube_stream_url_and_mime(html: str) -> tuple[str, str | None] | None:
    """Same as app: extractYouTubeStreamUrlAndMime."""
    result = extract_youtube_stream_from_player_response(html, "ytInitialPlayerResponse")
    if result:
        return result
    result = extract_youtube_stream_from_player_response(
        html, "ytInitialPlayerResponse ="
    )
    if result:
        return result
    return extract_youtube_stream_from_json_object(html, "streamingData")


def extract_media_url_from_page_content(
    html: str, page_url: str
) -> tuple[str, str | None] | None:
    """Same as app: extractMediaUrlFromPageContent. Returns (url, mimeType) or None."""
    if is_youtube_watch_url(page_url):
        return extract_youtube_stream_url_and_mime(html)

    soup = BeautifulSoup(html, "html.parser")
    # Resolve relative URLs using page_url as base
    def abs_url(href: str) -> str:
        if not href or href.startswith(("http://", "https://")):
            return href or ""
        return urljoin(page_url, href)

    # video
    for sel in ["video[src]", "video source[src]", "video[data-src]"]:
        el = soup.select_one(sel)
        if el:
            src = el.get("src") or el.get("data-src")
            url = abs_url(src) if src else ""
            if url and looks_like_direct_media_url(url):
                return (url, None)
    # audio
    for sel in ["audio[src]", "audio source[src]", "audio[data-src]"]:
        el = soup.select_one(sel)
        if el:
            src = el.get("src") or el.get("data-src")
            url = abs_url(src) if src else ""
            if url and looks_like_direct_media_url(url):
                return (url, None)
    # og:video, og:audio
    for prop in ["og:video", "og:video:url", "og:audio"]:
        el = soup.find("meta", attrs={"property": prop})
        if el and el.get("content"):
            url = (el["content"] or "").strip()
            if url and looks_like_direct_media_url(url):
                return (url, None)
    # PDF (quote attribute values so . and / are not parsed as selectors)
    for sel, attr in [
        ('a[href$=".pdf"]', "href"),
        ('embed[type="application/pdf"]', "src"),
        ('a[href*=".pdf"]', "href"),
    ]:
        el = soup.select_one(sel)
        if el and el.get(attr):
            url = abs_url(el[attr])
            if url and url.strip():
                return (url, None)

    # Fallback: many sites put stream URL in data-* attributes or in script/JSON
    for el in soup.find_all(attrs=lambda a: a and any(k.startswith("data-") for k in a)):
        for attr in ("data-video", "data-src", "data-url", "data-file", "data-source", "data-stream"):
            val = el.get(attr)
            if val and isinstance(val, str):
                url = abs_url(val.strip())
                if url and looks_like_direct_media_url(url):
                    return (url, None)
    # Links that point to common stream extensions
    for el in soup.find_all("a", href=True):
        href = el["href"]
        if ".m3u8" in href or ".mp4" in href or ".webm" in href:
            url = abs_url(href)
            if url and ("m3u8" in url or "mp4" in url or "webm" in url):
                return (url, None)
    # Regex over raw HTML: "url":"...", "file":"...", or plain https://.../.m3u8 / .mp4
    for pattern in [
        r'["\'](https?://[^"\']+\.(?:m3u8|mp4|webm|m4s)(?:\?[^"\']*)?)["\']',
        r'["\'](?:url|file|source|src)["\']\s*:\s*["\'](https?://[^"\']+)["\']',
        r'url\s*=\s*["\'](https?://[^"\']+)["\']',
    ]:
        for m in re.finditer(pattern, html, re.IGNORECASE):
            url = (m.group(1) or "").strip()
            if url and looks_like_direct_media_url(url):
                return (url, None)
    return None


def download_media(
    media_url: str, mime_type: str | None = None, save_path: str | None = None
) -> tuple[bool, str]:
    """Same headers as app: downloadToCache (including Referer/Origin for googlevideo.com)."""
    headers = {"User-Agent": USER_AGENT_DOWNLOAD}
    if "googlevideo.com" in media_url:
        headers["Referer"] = "https://www.youtube.com/"
        headers["Origin"] = "https://www.youtube.com"
    try:
        r = requests.get(
            media_url,
            headers=headers,
            timeout=TIMEOUT_DOWNLOAD,
            stream=True,
        )
        r.raise_for_status()
        data = r.content
        if len(data) == 0:
            return False, "Empty response"
        if save_path:
            with open(save_path, "wb") as f:
                f.write(data)
            return True, f"Saved {len(data)} bytes to {save_path}"
        return True, f"Downloaded {len(data)} bytes (no file written)"
    except Exception as e:
        return False, str(e)


def _write_ps1_cmd(
    stream_url: str,
    origin: str,
    cookie_header: str,
    output_path: str,
    is_hls: bool,
    out_cmd: str,
) -> None:
    """Write PowerShell one-liner to out_cmd."""
    origin_ps = origin.rstrip("/").replace("'", "''").replace('"', '`"')
    ua_ps = USER_AGENT.replace("'", "''").replace('"', '`"')
    uri_ps = stream_url.replace("'", "''")
    if is_hls:
        cookie_ps = cookie_header.replace("'", "''").replace('"', '`"') if cookie_header else ""
        ps_headers = f"Referer: {origin_ps}`r`nUser-Agent: {ua_ps}"
        if cookie_ps:
            ps_headers += f"`r`nCookie: {cookie_ps}"
        ps_headers += "`r`n"
        cmd = f'ffmpeg -headers "{ps_headers}" -i \'{uri_ps}\' -c copy {output_path}'
    else:
        headers_ps = f"@{{'Referer'='{origin_ps}'; 'User-Agent'='{ua_ps}'"
        if cookie_header:
            headers_ps += f"; 'Cookie'='{cookie_header.replace(chr(39), chr(39)+chr(39))}'"
        headers_ps += "}}"
        cmd = f"Invoke-WebRequest -Uri '{uri_ps}' -OutFile {output_path} -Headers {headers_ps}"
    Path(out_cmd).write_text(cmd, encoding="utf-8")


def _print_manual_commands(
    stream_url: str,
    origin: str,
    cookie_header: str,
    output_path: str,
    is_hls: bool,
    out_cmd: str | None,
) -> None:
    """Print copy-paste curl/ffmpeg/PowerShell commands."""
    origin_ps = origin.rstrip("/").replace("'", "''").replace('"', '`"')
    ua_ps = USER_AGENT.replace("'", "''").replace('"', '`"')
    uri_ps = stream_url.replace("'", "''")
    if is_hls:
        header_parts = [f"Referer: {origin.rstrip('/')}", f"User-Agent: {USER_AGENT}"]
        if cookie_header:
            header_parts.append(f"Cookie: {cookie_header}")
        header_content = "\\r\\n".join(header_parts) + "\\r\\n"
        header_bash = header_content.replace("'", "\\'")
        uri_bash = stream_url.replace("'", "'\\''")
        print("  Unix/Git Bash:")
        print(f"  ffmpeg -headers $'{header_bash}' -i '{uri_bash}' -c copy {output_path}")
        print()
        cookie_ps = cookie_header.replace("'", "''").replace('"', '`"') if cookie_header else ""
        ps_headers = f"Referer: {origin_ps}`r`nUser-Agent: {ua_ps}"
        if cookie_ps:
            ps_headers += f"`r`nCookie: {cookie_ps}"
        ps_headers += "`r`n"
        ps_cmd = f'ffmpeg -headers "{ps_headers}" -i \'{uri_ps}\' -c copy {output_path}'
        print("  PowerShell:")
        print(f"  {ps_cmd}")
        if out_cmd:
            Path(out_cmd).write_text(ps_cmd, encoding="utf-8")
            print(f"  Saved to: {out_cmd}")
    else:
        ua_escaped = USER_AGENT.replace('\\', '\\\\').replace('"', '\\"')
        cookie_escaped = cookie_header.replace('\\', '\\\\').replace('"', '\\"') if cookie_header else ""
        curl_headers = f'curl -o {output_path} -H "Referer: {origin}" -H "User-Agent: {ua_escaped}"'
        if cookie_escaped:
            curl_headers += f' -H "Cookie: {cookie_escaped}"'
        print("  curl:", curl_headers, f'"{stream_url}"')
        headers_ps = f"@{{'Referer'='{origin_ps}'; 'User-Agent'='{ua_ps}'"
        if cookie_header:
            headers_ps += f"; 'Cookie'='{cookie_header.replace(chr(39), chr(39)+chr(39))}'"
        headers_ps += "}}"
        ps_cmd = f"Invoke-WebRequest -Uri '{uri_ps}' -OutFile {output_path} -Headers {headers_ps}"
        print("  PowerShell:", ps_cmd)
        if out_cmd:
            Path(out_cmd).write_text(ps_cmd, encoding="utf-8")
            print(f"  Saved to: {out_cmd}")


def _run_capture_download(
    stream_url: str,
    origin: str,
    cookie_header: str,
    output_path: str,
    is_hls: bool,
) -> tuple[bool, str]:
    """Download stream to output_path. Returns (success, message)."""
    origin = origin.rstrip("/")
    headers_dict = {"Referer": origin, "User-Agent": USER_AGENT}
    if cookie_header:
        headers_dict["Cookie"] = cookie_header
    if is_hls:
        # ffmpeg -headers "..." -i URL -c copy out
        header_str = f"Referer: {origin}\r\nUser-Agent: {USER_AGENT}\r\n"
        if cookie_header:
            header_str += f"Cookie: {cookie_header}\r\n"
        header_str += "\r\n"
        try:
            # Some HLS streams use segment URLs with non-standard extensions (e.g. .gif);
            # allow them so we don't get "not in allowed_segment_extensions" / "Empty segment"
            # Short timeout: if variant playlists are fake (e.g. captcha .gif/.html), ffmpeg
            # will never succeed; we fail fast and fall back to yt-dlp instead of waiting minutes.
            proc = subprocess.run(
                [
                    "ffmpeg", "-y",
                    "-headers", header_str,
                    "-allowed_segment_extensions", "ts,m4s,m4v,mp4,mov,mpegts,fmp4,m3u8,gif,html",
                    "-extension_picky", "0",
                    "-i", stream_url,
                    "-c", "copy",
                    output_path,
                ],
                capture_output=False,
                timeout=60,
            )
            if proc.returncode == 0 and Path(output_path).exists():
                return True, output_path
            msg = f"ffmpeg exited {proc.returncode}"
            msg += " (stream may use anti-bot responses; try: yt-dlp -o out.mp4 <page-url>)"
            return False, msg
        except FileNotFoundError:
            return False, "ffmpeg not found. Install ffmpeg and add it to PATH."
        except subprocess.TimeoutExpired:
            return False, "ffmpeg timed out."
        except Exception as e:
            return False, str(e)
    # Direct URL: requests (show progress by size)
    try:
        r = requests.get(stream_url, headers=headers_dict, timeout=60, stream=True)
        r.raise_for_status()
        total = int(r.headers.get("content-length") or 0)
        written = 0
        with open(output_path, "wb") as f:
            for chunk in r.iter_content(chunk_size=65536):
                if chunk:
                    f.write(chunk)
                    written += len(chunk)
                    if total and written % (2 * 1024 * 1024) < 65536:  # every ~2 MB
                        pct = min(100, 100 * written // total)
                        print(f"\r  {written // (1024*1024)} MB ({pct}%)", end="", flush=True)
        if total:
            print()
        return True, output_path
    except Exception as e:
        return False, str(e)


def _try_yt_dlp(page_url: str, output_path: str) -> tuple[bool, str]:
    """Fallback: try yt-dlp on the page URL (handles many protected streams). Returns (success, message)."""
    out_arg = str(Path(output_path).resolve())
    if shutil.which("yt-dlp"):
        cmd = ["yt-dlp", "-o", out_arg, "--no-warnings", page_url]
    else:
        try:
            import yt_dlp  # noqa: F401
        except ImportError:
            return False, "yt-dlp not found (pip install yt-dlp)"
        cmd = [sys.executable, "-m", "yt_dlp", "-o", out_arg, "--no-warnings", page_url]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=False,
            timeout=1800,
        )
        if proc.returncode == 0 and Path(output_path).exists():
            return True, output_path
        return False, f"yt-dlp exited {proc.returncode}"
    except subprocess.TimeoutExpired:
        return False, "yt-dlp timed out"
    except Exception as e:
        return False, str(e)


def run_capture(
    url: str,
    wait_secs: float,
    verbose: bool = False,
    out_cmd: str | None = None,
    output_path: str = "out.mp4",
    print_commands: bool = False,
    ffmpeg_only: bool = False,
    ytdlp_only: bool = False,
) -> None:
    """Load page in headless browser, record network requests, print video/stream URLs."""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("Install Playwright: pip install playwright")
        print("Then: playwright install chromium")
        sys.exit(1)

    # Patterns that suggest video/audio stream (URL or content-type)
    VIDEO_URL_PATTERNS = (
        ".m3u8",
        ".mp4",
        ".webm",
        ".m4s",
        ".ts",
        "/video/",
        "/stream/",
        "googlevideo.com",
        "videoplayback",
        "/movie/",
        "/play/",
        "cloudfront",
        "akamai",
        "bitmovin",
        "/hls/",
        "/dash/",
        "segment",
        "chunk",
        "videomanifest",
        "master.m3u8",
        "playlist.m3u8",
    )
    VIDEO_TYPES = ("video/", "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/")

    captured: list[tuple[str, str | None]] = []  # (url, content_type or None)
    all_requests: list[str] = [] if verbose else None
    captured_cookies: list[dict] = []  # filled before browser closes

    def on_request(req):
        u = req.url
        if verbose and u.startswith("http"):
            all_requests.append(u)
        if any(p in u.lower() for p in VIDEO_URL_PATTERNS):
            captured.append((u, None))

    def on_response(resp):
        u = resp.url
        ct = (resp.headers.get("content-type") or "").lower()
        if any(p in u.lower() for p in VIDEO_URL_PATTERNS) or any(
            ct.startswith(t) for t in VIDEO_TYPES
        ):
            captured.append((u, ct))

    print("Capture mode: loading URL in headless browser and recording network requests...")
    print(f"Waiting up to {wait_secs}s for video requests...")
    if verbose:
        print("Verbose: will list all request URLs.")
    print()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            user_agent=USER_AGENT,
            viewport={"width": 1280, "height": 720},
        )
        context.set_default_timeout(20000)
        page = context.new_page()
        page.on("request", on_request)
        page.on("response", on_response)
        try:
            page.goto(url, wait_until="load", timeout=20000)
            page.wait_for_timeout(2000)
            # Try to click play / video so the player requests the stream
            play_selectors = [
                'button[aria-label*="lay"]',
                '[class*="play"]',
                '[class*="Play"]',
                '[id*="play"]',
                'a[href*=".m3u8"]',
                'a[href*=".mp4"]',
                "video",
                '[data-url]',
                'iframe',
            ]
            for selector in play_selectors:
                try:
                    for el in page.query_selector_all(selector):
                        try:
                            if selector == "iframe":
                                continue  # don't click iframes
                            el.click()
                            page.wait_for_timeout(2500)
                            break
                        except Exception:
                            pass
                    else:
                        continue
                    break
                except Exception:
                    pass
            page.wait_for_timeout(max(0, int(wait_secs * 1000) - 4000))
        except Exception as e:
            print(f"Navigation/click warning: {e}")
        finally:
            try:
                captured_cookies[:] = context.cookies()
            except Exception:
                pass
            browser.close()

    # Format cookies from browser session for download
    cookie_header = ""
    if captured_cookies:
        cookie_header = "; ".join(f"{c.get('name', '')}={c.get('value', '')}" for c in captured_cookies if c.get("name"))
        if verbose and cookie_header:
            print(f"Captured {len(captured_cookies)} cookie(s) from browser session.")
            print()

    # Dedupe by URL, keep first occurrence
    seen = set()
    unique = []
    for u, ct in captured:
        if u not in seen:
            seen.add(u)
            unique.append((u, ct))

    # Filter out page URL, blob: (not downloadable), and analytics/ping
    page_domain = url.split("/")[2] if "/" in url else ""
    def skip_url(u: str) -> bool:
        if u == url or u.startswith("blob:"):
            return True
        if ".gif" in u.lower() or "ping" in u.lower() or "/error/" in u.lower():
            return True
        if u.startswith("http") and page_domain in u and u.rstrip("/").endswith(".html"):
            return True
        return False
    filtered = [(u, ct) for u, ct in unique if not skip_url(u)]
    blob_urls = [(u, ct) for u, ct in unique if u.startswith("blob:")]

    # Prefer likely stream URLs first: /hls/, cloudfront with path, content-type video/
    def stream_priority(item):
        u, ct = item
        score = 0
        if ct and "video" in (ct or ""):
            score += 10
        if "/hls/" in u or ".m3u8" in u:
            score += 8
        if "cloudfront" in u and "/" in u.split("?", 1)[0].rstrip("/").split("/")[-1]:
            score += 5
        if "ployan" in u or "stream" in u:
            score += 4
        return -score
    filtered.sort(key=stream_priority)

    if verbose and all_requests:
        print("All request URLs (for debugging):")
        for u in all_requests[:80]:
            print(f"  {u[:120]}{'...' if len(u) > 120 else ''}")
        if len(all_requests) > 80:
            print(f"  ... and {len(all_requests) - 80} more")
        print()

    if not filtered and not blob_urls:
        print("No video/stream URLs captured.")
        print("Try: --capture-verbose to see all request URLs, or --capture-wait 20 for longer wait.")
        print("The page may load video in an iframe, require login, or use non-standard URL patterns.")
        return

    first_downloadable = filtered[0][0] if filtered else None
    if not first_downloadable:
        return
    parsed = urlparse(url)
    origin = f"{parsed.scheme}://{parsed.netloc}/"
    is_hls = ".m3u8" in first_downloadable

    if verbose or print_commands:
        print(f"Captured {len(filtered)} stream URL(s); using first:")
        print(f"  {first_downloadable[:100]}{'...' if len(first_downloadable) > 100 else ''}")
        if cookie_header:
            print(f"  + {len(captured_cookies)} cookie(s)")
        print()

    # Auto-download (engine: ffmpeg only, yt-dlp only, or ffmpeg then yt-dlp)
    if ytdlp_only:
        print(f"Downloading to {output_path} (yt-dlp only)...")
        yt_ok, yt_msg = _try_yt_dlp(url, output_path)
        if yt_ok:
            print(f"Done: {yt_msg}")
            return
        print(f"Download failed: {yt_msg}")
    else:
        print(f"Downloading to {output_path}...")
        ok, msg = _run_capture_download(
            first_downloadable, origin, cookie_header, output_path, is_hls
        )
        if ok:
            print(f"Done: {msg}")
            if out_cmd:
                _write_ps1_cmd(first_downloadable, origin, cookie_header, output_path, is_hls, out_cmd)
                print(f"Command also saved to: {out_cmd}")
            return
        print(f"Download failed: {msg}")
        if not ffmpeg_only:
            print("Trying yt-dlp on the page URL...")
            yt_ok, yt_msg = _try_yt_dlp(url, output_path)
            if yt_ok:
                print(f"Done (via yt-dlp): {yt_msg}")
                return
            print(f"yt-dlp failed: {yt_msg}")
    if not print_commands:
        print("Run with --print-cmd to see copy-paste curl/ffmpeg commands.")
    else:
        _print_manual_commands(first_downloadable, origin, cookie_header, output_path, is_hls, out_cmd)


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(
        description="Test app link-media conversion logic on a URL"
    )
    parser.add_argument("url", help="Page URL (e.g. YouTube watch or any page with media)")
    parser.add_argument(
        "--download",
        action="store_true",
        help="If a media URL is found, try to download it (same headers as app)",
    )
    parser.add_argument(
        "-o", "--output",
        default=None,
        help="Output path (with --capture: default out.mp4; else link_media_<timestamp>.<ext>)",
    )
    parser.add_argument(
        "--capture",
        action="store_true",
        help="Load URL in headless browser and capture video/stream URLs from network requests",
    )
    parser.add_argument(
        "--capture-wait",
        type=float,
        default=12.0,
        metavar="SECS",
        help="Seconds to wait for video requests when using --capture (default: 12)",
    )
    parser.add_argument(
        "--capture-verbose",
        action="store_true",
        help="With --capture: print all request URLs (to spot media patterns)",
    )
    parser.add_argument(
        "--out-cmd",
        metavar="FILE",
        default=None,
        help="With --capture: also save PowerShell command to FILE (e.g. download.ps1)",
    )
    parser.add_argument(
        "--print-cmd",
        action="store_true",
        help="With --capture: print copy-paste curl/ffmpeg commands (default: just run download)",
    )
    engine = parser.add_mutually_exclusive_group()
    engine.add_argument(
        "--ffmpeg-only",
        action="store_true",
        help="With --capture: use only ffmpeg (no yt-dlp fallback)",
    )
    engine.add_argument(
        "--ytdlp-only",
        action="store_true",
        dest="ytdlp_only",
        help="With --capture: use only yt-dlp on the page URL (skip ffmpeg)",
    )
    args = parser.parse_args()
    url = args.url.strip()

    if args.capture:
        out_path = args.output or "out.mp4"
        run_capture(
            url,
            args.capture_wait,
            verbose=args.capture_verbose,
            out_cmd=args.out_cmd,
            output_path=out_path,
            print_commands=args.print_cmd,
            ffmpeg_only=args.ffmpeg_only,
            ytdlp_only=args.ytdlp_only,
        )
        return

    print("Testing app logic for:", url)
    print("  (Same as: Link media → paste URL → OK)")
    print()

    # 1. Fetch HTML (same as app)
    print("1. fetchPageHtml...")
    html = fetch_page_html(url)
    if not html:
        print("   FAILED. App would show: Could not get media from link.")
        return
    print(f"   OK ({len(html)} chars)")
    print()

    # 2. Extract media URL (same as app)
    print("2. extractMediaUrlFromPageContent...")
    result = extract_media_url_from_page_content(html, url)
    if not result:
        print("   No direct media URL found.")
        print("   App would: save page as HTML → Convert to shows PDF, DOCX, TXT, etc.")
        if is_youtube_watch_url(url):
            print("   (YouTube often only gives signatureCipher, so app falls back to HTML.)")
        return

    media_url, mime_type = result
    print(f"   URL: {media_url[:80]}...")
    print(f"   mimeType: {mime_type or '(infer from URL)'}")
    print()
    print("   App would: download this URL and add as selected file.")
    print("   Convert to would show: video/audio formats (e.g. MP4, WebM, MP3).")
    print()

    # 3. Optional download test (same headers as app)
    if args.download:
        print("3. download (app headers, including Referer/Origin for googlevideo.com)...")
        path = args.output
        if not path:
            ext = "mp4" if "googlevideo.com" in media_url else "bin"
            path = f"link_media_test.{ext}"
        ok, msg = download_media(media_url, mime_type, path)
        if ok:
            print(f"   OK: {msg}")
        else:
            print(f"   FAILED: {msg}")
            print("   (App would then fall back to saving page as HTML.)")
    else:
        print("3. Run with --download to test the actual download (same as app).")


if __name__ == "__main__":
    main()
