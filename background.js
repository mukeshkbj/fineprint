import { ALL_SITES, CATEGORIES, MODELS, SYSTEM_PROMPT, flagsBySeverity, gradeOf, hostOf, htmlToText, normalize, parseReply, splitClauses } from "./lib.js";
import { markClause, readPage } from "./page.js";

const PARALLEL = 4;
const REQUEST_TIMEOUT = 20_000;
const KEPT_REPORTS = 30;
const PRIVATE_HOST = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|\[|[^.]+$)|\.(local|internal)$/;
const jobs = new Map();
const links = new Map();
const outlines = new Map();
const linked = new Map();
let consentSync = Promise.resolve();

chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: false });

chrome.action.onClicked.addListener((tab) => {
  openPanel(tab.id);
  chrome.runtime.sendMessage({ type: "activated", tabId: tab.id }).catch(() => {});
});

chrome.tabs.onRemoved.addListener((tabId) => {
  halt(tabId);
  jobs.delete(tabId);
  linked.delete(tabId);
});

chrome.tabs.onUpdated.addListener((tabId, change) => change.url && linked.delete(tabId));

function openPanel(tabId) {
  chrome.sidePanel.setOptions({ tabId, path: "sidepanel.html", enabled: true });
  return chrome.sidePanel.open({ tabId });
}

function loaded(tabId) {
  return new Promise((resolve) => {
    const listener = (id, change) => {
      if (id !== tabId || change.status !== "complete") return;
      chrome.tabs.onUpdated.removeListener(listener);
      resolve();
    };
    chrome.tabs.onUpdated.addListener(listener);
  });
}

const endpoint = (model, path) => `http://127.0.0.1:${MODELS[model].port}${path}`;
const reportKey = (model, url) => `report|${model}|${url.split("#")[0]}`;
const selectedModel = async () => (await chrome.storage.local.get("model")).model ?? "base";

function broadcast(job) {
  chrome.runtime.sendMessage({ type: "job", job: snapshot(job) }).catch(() => {});
  if (job.badgeTab) chrome.tabs.sendMessage(job.badgeTab, { type: "progress", url: job.url, done: job.done, total: job.total }).catch(() => {});
}

function snapshot({ controller, clauses, sides, badgeTab, ...job }) {
  return job;
}

async function inject(tabId, func, args = []) {
  const [{ result }] = await chrome.scripting.executeScript({ target: { tabId }, func, args });
  return result;
}

async function pageOf(tab, withText = false) {
  if (!/^(https?|file):/.test(tab.url ?? "")) return { readable: false };
  try {
    const { selection, text } = await inject(tab.id, readPage, [withText]);
    return { readable: true, url: tab.url, title: tab.title, selection, text };
  } catch {
    return { readable: false };
  }
}

const SEVERITY_MARK = { high: "h", medium: "m", low: "l" };
const markOf = ({ reply, skipped }) => (skipped ? "s" : !reply ? "x" : reply.flags.length ? SEVERITY_MARK[CATEGORIES[reply.flags[0]].severity] : "-");

async function health(model) {
  try {
    const response = await fetch(endpoint(model, "/health"), { signal: AbortSignal.timeout(1500) });
    if (response.ok) return "ready";
    return response.status === 503 ? "loading" : "offline";
  } catch {
    return "offline";
  }
}

async function classify(model, clause, signal) {
  const response = await fetch(endpoint(model, "/v1/chat/completions"), {
    method: "POST",
    headers: { "content-type": "application/json" },
    signal: AbortSignal.any([signal, AbortSignal.timeout(REQUEST_TIMEOUT)]),
    body: JSON.stringify({
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: normalize(clause) },
      ],
      temperature: 0,
      max_tokens: 96,
    }),
  });
  if (!response.ok) return { skipped: true };
  const body = await response.json();
  return { reply: parseReply(body.choices?.[0]?.message?.content ?? ""), tokens: body.usage?.completion_tokens ?? 0 };
}

