# Fineprint for Android: progress

Fineprint grades terms of service on the phone from A to E. The model runs on the device. A floating pill appears over any app that shows terms.

## Status

- Done: contract logic with JVM tests, LEAP inference, live result sheet, home screen, accessibility text reader for Chrome, pill over Chrome.
- In progress: overlay sheet, card tap that scrolls Chrome, selection menu, screenshots.

## Stack decision

The app uses the LEAP Android SDK `ai.liquid.leap:leap-sdk:0.10.9` with Kotlin and Jetpack Compose. Liquid marks LEAP as deprecated in favour of llama.cpp, but the artifacts remain on Maven Central and ship prebuilt arm64 llama.cpp libraries. The check took about 10 minutes:

- `LeapClient.loadModel(path)` loads a local `.gguf` file.
- `GenerationOptions(temperature = 0f, maxTokens = 96, functionCallParser = null)` gives greedy decoding and raw text. The log shows `temp = 0.000` and `effective constraint: None`.
- The AAR manifest declares no permissions.

## Speed on the emulator

LEAP's thread advisor reports 1 thread on the emulator. The app uses half the cores, at most 3, when the advisor reports 1. Measured on the Pixel 8 AVD (Android 16, 6 vCPU, Apple M4 Pro host), warm:

| Threads | Prefill tok/s | Decode tok/s |
|---|---|---|
| 1 | 18 | 9 |
| 2 | 210 | 76 |
| 3 | 315 | 134 |
| 4 | 48 | 7 |
| 6 | 400 | 43 to 63 |

The first model load reads 731 MB and takes about 15 s.

## Build and install

```sh
cd ~/honey/fineprint-android
./gradlew :app:assembleDebug          # JDK 17 is set in gradle.properties
./gradlew :app:testDebugUnitTest      # contract logic
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

SDK: `/opt/homebrew/share/android-commandlinetools` (Homebrew cask `android-commandlinetools`). AVD: `fineprint` (Pixel 8 screen, `system-images;android-36;google_apis;arm64-v8a`, 4 GB data partition). Start it with `emulator -avd fineprint -no-snapshot-save`.

Copy the model into the app without the file picker (debug builds only):

```sh
adb push ~/honey/fineprint-mobile/models/LFM2.5-1.2B-Instruct-Q4_K_M.gguf /data/local/tmp/base.gguf
adb shell "run-as app.fineprint sh -c 'mkdir -p files/models; cp /data/local/tmp/base.gguf files/models/base.gguf'"
```

The fine-tuned file goes to `files/models/tuned.gguf`, or use Settings > Fine-tuned model > Add.

Turn on the service without the Settings app:

```sh
adb shell settings put secure enabled_accessibility_services app.fineprint/app.fineprint.FineprintService
adb shell settings put secure accessibility_enabled 1
```

Fixtures: `python3 -m http.server 8765 --bind 127.0.0.1 -d ~/honey/fineprint-chrome/fixtures`, then open `http://10.0.2.2:8765/spotify.html` in the emulator's Chrome.

## Findings

- Chrome exposes the whole page, including off-screen text, once an accessibility service is on. Each node has extras `AccessibilityNodeInfo.cssDisplay` and `AccessibilityNodeInfo.chromeRole`. Inline pieces (links, `strong`, static text) join without a separator, because Chrome keeps their spaces. Off-screen nodes report clipped bounds, so geometry is not usable for joining.
- The web root node carries the page title as its text. The reader descends into nodes that have children.
- `java.text.BreakIterator` on the JVM does not break at a newline, but ICU does. `splitClauses` splits on line breaks first, so the JVM test and the device agree.
