(() => {
  const TERMS = /\b(terms|conditions|user agreement|terms of (service|use)|eula)\b/i;
  const CONSENT = /\b(agree|accept|consent|acknowledg)/i;
  const COLORS = { A: ["#1e8a4c", "#fff"], B: ["#7db342", "#1c1a17"], C: ["#f2b705", "#1c1a17"], D: ["#ef7d00", "#1c1a17"], E: ["#e0451f", "#fff"] };
  const MAX_BADGES = 2;
  const seen = new WeakSet();
  const graded = new Set();

  const STYLE = `
    :host { all: initial; display: inline-block; vertical-align: middle; margin-inline: 6px 2px; }
    * { box-sizing: border-box; }
    .badge {
      anchor-name: --fineprint;
      display: inline-flex; align-items: center; gap: 6px; height: 22px; padding: 0 8px 0 3px;
      border: 0; border-radius: 7px; background: #fff; color: #1c1a17; cursor: pointer;
      font: 600 12px/1 system-ui, -apple-system, "Segoe UI", sans-serif; white-space: nowrap;
      font-variant-numeric: tabular-nums;
      box-shadow: 0 0 0 1px rgb(0 0 0 / 0.1), 0 1px 2px rgb(0 0 0 / 0.08), 0 2px 6px rgb(0 0 0 / 0.05);
      transition: opacity 160ms cubic-bezier(0.2, 0, 0, 1), scale 160ms cubic-bezier(0.2, 0, 0, 1);
      @starting-style { opacity: 0; scale: 0.96; }
    }
    .badge:active { scale: 0.97; }
    .badge:focus-visible { outline: 2px solid #059669; outline-offset: 2px; }
    .letter {
      display: grid; place-items: center; width: 16px; height: 16px; border-radius: 4px;
      background: var(--bg, #ecebe8); color: var(--fg, #6b6a66); font-size: 11px;
      transition: background-color 240ms cubic-bezier(0.2, 0, 0, 1), color 240ms cubic-bezier(0.2, 0, 0, 1);
    }
    .text { color: #3f3d3a; font-weight: 500; }
    [data-state="loading"] .letter { animation: pulse 0.9s cubic-bezier(0.2, 0, 0, 1) infinite alternate; }
    @keyframes pulse { to { opacity: 0.4; } }

    .card {
      position-anchor: --fineprint;
      position: fixed; inset: auto; top: anchor(bottom); left: anchor(left);
      position-try-fallbacks: flip-block, flip-inline;
      width: 280px; margin: 8px 0 0; padding: 14px; border: 0; border-radius: 12px;
      background: #fff; color: #1c1a17; text-align: left;
      font: 400 12.5px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif;
      box-shadow: 0 0 0 1px rgb(0 0 0 / 0.07), 0 8px 24px -6px rgb(0 0 0 / 0.18), 0 2px 6px rgb(0 0 0 / 0.06);
      opacity: 0; translate: 0 -4px;
      transition: opacity 160ms cubic-bezier(0.2, 0, 0, 1), translate 160ms cubic-bezier(0.2, 0, 0, 1), display 160ms allow-discrete, overlay 160ms allow-discrete;
    }
    .card:popover-open {
      opacity: 1; translate: 0 0;
      @starting-style { opacity: 0; translate: 0 -4px; }
    }
    .strip { display: grid; grid-template-columns: repeat(5, 1fr); gap: 2px; height: 22px; margin-bottom: 12px; }
    .strip span {
      display: grid; place-items: center; border-radius: 2px; font-size: 10.5px; font-weight: 700;
      background: color-mix(in oklab, var(--c) 17%, #fff); color: color-mix(in oklab, var(--c) 62%, #1c1a17);
    }
    .strip span:first-child { border-radius: 6px 2px 2px 6px; }
    .strip span:last-child { border-radius: 2px 6px 6px 2px; }
    .strip [data-active] { background: var(--c); color: var(--on); box-shadow: 0 0 0 2px #fff, 0 3px 8px -2px var(--c); scale: 1.12; z-index: 1; }
    .headline { margin: 0 0 8px; font: 600 17px/1.2 Charter, "Iowan Old Style", Georgia, serif; }
    .row { display: grid; grid-template-columns: 8px 1fr; gap: 8px; margin: 0 0 6px; color: #55524d; }
    .row::before { content: ""; width: 7px; height: 7px; margin-top: 6px; border-radius: 50%; background: var(--dot); }
    .row b { color: #1c1a17; font-weight: 600; }
    .foot { margin: 10px 0 0; padding-top: 10px; border-top: 1px solid #ecebe8; color: #6b6a66; font-size: 11.5px; }
    @media (prefers-reduced-motion: reduce) {
      .badge, .card { transition-property: opacity, display, overlay; translate: none; }
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

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }

  function fillCard(card, result, grade, text) {
    const strip = element("div", "strip");
    for (const letter of "ABCDE") {
      const cell = element("span", "", letter);
      cell.style.setProperty("--c", COLORS[letter][0]);
      cell.style.setProperty("--on", COLORS[letter][1]);
      cell.toggleAttribute("data-active", letter === grade);
      strip.append(cell);
    }
    const rows = [["Serious", result.high, "#e0451f"], ["Worth knowing", result.medium, "#ef7d00"]]
      .filter(([, labels]) => labels.length)
      .map(([name, labels, dot]) => {
        const row = element("p", "row");
        row.style.setProperty("--dot", dot);
        const label = element("span");
        label.append(element("b", "", `${name}: `), labels.join(", "));
        row.append(label);
        return row;
      });
    card.replaceChildren(strip, element("p", "headline", `Grade ${grade} · ${text}`), ...rows, element("p", "foot", "Checked on this computer by Fineprint. Click the badge for the full report."));
  }

  function badge(link, url) {
    const host = document.createElement("fineprint-badge");
    const root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `<style>${STYLE}</style><button type="button" class="badge" data-state="loading"><span class="letter">F</span><span class="text">Reading terms</span></button><div class="card" popover="manual" role="tooltip"></div>`;
    const button = root.querySelector(".badge");
    const card = root.querySelector(".card");
    const text = root.querySelector(".text");
    let hideTimer = 0;
    const show = () => {
      clearTimeout(hideTimer);
      if (button.dataset.state === "done" && !card.matches(":popover-open")) card.showPopover();
    };
    const hide = () => {
      hideTimer = setTimeout(() => card.matches(":popover-open") && card.hidePopover(), 120);
    };
    button.addEventListener("pointerenter", show);
    button.addEventListener("focus", show);
    button.addEventListener("pointerleave", hide);
    button.addEventListener("blur", hide);
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (card.matches(":popover-open")) card.hidePopover();
      chrome.runtime.sendMessage({ type: "openLinked", url }).catch(() => open(url, "_blank", "noopener"));
    });
    link.after(host);

    chrome.runtime.onMessage.addListener((message) => {
      if (message.type !== "progress" || message.url !== url || button.dataset.state !== "loading") return;
      text.textContent = `Reading terms · ${Math.round((message.done / message.total) * 100)}%`;
    });

    chrome.runtime.sendMessage({ type: "grade", url }).catch(() => ({ status: "failed" })).then((result) => {
      const [grade, label] = summary(result);
      button.dataset.state = result.status;
      const letter = root.querySelector(".letter");
      letter.textContent = grade || "F";
      text.textContent = grade ? `Grade ${grade} · ${label}` : label;
      if (!grade) return;
      letter.style.setProperty("--bg", COLORS[grade][0]);
      letter.style.setProperty("--fg", COLORS[grade][1]);
      fillCard(card, result, grade, label);
      button.ariaLabel = `Fineprint grade ${grade}, ${label}. Open the full report.`;
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