async function run(job) {
  const startedAt = Date.now();
  let next = 0;
  let lastBroadcast = 0;
  const worker = async () => {
    while (next < job.clauses.length && job.status === "running") {
      const index = next++;
      job.marks[index] = "r";
      const result = await classify(job.model, job.clauses[index], job.controller.signal);
      const { reply, tokens, skipped } = result;
      if (job.status !== "running") return;
      job.marks[index] = markOf(result);
      job.done++;
      job.elapsed = Date.now() - startedAt;
      if (skipped) job.skipped++;
      else {
        job.tokens += tokens;
        if (!reply) job.invalid++;
        else if (reply.flags.length) job.findings.push({ index, text: job.clauses[index], side: job.sides?.[index], ...reply });
      }
      if (reply?.flags.length || Date.now() - lastBroadcast > 80) {
        lastBroadcast = Date.now();
        broadcast(job);
      }
    }
  };
  try {
    await Promise.all(Array.from({ length: PARALLEL }, worker));
  } catch (error) {
    if (job.status === "running") {
      job.controller.abort();
      job.status = error instanceof TypeError || error.name === "TimeoutError" ? "offline" : "failed";
      job.error = error.message;
    }
  }
  job.elapsed = Date.now() - startedAt;
  job.marks = job.marks.map((mark) => (mark === "r" ? "." : mark));
  if (job.status === "running") {
    job.status = "done";
    job.checkedAt = Date.now();
    job.findings.sort((a, b) => a.index - b.index);
  }
  broadcast(job);
  if (job.status !== "done") return;
  await chrome.storage.local.set({ [`pace|${job.model}`]: job.done / Math.max(job.elapsed / 1000, 0.001) });
  if (job.source === "page" || job.source === "link") await saveReport(job);
}

async function saveReport(job) {
  await chrome.storage.local.set({ [reportKey(job.model, job.url)]: snapshot(job) });
  const reports = Object.entries(await chrome.storage.local.get(null)).filter(([key]) => key.startsWith("report|"));
  const stale = reports.sort(([, a], [, b]) => b.checkedAt - a.checkedAt).slice(KEPT_REPORTS).map(([key]) => key);
  if (stale.length) await chrome.storage.local.remove(stale);
}

function createJob({ tabId = null, model, url, title = "", source, clauses, sides }) {
  return {
    id: crypto.randomUUID(),
    tabId,
    model,
    url,
    title,
    source,
    status: clauses.length ? "running" : "empty",
    total: clauses.length,
    done: 0,
    invalid: 0,
    skipped: 0,
    tokens: 0,
    elapsed: 0,
    findings: [],
    lengths: clauses.map((clause) => clause.length),
    marks: clauses.map(() => "."),
    clauses,
    sides,
    controller: new AbortController(),
  };
}

function start(job) {
  jobs.set(job.tabId, job);
  broadcast(job);
  if (job.status === "running") run(job);
  return snapshot(job);
}

function halt(tabId) {
  const job = jobs.get(tabId);
  if (job?.status !== "running") return null;
  job.status = "stopped";
  job.controller.abort();
  broadcast(job);
  return snapshot(job);
}

async function cachedReport(model, url) {
  const key = reportKey(model, url);
  return (await chrome.storage.local.get(key))[key] ?? null;
}

async function lastReport(model, tabId, url) {
  const job = jobs.get(tabId);
  if (job?.model === model && (job.source === "diff" || job.url === url)) return snapshot(job);
  return cachedReport(model, url);
}

function mayFetch(url, pageUrl) {
  const target = new URL(url);
  if (!/^https?:$/.test(target.protocol)) return false;
  return target.origin === new URL(pageUrl).origin || !PRIVATE_HOST.test(target.hostname);
}

async function gradeLink(url, model, badgeTab) {
  const cached = await cachedReport(model, url);
  if (cached?.status === "done") return cached;
  if (await health(model) !== "ready") return { status: "offline" };
  const response = await fetch(url, { credentials: "omit" });
  if (!response.ok) return { status: "failed" };
  const html = await response.text();
  const title = htmlToText(html.match(/<title[^>]*>([^<]*)/i)?.[1] ?? "").trim();
  const job = createJob({ model, url, title, source: "link", clauses: splitClauses(htmlToText(html)) });
  job.badgeTab = badgeTab;
  if (job.status === "running") await run(job);
  return snapshot(job);
}

function badgeSummary(report) {
  if (report.status !== "done") return { status: report.status };
  const { high, medium } = flagsBySeverity(report.findings);
  const label = (flag) => CATEGORIES[flag].label;
  return { status: "done", grade: gradeOf(report.findings), high: high.map(label), medium: medium.map(label) };
}

async function applyConsentScript() {
  const registered = await chrome.scripting.getRegisteredContentScripts({ ids: ["consent"] });
  const granted = await chrome.permissions.contains(ALL_SITES);
  if (granted && !registered.length) {
    await chrome.scripting.registerContentScripts([{ id: "consent", matches: ["<all_urls>"], js: ["consent.js"], runAt: "document_idle" }]);
  }
  if (!granted && registered.length) await chrome.scripting.unregisterContentScripts({ ids: ["consent"] });
}

