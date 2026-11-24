# Offline Intercom (Android)

Walkie-talkie / intercom that works **without internet**:

* **Wi-Fi (group)** – any number of phones on the same Wi-Fi network. The network does
  not need internet: a router with no WAN, or one phone's **mobile hotspot** (mobile data
  can be off). Phones find each other automatically. Channels 1–9 keep groups separate.
* **Bluetooth (1-to-1)** – two paired phones. One taps **Host**, the other taps the host
  in the paired list.
* **Hold to talk** (walkie-talkie) or **Hands-free** (open line, with echo cancellation).
* Loudspeaker / earpiece toggle. Name and channel are remembered.

## Get the APK – no installs needed (GitHub, free)

1. Create a new repository on github.com (private is fine).
2. Upload all files in this folder (keep the `.github` folder — on the upload page drag
   the whole folder in; on a phone use "Add file → Upload files").
3. Open the **Actions** tab. The *Build APK* job runs automatically (~4 min).
   If it didn't start, choose *Build APK → Run workflow*.
4. Open the finished run → **Artifacts → OfflineIntercom-apk** → download, unzip,
   copy `app-release.apk` to your phone and install (allow "install unknown apps").

## Or build in Android Studio

Open this folder → let Gradle sync → **Build → Build APK(s)**.

## Using it

* Wi-Fi: all phones join the same Wi-Fi/hotspot → same channel → **Start Wi-Fi intercom**.
* Bluetooth: pair the phones once in system Bluetooth settings ("Pair…" button) →
  phone A **Host** → phone B taps phone A in the list.
* Keep the app open (screen stays on while it runs).

## Technical notes

* Audio: 16-bit PCM, 20 ms frames, 16 kHz on Wi-Fi, 8 kHz on Bluetooth; ~60 ms jitter buffer
  and a mixer so several people can be heard at once.
* Wi-Fi: UDP port 50505, broadcast discovery + unicast audio.
* Bluetooth: classic RFCOMM, length-prefixed frames.
* minSdk 23 (Android 6.0), targetSdk 34. The release build is signed with the debug key so
  it installs directly; use your own keystore before publishing to Play Store.
