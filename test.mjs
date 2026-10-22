import assert from "node:assert/strict";
import { gradeOf, htmlToText, normalize, parseReply, splitClauses } from "./lib.js";

assert.equal(normalize("  You AGREE\n\tto   Arbitration. "), "you agree to arbitration.");

const text = [
  "1. Disputes",
  "Any dispute will be resolved by binding arbitration in Delaware. We may change these Terms at any time without notice.",
  "",
  "Short one. Too short.",
  "",
  "You agree that we may suspend or terminate   your account at our sole discretion.",
].join("\n");
assert.deepEqual(splitClauses(text), [
  "Any dispute will be resolved by binding arbitration in Delaware.",
  "We may change these Terms at any time without notice.",
  "You agree that we may suspend or terminate your account at our sole discretion.",
]);
assert.equal(splitClauses("This sentence is long enough to count as one clause. ".repeat(500)).length, 400);

assert.deepEqual(parseReply('{"flags": ["arbitration"], "plain": "You cannot sue."}'), { flags: ["arbitration"], plain: "You cannot sue." });
assert.deepEqual(parseReply('Sure! ```json\n{"flags": ["contract_by_using", "arbitration", "arbitration", "made_up"], "plain": ""}\n```'), {
  flags: ["arbitration", "contract_by_using"],
  plain: "You give up your right to sue in court.",
});
assert.deepEqual(parseReply('{"flags": [], "plain": "Nothing here."}'), { flags: [], plain: "" });
for (const broken of ["", "no json here", '{"flags": ["arbitration"', '{"plain": "x"}', '{"flags": "arbitration"}', "} {"]) {
  assert.equal(parseReply(broken), null, broken);
}

const finding = (...flags) => ({ flags });
assert.equal(gradeOf([]), "A");
assert.equal(gradeOf([finding("contract_by_using")]), "A");
assert.equal(gradeOf([finding("jurisdiction")]), "B");
assert.equal(gradeOf([finding("arbitration"), finding("arbitration")]), "C");
assert.equal(gradeOf([finding("arbitration", "unilateral_change"), finding("jurisdiction")]), "D");
assert.equal(gradeOf([finding("arbitration", "unilateral_change"), finding("limitation_of_liability", "unilateral_termination")]), "E");

const page = `<html><head><title>x</title><style>p{}</style></head><body><nav>Home</nav><main>
<h1>Terms</h1><p>We may change these Terms&nbsp;at any time &amp; without notice.</p><script>var a = "<p>no</p>";</script>
<p>You waive your right to a jury trial &#8212; disputes go to arbitration.<br>See &#x41;bove.</p></main><footer>Footer</footer></body></html>`;
assert.deepEqual(splitClauses(htmlToText(page)), [
  "We may change these Terms at any time & without notice.",
  "You waive your right to a jury trial — disputes go to arbitration.",
]);

console.log("ok");
