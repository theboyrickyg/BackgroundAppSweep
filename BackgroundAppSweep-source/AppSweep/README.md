# Background App Sweep

A neon-style app for **Android phones and Android TV / Google TV**.

## What it does (and why it beats "Background Apps & Process List")

| | Background Apps & Process List | **Background App Sweep** |
|---|---|---|
| What it lists | Every installed app, running or not | **Only what's actually running** |
| Closing apps | Opens each App info page; you tap Force Stop yourself, one by one | **Closes everything instantly in the background, no screens** |
| Clears cache | No | **Clears every app's cache in the same press** |
| RAM per app | No | **Yes** |
| CPU per app | No | **Yes, with a HIGH CPU warning** |
| Cache size per app + total | No | **Yes** |
| Shows what you freed | No | **"Closed 9 apps · freed 640 MB RAM · cleared 1.2 GB of cache"** |
| Stop apps restarting themselves | No | **"Block background running" per app** |
| Clear one app's cache | No | **Yes (Android 14+)** |
| Protect apps from Close All | No | **Yes (e.g. music, alarm, WhatsApp)** |
| One-press sweep without opening the app | No | **Quick Sweep tile on the TV home screen, phone icon shortcut, and pull-down Quick Settings tile** |
| List order | Alphabetical | **Biggest RAM users first, updates itself live** |
| Safety | Lets you force-stop system apps | **Never touches your launcher, keyboard, remote/voice, Google services, system UI. System apps are never listed or closed** |

### Using it
- **CLOSE ALL & CLEAR CACHE**: the big button.
- **Tap an app** for its menu: close it, clear its cache, block background running, protect it, or open App info.
- **Hold an app** to close it instantly.
- The list sorts itself by RAM (biggest first) and updates every few seconds; no refresh button needed.
- The button turns **green** ("✓ ALL CLEAN") when the sweep is finished.
- **Quick Sweep**:
  - TV: add the *Quick Sweep* tile to your home screen's Apps row.
  - Phone: long-press the Background App Sweep icon, or add the *Quick Sweep* tile to your pull-down panel (edit tiles ✏️).
  - It sweeps, shows the result for 3 seconds, then disappears.

## Why it needs Shizuku

Since Android 10, normal apps are blocked from seeing other apps, closing them, or clearing their caches. That's why most "RAM booster" apps don't do anything any more. **Shizuku** is a free, well-known helper app that gives the app the same rights as a USB debugging (ADB) connection, so the buttons actually work.

You set Shizuku up once per device. After a **reboot** you have to start Shizuku again (on Android 11+ phones it can be set to start itself).

---

## 1. Build the APK (GitHub does it for you)

1. Create a new **public** repository on GitHub called `AppSweep`.
2. Upload everything in this folder to it (drag-and-drop on the GitHub website works). Keep the `.github` folder; it's the build script. (The file browser may hide it because the name starts with a dot.)
3. Add the signing key (so each new version installs over the old one):
   repo → **Settings → Secrets and variables → Actions → New repository secret**.
   Add the 4 secrets from `AppSweep-SIGNING-SECRETS.txt`: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   **Never upload that .txt file to the repo.**
4. Go to the **Actions** tab → **Build Background App Sweep APK** → **Run workflow**. About 3–5 minutes later the APK appears under **Releases**.

The download link always points at the newest build:

```
https://github.com/YOUR-USERNAME/AppSweep/releases/latest/download/BackgroundAppSweep.apk
```

Put that link into aftvnews.com/downloader to get a Downloader code for the TV.

## 2. Set up Shizuku on a phone (Android 11 or newer, no computer needed)

1. Install **Shizuku** from the Play Store (or https://shizuku.rikka.app/download/).
2. Turn on Developer options: **Settings → About phone →** tap **Build number** 7 times.
3. In **Developer options**, turn on **Wireless debugging** (the phone must be on Wi-Fi).
4. Open Shizuku → **Start via Wireless debugging → Pairing** and follow the steps (you type the pairing code into Shizuku's notification).
5. Tap **Start** in Shizuku. It should say *Shizuku is running*.
6. Open Background App Sweep → tap **Allow**.

## 3. Set up Shizuku on Android TV / Google TV

TVs can't do the phone pairing step, so you start Shizuku from a computer once (and again after each TV reboot).

1. On the TV, use Downloader to install Shizuku from https://github.com/RikkaApps/Shizuku/releases (download the `.apk`).
2. Turn on Developer options: **Settings → System → About →** click **Android TV OS build** 7 times.
3. **Settings → System → Developer options →** turn on **USB debugging** (and **Network debugging** if it's listed).
4. Note the TV's IP address: **Settings → Network & Internet →** your Wi-Fi network.
5. On a PC on the same Wi-Fi, install Google's **SDK Platform Tools** (that gives you `adb`) and run:
   ```
   adb connect TV-IP-ADDRESS:5555
   ```
   Click **Allow** on the TV when it asks.
6. Open Shizuku on the TV → **Start via ADB → View command**. Type that exact command on the PC, e.g.
   ```
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```
   (Use whatever command your Shizuku version shows; it changes between versions.)
7. Open Background App Sweep on the TV → **Allow**.

## Good to know

- Force-stopped apps won't send notifications or run alarms until you open them again. Hold an app to protect it (e.g. WhatsApp, alarm clock).
- After a cache clear, apps may open a bit slower the first time while they rebuild their cache, and some will need to re-download thumbnails. That's normal.
- Android may restart some system apps by itself straight away. That's also normal.
