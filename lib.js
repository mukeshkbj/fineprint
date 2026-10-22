export const SYSTEM_PROMPT = 'You read one clause from a Terms of Service. Reply with JSON only: {"flags": [...], "plain": "..."}. "flags" lists every category that applies, chosen from: limitation_of_liability, unilateral_termination, unilateral_change, content_removal, contract_by_using, choice_of_law, jurisdiction, arbitration. "plain" is one short sentence that tells the user what they agree to. If the clause is harmless, reply {"flags": [], "plain": ""}.';

export const MODELS = {
  base: { port: 8080, label: "Base" },
  tuned: { port: 8081, label: "Fine-tuned" },
};

export const SEVERITIES = ["high", "medium", "low"];

export const CATEGORIES = {
  arbitration: { label: "Arbitration", severity: "high", fallback: "You give up your right to sue in court." },
  limitation_of_liability: { label: "Liability limit", severity: "high", fallback: "They limit what they owe you if something goes wrong." },
  unilateral_termination: { label: "Can close your account", severity: "high", fallback: "They can end your account whenever they choose." },
  unilateral_change: { label: "Can change the terms", severity: "high", fallback: "They can change these terms without asking you." },
  content_removal: { label: "Can remove your content", severity: "medium", fallback: "They can delete what you post." },
  jurisdiction: { label: "Court location", severity: "medium", fallback: "Disputes go to a court they choose." },
  choice_of_law: { label: "Governing law", severity: "medium", fallback: "A law they choose applies to disputes." },
  contract_by_using: { label: "Agree by using", severity: "low", fallback: "Using the service means you accept these terms." },
};

const MAX_CLAUSES = 400;
const MIN_CLAUSE_LENGTH = 40;
const sentences = new Intl.Segmenter("en", { granularity: "sentence" });

export const collapse = (text) => text.replace(/\s+/g, " ").trim();
export const normalize = (clause) => collapse(clause.toLowerCase());

export function splitClauses(text) {
  const clauses = [];
  for (const block of text.split(/\n\s*\n/)) {
    for (const { segment } of sentences.segment(block)) {
      const clause = collapse(segment);
      if (clause.length < MIN_CLAUSE_LENGTH) continue;
      clauses.push(clause);
      if (clauses.length === MAX_CLAUSES) return clauses;
    }
  }
  return clauses;
}

export function parseReply(content) {
  const start = content.indexOf("{");
  const end = content.lastIndexOf("}");
  if (start === -1 || end < start) return null;
  let reply;
  try {
    reply = JSON.parse(content.slice(start, end + 1));
  } catch {
    return null;
  }
  if (!Array.isArray(reply?.flags)) return null;
  const flags = [...new Set(reply.flags)].filter((flag) => Object.hasOwn(CATEGORIES, flag));
  flags.sort((a, b) => rank(a) - rank(b));
  const plain = typeof reply.plain === "string" ? reply.plain.trim() : "";
  return { flags, plain: flags.length ? plain || CATEGORIES[flags[0]].fallback : "" };
}

const rank = (flag) => SEVERITIES.indexOf(CATEGORIES[flag].severity);

export const severityOf = (flags) => CATEGORIES[flags[0]].severity;

export function gradeOf(findings) {
  const distinct = new Set(findings.flatMap((finding) => finding.flags));
  const count = (severity) => [...distinct].filter((flag) => CATEGORIES[flag].severity === severity).length;
  const high = count("high");
  if (high === 0) return count("medium") === 0 ? "A" : "B";
  return "CDE"[Math.min(high, 3) - 1];
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", rsquo: "’", lsquo: "‘", rdquo: "”", ldquo: "“", mdash: "—", ndash: "–", hellip: "…" };

export function htmlToText(html) {
  const body = html.match(/<main[\s>][\s\S]*<\/main>/i)?.[0] ?? html.match(/<article[\s>][\s\S]*<\/article>/i)?.[0] ?? html;
  return body
    .replace(/<(script|style|noscript|svg|template|head)[\s>][\s\S]*?<\/\1>/gi, " ")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|h[1-6]|section|tr|blockquote|ul|ol|table|article|header|footer|dd|dt)>/gi, "\n\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (entity, code) =>
      code[0] !== "#" ? ENTITIES[code.toLowerCase()] ?? entity
      : String.fromCodePoint(code[1].toLowerCase() === "x" ? parseInt(code.slice(2), 16) : Number(code.slice(1))))
    .replace(/[ \t]+/g, " ");
}
