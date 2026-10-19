# YouTube proxy for AnyConvert

This backend uses **yt-dlp** to resolve YouTube watch URLs to direct stream URLs so the Android app can download video/audio when in-app extraction fails (e.g. signatureCipher-only responses).

## Run locally

```bash
cd backend
pip install -r requirements.txt
python app.py
```

Server listens on `http://0.0.0.0:5000`.

- **Health:** `GET /health` → 200
- **Resolve:** `GET /resolve?url=ENCODED_YOUTUBE_URL` → JSON `{ "url": "...", "mimeType": "...", "title": "..." }`

## Configure the app

1. Set the proxy base URL in the app so it can call the backend:
   - Open `app/build.gradle.kts`.
   - In `defaultConfig`, set `buildConfigField` to your backend URL, for example:
     - Local machine (phone and PC on same Wi‑Fi): `"http://192.168.1.10:5000"` (replace with your PC’s IP).
     - Public server: `"https://your-domain.com"` (use HTTPS in production).
   ```kotlin
   buildConfigField("String", "YOUTUBE_PROXY_BASE_URL", "\"http://192.168.1.10:5000\"")
   ```
2. Rebuild the app and install.

When you paste a YouTube URL and tap **Link media**, the app will try in-app extraction first; if that fails, it calls the proxy and uses the returned stream URL to download the file.

## Production

- Run the backend behind HTTPS (e.g. nginx + Let’s Encrypt).
- Set `YOUTUBE_PROXY_BASE_URL` to your `https://` URL.
- Consider rate limiting and access control on the proxy.
