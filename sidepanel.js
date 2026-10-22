import { CATEGORIES, MODELS, SEVERITIES, gradeOf, severityOf } from "./lib.js";

const COMMANDS = {
  base: "llama-server -hf LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q8_0 --port 8080 -np 4 -c 16384",
  tuned: "llama-server -m fineprint-Q8_0.gguf --port 8081 -np 4 -c 16384",
};
const GROUPS = { high: "Serious", medium: "Worth knowing", low: "Minor" };
const SERVER_TEXT = { ready: "Ready", loading: "Loading", offline: "Not running" };
const SPRING = "linear(0, 0.03 2.6%, 0.152 7%, 0.305 11.5%, 0.453 15.9%, 0.58 20.4%, 0.684 24.8%, 0.765 29.3%, 0.828 33.7%, 0.874 38.1%, 0.909 42.6%, 0.934 47%, 0.953 51.5%, 0.966 55.9%, 0.976 60.4%, 0.983 64.8%, 0.988 69.3%, 0.991 73.7%, 0.994 78.1%, 0.996 82.6%, 0.997 87%, 0.998 91.5%, 0.999 95.9%, 1)";
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
const count = new Intl.NumberFormat("en");
const list = new Intl.ListFormat("en", { type: "conjunction" });

const screen = document.getElementById("screen");
const server = document.getElementById("server");
const segmented = document.querySelector(".segmented");

let model = (await chrome.storage.local.get("model")).model ?? "base";
let tabId = null;
let view = null;
let current = { key: null, root: null };
let editing = false;
const drafts = { before: "", after: "" };

const send = (message) => chrome.runtime.sendMessage(message);
const clone = (id) => document.getElementById(id).content.firstElementChild.cloneNode(true);
const seconds = (ms) => `${ms < 10_000 ? (ms / 1000).toFixed(1) : Math.round(ms / 1000)} s`;
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

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
  if (!view.page.readable) return renderUnreadable();
  const job = view.job;
  if (!job || job.status === "empty") return renderIntro(job);
  renderReport(job);
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
  setting.dataset.on = view.granted;
}

function renderIntro(job) {
  const root = show(`intro|${model}`, "intro");
  renderSites(root);
  const action = root.querySelector(".intro-action");
  if (view.server === "offline") {
    if (!action.querySelector(".notice")) action.replaceChildren(offlineNotice());
    return;
  }
  const check = action.querySelector(".check") ?? action.appendChild(clone("check"));
  action.querySelector(".notice")?.remove();
  const button = check.querySelector("button");
  const { selection, host } = view.page;
  button.textContent = selection ? "Check selection" : "Check this page";
  button.disabled = view.server !== "ready";
  check.querySelector(".hint").textContent =
    view.server === "loading" ? "The model is still loading. This takes a few seconds."
    : job?.status === "empty" ? `No terms found on ${host}. Select the terms and check again.`
    : selection ? `${count.format(selection)} characters selected on ${host}.`
    : `Reads ${host}. Select text to check only that part.`;
}

function renderDiffForm(hint = "") {
  const root = show(`diff|${model}`, "diff");
  const fields = root.querySelectorAll("textarea");
  if (!root.dataset.ready) {
    root.dataset.ready = "";
    for (const field of fields) {
      field.value = drafts[field.name];
      field.addEventListener("input", () => (drafts[field.name] = field.value));
    }
  }
  root.querySelector('[data-action="use-page"]').hidden = !view.page.readable;
  root.querySelector('[data-action="diff"]').disabled = view.server !== "ready";
  root.querySelector(".hint").textContent = hint || (view.server === "ready" ? "Both versions are checked on this computer." : "Start the local model to compare.");
}

function offlineNotice(title, text) {
  const notice = clone("offline");
  if (title) notice.querySelector(".notice-title").textContent = title;
  if (text) notice.querySelector(".notice-text").textContent = text;
  notice.querySelector("code").textContent = COMMANDS[model];
  return notice;
}

