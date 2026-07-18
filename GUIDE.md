# Full setup guide

Do **Part 1 (the server)** first, verify it responds, then do
**Part 2 (build the APK)** and **Part 3 (install + configure on the phone)**.

Everything you type as a command is shown in a code block. Replace anything in
`<angle brackets>`.

## Machines in this setup

| Name | What it is | Its job here |
|------|------------|--------------|
| **Home server** | Headless Linux home server (no desktop GUI). This repo lives here. | Runs the FastAPI receiver + reverse proxy. **Part 1.** |
| **Windows PC** | Your Windows workstation. | Runs Android Studio to build the APK and installs it to the phone over USB. **Parts 2 & 3.** |
| **Galaxy S25 Ultra** | The phone. | Runs the app. **Part 3.** |

Because the server is headless it can't run Android Studio, so the app is built on
your Windows PC. Windows commands below are for **PowerShell** (open *Start ▸
Windows PowerShell*).

---

## Part 1 — Deploy the receiver on the server

All commands run **on the server** (directly at the machine or over SSH); you need
sudo. This repo already lives on the server, so there is nothing to copy over the
network — you just install the `server/` folder into `/opt/photosync`.

### 1.1 Put the server folder in place

From the directory where this repo is checked out on the server (adjust the
`photo-sync/` path if it lives elsewhere):

```bash
sudo mkdir -p /opt/photosync
sudo cp -r photo-sync/server /opt/photosync/server

# The service runs as youruser, so hand the whole install to that account now.
# This lets the rest of Part 1 run without sudo and lets the service read its
# own files. (The copy above is root-owned because of sudo.)
sudo chown -R youruser:youruser /opt/photosync/server
```

> Prefer `sudo mv photo-sync/server /opt/photosync/server` if you don't need to
> keep a copy inside the repo checkout. Either way `/opt/photosync/server` is the
> install location the rest of this guide (and the systemd unit) expects.

### 1.2 Confirm the data directory

`/data/photos` already exists on the server as `root:root` with mode `0777`
(world-writable), and everything below it is owned by `youruser:youruser`. That's
exactly what we want, so **no changes are needed here** — the service will run as
`youruser` (set in the unit, step 1.5) and create the `myphone-galaxys25ultra/`
folder inside the 777 root, owned by `youruser:youruser` like the rest.

Verify it's still world-writable:

```bash
ls -lahd /data/photos      # expect: drwxrwxrwx ... root root
```

> Running the service under a *different* account? Set `User=`/`Group=` in the
> systemd unit to that account. As long as `/data/photos` stays `0777` the
> account doesn't need to own it — it just needs write access to create the
> device folder.

### 1.3 Python virtual environment + dependencies

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip

# No sudo below: youruser owns /opt/photosync/server (from step 1.1), so the
# venv and its files are created owned by the account that runs the service.
cd /opt/photosync/server
python3 -m venv .venv
.venv/bin/pip install --upgrade pip
.venv/bin/pip install -r requirements.txt
```

### 1.4 Generate the token and write `.env`

```bash
cd /opt/photosync/server
cp .env.example .env

# Generate a strong token and print it — COPY THIS, you need it in the app too:
.venv/bin/python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Edit `.env` and paste the token into `PHOTOSYNC_TOKEN=`. Leave the rest as-is
(defaults already match this setup: data dir `/data/photos`, bind
`127.0.0.1:8080`, root path `/photos`). Then lock it down (already youruser-owned
from step 1.1, so no sudo needed):

```bash
chmod 600 /opt/photosync/server/.env
```

### 1.5 Install the systemd service

```bash
sudo cp /opt/photosync/server/photosync.service /etc/systemd/system/photosync.service
# Open it and confirm User/Group/paths match your install:
sudo vim /etc/systemd/system/photosync.service

sudo systemctl daemon-reload
sudo systemctl enable --now photosync
sudo systemctl status photosync         # should be "active (running)"
```

Quick local check (still on the server):

```bash
curl -s http://127.0.0.1:8080/health
# -> {"status":"ok","version":"1.0.0"}
```

### 1.6 Add the `/photos` route in Caddy (port 8443)

The :8443 endpoint is served by **Caddy** (there is no nginx on the server). Edit the
live Caddyfile at `/etc/caddy/Caddyfile` (repo copy: `~/rss-reader/deploy/Caddyfile`).

Currently the site block proxies everything to the reader and gates it with
**site-level `basicauth`** (the cert is obtained out-of-band by acme.sh via
DNS-01, so the `tls` line points at explicit cert files):

