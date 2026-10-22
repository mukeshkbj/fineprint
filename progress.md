# Fineprint for Android: progress

Fineprint grades terms of service on the phone from A to E. The model runs on the device, and the app has no internet permission. When you open terms in any app, a small grade pill appears on the edge of the screen. You never switch to Fineprint.

## Status

Done and checked on the emulator (screenshots in `shots/`):

- Accessibility service reads the terms in Chrome, including off-screen text, and starts a check by itself.
- Floating pill over Chrome: progress ring and "33 of 220", then the grade letter with a haptic tick. After 3.5 s it collapses to the letter. It drags, snaps to the nearest edge with a spring, and has a dismiss target.
- Overlay sheet over Chrome: grade strip, headline, category list, stats, cards grouped by severity, and a Base / Fine-tuned switch.
- A card tap lowers the sheet to a peek bar, scrolls Chrome to the clause, and highlights the sentence's own lines in yellow. The previous and next buttons step through findings. The highlight follows when you scroll.
- "Check with Fineprint" in the text selection menu of every app, and Share for plain text. Both open the same sheet in a translucent activity.
- App: setup (model import, Accessibility), two sample excerpts, a paste box, recent checks, settings (model switch, fine-tuned import, automatic checks), and a privacy row.
- Results and per-clause answers persist in app storage, so revisiting a page or scrolling never recomputes.
- Light and dark themes. The system "Remove animations" setting turns motion off.

Not done:

- Camera scan of paper contracts.
- A foreground service for very long checks. The bound accessibility service keeps the process alive during overlay checks. A check started from the selection sheet keeps running if you close the sheet, but Android may stop the process in the background.

## Evidence

- Real run, final build, base model, cold start (app data cleared, Chrome restarted), Spotify fixture in Chrome on the emulator: 263 clauses in 4 min 6 s at 127 tok/s decode, 1 invalid output, grade E (`shots/07-sheet-graded-spotify-263-clauses-4m06s.png`). The engine log for the same run gives 13,049 decoded tokens in 100.1 s, which is 130 tok/s. The pill appeared 4 s after the page opened. An earlier run gave 4 min 12 s at 126 tok/s. The Tinder excerpt: 74 clauses in 1 min 14 s at 120 tok/s, 3 invalid outputs.
- The clause list that the service reads from Chrome matches `lib.js` exactly: 263 clauses on `spotify.html`, with an empty `diff`.
- Merged manifest: no INTERNET permission. `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` lists only `app.fineprint.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which is AndroidX's own signature permission. The manifest removes INTERNET and ACCESS_NETWORK_STATE with `tools:node="remove"`. The Privacy row in the app reads the live permission state.
- Greedy decoding is deterministic: the comparison table, run with a copy of the base file in the fine-tuned slot, gave the same grade, flagged clauses, and invalid count for both. The copy was deleted afterwards.
- JVM tests for normalisation, reply parsing (including malformed replies), grade, and clause splitting: `./gradlew :app:testDebugUnitTest`, 4 of 4 pass.

## Stack decision

Kotlin, Jetpack Compose (including the overlay windows), and the LEAP Android SDK `ai.liquid.leap:leap-sdk:0.10.9`. Liquid marks LEAP as deprecated in favour of llama.cpp, but the artifacts are on Maven Central and ship prebuilt arm64 llama.cpp libraries. A llama.cpp build would need the NDK and CMake. The LEAP check took about 10 minutes:

- `LeapClient.loadModel(path)` loads a local `.gguf` file, so a fine-tuned file loads the same way.
- `GenerationOptions(temperature = 0f, maxTokens = 96, functionCallParser = null)` gives greedy decoding and the raw reply text. The engine log shows `temp = 0.000` and `effective constraint: None`.
- The AAR manifest declares no permissions. LEAP pulls Ktor and OkHttp for its unused model downloader. Without the INTERNET permission, Android blocks any socket the process opens.

## Form factor

The parent's analysis is built as written, with these changes:

- Card tap highlights the exact sentence, not the whole paragraph. Chrome returns per-character boxes through `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY`. The first request returns coarse boxes, so the service asks twice.
- `ACTION_SHOW_ON_SCREEN` alone can leave the clause behind the peek bar. The service measures the clause after that action and adds a short scroll gesture so the clause sits in the upper third, above the peek bar. This is why the service asks for gesture permission.
- The pill shows a progress ring around the provisional grade letter during a check, so the letter in the collapsed state means the same thing in both phases.
- The dismiss target is a separate window that exists while the pill exists and fades in during a drag. A window created during the drag stacked above the pill and covered it.

## Build, install, and run

```sh
cd ~/honey/fineprint-android
./gradlew :app:assembleDebug          # JDK 17 is set in gradle.properties
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

