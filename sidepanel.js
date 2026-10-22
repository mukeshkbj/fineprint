import { ALL_SITES, CATEGORIES, MODELS, SEVERITIES, flagsBySeverity, gradeOf, hostOf, severityOf } from "./lib.js";

const GROUPS = { high: "Serious", medium: "Worth knowing", low: "Minor" };
const SERVER_TEXT = { ready: "Ready", loading: "Loading", offline: "Not running" };
const SPRING = "linear(0, 0.03 2.6%, 0.152 7%, 0.305 11.5%, 0.453 15.9%, 0.58 20.4%, 0.684 24.8%, 0.765 29.3%, 0.828 33.7%, 0.874 38.1%, 0.909 42.6%, 0.934 47%, 0.953 51.5%, 0.966 55.9%, 0.976 60.4%, 0.983 64.8%, 0.988 69.3%, 0.991 73.7%, 0.994 78.1%, 0.996 82.6%, 0.997 87%, 0.998 91.5%, 0.999 95.9%, 1)";
const POLL = 2500;
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
const count = new Intl.NumberFormat("en");
const list = new Intl.ListFormat("en", { type: "conjunction" });
const ago = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

const screen = document.getElementById("screen");
const server = document.getElementById("server");
const segmented = document.querySelector(".segmented");

let model = (await chrome.storage.local.get("model")).model ?? "base";
const [{ id: tabId }] = await chrome.tabs.query({ active: true, currentWindow: true });
let view = null;
let current = { key: null, root: null };
let editing = false;
let diffHint = "";
const drafts = { before: "", after: "" };

const send = (message) => chrome.runtime.sendMessage(message);
const clone = (id) => document.getElementById(id).content.firstElementChild.cloneNode(true);
const seconds = (ms) => `${ms < 10_000 ? (ms / 1000).toFixed(1) : Math.round(ms / 1000)} s`;
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
const labelsOf = (flags) => list.format(flags.map((flag) => CATEGORIES[flag].label));

function duration(secondsLeft) {
  if (secondsLeft < 50) return `${Math.max(5, Math.round(secondsLeft / 5) * 5)} s`;
  const minutes = Math.round(secondsLeft / 60);
  return minutes <= 1 ? "a minute" : `${minutes} minutes`;
}

function renderMap(container, lengths, marks = []) {
  if (container.childElementCount !== lengths.length) {
    const total = lengths.reduce((sum, length) => sum + length, 0) || 1;
    const rows = Math.min(14, Math.max(2, Math.ceil(lengths.length / 8)));
    container.replaceChildren(...lengths.map((length, index) => {
      const line = document.createElement("span");
      line.style.setProperty("--w", ((length * rows * 100) / total).toFixed(3));
      line.style.setProperty("--i", index);
      return line;
    }));
  }
  marks.forEach((mark, index) => {
    const line = container.children[index];
    if (line.dataset.mark !== mark) line.dataset.mark = mark;
  });
}

function renderDoc(root, title, meta) {
  const heading = root.querySelector(".doc-title");
  setText(heading, title);
  heading.title = title;
  setText(root.querySelector(".doc-meta"), meta.filter(Boolean).join(" · "));
}

function since(time) {
  const minutes = Math.round((time - Date.now()) / 60_000);
  if (minutes > -1) return "just now";
  if (minutes > -60) return ago.format(minutes, "minute");
  if (minutes > -1440) return ago.format(Math.round(minutes / 60), "hour");
  return ago.format(Math.round(minutes / 1440), "day");
}

function show(key, template) {
  if (current.key === key) return current.root;
  const root = clone(template);
  screen.replaceChildren(root);
  for (const group of root.querySelectorAll(".group")) {
    group.querySelector(".group-name").textContent = GROUPS[group.dataset.severity];
    fluidHover(group);
  }
  const legend = root.querySelector(".legend-list");
  for (const severity of legend ? SEVERITIES : []) {
    const row = document.createElement("div");
    row.dataset.severity = severity;
    row.append(document.createElement("dt"), document.createElement("dd"));
    row.firstChild.textContent = GROUPS[severity];
    row.lastChild.textContent = Object.values(CATEGORIES).filter((category) => category.severity === severity).map((category) => category.label).join(", ");
    legend.append(row);
  }
  current = { key, root, cards: new Map(), selected: null };
  return root;
}

function render() {
  server.dataset.state = view?.server ?? "unknown";
  document.getElementById("server-text").textContent = `${MODELS[model].label} model on port ${MODELS[model].port} · ${SERVER_TEXT[view?.server] ?? "Checking"}`;
  if (!view) return;
  if (editing) return renderDiffForm();
  if (!view.readable) return renderUnreadable();
  if (!view.job || view.job.status === "empty") return renderIntro(view.job);
  renderReport(view.job);
}

