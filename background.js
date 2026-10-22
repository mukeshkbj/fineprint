import { CATEGORIES, MODELS, SYSTEM_PROMPT, gradeOf, htmlToText, normalize, parseReply, splitClauses } from "./lib.js";
import { markClause, readPage } from "./page.js";

const PARALLEL = 4;
const ALL_SITES = { origins: ["<all_urls>"] };
const jobs = new Map();
const links = new Map();

chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });

const endpoint = (model, path) => `http://127.0.0.1:${MODELS[model].port}${path}`;
const reportKey = (model, url) => `report|${model}|${url.split("#")[0]}`;
const selectedModel = async () => (await chrome.storage.local.get("model")).model ?? "base";
function broadcast(job) {
  chrome.runtime.sendMessage({ type: "job", job: snapshot(job) }).catch(() => {});
  if (job.badgeTab) chrome.tabs.sendMessage(job.badgeTab, { type: "progress", url: job.url, done: job.done, total: job.total }).catch(() => {});
}

function snapshot({ controller, clauses, badgeTab, ...job }) {
  return job;
}

async function inject(tabId, func, args = []) {
  const [{ result }] = await chrome.scripting.executeScript({ target: { tabId }, func, args });
  return result;
}

async function pageOf(tabId) {
  const tab = await chrome.tabs.get(tabId);
  if (!tab.url?.startsWith("http") && !tab.url?.startsWith("file:")) return { readable: false };
  try {
    const { selection, text } = await inject(tabId, readPage);
    return { readable: true, url: tab.url, title: tab.title, selection, text };
  } catch {
    return { readable: false };
  }
}

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
    signal,
    body: JSON.stringify({
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: normalize(clause) },
      ],
      temperature: 0,
      max_tokens: 96,
    }),
  });
  if (!response.ok) throw new Error(`The model server answered ${response.status}.`);
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
      const { reply, tokens } = await classify(job.model, job.clauses[index], job.controller.signal);
      if (job.status !== "running") return;
      job.done++;
      job.tokens += tokens;
      job.elapsed = Date.now() - startedAt;
      if (!reply) job.invalid++;
      else if (reply.flags.length) job.findings.push({ index, text: job.clauses[index], ...reply });
      if (reply?.flags.length || Date.now() - lastBroadcast > 80) {
        lastBroadcast = Date.now();
        broadcast(job);
      }
    }
  };
  try {
    await Promise.all(Array.from({ length: PARALLEL }, worker));
    if (job.status !== "running") return;
    job.status = "done";
    job.elapsed = Date.now() - startedAt;
    job.findings.sort((a, b) => a.index - b.index);
    if (job.source !== "selection") await chrome.storage.local.set({ [reportKey(job.model, job.url)]: snapshot(job) });
  } catch (error) {
    if (job.status !== "running") return;
    job.controller.abort();
    job.elapsed = Date.now() - startedAt;
    job.status = error instanceof TypeError ? "offline" : "failed";
    job.error = error.message;
  }
  broadcast(job);
}

function createJob({ tabId = null, model, url, source, clauses }) {
  return {
    id: crypto.randomUUID(),
    tabId,
    model,
    url,
    host: new URL(url).host || "this file",
    source,
    status: clauses.length ? "running" : "empty",
    total: clauses.length,
    done: 0,
    invalid: 0,
    tokens: 0,
    elapsed: 0,
    findings: [],
    clauses,
    controller: new AbortController(),
  };
}

async function gradeLink(url, model, badgeTab) {
  const cached = await lastReport(model, null, url);
  if (cached?.status === "done") return cached;
  if (await health(model) !== "ready") return { status: "offline" };
  const response = await fetch(url, { credentials: "omit" });
  if (!response.ok) return { status: "failed" };
  const job = createJob({ model, url, source: "link", clauses: splitClauses(htmlToText(await response.text())) });
  job.badgeTab = badgeTab;
  if (job.status === "running") await run(job);
  return snapshot(job);
}

function badgeSummary(report) {
  if (report.status !== "done") return { status: report.status };
  const flags = [...new Set(report.findings.flatMap((finding) => finding.flags))];
  const labels = (severity) => flags.filter((flag) => CATEGORIES[flag].severity === severity).map((flag) => CATEGORIES[flag].label);
  return { status: "done", grade: gradeOf(report.findings), high: labels("high"), medium: labels("medium"), model: report.model };
}

async function syncConsentScript() {
  const registered = await chrome.scripting.getRegisteredContentScripts({ ids: ["consent"] });
  const granted = await chrome.permissions.contains(ALL_SITES);
  if (granted && !registered.length) {
    await chrome.scripting.registerContentScripts([{ id: "consent", matches: ["<all_urls>"], js: ["consent.js"], runAt: "document_idle" }]);
  }
  if (!granted && registered.length) await chrome.scripting.unregisterContentScripts({ ids: ["consent"] });
}

chrome.permissions.onAdded.addListener(syncConsentScript);
chrome.permissions.onRemoved.addListener(syncConsentScript);
chrome.runtime.onInstalled.addListener(syncConsentScript);

async function lastReport(model, tabId, url) {
  const job = jobs.get(tabId);
  if (job?.model === model && job.url === url) return snapshot(job);
  const key = reportKey(model, url);
  return (await chrome.storage.local.get(key))[key] ?? null;
}

const handlers = {
  async view({ tabId, model }) {
    const [page, server, granted] = await Promise.all([pageOf(tabId), health(model), chrome.permissions.contains(ALL_SITES)]);
    if (!page.readable) return { page, granted, server, job: null, other: null };
    const otherModel = model === "base" ? "tuned" : "base";
    const [job, other] = await Promise.all([lastReport(model, tabId, page.url), lastReport(otherModel, tabId, page.url)]);
    return {
      granted,
      page: { readable: true, host: new URL(page.url).host || "this file", title: page.title, selection: page.selection.length, length: page.text.length },
      server,
      job,
      other: other?.status === "done" ? other : null,
    };
  },

  async check({ tabId, model }) {
    await handlers.stop({ tabId });
    const page = await pageOf(tabId);
    if (!page.readable) return { error: "unreadable" };
    const source = page.selection ? "selection" : "page";
    const job = createJob({ tabId, model, url: page.url, source, clauses: splitClauses(page.selection || page.text) });
    jobs.set(tabId, job);
    broadcast(job);
    if (job.status === "running") run(job);
    return snapshot(job);
  },

  async stop({ tabId }) {
    const job = jobs.get(tabId);
    if (job?.status !== "running") return null;
    job.status = "stopped";
    job.controller.abort();
    broadcast(job);
    return snapshot(job);
  },

  async mark({ tabId, text, url }) {
    const tab = await chrome.tabs.get(tabId);
    if (tab.url?.split("#")[0] !== url.split("#")[0]) return { found: false };
    try {
      return { found: await inject(tabId, markClause, [text]) };
    } catch {
      return { found: false };
    }
  },

  health: ({ model }) => health(model),

  async grade({ url }, sender) {
    const model = await selectedModel();
    const key = `${model}|${url}`;
    if (!links.has(key)) links.set(key, gradeLink(url, model, sender.tab?.id).catch(() => ({ status: "failed" })).finally(() => setTimeout(() => links.delete(key), 5000)));
    return badgeSummary(await links.get(key));
  },
};

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  const handler = handlers[message.type];
  if (!handler) return false;
  handler(message, sender).then(respond, (error) => respond({ error: error.message }));
  return true;
});