```caddy
your-domain.duckdns.org:8443 {
    tls /etc/caddy/certs/your-domain.duckdns.org.cer /etc/caddy/certs/your-domain.duckdns.org.key

    # The app has no auth — gate it. (Caddy < 2.8 uses "basicauth"; 2.8+ "basic_auth".)
    basicauth {
        youruser <paste the hash printed by: caddy hash-password>
    }

    encode gzip
    reverse_proxy 127.0.0.1:3000
}
```

> ⚠️ **Important:** that `basicauth` is at the site level, so it currently applies
> to *every* request on :8443. The photo app authenticates with its **own Bearer
> token** and does **not** send HTTP Basic credentials — so `/photos` must **not**
> sit behind `basicauth`, or Caddy will reject every upload with `401`. The rewrite
> below moves `basicauth` **inside the reader's `handle`** so it gates only the
> reader, leaving `/photos` for the app's token to protect.

Rewrite the site block so a `/photos` route forwards to the photo receiver on
`127.0.0.1:8080`, and the reader (with its `basicauth`) becomes the fallback
`handle` (full snippet in
[`server/caddy-photos.conf.example`](server/caddy-photos.conf.example)). Leave the
global `{ servers { protocols h1 h2 } }` block that disables HTTP/3 unchanged:

```caddy
your-domain.duckdns.org:8443 {
    tls /etc/caddy/certs/your-domain.duckdns.org.cer /etc/caddy/certs/your-domain.duckdns.org.key
    encode gzip

    # Photo-sync receiver under /photos -> uvicorn on 127.0.0.1:8080 (prefix
    # stripped). Auth is the app's Bearer token, so NO basicauth here.
    handle_path /photos/* {
        reverse_proxy 127.0.0.1:8080 {
            header_up X-Forwarded-Prefix /photos
            flush_interval -1          # stream large up/downloads, don't buffer
        }
    }

    # Everything else -> the RSS reader, still gated by basic auth.
    handle {
        basicauth {
            youruser <paste the hash printed by: caddy hash-password>
        }
        reverse_proxy 127.0.0.1:3000
    }
}
```