function renderUnreadable() {
  const root = show(`unreadable|${view.granted}`, "unreadable");
  root.querySelector(".title").textContent = view.granted ? "Fineprint can’t read this page" : "Click the Fineprint icon to read this page";
  root.querySelector(".lede").textContent = view.granted
    ? "Chrome pages, the Web Store, and the PDF viewer stay off limits. Open the terms as a web page and check again."
    : "Chrome lets Fineprint read a page after you click its icon on that page, and again after the tab moves to a new page.";
  renderSites(root);
}

function renderSites(root) {
  const setting = root.querySelector(".setting") ?? root.appendChild(clone("sites"));
  setting.querySelector("button").textContent = view.granted ? "Turn off" : "Turn on";
}

function renderIntro(job) {
  const root = show(`intro|${model}`, "intro");
  renderSites(root);
  const clauses = view.outline.length;
  renderDoc(root, view.selected ? "Your selection" : view.title, [view.host, clauses && plural(clauses, "clause")]);
  renderMap(root.querySelector(".map-bars"), view.outline);
  root.querySelector(".map").hidden = !clauses;
  const action = root.querySelector(".intro-action");
  if (view.server === "offline") {
    if (!action.querySelector(".notice")) action.replaceChildren(offlineNotice());
    return;
  }
  const check = action.querySelector(".check") ?? action.appendChild(clone("check"));
  action.querySelector(".notice")?.remove();
  const button = check.querySelector("button");
  button.textContent = view.selected ? "Check selection" : "Check this page";
  button.disabled = view.server !== "ready";
  check.querySelector(".hint").textContent =
    view.server === "loading" ? "The model is still loading. This takes a few seconds."
    : job?.status === "empty" ? `No terms found on ${view.host}. Select the terms and check again.`
    : !view.outline.length ? `No terms found on ${view.host}. Select the terms and check again.`
    : `About ${duration(view.estimate)} with the ${MODELS[model].label.toLowerCase()} model.${view.selected ? "" : " Select text to check only part of the page."}`;
}

function renderDiffForm() {
  const root = show(`diff|${model}`, "diff");
  if (!root.dataset.ready) {
    root.dataset.ready = "";
    for (const field of root.querySelectorAll("textarea")) {
      field.value = drafts[field.name];
      field.addEventListener("input", () => {
        drafts[field.name] = field.value;
        diffHint = "";
      });
    }
  }
  root.querySelector('[data-action="use-page"]').hidden = !view.readable;
  root.querySelector('[data-action="diff"]').disabled = view.server !== "ready";
  root.querySelector(".hint").textContent = diffHint || (view.server === "ready" ? "Both versions are checked on this computer." : "Start the local model to compare.");
}

function offlineNotice(title, text) {
  const notice = clone("offline");
  if (title) notice.querySelector(".notice-title").textContent = title;
  if (text) notice.querySelector(".notice-text").textContent = text;
  notice.querySelector("code").textContent = MODELS[model].command;
  return notice;
}

