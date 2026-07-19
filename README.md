# Photo Sync

> **🤖 Built entirely by AI.** Every line of code, all documentation, and the
> debugging were written by Claude (Anthropic's AI) in Claude Code. A human
> directed the work — specifying requirements, deploying to a real server,
> testing on physical phones, and reporting back results — but wrote none of the
> code or docs themselves. See [Development](#development) for details.

One-way backup of the photos **and** videos on an Android phone to a
self-hosted home server. Two parts:

| Part | Where it runs | Tech |
|------|---------------|------|
| **`server/`** | the server (behind your existing Caddy proxy on port **8443**) | Python + FastAPI + uvicorn |
| **`android/`** | Your phone (sideloaded APK) | Kotlin + WorkManager + OkHttp |

## How it works

```
 ┌──────────────────────────┐         HTTPS (TLS at proxy)          ┌───────────────────────────┐
 │ Android app (Galaxy S25)  │  POST /photos/v1/upload  (Bearer)     │ Caddy :8443  ──►  uvicorn   │
 │  • WorkManager every 15m  │ ────────────────────────────────────► │  :8080  (FastAPI receiver)  │
 │  • MediaStore scan        │  X-Device / X-Capture-Date / X-Sha256 │                             │
 │  • Room DB = already-sent │ ◄──────────────────────────────────── │  writes /data/photos/...    │
 └──────────────────────────┘        {"status":"stored"}            └───────────────────────────┘
```

* **Connectivity:** the receiver listens only on `127.0.0.1:8080`; your existing
  **Caddy** reverse proxy on **port 8443** (already forwarded, outside your ISP's
  blocked ports) exposes it at `https://your-domain.duckdns.org:8443/photos/` via a
  `/photos` route. TLS is handled by Caddy's existing certificate — no new port
  is opened.
* **Auth:** a long pre-shared bearer token, stored encrypted on the phone and in
  a `chmod 600` `.env` on the server. Compared in constant time; never logged.
* **Scope:** camera **images and videos**. Strictly **one-way** — nothing is ever
  deleted on the server, even if you delete it on the phone.
* **Retries:** the periodic worker runs every 15 minutes (WorkManager's minimum)
  and on failure retries with a 15-minute backoff, so a dropped connection heals
  automatically. A foreground media observer also triggers a near-real-time sync
  moments after you take a photo ("on the fly").

## Server storage layout

```
/data/photos/
└── myphone-galaxys25ultra/          # <phonename-phonemodel>, lowercase, created 0777
    └── 2025/
        └── 2025_11_28/
            ├── IMG_20251128_101500.jpg
            └── VID_20251128_1830.mp4
```

* The **capture date** comes from the phone (MediaStore `DATE_TAKEN`), so a photo
  taken on Nov 28 2025 lands in `2025/2025_11_28/` regardless of when it uploads.
* Duplicate protection: the same file (matched by SHA-256) is never stored twice.
  Two different files that happen to share a name are stored side-by-side
  (`name_<8hex>.ext`) so nothing is overwritten.

## Repository layout

```
photo-sync/
├── server/        FastAPI receiver + systemd unit + Caddy snippet + tests
└── android/       Gradle/Kotlin app project (open in Android Studio)
```

**Full setup instructions are in [`GUIDE.md`](GUIDE.md).** Start there.

## Development

This project was developed **entirely by AI** — specifically Claude (Anthropic)
running in Claude Code. Across a single collaborative session, the AI:

- designed the architecture and wrote all Kotlin (Android app) and Python
  (FastAPI server) code, plus the Caddy/systemd config and the setup guide;
- diagnosed and fixed real runtime bugs from device logs (an Android 14+
  foreground-service crash, a stale-UI issue, a response-spoofing hole exposed
  by a captive-portal-style network);
- adapted the app for multiple devices (Samsung Galaxy S25 Ultra, OnePlus Nord
  N30 5G) and prepared this repository for public release.

The human collaborator provided the goals and constraints, ran the commands,
deployed to the server, tested on physical phones, and reported results — but
did not write the code or documentation. This README, the guide, and every
source file are AI-authored.

## License

Released under the [MIT License](LICENSE).
