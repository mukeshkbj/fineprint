<img src="assets/icon.svg" width="64" height="64" alt="">

# Fineprint

Fineprint reads terms of service on your own device and tells you, in plain English, what you agree to. A 1.2B model (Liquid AI LFM2.5-1.2B, fine-tuned on the expert-labelled UNFAIR-ToS dataset) flags each clause. Fineprint then grades the whole document from A to E. No text leaves the device.

## Layout

| Folder | What it is |
|---|---|
| [`chrome/`](chrome/) | Chrome extension (Manifest V3, plain JavaScript, no build step). It talks to the model through `llama-server` on `127.0.0.1`. |
| `android/` | Android app with an on-device model and an overlay above other apps. Arrives in a later pull request. |

## Chrome quick start

1. Install llama.cpp (`brew install llama.cpp`), then start the base model:
   `llama-server -hf LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q8_0 --port 8080 -np 4 -c 16384`
2. Start the fine-tuned model on port 8081 when you have it:
   `llama-server -m fineprint-Q8_0.gguf --port 8081 -np 4 -c 16384`
3. Open `chrome://extensions`, turn on Developer mode, click "Load unpacked", and pick the `chrome/` folder.
4. Open a terms page and click the Fineprint icon.

[`chrome/progress.md`](chrome/progress.md) has the demo script, the offline demo pages, what works, the evidence, and the known limits.
