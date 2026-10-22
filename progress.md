# Fineprint Chrome extension: progress

## Load it

1. Start the base model: `llama-server -hf LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q8_0 --port 8080 -np 4 -c 16384`
2. When the fine-tuned model arrives: `llama-server -m fineprint-Q8_0.gguf --port 8081 -np 4 -c 16384`
3. Open `chrome://extensions`, turn on Developer mode, click "Load unpacked", and pick this folder.
4. Open a terms page, then click the Fineprint icon in the toolbar. The side panel opens for that tab.

Offline demo pages are in `fixtures/`: the Spotify, Tinder, Reddit and Uber terms, saved with inline CSS, plus `signup.html`, a sign-up form that links to the Spotify terms. Serve them with `python3 -m http.server 8765 --bind 127.0.0.1 -d fixtures`, then open `http://127.0.0.1:8765/spotify.html`. To open them as `file://` pages instead, turn on "Allow access to file URLs" for Fineprint in `chrome://extensions`.

Run the logic checks with `node test.mjs`.

## Demo script

1. Open `spotify.html` and click the Fineprint icon. The panel shows the page's title, its clause count, an estimated time, and a document map: one line per clause, laid out like the page itself. Click "Check this page". The lines fill in as the model reads them, red and orange where it flags something, and the grade moves along the A–E strip. When the check ends, the grade lands like a stamp. The final view shows the invalid-output count.
2. Switch to Fine-tuned and click "Check this page" again. When both models have checked the page, a table compares grade, flagged clauses, invalid output, and time.
3. Click a card. The page scrolls to the clause and a highlighter mark sweeps across it.
4. In the panel, turn on "Grade terms on sign-up forms", then open `signup.html`. A badge next to "Terms of Use" reads the linked terms and shows the grade. Hover it for a summary card. Click it, and the side panel opens on the sign-up page with the full report on the linked terms. Click a card there, and the terms open in a new tab with that clause highlighted.
5. Click "Compare two versions of the terms". Paste an old version, click "Use this page" for the new one, and the panel shows only the flagged clauses the new version adds.

## Works (verified 2026-10-22 against the base model)

- Side panel per tab, opened by the toolbar icon. "Check this page" reads `main`, then `article`, then `body`. With text selected, the button reads "Check selection" and checks only that.
- The model contract: clause splitting, 4 parallel requests to `127.0.0.1:{8080|8081}` from the service worker, the exact system prompt, lower-cased input, and no `response_format`. Each request times out after 20 s.
- Live progress ("Clause 20 of 263"), a live invalid-output count, a provisional grade that moves along the strip, and cards that stream in by severity. Existing cards glide when a new card is inserted above them.
- Final view: grade, serious and notable categories, clause count, time, tok/s, invalid-output count.
- A card click scrolls the page to the clause and sweeps a highlight across it. The highlight uses the CSS Custom Highlight API and does not change the page's DOM. A clause that is no longer on the page shows a note on the card.
- Base / Fine-tuned switch, saved in `chrome.storage.local`. Results are cached per model and URL. A comparison table appears when both models have checked a URL.
- Sign-up badges (opt-in): the "Grade terms on sign-up forms" switch asks for access to all sites. With access, a content script finds "I agree to the Terms" links. The service worker fetches the linked page, checks it on the local model, and the badge shows its progress and then the grade. The same access lets the panel read any tab without a click.
- "What changed?": two text fields (old and new version), "Use this page" for the new one, a grade before and after, and cards only for flagged clauses that are new.
- Every failure path has its own screen: model not running (command with a copy button, reconnects by itself), model loading, model lost during a check (partial results kept), Stop, no terms found, and a page Chrome does not allow.
- Design: Newsreader (OFL, bundled in `fonts/`, no remote calls) for headlines and quoted clauses, system sans for the interface. A document map shows where the flags sit in the document, doubles as the progress display, and links both ways with the cards. Long groups show five cards and a "Show N more" control. The base-versus-fine-tuned comparison is a pair of grade cards.
- Light and dark mode, keyboard focus rings, arrow keys on the model switch, and reduced motion (drops movement, keeps fades).

## Evidence

- `node test.mjs` passes: normalisation, clause splitting, reply parsing with malformed replies, grade rule, HTML-to-text.
- Spotify terms, full page, base model: 263 clauses in 49–58 s at about 200 tok/s, 1 invalid output.
- Sign-up badge on `signup.html`: progress from 0 to 100 %, then "Grade E · 4 serious terms" (base model). Badge click opened the side panel in place (Chrome accepts the click forwarded from the content script as a user gesture). A card click opened the terms tab with the clause highlighted.
- Motion checked frame by frame at 20–25 % speed through the DevTools Animation domain: map fill, grade travel, stamp and ink ring, card insertion.
- Screenshots came from Puppeteer driving Chrome for Testing. Branded Chrome 137 and later no longer loads unpacked extensions from the command line. The scripts are outside the repo, in `/tmp/fp-harness`.

## Known limits

- The base model flags almost every clause (218 of 263 on Spotify). That is the "before" half of the demo.
- Without the all-sites switch, Chrome grants page access only after a click on the icon, and again after the tab navigates. The panel says so.
- Terms inside iframes, shadow DOM, or Chrome's PDF viewer are not read. The sign-up badge reads the linked page's HTML, so terms pages that render only with JavaScript come back empty.
- `Intl.Segmenter` splits after abbreviations such as "e.g.". The contract requires this splitter, so it stays.
