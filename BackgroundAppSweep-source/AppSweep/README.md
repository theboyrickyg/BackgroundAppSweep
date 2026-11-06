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

## One app, one-time setup

Since Android 10, normal apps are blocked from seeing other apps, closing them, or clearing their caches. That's why most "RAM booster" apps don't do anything any more.

Background App Sweep gets round this properly by connecting to the device's own **debugging service**, the same thing a PC uses over USB, built right into the app. **No second app is needed.** Android only lets the device owner switch debugging on, so there's a short one-time setup, and the app walks you through it on screen.

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

## 2. Set up on a TV (no PC needed)

1. Install Background App Sweep and open it.
2. **Developer options:** Settings → System → About → click **Android TV OS build** 7 times.
3. **Debugging:** Settings → System → Developer options → switch on **Network debugging** (sometimes called ADB debugging).
4. Back in the app, press **Connect**. When the TV asks **"Allow debugging?"**, tick **Always allow** and press **OK**.

That's it. On most TVs it keeps working after a restart.

*If your TV only has **Wireless debugging**:* switch it on, then choose **Pair device with pairing code**. Note the 6-digit code, press **HOME** (not Back), open Background App Sweep, type the code and press **Pair**.

## 3. Set up on a phone (Android 11 or newer)

1. Install Background App Sweep and open it.
2. **Developer options:** Settings → About phone → tap **Build number** 7 times.
3. **Wireless debugging:** Settings → System → Developer options → switch on **Wireless debugging** (Wi-Fi must be on).
4. In the app press **Start pairing**, then in Wireless debugging tap **Pair device with pairing code**.
5. Pull down your notifications and type the 6-digit code into the **Background App Sweep** notification. It says **Paired ✓** when done.
6. Go back to the app. It connects by itself.

Android turns Wireless debugging off after a restart or a Wi-Fi change. If the app asks, switch it back on in Developer options. No new code is needed.

*Already use Shizuku?* The app can use that instead: tap **"Already use Shizuku? Use it instead"** on the setup screen.

## Good to know

- Force-stopped apps won't send notifications or run alarms until you open them again. Hold an app to protect it (e.g. WhatsApp, alarm clock).
- After a cache clear, apps may open a bit slower the first time while they rebuild their cache, and some will need to re-download thumbnails. That's normal.
- Android may restart some system apps by itself straight away. That's also normal.
