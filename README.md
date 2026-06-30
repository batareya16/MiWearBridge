# MiWearBridge

An LSPosed module for Xiaomi Mi Band / Vela wearables that hooks **Mi Fitness** (`com.xiaomi.wearable`).

It is **based on [A5245/Wearable-Debug](https://github.com/A5245/Wearable-Debug)** (third‑party quick‑app / watchface / firmware install, log pull, encrypt‑key dump) and extends it with the part that was missing for real third‑party use:

- **`@system.interconnect` bridging** — any *same‑signed* Android app can talk to a watch quick‑app **without Xiaomi partner onboarding** ("bond").
- **Coordinator bindings** — many watch quick‑apps → one phone app.
- **Periodic watch → cloud auto‑sync**, even when Mi Fitness isn't opened manually.

> ⚠️ Research / personal‑use project. It changes Mi Fitness behaviour at runtime via Xposed. Use at your own risk, on your own device. Not affiliated with Xiaomi.

## Why interconnect needs this

Xiaomi's `interconnect` (watch quick‑app ↔ phone app) officially requires the third‑party app to be **authorised by Xiaomi** ("bond"). Without it the band silently refuses to route the app's messages — even when package and signature match. The transport itself carries no authorisation: routing is purely by package, and *"is this app's companion online"* is decided on the phone via `syncPhoneAppStatus` (device command `20/7`). The original failure was simply that Mi Fitness never sent that signal for an unauthorised app (and `getCurrentDeviceModel()` returned `null`). MiWearBridge supplies everything the phone side needs so the band accepts the app and opens the channel.

## Features

All actions live in a hidden menu inside Mi Fitness:
**Profile → About this app → tap the User Agreement / privacy line.**

Inherited from Wearable‑Debug:

- **App** — install a third‑party quick app (`.rpk`). Enter a package name, pick the file (any name works at install; the real package is only needed to uninstall).
- **WatchFace** — install a watchface file. No package name needed.
- **Firmware** — install a firmware file. ⚠️ *May brick the device — use with caution.*
- **Pull log** — dump device logs to `/sdcard/Android/data/<pkg>/files/log/devicelog/`.
- **Encrypt Key** — show the EncryptKey stored for the connected device(s). Format: `did: [name, EncryptKey]`.

Added by MiWearBridge:

- **Bind apps to watch** — manage `watch app → coordinator app` bindings. Tap **+ Add binding**, pick the phone coordinator from the installed‑apps list, enter the watch app's package. Remove with ✕. If a watch package is **not** listed it falls back to itself (watch package == phone package — the default "matching name" behaviour), so simple single‑app setups need no entry.
- **Auto‑sync to cloud** — toggle periodic sync of watch data to the cloud and set the interval (minutes).

## How the bridge works

Inside the Mi Fitness process:

- Redirects the XMS SDK bind target to Mi Fitness and swallows the client‑side `not bond` exception.
- Forces the XMS service checks (`Status` results, install/permission checks) to success.
- Returns a real `WatchAppItemEntity` / `WatchAppCapability` for bound packages, with the correct **SHA‑1 signing fingerprint**.
- Fixes `WearableDeviceManager.getCurrentDeviceModel()` returning `null` by falling back to the connected device from the manager.
- Announces the app as **online** to the band via `syncPhoneAppStatus` — the signal that actually opens the watch‑side channel.

Coordinator routing:

- **Watch → phone:** remaps the incoming watch package to its coordinator (`P2`) so messages land in the coordinator's listener.
- **Phone → watch:** the coordinator addresses a specific watch app with a header `@w:<watchPackage>\n` in the payload; the hook strips it and routes the message to that app.

## Requirements

- Rooted device with **LSPosed** (Xposed API 82+).
- **Mi Fitness** (`com.xiaomi.wearable`); `com.mi.health` (Mi Health) is also hooked if present.
- Watch quick‑app(s) and the phone app **must share the same signing key** (interconnect requirement; the fingerprint used in checks comes from the coordinator app).
- For background auto‑sync: set Mi Fitness to **no battery restriction**.

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Scope — what to enable (important)

The module hooks **two sides**, so for the stock setup you must enable it on **both**:

1. **Mi Fitness** (`com.xiaomi.wearable`, and `com.mi.health` if present) — the server side
   (forge records, online announce, routing).
2. **Every companion app**.

## Contract for your apps

1. **Signing:** sign every watch quick‑app (`.rpk`) with the coordinator app's key.
2. **Phone → watch:** to reply to a specific watch app, prefix the message with `@w:<watchPackage>\n` followed by the real payload.
3. **Watch → phone:** the coordinator's listener receives `(deviceId, data)` without the source package, so include each watch app's own identifier inside the payload (e.g. a `"src"` field) to tell senders apart.

## Credits

- Built on [A5245/Wearable-Debug](https://github.com/A5245/Wearable-Debug).
- Reverse‑engineering for interoperability and personal use. No warranty.