SDK: `/opt/homebrew/share/android-commandlinetools` (Homebrew cask `android-commandlinetools`), with platform 37.0, build-tools 36.0.0, platform-tools, and the emulator. AVD `fineprint`: Pixel 8 screen, `system-images;android-36;google_apis;arm64-v8a`, 6 vCPU, 6 GB RAM, 4 GB data partition.

Start the emulator headless:

```sh
emulator -avd fineprint -no-window -no-snapshot-save -no-boot-anim -no-audio -gpu swiftshader_indirect
```

Run it headless. With a window, macOS throttles the emulator's vCPU threads to priority 4 once the window is in the background, and decoding drops from about 125 to about 8 tok/s. With 4 GB of guest RAM, Chrome and the model swap and decoding falls to about 6 tok/s.

### Model

In the app: Set up > Add the model, then pick the `.gguf` file in the system picker. The app checks the `GGUF` magic bytes and copies the file into its own storage. For the fine-tuned file: Settings > Fine-tuned model > Add. The Base / Fine-tuned switch appears in the settings and on every result.

To skip the picker on a debug build:

```sh
adb push ~/honey/fineprint-mobile/models/LFM2.5-1.2B-Instruct-Q4_K_M.gguf /data/local/tmp/base.gguf
adb shell "run-as app.fineprint sh -c 'mkdir -p files/models; cp /data/local/tmp/base.gguf files/models/base.gguf'"
```

The fine-tuned file goes to `files/models/tuned.gguf`.

### Accessibility service

In the app: Set up > Turn on, then Fineprint > Use Fineprint > Allow. Without the Settings app:

```sh
adb shell settings put secure enabled_accessibility_services app.fineprint/app.fineprint.FineprintService
adb shell settings put secure accessibility_enabled 1
```

`adb shell am force-stop app.fineprint` turns the service off. Turn it on again afterwards.

On a phone, Android 13 and later block accessibility services from apps installed outside an app store. If "Use Fineprint" is greyed out, open App info > ⋮ > Allow restricted settings, then try again. `adb install` does not trigger this block.

### Demo

1. Serve the fixtures: `python3 -m http.server 8765 --bind 127.0.0.1 -d ~/honey/fineprint-chrome/fixtures`.
2. Open `http://10.0.2.2:8765/spotify.html` in the emulator's Chrome. The pill appears in about 2 s and counts clauses.
3. Tap the pill for the sheet. Tap a card: Chrome scrolls to the clause and highlights it. Use the arrows to step through findings, and Back to close.
4. Drag the pill to the other edge, or onto the × to hide it on this page.
5. Select a passage in Chrome, then ⋮ > Check with Fineprint.
6. Before and after: flip Base / Fine-tuned on the sheet. When both models have checked the same text, a comparison table appears.
7. Privacy: open the app and scroll to Privacy, or run `aapt dump permissions` on the APK.

## Findings

- Chrome exposes the whole page, including off-screen text, to a bound service. Nodes carry extras `AccessibilityNodeInfo.chromeRole` and `AccessibilityNodeInfo.cssDisplay`. The reader takes the `main` landmark (else `article`, else the root), joins inline pieces (links, `strong`, static text) without a separator because Chrome keeps their spaces, and skips `listMarker` nodes, which HTML-to-text never sees.
- The web root node carries the page title as its text, so the reader descends into any node that has children.
- Right after the service binds, Chrome's tree can be empty for about 2 s. The service retries a failed scan up to 12 times, 1.5 s apart.
- If the service rebinds while Chrome keeps a page open (for example after reinstalling the app), Chrome can expose some paragraphs with no children and no text until Chrome restarts. `refresh()` does not fix them. Reading the same page then gives 220 to 239 clauses instead of 263. This was seen only after reinstalls.
- `java.text.BreakIterator` on the JVM does not break at a newline, but ICU does. `splitClauses` splits on line breaks first, so the JVM test and the device agree.
- LEAP's thread advisor returns 1 thread on the emulator. The app uses half the cores, at most 3, when the advisor returns 1. Measured on this emulator (warm, decode tok/s): 1 thread 9, 2 threads 76, 3 threads 134, 4 threads 7, 6 threads 43 to 63.
- The clause cap is 300, from the model contract. The Chrome extension uses 400.
