# Fineprint Chrome extension: progress

## Load it

1. Start the base model: `llama-server -hf LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q8_0 --port 8080 -np 4 -c 16384`
2. When the fine-tuned model arrives: `llama-server -m fineprint-Q8_0.gguf --port 8081 -np 4 -c 16384`
3. Open `chrome://extensions`, turn on Developer mode, click "Load unpacked", and pick this folder.
4. Open a terms page, then click the Fineprint icon in the toolbar. The side panel opens.

Offline demo pages are in `fixtures/` (Spotify, Tinder, Reddit, Uber terms, saved with inline CSS). Serve them with `python3 -m http.server 8765 --bind 127.0.0.1 -d fixtures` and open `http://127.0.0.1:8765/spotify.html`. To open them as `file://` pages instead, turn on "Allow access to file URLs" for Fineprint in `chrome://extensions`.

Run the logic checks with `node test.mjs`.

## Works (v1, verified 2026-10-22 against the base model)

- Side panel opens from the toolbar icon. "Check this page" reads `main`, then `article`, then `body`. If text is selected, the button changes to "Check selection" and checks only that.
- Clause splitting, 4 parallel requests to `127.0.0.1:{8080|8081}` from the service worker, exact system prompt, lower-cased input, no `response_format`.
- Live progress ("Clause 20 of 263"), live invalid-output count, provisional grade that moves along the A–E strip, cards that stream in by severity.
- Final view: grade, the serious and notable categories, clause count, time, tok/s, invalid-output count.
- Clicking a card scrolls the page to the clause and draws a highlighter mark over it (CSS Custom Highlight API, no DOM changes).
- Base / Fine-tuned switch, saved in `chrome.storage.local`. When both models have checked the same URL, a comparison table shows grade, flagged clauses, invalid output, and time for each.
- Results are cached per model and URL in `chrome.storage.local`.
- Server-down state shows the exact `llama-server` command with a copy button and reconnects on its own.
- Light and dark mode. Reduced motion drops movement and keeps fades.

Evidence: Spotify terms, full page, base model: 263 clauses in 49–58 s at about 200 tok/s, 1 invalid output. Screenshots were taken with Puppeteer and Chrome for Testing (branded Chrome 137+ no longer loads unpacked extensions from the command line).

## Known limits

- The base model flags almost every clause (218 of 263 on Spotify). This is the before half of the demo. The fine-tuned model should flag far fewer.
- After switching tabs with the panel open, Chrome only grants page access again after a click on the Fineprint icon. The panel says so.
- Pages that render their terms inside iframes or shadow DOM are not read.
