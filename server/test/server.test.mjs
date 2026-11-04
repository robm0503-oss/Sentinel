// Lokaler Test des Servers mit SQLite statt D1 und nachgebautem Anthropic/RevenueCat
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import assert from "node:assert";
import worker from "../src/index.js";

const sql = new DatabaseSync(":memory:");
sql.exec(fs.readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
const DB = { prepare: q => { const st = sql.prepare(q); let args = []; const o = { bind: (...a) => { args = a; return o; }, first: async () => st.get(...args) ?? null, run: async () => { const r = st.run(...args); return { meta: { changes: r.changes } }; }, all: async () => ({ results: st.all(...args) }) }; return o; } };

let rc = {}; const gesendet = [];
globalThis.fetch = async (url, opt) => {
  if (String(url).includes("revenuecat")) return new Response(JSON.stringify({ subscriber: rc }), { status: 200 });
  const b = JSON.parse(opt.body); gesendet.push(b);
  return new Response(JSON.stringify({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn", usage: { input_tokens: 200000, output_tokens: 20000 } }), { status: 200 });
};
const env = { DB, ANTHROPIC_KEY: "k", RC_SECRET: "rc", PROBE_DAY_CAP: "3000" };
const uid = "11111111-2222-4333-8444-555555555555";
const call = (path, body) => worker.fetch(new Request("https://x" + path, { method: body ? "POST" : "GET", headers: { authorization: "Bearer " + uid }, body: body && JSON.stringify(body) }), env).then(async r => ({ status: r.status, j: await r.json() }));
const msg = { model: "claude-sonnet-5-5", max_tokens: 9000, messages: [{ role: "user", content: "hi" }], tools: [{ type: "web_search_20250305", name: "web_search" }, { name: "x", input_schema: {} }] };

// Probe: Haiku, keine Websuche, 30 Anfragen
let r = await call("/v1/messages", msg);
assert.equal(r.status, 200); assert.equal(gesendet.at(-1).model, "claude-haiku-5-5"); assert.equal(gesendet.at(-1).tools.length, 1); assert.equal(gesendet.at(-1).max_tokens, 1000);
console.log("Probe:", r.j.sentinel);

// Plus-Abo: Sonnet erlaubt, Bremse ab 80 %
rc = { entitlements: { plus: { expires_date: "2099-01-01T00:00:00Z" } } };
sql.exec("UPDATE users SET tier_checked = 0");
r = await call("/v1/messages", msg);
assert.equal(gesendet.at(-1).model, "claude-sonnet-5-5"); console.log("Plus:", r.j.sentinel);
for (let i = 0; i < 12 && r.status === 200; i++) r = await call("/v1/messages", msg);
console.log("Plus nach vielen Anfragen:", r.status, r.j.error?.type || r.j.sentinel, "letztes Modell:", gesendet.at(-1).model);

// Business, Budget leer -> Nachschub nötig; Nachschub gekauft -> weiter
rc = { entitlements: { business: { expires_date: null } } };
sql.exec("UPDATE users SET tier_checked = 0, used_usd = 30");
r = await call("/v1/messages", msg); console.log("Business leer:", r.status, r.j.error?.type);
rc = { entitlements: { business: { expires_date: null } }, non_subscriptions: { nachschub_10: [{ id: "tx1", purchase_date: new Date().toISOString() }] } };
sql.exec("UPDATE users SET tier_checked = 0");
r = await call("/v1/messages", { ...msg, model: "claude-opus-5-5" }); console.log("Business mit Nachschub:", r.status, r.j.sentinel, gesendet.at(-1).model);
sql.exec("UPDATE users SET tier_checked = 0");
r = await call("/v1/me"); console.log("Gleicher Nachschub nicht doppelt:", r.j.budget_eur);
r = await worker.fetch(new Request("https://x/v1/me", { headers: { authorization: "Bearer quatsch" } }), env); assert.equal(r.status, 401);
console.log("ALLE TESTS OK");
