(() => {
  const TERMS = /\b(terms|conditions|user agreement|terms of (service|use)|eula)\b/i;
  const CONSENT = /\b(agree|accept|consent|acknowledg)/i;
  const COLORS = { A: ["#1e8a4c", "#fff"], B: ["#7db342", "#1c1a17"], C: ["#f2b705", "#1c1a17"], D: ["#ef7d00", "#1c1a17"], E: ["#e0451f", "#fff"] };
  const MAX_BADGES = 2;
  const seen = new WeakSet();
  const graded = new Set();

  const STYLE = `
    :host { all: initial; display: inline-block; vertical-align: middle; margin-inline-start: 6px; }
    button {
      display: inline-flex; align-items: center; gap: 6px; height: 22px; padding: 0 8px 0 3px;
      border: 0; border-radius: 7px; background: #fff; color: #1c1a17; cursor: pointer;
      font: 600 12px/1 system-ui, -apple-system, "Segoe UI", sans-serif; white-space: nowrap;
      box-shadow: 0 0 0 1px rgb(0 0 0 / 0.1), 0 1px 2px rgb(0 0 0 / 0.08), 0 2px 6px rgb(0 0 0 / 0.05);
      transition: opacity 160ms cubic-bezier(0.2, 0, 0, 1), scale 160ms cubic-bezier(0.2, 0, 0, 1);
      @starting-style { opacity: 0; scale: 0.96; }
    }
    button:active { scale: 0.97; }
    button:focus-visible { outline: 2px solid #059669; outline-offset: 2px; }
    .letter {
      display: grid; place-items: center; width: 16px; height: 16px; border-radius: 4px;
      background: var(--bg, #ecebe8); color: var(--fg, #6b6a66); font-size: 11px;
      transition: background-color 240ms cubic-bezier(0.2, 0, 0, 1), color 240ms cubic-bezier(0.2, 0, 0, 1);
    }
    .text { color: #3f3d3a; font-weight: 500; font-variant-numeric: tabular-nums; }
    [data-state="loading"] .letter { animation: pulse 0.9s cubic-bezier(0.2, 0, 0, 1) infinite alternate; }
    @keyframes pulse { to { opacity: 0.4; } }
    @media (prefers-reduced-motion: reduce) {
      button { transition-property: opacity; }
      [data-state="loading"] .letter { animation: none; }
    }
  `;

  function summary(result) {
    if (result.status === "offline") return ["", "Start the local model to grade these terms"];
    if (result.status !== "done") return ["", "Fineprint couldn’t read these terms"];
    const serious = result.high.length;
    const notable = result.medium.length;
    const text = serious ? `${serious} serious term${serious === 1 ? "" : "s"}`
      : notable ? `${notable} term${notable === 1 ? "" : "s"} worth knowing`
      : "Nothing unfair found";
    return [result.grade, text];
  }

  function badge(link, url) {
    const host = document.createElement("fineprint-badge");
    const root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `<style>${STYLE}</style><button type="button" data-state="loading"><span class="letter">F</span><span class="text">Reading terms</span></button>`;
    const button = root.querySelector("button");
    button.title = "Fineprint is reading these terms on your computer";
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      open(url, "_blank", "noopener");
    });
    link.after(host);
    chrome.runtime.onMessage.addListener((message) => {
      if (message.type !== "progress" || message.url !== url || button.dataset.state !== "loading") return;
      root.querySelector(".text").textContent = `Reading terms · ${Math.round((message.done / message.total) * 100)}%`;
    });

    chrome.runtime.sendMessage({ type: "grade", url }).catch(() => ({ status: "failed" })).then((result) => {
      const [grade, text] = summary(result);
      button.dataset.state = result.status;
      const letter = root.querySelector(".letter");
      letter.textContent = grade || "F";
      if (grade) {
        letter.style.setProperty("--bg", COLORS[grade][0]);
        letter.style.setProperty("--fg", COLORS[grade][1]);
      }
      root.querySelector(".text").textContent = grade ? `Grade ${grade} · ${text}` : text;
      const details = [...result.high ?? [], ...result.medium ?? []];
      button.title = `${details.length ? `${details.join(", ")}. ` : ""}Graded on your computer by Fineprint. Click to open the terms.`;
    });
  }

  function scan() {
    for (const link of document.querySelectorAll("a[href]")) {
      if (graded.size >= MAX_BADGES) return observer.disconnect();
      if (seen.has(link)) continue;
      seen.add(link);
      if (!TERMS.test(link.textContent) || !/^https?:/.test(link.href)) continue;
      if (!CONSENT.test(link.closest("label, p, li, span, div")?.textContent ?? "")) continue;
      const url = link.href.split("#")[0];
      if (url === location.href.split("#")[0] || graded.has(url)) continue;
      graded.add(url);
      badge(link, url);
    }
  }

  let pending = 0;
  const observer = new MutationObserver(() => {
    clearTimeout(pending);
    pending = setTimeout(scan, 400);
  });
  observer.observe(document.body, { childList: true, subtree: true });
  scan();
})();