function renderReport(job) {
  const root = show(`report|${job.id ?? job.url}|${model}`, "report");
  const running = job.status === "running";
  root.dataset.status = job.status;

  const diff = job.source === "diff";
  const side = (finding) => job.sides?.[finding.index] ?? "new";
  const findings = diff ? job.findings.filter((finding) => side(finding) !== "old") : job.findings;
  const grade = gradeOf(findings);
  const strip = root.querySelector(".strip");
  strip.dataset.grade = job.done ? grade : "";
  strip.style.setProperty("--index", "ABCDE".indexOf(grade));
  strip.setAttribute("aria-label", job.done ? `Grade ${grade}` : "No grade yet");
  for (const cell of strip.querySelectorAll(".cell")) cell.toggleAttribute("data-active", job.done > 0 && cell.dataset.grade === grade);

  const previous = job.findings.filter((finding) => side(finding) !== "new");
  const known = new Set(diff ? previous.flatMap((finding) => finding.flags) : []);
  const flags = [...new Set(findings.flatMap((finding) => finding.flags))].filter((flag) => !known.has(flag));
  const distinct = (severity) => flags.filter((flag) => CATEGORIES[flag].severity === severity);
  const high = distinct("high");
  const medium = distinct("medium");
  const fresh = diff ? "new " : "";
  const headline = high.length ? plural(high.length, `${fresh}serious term`)
    : medium.length ? `${plural(medium.length, `${fresh}term`)} worth knowing`
    : diff ? "nothing new to worry about" : "nothing unfair found";
  const verdict = !job.done ? "Reading the terms" : running ? `So far, ${headline}` : headline[0].toUpperCase() + headline.slice(1);
  setText(root.querySelector(".verdict"), verdict);
  const labels = list.format([...high, ...medium].map((flag) => CATEGORIES[flag].label));
  setText(root.querySelector(".verdict-detail"),
    diff && job.done ? `Grade ${gradeOf(previous)} before, ${grade} now.${labels ? ` New: ${labels}.` : ""}`
    : labels ? `${labels}.`
    : running ? `Checking ${diff ? "both versions" : job.source === "selection" ? "your selection" : job.host} on this computer.`
    : `No serious or notable terms in ${plural(job.done, "clause")}.`);

  root.querySelector(".meter").hidden = !running;
  if (running) {
    setText(root.querySelector(".meter-label"), `Clause ${count.format(job.done)} of ${count.format(job.total)}`);
    setText(root.querySelector(".meter-time"), `${job.invalid ? `${count.format(job.invalid)} invalid · ` : ""}${seconds(job.elapsed)}`);
    root.querySelector(".meter-fill").style.transform = `scaleX(${job.done / job.total})`;
  }

  const outcome = root.querySelector(".outcome");
  const outcomeKey = running || job.status === "done" ? "" : job.status;
  if (outcome.dataset.key !== outcomeKey) {
    outcome.dataset.key = outcomeKey;
    const partial = `after ${count.format(job.done)} of ${count.format(job.total)} clauses`;
    outcome.replaceChildren(
      job.status === "offline" ? offlineNotice("The model stopped answering", `It stopped ${partial}. Start it again with this command, then check again.`)
      : job.status === "stopped" ? paragraph(`Stopped ${partial}. The grade covers only those.`)
      : job.status === "failed" ? paragraph(`${job.error} The check stopped ${partial}.`)
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
  compare.hidden = !(job.status === "done" && view.other) || diff;
  if (!compare.hidden) {
    for (const report of [job, view.other]) {
      const cell = (name, value) => (compare.querySelector(`[data-${report.model}="${name}"]`).textContent = value);
      cell("grade", gradeOf(report.findings));
      cell("flagged", count.format(report.findings.length));
      cell("invalid", count.format(report.invalid));
      cell("time", seconds(report.elapsed));
    }
    compare.dataset.current = model;
  }

  renderFindings(root, diff ? job.findings.filter((finding) => side(finding) === "new") : job.findings, job);
  const actions = root.querySelector(".report-actions");
  actions.hidden = running;
  for (const button of actions.querySelectorAll("[data-for]")) button.hidden = (button.dataset.for === "diff") !== diff;
  actions.querySelector('[data-action="check"]').disabled = view.server !== "ready";
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
  card.querySelector(".card-plain").textContent = finding.plain;
  card.querySelector(".card-quote").textContent = `“${finding.text}”`;
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
  const { found } = await send({ type: "mark", tabId: job.tabId, url: job.url, text: finding.text });
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
  const light = (card) => {
    if (card === lit) return;
    const entering = !lit;
    lit?.removeAttribute("data-lit");
    lit = card;
    if (!card) return glow.classList.remove("on");
    card.setAttribute("data-lit", "");
    glow.classList.toggle("instant", entering);
    glow.style.transform = `translateY(${card.offsetTop}px)`;
    glow.style.height = `${card.offsetHeight}px`;
    if (entering) glow.getBoundingClientRect();
    glow.classList.remove("instant");
    glow.classList.add("on");
  };
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

async function refresh() {
  view = await send({ type: "view", tabId, model });
  render();
}

let refreshing = 0;
const refreshSoon = () => {
  if (Date.now() - refreshing < 500) return;
  refreshing = Date.now();
  refresh();
};

function selectModel(next) {
  model = next;
  chrome.storage.local.set({ model });
  segmented.dataset.model = model;
  for (const radio of segmented.querySelectorAll("[role=radio]")) {
    const checked = radio.dataset.model === model;
    radio.setAttribute("aria-checked", checked);
    radio.tabIndex = checked ? 0 : -1;
  }
  view = view && { ...view, server: null };
  refresh();
}

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

screen.addEventListener("click", async (event) => {
  const action = event.target.closest("[data-action]")?.dataset.action;
  if (action === "check") {
    const job = await send({ type: "check", tabId, model });
    if (job.error) return refresh();
    view = { ...view, job };
    render();
  }
  if (action === "stop") send({ type: "stop", tabId });
  if (action === "diff-form") {
    editing = true;
    render();
  }
  if (action === "home") {
    editing = false;
    await send({ type: "dismiss", tabId });
    refresh();
  }
  if (action === "use-page") {
    const field = screen.querySelector('textarea[name="after"]');
    field.value = drafts.after = await send({ type: "pageText", tabId });
  }
  if (action === "diff") {
    if (!drafts.before.trim() || !drafts.after.trim()) return renderDiffForm("Paste both versions first.");
    const job = await send({ type: "diff", tabId, model, ...drafts });
    if (job.status === "empty") return renderDiffForm("Fineprint found no clauses to compare. Paste longer passages.");
    editing = false;
    view = { ...view, job };
    render();
  }
  if (action === "refresh") refresh();
  if (action === "sites") {
    const origins = { origins: ["<all_urls>"] };
    await (view.granted ? chrome.permissions.remove(origins) : chrome.permissions.request(origins));
    refresh();
  }
  if (action === "copy") {
    const notice = event.target.closest(".notice");
    await navigator.clipboard.writeText(notice.querySelector("code").textContent);
    notice.classList.add("copied");
    notice.querySelector("[data-copied]").textContent = "Copied";
    setTimeout(() => {
      notice.classList.remove("copied");
      notice.querySelector("[data-copied]").textContent = "";
    }, 1600);
  }
});

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === "activated" && message.tabId === tabId) return refresh();
  if (message.type !== "job" || message.job.tabId !== tabId) return;
  if (message.job.model !== model) return;
  const finished = view?.job?.status === "running" && message.job.status !== "running";
  view = { ...view, job: message.job };
  render();
  if (finished) refresh();
});

[{ id: tabId }] = await chrome.tabs.query({ active: true, currentWindow: true });
chrome.tabs.onUpdated.addListener((id, change) => id === tabId && change.status === "complete" && refresh());
document.addEventListener("pointerenter", refreshSoon);
addEventListener("focus", refreshSoon);
setInterval(async () => {
  if (document.hidden || !view || view.job?.status === "running") return;
  const state = await send({ type: "health", model });
  if (state !== view.server) {
    view = { ...view, server: state };
    render();
  }
}, 2500);

selectModel(model);
