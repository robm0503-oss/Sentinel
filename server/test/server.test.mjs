// Lokaler Test des Servers mit SQLite statt D1 und nachgebautem Anthropic/RevenueCat
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import assert from "node:assert";
import worker from "../src/index.js";

const sql = new DatabaseSync(":memory:");
sql.exec(fs.readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
const DB = { prepare: q => { const st = sql.prepare(q); let args = []; const o = { bind: (...a) => { args = a; return o; }, first: async () => st.get(...args) ?? null, run: async () => { const r = st.run(...args); return { meta: { changes: r.changes } }; }, all: async () => ({ results: st.all(...args) }) }; return o; } };

let rc = {}; const gesendet = [], tgNachrichten = [];
globalThis.fetch = async (url, opt) => {
  if (String(url).includes("revenuecat")) return new Response(JSON.stringify({ subscriber: rc }), { status: 200 });
  if (String(url).includes("api.telegram.org")) { tgNachrichten.push(JSON.parse(opt.body)); return new Response(JSON.stringify({ ok: true }), { status: 200 }); }
  const b = JSON.parse(opt.body);
  if (typeof b.system === "string" && b.system.includes("Lernkarten")) {
    const k = b.messages[0].content, persoenlich = /Lisa|Mama/.test(k);
    const antw = persoenlich ? { ok: false } : { ok: true, text: "Bei Govee heißt der Farbbefehl colorRgb.", schluessel: "govee-farbe-befehl", thema: "licht" };
    return new Response(JSON.stringify({ content: [{ type: "text", text: JSON.stringify(antw) }], usage: { input_tokens: 10, output_tokens: 10 } }), { status: 200 });
  }
  gesendet.push(b);
  return new Response(JSON.stringify({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn", usage: { input_tokens: 200000, output_tokens: 20000 } }), { status: 200 });
};
const env = { DB, ANTHROPIC_KEY: "k", RC_SECRET: "rc", PROBE_DAY_CAP: "3000", TG_TOKEN: "t", TG_CHAT: "1", ADMIN_KEY: "geheim", SERVER_URL: "https://x" };
const uid = "11111111-2222-4333-8444-555555555555";
const call = (path, body) => worker.fetch(new Request("https://x" + path, { method: body ? "POST" : "GET", headers: { authorization: "Bearer " + uid }, body: body && JSON.stringify(body) }), env).then(async r => ({ status: r.status, j: await r.json() }));
const msg = { model: "claude-sonnet-5-5", max_tokens: 9000, messages: [{ role: "user", content: "hi" }], tools: [{ type: "web_search_20250305", name: "web_search" }, { name: "x", input_schema: {} }] };

// Gratis: Haiku, keine Websuche, 10 Anfragen pro Tag
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

// Bilder: Workers AI nachgebaut, Tageslimit Probe = 3
{
  const runs = [];
  const env2 = { ...env, AI: { run: async (m, o) => { runs.push({ m, ct: o.multipart.contentType, len: (await new Response(o.multipart.body).arrayBuffer()).byteLength }); return { image: "QUJD" }; } } };
  const uid2 = "22222222-2222-4333-8444-555555555555";
  const bildCall = body => worker.fetch(new Request("https://x/v1/bild", { method: "POST", headers: { authorization: "Bearer " + uid2 }, body: JSON.stringify(body) }), env2).then(async r => ({ status: r.status, j: await r.json() }));
  rc = {};
  let r1 = await bildCall({ prompt: "ein Hund", format: "quer" });
  assert.equal(r1.status, 200); assert.equal(r1.j.data, "QUJD"); assert.ok(runs[0].ct.startsWith("multipart/form-data"));
  r1 = await bildCall({ prompt: "blauer Himmel", bild: "QUJD" }); assert.equal(r1.status, 200); assert.ok(runs[1].len > runs[0].len - 40);
  r1 = await bildCall({ prompt: "x" }); assert.equal(r1.status, 200); assert.equal(r1.j.rest, 0);
  r1 = await bildCall({ prompt: "x" }); assert.equal(r1.status, 429);
  console.log("Bilder ok:", runs.map(x => x.m));
}

// Einrichtung zählt in der Probe nicht mit
{
  const uid3 = "33333333-2222-4333-8444-555555555555";
  const c3 = body => worker.fetch(new Request("https://x/v1/messages", { method: "POST", headers: { authorization: "Bearer " + uid3 }, body: JSON.stringify(body) }), env).then(async r => ({ status: r.status, j: await r.json() }));
  rc = {};
  let r3;
  for (let i = 0; i < 5; i++) r3 = await c3({ ...msg, sentinel_einrichtung: true });
  if (r3.status !== 200) console.log(r3.j); assert.equal(r3.status, 200); assert.equal(r3.j.sentinel.probe_rest, 10); assert.equal(gesendet.at(-1).sentinel_einrichtung, undefined);
  r3 = await c3(msg); assert.equal(r3.j.sentinel.probe_rest, 9);
  for (let i = 0; i < 9; i++) r3 = await c3(msg);
  assert.equal(r3.j.sentinel.probe_rest, 0); r3 = await c3(msg); assert.equal(r3.status, 429); assert.equal(r3.j.error.type, "tageslimit");
  r3 = await c3({ ...msg, sentinel_einrichtung: true }); assert.equal(r3.status, 200);
  console.log("Einrichtung gratis ok");
}

// Schwarmwissen
{
  const u = i => `${i}${i}${i}${i}${i}${i}${i}${i}-2222-4333-8444-555555555555`;
  const melde = (i, karten) => worker.fetch(new Request("https://x/v1/wissen", { method: "POST", headers: { authorization: "Bearer " + u(i) }, body: JSON.stringify({ karten }) }), env).then(r => r.json());
  const hole = () => worker.fetch(new Request("https://x/v1/wissen", { headers: { authorization: "Bearer " + u(1) } }), env).then(r => r.json());
  let r = await melde(4, [{ art: "korrektur", text: "Govee Farbe geht mit colorRgb, nicht color" }, { art: "tipp", text: "Lisa mag keine Pilze, das merke ich mir" }, { art: "tipp", text: "Ruf mich an unter 0170 1234567 bitte" }, { art: "fehler", thema: "licht", text: "Govee Fehler 429 zu viele Anfragen" }]);
  assert.deepEqual(r, { angenommen: 2, abgelehnt: 2 });
  assert.equal(tgNachrichten.length, 1); assert.ok(tgNachrichten[0].reply_markup.inline_keyboard[0][0].url.includes("/admin/wissen?id=1&a=frei&k="));
  assert.equal((await hole()).karten.length, 0);              // noch nicht frei
  await melde(5, [{ art: "korrektur", text: "colorRgb für Govee Farben" }]);
  await melde(5, [{ art: "korrektur", text: "colorRgb für Govee Farben nochmal" }]); // gleiches Gerät zählt nicht doppelt
  assert.equal((await hole()).karten.length, 0);
  await melde(6, [{ art: "korrektur", text: "Govee: colorRgb verwenden" }]);
  const h = await hole(); assert.equal(h.karten.length, 1); assert.equal(h.karten[0].text, "Bei Govee heißt der Farbbefehl colorRgb.");
  assert.ok(tgNachrichten.at(-1).text.includes("automatisch freigegeben"));
  // Admin: Link mit falscher Signatur, dann zurückziehen per Telegram-Link
  let a = await worker.fetch(new Request("https://x/admin/wissen?id=1&a=abgelehnt&k=falsch"), env); assert.equal(a.status, 403);
  const link = tgNachrichten[0].reply_markup.inline_keyboard[0][1].url.replace("https://x", "https://x");
  a = await worker.fetch(new Request(link), env); assert.equal(a.status, 200);
  assert.equal((await hole()).karten.length, 0);
  a = await worker.fetch(new Request("https://x/admin?k=geheim"), env); const seite = await a.text();
  assert.ok(seite.includes("Fehler-Hitliste") && seite.includes("licht"));
  assert.equal((await worker.fetch(new Request("https://x/admin?k=nein"), env)).status, 403);
  // Tagesbericht
  const vorher = tgNachrichten.length; await worker.scheduled({}, env, { waitUntil: p => p }); await new Promise(r => setTimeout(r, 10));
  assert.ok(tgNachrichten.length === vorher + 1 && tgNachrichten.at(-1).text.includes("Tagesbericht"));
  // Tageslimit 20
  const viele = Array.from({ length: 5 }, (_, i) => ({ art: "fehler", thema: "x", text: "Fehler Nummer " + i }));
  for (let i = 0; i < 4; i++) await melde(7, viele);
  r = await melde(7, viele); assert.equal(r.abgelehnt, 5);
  console.log("Schwarmwissen ok");
}

// VIP: eigenes Gerät über die Admin-Seite freischalten
{
  const uidV = "99999999-2222-4333-8444-555555555555";
  let a = await worker.fetch(new Request("https://x/admin/vip?k=geheim&uid=" + uidV), env); assert.equal(a.status, 200);
  const me = await worker.fetch(new Request("https://x/v1/me", { headers: { authorization: "Bearer " + uidV } }), env).then(r => r.json());
  assert.equal(me.stufe, "business"); assert.equal(me.vip, true);
  assert.ok((await (await worker.fetch(new Request("https://x/admin?k=geheim"), env)).text()).includes(uidV));
  a = await worker.fetch(new Request("https://x/admin/vip?k=geheim&weg=1&uid=" + uidV), env); assert.equal(a.status, 200);
  assert.equal((await worker.fetch(new Request("https://x/admin/vip?k=falsch&uid=" + uidV), env)).status, 403);
  console.log("VIP ok");
}