Then validate and reload:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile   # "Valid configuration"
sudo systemctl reload caddy
```

> * **`basicauth` moved into the reader `handle`** so it no longer gates `/photos`.
>   Keep the exact same hash line you already have (the one shown is copied from
>   your current file); use `basic_auth` instead of `basicauth` only if you're on
>   Caddy 2.8+.
> * `handle_path /photos/*` strips the `/photos` prefix before forwarding (the
>   app's `PHOTOSYNC_ROOT_PATH=/photos` + the `X-Forwarded-Prefix` header let it
>   rebuild absolute URLs correctly).
> * Caddy streams request bodies by default and imposes no max-body-size, so no
>   large-upload / buffering / timeout tuning is needed; `flush_interval -1`
>   covers streaming large *responses* (video downloads).
> * Caddy sets `X-Forwarded-For` / `-Proto` / `-Host` automatically — only
>   `X-Forwarded-Prefix` needs adding (done above).
> * `encode gzip` stays at the site level (harmless for `/photos`); only
>   `basicauth` must be scoped to the reader.

### 1.7 Verify from the internet

From your phone's browser (on mobile data, not home Wi-Fi, to prove it's
reachable from outside):

```
https://your-domain.duckdns.org:8443/photos/health
```

You should see the JSON health response. **If this works, the server is done.**

Watch logs during the first sync later with:

```bash
sudo journalctl -u photosync -f
```

---

## Part 2 — Build the APK (on your Windows PC)

Everything in this part happens on **your Windows PC** (Windows 11), since headless
the server can't run a GUI. The easiest path (recommended) is **Android Studio**,
which downloads the Android SDK, generates the Gradle wrapper, and builds/signs
for you.

### 2.1 Copy the app source from the server to your Windows PC

The `android/` folder currently lives in the repo on the server. Copy it to
your Windows PC — Windows 11 includes an `scp` client, so in **PowerShell**:

```powershell
scp -r <you>@<server>:<path-to-repo>/photo-sync/android $HOME\photo-sync-android
```

Replace `<you>` with your server login and `<path-to-repo>` with wherever the
repo is checked out (e.g. `/home/<you>`). Prefer a GUI? Use **WinSCP** to drag
the `photo-sync/android` folder from the server into `C:\Users\<you>\photo-sync-android`.

> Only the `android/` folder is needed on your Windows PC; `server/` stays on the server.

### 2.2 Install Android Studio

Download from <https://developer.android.com/studio> and run the installer (or in
PowerShell: `winget install Google.AndroidStudio`). On first launch let the setup
wizard install the default SDK.

### 2.3 Open the project

* **File ▸ Open…** and select the folder you copied in 2.1
  (`C:\Users\<you>\photo-sync-android`) — open that `android` folder itself.
* Android Studio will "Sync" Gradle — this downloads Gradle 8.9, the Android SDK
  35, and all libraries, and **generates the Gradle wrapper**. Wait for
  "Gradle sync finished" (first time can take several minutes).
* If prompted to install SDK 35 / build-tools, accept.
* **If Studio offers an "Android Gradle Plugin Upgrade Assistant"** (e.g. bump
  AGP 8.7.3 → 8.13.x): **decline it** (Skip / Remind me later). The project is
  pinned to a known-good AGP 8.7.3 + Gradle 8.9 combo; upgrading AGP can cascade
  into needing a newer Gradle/JDK for no benefit. Note the **AGP** version
  (8.7.3, in `build.gradle.kts`) is *not* the same as the **Gradle** version
  (8.9, in `gradle-wrapper.properties`) — they're independent numbers.

### 2.4 Build a debug APK (simplest for your test bed)

A **debug** APK is signed with a throwaway debug key and installs fine for
personal use — perfect for the S25 Ultra test bed.

* **Build ▸ Generate App Bundle or APK ▸ Generate APKs** (older Android Studio
  labels this **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)** — same action).
  It builds the currently selected build variant, which defaults to **debug**
  (check via **View ▸ Tool Windows ▸ Build Variants** if unsure).
* Wait for the **Build** tool window (bottom) to show **`BUILD SUCCESSFUL`**.
* A balloon at the bottom-right offers a **locate** link, but it fades quickly.
  You don't need it — the APK is always written to this path under the folder you
  opened:

```
app\build\outputs\apk\debug\app-debug.apk
```

  Full path if you copied to `$HOME\photo-sync-android`:
  `C:\Users\<you>\photo-sync-android\app\build\outputs\apk\debug\app-debug.apk`.
  Confirm it from PowerShell with
  `dir $HOME\photo-sync-android\app\build\outputs\apk\debug\`, or reopen the
  message later via the **🔔 Notifications** icon in the status bar.

<details>
<summary>Optional: signed <b>release</b> APK (for a longer-lived install)</summary>

* **Build ▸ Generate Signed App Bundle / APK ▸ APK ▸ Next**.
* **Create new…** keystore — pick a path, set passwords, fill in your name.
  **Keep this keystore file and passwords safe**; you need the same key to ship
  updates that install over the existing app.
* Choose the **release** build variant ▸ **Create**.
* Output: `app\build\outputs\apk\release\app-release.apk`.

</details>

<details>
<summary>Optional: command-line build on your Windows PC (no Android Studio GUI)</summary>

> **Skip this whole section if you have Android Studio** — §2.2–2.4 already build
> the APK. This is a standalone alternative for a machine *without* the Studio
> GUI, and it's mutually exclusive with it. In particular, do **not** run the
> `local.properties` step below on a project you also open in Studio — it points
> the SDK at `C:\Android` and will break the Studio build (delete the file and
> re-sync if you did).

In **PowerShell**. Requires JDK 17 **and** the Android command-line tools, which
you must first **download and unzip** to `C:\Android\cmdline-tools\latest\` from
<https://developer.android.com/studio#command-tools>. The `sdkmanager` and
`gradle`/`gradlew` "command not recognized" errors mean this download step hasn't
been done yet.

```powershell
# 1. JDK 17
winget install EclipseAdoptium.Temurin.17.JDK

# 2. Android command-line tools -> https://developer.android.com/studio#command-tools
#    Unzip to C:\Android\cmdline-tools\latest\
$env:ANDROID_HOME = "C:\Android"
$env:Path += ";$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools"

# 3. SDK packages + licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses            # accept each prompt

# 4. Point the build at the SDK (from the folder copied in 2.1)
cd $HOME\photo-sync-android
"sdk.dir=C:\\Android" | Out-File -Encoding ascii local.properties

# 5. Generate the Gradle wrapper once (needs a system Gradle >= 8.9), then build
gradle wrapper --gradle-version 8.9   # or install Studio once to create it
.\gradlew.bat assembleDebug
# APK -> app\build\outputs\apk\debug\app-debug.apk
```
</details>

### 2.5 Rebuilding after a source change

Whenever the source changes on the server, repeat this cycle:

```powershell
# 1. Refresh the source on your Windows PC (adds/updates files; does NOT delete
#    files removed on the server — remove those by hand if the change notes say so)
scp -r <you>@<server>:<path-to-repo>/photo-sync/android/app/src $HOME\photo-sync-android\app\

# 2. In Android Studio: File > Sync Project with Gradle Files,
#    then Build > Generate App Bundle or APK > Generate APKs
#    (wait for BUILD SUCCESSFUL)

# 3. Install on each phone over USB (settings and sync history survive -r)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r $HOME\photo-sync-android\app\build\outputs\apk\debug\app-debug.apk
```

If `server/` files changed too, on the server:

```bash
cp -r <path-to-repo>/photo-sync/server/app /opt/photosync/server/
sudo systemctl restart photosync
curl -s http://127.0.0.1:8080/health    # expect {"status":"ok",...}
```

---

## Part 3 — Install and configure on the Galaxy S25 Ultra

### 3.1 Put the APK on the phone (from your Windows PC)

**Option A — over USB with adb (fastest):** `adb` ships with Android Studio's
platform-tools at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

1. On the phone: **Settings ▸ About phone ▸ Software information** and tap
   **Build number** 7 times to enable Developer options.
2. **On Galaxy S24/S25 (One UI 6/7): turn off Auto Blocker first** —
   **Settings ▸ Security and privacy ▸ Auto Blocker → OFF**. It's on by default
   and otherwise **greys out the USB debugging toggle** and blocks sideloading
   (both adb and file-manager installs). You can re-enable it after the app is
   installed.
3. **Settings ▸ Developer options ▸ USB debugging = ON**. If the toggle is still
   greyed right after disabling Auto Blocker: unplug USB, toggle it on, then
   re-plug.
4. Connect the phone to your Windows PC by USB; on the phone pick **File Transfer**
   mode and accept the "Allow USB debugging" prompt (tick "Always allow from this
   computer"). Then in **PowerShell**:

```powershell
cd $HOME\photo-sync-android
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

If Windows doesn't detect the phone, install the **Samsung Android USB Driver**
and reconnect.

**Option B — copy the file:** transfer `app-debug.apk` to the phone (USB drag in
File Explorer, Google Drive, email to yourself, etc.), tap it in **My Files**,
and allow "Install unknown apps" for the app you opened it with.

### 3.2 First run

Open **Photo Sync** and:

1. Grant permissions when asked — choose **Allow** for Photos and videos, and
   **Allow** notifications. (If you tapped "Don't allow", enable them under
   **Settings ▸ Apps ▸ Photo Sync ▸ Permissions**.)
2. **Server URL:** `https://your-domain.duckdns.org:8443/photos`
3. **Access token:** paste the exact token from `.env` (step 1.4).
4. **Device folder:** `myphone-galaxys25ultra` (lowercase `phonename-phonemodel`).
   On first run the field is **auto-filled** from the phone's Device name +
   model; tap **Detect from this phone** to re-fill it. Edit if needed — some
   phones report the model as a code (e.g. `sms938b`) rather than
   `galaxys25ultra`. The field auto-strips illegal characters.
5. Optional toggles: **Wi-Fi only** (default ON) and **Camera roll only**
   (default ON — uncheck to also sync screenshots/WhatsApp/downloads).
6. Tap **Test connection** → expect *"Connection OK — server reachable and token
   accepted"*. If not, see Troubleshooting.
7. Tap **Save**, then turn **Background sync ON**. The first full sync starts
   immediately (it can take a while depending on how many photos you have).

### 3.3 IMPORTANT — stop the phone from killing background sync

OEM battery management will otherwise pause background sync. Do the steps for
your brand:

**Samsung (Galaxy S25 Ultra):**

* **Settings ▸ Apps ▸ Photo Sync ▸ Battery ▸ Unrestricted**.
* **Settings ▸ Battery ▸ Background usage limits ▸ Never sleeping apps ▸ +** and
  add Photo Sync (make sure it is **not** in "Sleeping/Deep sleeping").

**OnePlus (Nord N30 5G / OxygenOS):**

* **Settings ▸ Apps ▸ Photo Sync ▸ Battery usage ▸ Unrestricted** (older
  OxygenOS: Settings ▸ Battery ▸ Battery optimization ▸ Photo Sync ▸
  **Don't optimize**).
* In the same app page, enable **Allow auto-launch** if present (lets the sync
  schedule restore after reboot).
* Note that Battery Saver mode pauses background sync until charging.

### 3.4 Verify on the server

```bash
ls -R /data/photos/myphone-galaxys25ultra | head
sudo journalctl -u photosync -f      # watch "stored …" lines as uploads arrive
```

### 3.5 Adding another phone (e.g. OnePlus Nord N30 5G)

Nothing changes on the server — each phone gets its own folder automatically. On the
new phone:

1. **Set its Device name** (Settings ▸ About device ▸ Device name) to the
   nickname you want in the folder (like "myphone" on the S25).
2. Install the **same APK** (§3.1). On OnePlus there is no Auto Blocker; just
   allow "Install unknown apps" (Option B) or enable USB debugging for adb
   (same Build-number ×7 procedure).
3. First run (§3.2): grant **Allow all** for photos and videos — *not* "Select
   photos", which would sync only a frozen selection.
4. Same **Server URL** and **Access token** as the first phone. Tap
   **Detect from this phone** → e.g. `alice-oneplusnordn305g`. Edit if you
   prefer a different model part.
5. **Test connection**, **Save**, turn **Background sync ON**, then do the
   OnePlus battery steps in §3.3.

---

## Behaviour & tuning

* **One-way only.** Deleting a photo on the phone never deletes it on the server.
* **No duplicates / resumable.** Each file is recorded locally once stored; the
  app also asks the server (by SHA-256) before sending, so re-runs and reinstalls
  don't re-upload. Interrupted uploads simply retry next cycle.
* **Camera roll only** is ON by default: syncs `DCIM/` (all camera apps) but
  excludes screenshot folders. Uncheck it in the app to sync every image/video
  on the phone (screenshots, WhatsApp, downloads…). Changing the toggle never
  deletes anything already on the server.
* **Give-up on rejected files.** A file the server permanently rejects (4xx) is
  retried 3 times, then skipped to save battery — until the file changes, which
  resets its attempts. Connection failures are unaffected: they retry forever.
* **Wi-Fi only** is ON by default (good for large videos). Turn it off in the app
  to also sync on mobile data.
* **Cadence.** Background sync every 15 min + near-real-time when you take a
  photo. Failures retry automatically every 15 min until they succeed.
* **Big videos.** The app streams straight from the file to the socket, and Caddy
  streams request bodies by default with no size limit — so the only cap is the
  app's own `PHOTOSYNC_MAX_UPLOAD_BYTES` (default 8 GiB). Raise it in `.env` if
  you shoot very large 8K clips.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `/photos/health` fails from mobile data | Port 8443 not forwarded to the server, or the Caddy `/photos` route not added / Caddy not reloaded (`sudo systemctl reload caddy`). Re-check 1.6–1.7. |
| Test connection: *"Cannot reach server"* | DNS/duckdns not updated, or you're on a network that blocks 8443. Try mobile data. |
| Test connection: *"Token rejected"* | Token in the app ≠ `PHOTOSYNC_TOKEN` in `.env`. Re-copy it; no stray spaces. |
| App says "already N" but nothing appears on the server | A captive portal / VPN / filtering middlebox answered fake 200s to the first sync (app versions < 1.1.1 believed them). Update the app, then **Clear data** (Settings ▸ Apps ▸ Photo Sync ▸ Storage) to wipe the bogus ledger, reconfigure, and re-run **Test connection** on the same Wi-Fi the phone will sync on — v1.1.1 detects interception ("Got a reply, but not from the photo server"). Check the phone for VPN/ad-block/"secure Wi-Fi" apps or Private DNS. |
| Uploads/health get `401` with a browser Basic-auth popup | Caddy `basicauth` is still gating `/photos`. Move it inside the reader's `handle` block (§1.6) so only the reader is gated, then `sudo systemctl reload caddy`. |
| Uploads 413 | File bigger than the app's cap. Raise `PHOTOSYNC_MAX_UPLOAD_BYTES` in `.env` and `sudo systemctl restart photosync`. (Caddy has no body-size limit, so nothing to change there.) |
| USB debugging greyed out, or `adb install` blocked | Samsung **Auto Blocker** is on — Settings ▸ Security and privacy ▸ Auto Blocker → OFF, then toggle USB debugging (§3.1). |
| Photos stop syncing when screen is off | Samsung battery limits — do step 3.3. |
| Folders not world-writable | Service `UMask=000` must be set (it is in the unit) and the run user must own `/data/photos`. |
| Wrong dates on folders | Some apps don't set `DATE_TAKEN`; the app falls back to `DATE_ADDED`. |

Run the server logic tests any time with:

```bash
cd photo-sync/server && python3 tests/test_storage.py
```
