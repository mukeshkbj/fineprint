export function readPage(withText) {
  const selection = getSelection().toString().trim();
  const root = document.querySelector("main") ?? document.querySelector("article") ?? document.body;
  return { selection, text: withText ? root.innerText : "" };
}

export function markClause(clause) {
  const SKIP = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "TEXTAREA"]);
  const nodes = [];
  const offsets = [];
  let haystack = "";
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => (SKIP.has(node.parentElement?.tagName) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  });
  for (let node; (node = walker.nextNode()); ) {
    const value = node.nodeValue;
    for (let i = 0; i < value.length; i++) {
      if (/\s/.test(value[i])) continue;
      const lower = value[i].toLowerCase();
      haystack += lower.length === 1 ? lower : value[i];
      nodes.push(node);
      offsets.push(i);
    }
  }

  const rangeAt = (start, length) => {
    const range = document.createRange();
    range.setStart(nodes[start], offsets[start]);
    range.setEnd(nodes[start + length - 1], offsets[start + length - 1] + 1);
    return range;
  };
  const find = (needle) => {
    for (let at = haystack.indexOf(needle); at !== -1; at = haystack.indexOf(needle, at + 1)) {
      const range = rangeAt(at, needle.length);
      if (range.getClientRects().length) return { at, range };
    }
  };

  const needle = clause.replace(/\s+/g, "").toLowerCase();
  const match = find(needle) ?? find(needle.slice(0, 60));
  if (!match) return false;

  if (!document.getElementById("fineprint-highlight")) {
    const style = document.createElement("style");
    style.id = "fineprint-highlight";
    style.textContent = "::highlight(fineprint) { background-color: rgb(255 208 0 / 0.42); }";
    document.head.append(style);
  }

  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const length = match.range.toString().replace(/\s+/g, "").length;
  const sweep = () => {
    if (reduced) return CSS.highlights.set("fineprint", new Highlight(match.range));
    const started = performance.now();
    const duration = Math.min(700, 240 + length * 1.5);
    const frame = (now) => {
      const progress = Math.min(1, (now - started) / duration);
      const eased = 1 - (1 - progress) ** 3;
      CSS.highlights.set("fineprint", new Highlight(rangeAt(match.at, Math.max(1, Math.round(length * eased)))));
      if (progress < 1) requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  };

  CSS.highlights.delete("fineprint");
  const rect = match.range.getBoundingClientRect();
  const visible = rect.top >= 0 && rect.bottom <= innerHeight;
  if (visible) {
    sweep();
    return true;
  }
  const scroller = nodes[match.at].parentElement;
  scroller.scrollIntoView({ block: "center", behavior: reduced ? "instant" : "smooth" });
  let swept = false;
  const once = () => {
    if (swept) return;
    swept = true;
    sweep();
  };
  addEventListener("scrollend", once, { once: true, capture: true });
  setTimeout(once, 900);
  return true;
}