const syncConsentScript = () => (consentSync = consentSync.then(applyConsentScript).catch((error) => console.error(error)));
chrome.permissions.onAdded.addListener(syncConsentScript);
chrome.permissions.onRemoved.addListener(syncConsentScript);
chrome.runtime.onInstalled.addListener(syncConsentScript);

const handlers = {
  async view({ tabId, model }) {
    const tab = await chrome.tabs.get(tabId);
    const [page, server, granted, stored] = await Promise.all([
      pageOf(tab, !outlines.has(tab.url)),
      health(model),
      chrome.permissions.contains(ALL_SITES),
      chrome.storage.local.get(`pace|${model}`),
    ]);
    if (!page.readable) return { readable: false, granted, server };
    if (linked.has(tabId)) {
      const job = await cachedReport(model, linked.get(tabId));
      if (job) return { readable: true, granted, server, host: hostOf(page.url), selected: 0, outline: [], estimate: 0, job, other: null };
    }
    if (!outlines.has(page.url)) outlines.set(page.url, splitClauses(page.text).map((clause) => clause.length));
    const outline = page.selection ? splitClauses(page.selection).map((clause) => clause.length) : outlines.get(page.url);
    const otherModel = model === "base" ? "tuned" : "base";
    const [job, other] = await Promise.all([lastReport(model, tabId, page.url), lastReport(otherModel, tabId, page.url)]);
    return {
      readable: true,
      granted,
      server,
      title: page.title,
      host: hostOf(page.url),
      selected: page.selection.length,
      outline,
      estimate: outline.length / (stored[`pace|${model}`] ?? 5),
      job,
      other: other?.status === "done" ? other : null,
    };
  },

  async check({ tabId, model }) {
    const page = await pageOf(await chrome.tabs.get(tabId), true);
    if (!page.readable) return { error: "unreadable" };
    halt(tabId);
    const source = page.selection ? "selection" : "page";
    return start(createJob({ tabId, model, url: page.url, title: page.title, source, clauses: splitClauses(page.selection || page.text) }));
  },

  async diff({ tabId, model, before, after }) {
    const previous = new Map(splitClauses(before).map((clause) => [normalize(clause), clause]));
    const entries = new Map([...previous].map(([key, clause]) => [key, { clause, side: "old" }]));
    for (const clause of splitClauses(after)) {
      const key = normalize(clause);
      entries.set(key, { clause, side: previous.has(key) ? "both" : "new" });
    }
    halt(tabId);
    const clauses = [...entries.values()];
    return start(createJob({ tabId, model, url: null, source: "diff", clauses: clauses.map((entry) => entry.clause), sides: clauses.map((entry) => entry.side) }));
  },

  async dismiss({ tabId }) {
    linked.delete(tabId);
    if (jobs.get(tabId)?.status !== "running") jobs.delete(tabId);
    return null;
  },

  async openLinked({ url }, sender) {
    linked.set(sender.tab.id, url);
    try {
      await openPanel(sender.tab.id);
      chrome.runtime.sendMessage({ type: "activated", tabId: sender.tab.id }).catch(() => {});
    } catch {
      linked.delete(sender.tab.id);
      await chrome.tabs.create({ url, index: sender.tab.index + 1 });
    }
    return null;
  },

  async pageText({ tabId }) {
    const page = await pageOf(await chrome.tabs.get(tabId), true);
    return page.readable ? page.selection || page.text : "";
  },

  async stop({ tabId }) {
    return halt(tabId);
  },

  async mark({ tabId, text, url, open }) {
    let tab = await chrome.tabs.get(tabId);
    if (open) {
      tab = await chrome.tabs.create({ url, index: tab.index + 1 });
      await loaded(tab.id);
      tabId = tab.id;
      tab = await chrome.tabs.get(tabId);
    }
    if (tab.url?.split("#")[0] !== url.split("#")[0]) return { found: false };
    try {
      return { found: await inject(tabId, markClause, [text]) };
    } catch {
      return { found: false };
    }
  },

  async grade({ url }, sender) {
    if (!sender.tab || !mayFetch(url, sender.tab.url)) return { status: "failed" };
    const model = await selectedModel();
    const key = `${model}|${url}`;
    if (!links.has(key)) links.set(key, gradeLink(url, model, sender.tab.id).catch(() => ({ status: "failed" })).finally(() => setTimeout(() => links.delete(key), 5000)));
    return badgeSummary(await links.get(key));
  },
};

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  const handler = handlers[message.type];
  if (!handler) return false;
  handler(message, sender).then(respond, (error) => respond({ error: error.message }));
  return true;
});