function renderReport(job) {
  const root = show(`report|${job.id}|${model}`, "report");
  const running = job.status === "running";
  const diff = job.source === "diff";
  const finishing = root.dataset.status === "running" && job.status === "done";
  root.dataset.status = job.status;
  renderDoc(root,
    diff ? "Two versions compared" : job.source === "selection" ? "Your selection" : job.title || hostOf(job.url),
    [job.source === "link" && "Linked from this page", !diff && hostOf(job.url), plural(job.total, "clause"), job.checkedAt && `checked ${since(job.checkedAt)}`]);

  const shown = job.findings.filter((finding) => finding.side !== "old");
  const previous = job.findings.filter((finding) => finding.side === "old" || finding.side === "both");
  const added = job.findings.filter((finding) => finding.side !== "old" && finding.side !== "both");
  const grade = gradeOf(shown);
  const strip = root.querySelector(".strip");
  strip.dataset.grade = job.done ? grade : "";
  strip.style.setProperty("--index", "ABCDE".indexOf(grade));
  strip.setAttribute("aria-label", job.done ? `Grade ${grade}` : "No grade yet");
  for (const cell of strip.querySelectorAll(".cell")) cell.toggleAttribute("data-active", job.done > 0 && cell.dataset.grade === grade);
  if (finishing && !reducedMotion.matches) {
    strip.classList.add("stamp");
    setTimeout(() => strip.classList.remove("stamp"), 900);
  }

  const known = new Set(previous.flatMap((finding) => finding.flags));
  const { high, medium } = flagsBySeverity(shown);
  const fresh = (flags) => flags.filter((flag) => !known.has(flag));
  const [newHigh, newMedium] = [fresh(high), fresh(medium)];
  const prefix = diff ? "new " : "";
  const headline = newHigh.length ? plural(newHigh.length, `${prefix}serious term`)
    : newMedium.length ? `${plural(newMedium.length, `${prefix}term`)} worth knowing`
    : diff ? "nothing new to worry about" : "nothing unfair found";
  setText(root.querySelector(".verdict"), !job.done ? "Reading the terms" : running ? `So far, ${headline}` : headline[0].toUpperCase() + headline.slice(1));
  const labels = labelsOf([...newHigh, ...newMedium]);
  setText(root.querySelector(".verdict-detail"),
    diff && job.done ? `Grade ${gradeOf(previous)} before, ${grade} now.${labels ? ` New: ${labels}.` : ""}`
    : labels ? `${labels}.`
    : running ? `Checking ${diff ? "both versions" : job.source === "selection" ? "your selection" : hostOf(job.url)} on this computer.`
    : `No serious or notable terms in ${plural(job.done, "clause")}.`);

  setText(root.querySelector(".map-label"), running ? `Clause ${count.format(job.done)} of ${count.format(job.total)}` : "Where the flags are");
  setText(root.querySelector(".map-time"), `${job.invalid ? `${count.format(job.invalid)} invalid · ` : ""}${seconds(job.elapsed)}`);
  renderMap(root.querySelector(".map-bars"), job.lengths, job.marks);

  const outcome = root.querySelector(".outcome");
  const outcomeKey = running ? "" : `${job.status}|${job.skipped}`;
  if (outcome.dataset.key !== outcomeKey) {
    outcome.dataset.key = outcomeKey;
    const partial = `after ${count.format(job.done)} of ${count.format(job.total)} clauses`;
    outcome.replaceChildren(
      job.status === "offline" ? offlineNotice("The model stopped answering", `It stopped ${partial}. Start it again with this command, then check again.`)
      : job.status === "stopped" ? paragraph(`Stopped ${partial}. The grade covers only those.`)
      : job.status === "failed" ? paragraph(`${job.error} The check stopped ${partial}.`)
      : job.status === "done" && job.skipped ? paragraph(`The model could not read ${plural(job.skipped, "clause")}, usually because ${job.skipped === 1 ? "it was" : "they were"} too long. The grade leaves ${job.skipped === 1 ? "it" : "them"} out.`)
      : "",
    );
  }

  const stats = root.querySelector(".stats");
  stats.hidden = running || !job.done;
  if (!stats.hidden) {
    stat(stats, "clauses", job.done === job.total ? count.format(job.total) : `${count.format(job.done)} of ${count.format(job.total)}`);
    stat(stats, "time", seconds(job.elapsed));
    stat(stats, "speed", `${Math.round(job.tokens / Math.max(job.elapsed / 1000, 0.001))} tok/s`);
    stat(stats, "invalid", count.format(job.invalid)).toggleAttribute("data-warn", job.invalid > 0);
  }

  const compare = root.querySelector(".compare");
  compare.hidden = diff || job.status !== "done" || !view.other;
  if (!compare.hidden) {
    for (const report of [job, view.other]) {
      const column = compare.querySelector(`.compare-col[data-model="${report.model}"]`);
      const value = (name, text) => setText(column.querySelector(`[data-value="${name}"]`), text);
      column.toggleAttribute("data-current", report.model === model);
      value("grade", gradeOf(report.findings));
      column.querySelector('[data-value="grade"]').dataset.grade = gradeOf(report.findings);
      value("flagged", count.format(report.findings.length));
      value("invalid", count.format(report.invalid));
      value("time", seconds(report.elapsed));
    }
  }

  renderFindings(root, added, job);
  const actions = root.querySelector(".report-actions");
  actions.hidden = running;
  for (const button of actions.querySelectorAll("[data-for]")) button.hidden = !button.dataset.for.split(" ").includes(job.source);
  actions.querySelector('[data-action="check"]').disabled = view.server !== "ready";
  actions.querySelector('[data-action="open-terms"]').dataset.url = job.url ?? "";
}

function renderFindings(root, findings, job) {
  const fresh = findings.filter((finding) => !current.cards.has(finding.index));
  if (!fresh.length) return;
  const initial = current.cards.size === 0;
  flip(root.querySelector(".findings"), () => {
    fresh.forEach((finding, order) => {
      const card = createCard(finding, job);
      if (initial && job.status !== "running") card.style.transitionDelay = `${Math.min(order, 10) * 24}ms`;
      const cards = root.querySelector(`.group[data-severity="${severityOf(finding.flags)}"] .cards`);
      const next = [...cards.children].find((other) => Number(other.dataset.index) > finding.index);
      cards.insertBefore(card, next ?? null);
      current.cards.set(finding.index, card);
    });
    for (const group of root.querySelectorAll(".group")) {
      const total = group.querySelector(".cards").children.length;
      group.hidden = total === 0;
      group.querySelector(".group-count").textContent = total;
      group.querySelector(".group-more").textContent = `Show ${count.format(total - 5)} more`;
    }
  });
}

function createCard(finding, job) {
  const card = clone("card");
  card.dataset.index = finding.index;
  const flags = card.querySelector(".card-flags");
  for (const flag of finding.flags) {
    const label = document.createElement("span");
    label.className = "flag";
    label.dataset.severity = CATEGORIES[flag].severity;
    label.textContent = CATEGORIES[flag].label;
    flags.append(label);
  }
  card.querySelector(".card-number").textContent = `§ ${finding.index + 1}`;
  card.querySelector(".card-plain").textContent = finding.plain;
  card.querySelector(".card-quote").textContent = `“${finding.text}”`;
  const line = () => current.root.querySelector(".map-bars").children[finding.index];
  line().title = `§ ${finding.index + 1} · ${finding.flags.map((flag) => CATEGORIES[flag].label).join(", ")}`;
  card.addEventListener("pointerenter", () => line().setAttribute("data-focus", ""));
  card.addEventListener("pointerleave", () => line().removeAttribute("data-focus"));
  card.querySelector(".card-hit").addEventListener("click", () => select(card, finding, job));
  card.addEventListener("transitionend", () => card.style.removeProperty("transition-delay"), { once: true });
  return card;
}

async function select(card, finding, job) {
  const findings = current.root.querySelector(".findings");
  flip(findings, () => {
    if (current.selected && current.selected !== card) {
      current.selected.classList.remove("selected");
      current.selected.querySelector(".card-hit").setAttribute("aria-expanded", "false");
    }
    card.classList.add("selected");
    card.querySelector(".card-hit").setAttribute("aria-expanded", "true");
  });
  current.selected = card;
  if (job.source === "diff") return;
  const { found } = await send({ type: "mark", tabId, url: job.url, text: finding.text, open: job.source === "link" });
  card.classList.toggle("missing", !found);
}

function flip(container, mutate) {
  if (reducedMotion.matches) return mutate();
  const groups = [...container.querySelectorAll(".group:not([hidden])")];
  const cards = [...container.querySelectorAll(".card")];
  const groupTop = new Map(groups.map((group) => [group, group.getBoundingClientRect().top]));
  const cardTop = new Map(cards.map((card) => [card, card.getBoundingClientRect().top - card.parentElement.getBoundingClientRect().top]));
  mutate();
  const move = (element, delta) => {
    if (Math.abs(delta) < 0.5) return;
    element.animate([{ transform: `translateY(${delta}px)` }, { transform: "none" }], { duration: 450, easing: SPRING, composite: "add" });
  };
  for (const [group, top] of groupTop) if (!group.hidden) move(group, top - group.getBoundingClientRect().top);
  for (const [card, top] of cardTop) move(card, top - (card.getBoundingClientRect().top - card.parentElement.getBoundingClientRect().top));
}

function fluidHover(group) {
  const glow = group.querySelector(".hover");
  const cards = group.querySelector(".cards");
  let lit = null;
  const place = (card) => {
    glow.style.transform = `translateY(${card.offsetTop}px)`;
    glow.style.height = `${card.offsetHeight}px`;
  };
  const light = (card) => {
    if (card === lit) return;
    const entering = !lit;
    lit?.removeAttribute("data-lit");
    lit = card;
    if (!card) return glow.classList.remove("on");
    card.setAttribute("data-lit", "");
    glow.classList.toggle("instant", entering);
    place(card);
    if (entering) glow.getBoundingClientRect();
    glow.classList.remove("instant");
    glow.classList.add("on");
  };
  new ResizeObserver(() => lit && place(lit)).observe(cards);
  cards.addEventListener("pointermove", (event) => {
    let nearest = null;
    let distance = Infinity;
    for (const card of cards.children) {
      const rect = card.getBoundingClientRect();
      const gap = Math.abs(event.clientY - (rect.top + rect.height / 2));
      if (gap < distance) [nearest, distance] = [card, gap];
    }
    light(nearest);
  });
  cards.addEventListener("pointerleave", () => light(null));
}

function setText(element, text) {
  if (element.textContent !== text) element.textContent = text;
}

function paragraph(text) {
  const p = document.createElement("p");
  p.className = "outcome-text";
  p.textContent = text;
  return p;
}

function stat(stats, name, value) {
  const element = stats.querySelector(`[data-stat="${name}"]`);
  setText(element, value);
  return element;
}

let refreshing = null;
let stale = false;

async function refresh() {
  if (refreshing) {
    stale = true;
    return refreshing;
  }
  refreshing = send({ type: "view", tabId, model });
  view = await refreshing;
  refreshing = null;
  render();
  if (stale) {
    stale = false;
    refresh();
  }
}

function selectModel(next) {
  model = next;
  chrome.storage.local.set({ model });
  segmented.dataset.selected = model;
  for (const radio of segmented.querySelectorAll("[role=radio]")) {
    const checked = radio.dataset.model === model;
    radio.setAttribute("aria-checked", checked);
    radio.tabIndex = checked ? 0 : -1;
  }
  view = view && { ...view, server: null };
  refresh();
}

async function copyCommand(event) {
  const notice = event.target.closest(".notice");
  const status = notice.querySelector("[data-copied]");
  await navigator.clipboard.writeText(notice.querySelector("code").textContent);
  notice.classList.add("copied");
  status.textContent = "Copied";
  setTimeout(() => {
    notice.classList.remove("copied");
    status.textContent = "";
  }, 1600);
}

const actions = {
  async check() {
    const job = await send({ type: "check", tabId, model });
    if (job.error) return refresh();
    view = { ...view, job };
    render();
  },
  stop: () => send({ type: "stop", tabId }),
  more(event) {
    const group = event.target.closest(".group");
    flip(current.root.querySelector(".findings"), () => group.setAttribute("data-expanded", ""));
  },
  refresh,
  copy: copyCommand,
  "diff-form"() {
    editing = true;
    render();
  },
  async home() {
    editing = false;
    await send({ type: "dismiss", tabId });
    refresh();
  },
  "open-terms"(event) {
    chrome.tabs.create({ url: event.target.dataset.url });
  },
  async "use-page"() {
    screen.querySelector('textarea[name="after"]').value = drafts.after = await send({ type: "pageText", tabId });
  },
  async diff() {
    if (!drafts.before.trim() || !drafts.after.trim()) {
      diffHint = "Paste both versions first.";
      return render();
    }
    const job = await send({ type: "diff", tabId, model, ...drafts });
    if (job.status === "empty") {
      diffHint = "Fineprint found no clauses to compare. Paste longer passages.";
      return render();
    }
    editing = false;
    view = { ...view, job };
    render();
  },
  async sites() {
    await (view.granted ? chrome.permissions.remove(ALL_SITES) : chrome.permissions.request(ALL_SITES));
    refresh();
  },
};

segmented.addEventListener("click", (event) => {
  const radio = event.target.closest("[role=radio]");
  if (radio && radio.dataset.model !== model) selectModel(radio.dataset.model);
});
segmented.addEventListener("keydown", (event) => {
  if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
  event.preventDefault();
  selectModel(model === "base" ? "tuned" : "base");
  segmented.querySelector(`[data-model="${model}"]`).focus();
});
screen.addEventListener("click", (event) => actions[event.target.closest("[data-action]")?.dataset.action]?.(event));
screen.addEventListener("click", (event) => {
  const line = event.target.closest(".map-bars > span");
  const card = line && current.cards.get([...line.parentElement.children].indexOf(line));
  if (!card) return;
  card.closest(".group").setAttribute("data-expanded", "");
  card.scrollIntoView({ block: "center", behavior: reducedMotion.matches ? "instant" : "smooth" });
  card.querySelector(".card-hit").click();
});

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === "activated" && message.tabId === tabId) return refresh();
  if (message.type !== "job" || message.job.tabId !== tabId || message.job.model !== model) return;
  const finished = view?.job?.status === "running" && message.job.status !== "running";
  view = { ...view, job: message.job };
  render();
  if (finished) refresh();
});
chrome.tabs.onUpdated.addListener((id, change) => id === tabId && change.status === "complete" && refresh());
addEventListener("focus", refresh);
setInterval(() => !document.hidden && view?.job?.status !== "running" && refresh(), POLL);

selectModel(model);
