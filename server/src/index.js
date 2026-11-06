// Sentinel-Server: verwaltet Abo-Stufen, Limits und Budget und spricht mit Claude.
// Die App schickt ihre Anfrage hierher statt direkt zu Anthropic; der API-Key bleibt auf dem Server.

const USD_PRO_EUR = 1.08;

// Preise in US-Dollar je 1 Mio. Tokens
export const PREIS = {
  "claude-haiku-5-5": { in: 0.10, out: 0.50, cr: 0.01, cw: 0.125 },
  "claude-sonnet-5-5": { in: 3, out: 15, cr: 0.30, cw: 3.75 },
  "claude-opus-5-5": { in: 4, out: 20, cr: 0.20, cw: 5 },
};
const HAIKU = "claude-haiku-5-5", SONNET = "claude-sonnet-5-5", OPUS = "claude-opus-5-5";

// Abo-Stufen (Budget pro Monat in Euro, Anfragen pro Tag)
export const STUFEN = {
  probe:    { name: "Probe",    budgetEur: 0,  proTag: 0,    bilderTag: 3,   gesamt: 30, modelle: [HAIKU],               suche: false, maxTokens: 1000 },
  basis:    { name: "Basis",    budgetEur: 1.5, proTag: 40,  bilderTag: 10,  modelle: [HAIKU],                       suche: false, maxTokens: 1500 },
  plus:     { name: "Plus",     budgetEur: 4,  proTag: 80,   bilderTag: 25, modelle: [HAIKU, SONNET],               suche: true,  maxTokens: 4000, bremse: 0.8 },
  pro:      { name: "Pro",      budgetEur: 9,  proTag: 150,  bilderTag: 60, modelle: [HAIKU, SONNET],               suche: true,  maxTokens: 4000, bremse: 0.9 },
  business: { name: "Business", budgetEur: 25, proTag: null, bilderTag: 150, modelle: [HAIKU, SONNET, OPUS],         suche: true,  maxTokens: 6000, bremse: 0.95 },
};
const RANG = ["probe", "basis", "plus", "pro", "business"];
// Nachschub-Pakete (Einmalkauf) -> zusätzliches Budget in Euro
export const NACHSCHUB = { nachschub_10: 10 };

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { "content-type": "application/json", "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type", "access-control-allow-methods": "GET, POST, OPTIONS" },
});
const fehler = (status, type, message, extra = {}) => json({ type: "error", error: { type, message }, ...extra }, status);
const heute = () => new Date().toISOString().slice(0, 10);
const monat = () => heute().slice(0, 7);

export function kosten(u, model) {
  if (!u) return 0;
  const p = PREIS[model] || PREIS[SONNET];
  return ((u.input_tokens || 0) * p.in + (u.output_tokens || 0) * p.out + (u.cache_read_input_tokens || 0) * p.cr + (u.cache_creation_input_tokens || 0) * p.cw) / 1e6
    + ((u.server_tool_use && u.server_tool_use.web_search_requests) || 0) * 0.01;
}

// Welche Stufe hat der Nutzer laut RevenueCat? (10 Minuten zwischengespeichert)
async function stufeHolen(env, user) {
  if (!env.RC_SECRET) return { stufe: user.tier || "probe", topups: [] };
  if (Date.now() - (user.tier_checked || 0) < 10 * 60000) return { stufe: user.tier, topups: null };
  const r = await fetch(`https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(user.uid)}`, { headers: { authorization: `Bearer ${env.RC_SECRET}` } });
  if (!r.ok) return { stufe: user.tier || "probe", topups: null };
  const s = (await r.json()).subscriber || {};
  let stufe = "probe";
  for (const [id, e] of Object.entries(s.entitlements || {})) {
    if (RANG.includes(id) && (!e.expires_date || Date.parse(e.expires_date) > Date.now()) && RANG.indexOf(id) > RANG.indexOf(stufe)) stufe = id;
  }
  const topups = [];
  for (const [prod, kaeufe] of Object.entries(s.non_subscriptions || {})) {
    if (!NACHSCHUB[prod]) continue;
    for (const k of kaeufe) if ((k.purchase_date || "").slice(0, 7) === monat()) topups.push({ tx: k.id || k.store_transaction_id, eur: NACHSCHUB[prod] });
  }
  return { stufe, topups };
}

async function nutzerLaden(env, uid) {
  let u = await env.DB.prepare("SELECT * FROM users WHERE uid = ?").bind(uid).first();
  if (!u) {
    await env.DB.prepare("INSERT INTO users (uid, tier, created) VALUES (?, 'probe', ?)").bind(uid, Date.now()).run();
    u = await env.DB.prepare("SELECT * FROM users WHERE uid = ?").bind(uid).first();
  }
  if (u.month !== monat()) { u.month = monat(); u.used_usd = 0; u.topup_usd = 0; }
  if (u.day !== heute()) { u.day = heute(); u.req_day = 0; }
  const { stufe, topups } = await stufeHolen(env, u);
  if (stufe !== u.tier || topups) { u.tier = stufe; u.tier_checked = Date.now(); }
  if (topups) for (const t of topups) {
    const neu = await env.DB.prepare("INSERT OR IGNORE INTO topups (tx, uid, month, usd) VALUES (?, ?, ?, ?)").bind(String(t.tx), uid, monat(), t.eur * USD_PRO_EUR).run();
    if (neu.meta && neu.meta.changes) u.topup_usd += t.eur * USD_PRO_EUR;
  }
  return u;
}
const speichern = (env, u) => env.DB.prepare("UPDATE users SET tier=?, tier_checked=?, month=?, used_usd=?, topup_usd=?, day=?, req_day=?, probe_used=? WHERE uid=?")
  .bind(u.tier, u.tier_checked || 0, u.month, u.used_usd, u.topup_usd, u.day, u.req_day, u.probe_used || 0, u.uid).run();

export function stand(u) {
  const st = STUFEN[u.tier] || STUFEN.probe;
  const budgetUsd = st.budgetEur * USD_PRO_EUR + (u.topup_usd || 0);
  return {
    stufe: u.tier, name: st.name,
    verbraucht_eur: +(u.used_usd / USD_PRO_EUR).toFixed(2), budget_eur: +(budgetUsd / USD_PRO_EUR).toFixed(2),
    anfragen_heute: u.req_day, limit_heute: st.proTag, probe_rest: u.tier === "probe" ? Math.max(0, st.gesamt - (u.probe_used || 0)) : null,
  };
}

// Welches Gehirn darf diese Anfrage nutzen?
export function modellWaehlen(u, gewuenscht) {
  const st = STUFEN[u.tier] || STUFEN.probe;
  let m = st.modelle.includes(gewuenscht) ? gewuenscht : st.modelle[Math.min(st.modelle.length - 1, gewuenscht === OPUS ? 2 : gewuenscht === SONNET ? 1 : 0)];
  const budgetUsd = st.budgetEur * USD_PRO_EUR + (u.topup_usd || 0);
  if (st.bremse && budgetUsd > 0 && u.used_usd >= budgetUsd * st.bremse) m = HAIKU; // Budget-Bremse
  return m;
}

async function chat(req, env, uid) {
  const u = await nutzerLaden(env, uid);
  const st = STUFEN[u.tier] || STUFEN.probe;
  // Limits prüfen
  if (u.tier === "probe") {
    const s = await env.DB.prepare("SELECT probe_req FROM stats WHERE day = ?").bind(heute()).first();
    if ((u.probe_used || 0) >= st.gesamt) { await speichern(env, u); return fehler(402, "abo_noetig", "Die kostenlosen Probe-Anfragen sind aufgebraucht. Wähle ein Abo, um weiterzumachen.", { stand: stand(u) }); }
    if (s && s.probe_req >= Number(env.PROBE_DAY_CAP || 3000)) return fehler(429, "probe_voll", "Die Probe ist heute ausgelastet. Versuch es morgen oder wähle ein Abo.");
  } else {
    if (st.proTag && u.req_day >= st.proTag) { await speichern(env, u); return fehler(429, "tageslimit", `Tageslimit von ${st.proTag} Anfragen erreicht. Morgen geht's weiter – oder nimm eine höhere Stufe.`, { stand: stand(u) }); }
    const budgetUsd = st.budgetEur * USD_PRO_EUR + (u.topup_usd || 0);
    if (u.used_usd >= budgetUsd) { await speichern(env, u); return fehler(402, u.tier === "business" ? "nachschub_noetig" : "budget_leer", u.tier === "business" ? "Das Monatsbudget ist aufgebraucht. Ein Nachschub-Paket macht sofort weiter." : "Das Monatsbudget deiner Stufe ist aufgebraucht. Ab nächstem Monat geht's weiter – oder nimm eine höhere Stufe.", { stand: stand(u) }); }
  }
  let body;
  try { body = await req.json(); } catch (e) { return fehler(400, "invalid_request_error", "Ungültige Anfrage"); }
  const model = modellWaehlen(u, body.model);
  const tools = (body.tools || []).filter(t => st.suche || !String(t.type || "").startsWith("web_search"));
  const anfrage = { ...body, model, max_tokens: Math.min(Number(body.max_tokens) || 1000, st.maxTokens), ...(tools.length ? { tools } : { tools: undefined }) };
  if (!anfrage.tools) delete anfrage.tools;
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": env.ANTHROPIC_KEY, "anthropic-version": "2023-06-01" },
    body: JSON.stringify(anfrage),
  });
  const data = await r.json().catch(() => null);
  if (!r.ok || !data) return json(data || { type: "error", error: { type: "api_error", message: "Gehirn nicht erreichbar" } }, r.status || 502);
  const usd = kosten(data.usage, model);
  u.used_usd += usd; u.req_day += 1;
  if (u.tier === "probe") u.probe_used = (u.probe_used || 0) + 1;
  await speichern(env, u);
  await env.DB.prepare("INSERT INTO stats (day, probe_req, req, usd) VALUES (?, ?, 1, ?) ON CONFLICT(day) DO UPDATE SET probe_req = probe_req + excluded.probe_req, req = req + 1, usd = usd + excluded.usd")
    .bind(heute(), u.tier === "probe" ? 1 : 0, usd).run();
  data.sentinel = { ...stand(u), modell: model };
  return json(data);
}

// Bilder malen/bearbeiten mit Cloudflare Workers AI (FLUX.2) – im Gratis-Kontingent von Cloudflare
const BILD_MODELL = "@cf/black-forest-labs/flux-2-klein-4b";
const GRAU = { quadrat: [1024, 1024], hoch: [768, 1344], quer: [1344, 768] };
const b64ZuBytes = b64 => Uint8Array.from(atob(b64), c => c.charCodeAt(0));
async function zuB64(x) {
  if (typeof x === "string") return x;
  if (x && typeof x.image === "string") return x.image;
  if (x && x.result && typeof x.result.image === "string") return x.result.image;
  const buf = x instanceof ReadableStream ? await new Response(x).arrayBuffer() : x instanceof ArrayBuffer ? x : x && x.buffer ? x.buffer : null;
  if (!buf) throw new Error("Unbekannte Bild-Antwort");
  let bin = ""; const a = new Uint8Array(buf); for (let i = 0; i < a.length; i += 0x8000) bin += String.fromCharCode(...a.subarray(i, i + 0x8000));
  return btoa(bin);
}
async function bild(req, env, uid) {
  if (!env.AI) return fehler(503, "api_error", "Bilder sind auf dem Server nicht eingerichtet.");
  const u = await nutzerLaden(env, uid); await speichern(env, u);
  const st = STUFEN[u.tier] || STUFEN.probe;
  const z = await env.DB.prepare("SELECT n FROM bilder WHERE uid = ? AND day = ?").bind(uid, heute()).first();
  if (z && z.n >= st.bilderTag) return fehler(429, "bilder_limit", `Heute sind ${st.bilderTag} Bilder drin – morgen geht's weiter${u.tier === "business" ? "" : " oder mit einer höheren Stufe"}.`);
  let b; try { b = await req.json(); } catch (e) { return fehler(400, "invalid_request_error", "Ungültige Anfrage"); }
  const prompt = String(b.prompt || "").slice(0, 2000);
  if (!prompt) return fehler(400, "invalid_request_error", "Beschreibung fehlt");
  const [w, h] = GRAU[b.format] || GRAU.quadrat;
  const form = new FormData();
  form.append("prompt", prompt); form.append("width", String(w)); form.append("height", String(h));
  if (b.bild) form.append("input_image_0", new Blob([b64ZuBytes(b.bild)], { type: b.mime || "image/jpeg" }), "eingabe.jpg");
  const fr = new Response(form);
  let out;
  try { out = await env.AI.run(BILD_MODELL, { multipart: { body: fr.body, contentType: fr.headers.get("content-type") } }); }
  catch (e) { return fehler(502, "api_error", "Die Bild-KI hat abgelehnt: " + String(e.message || e).slice(0, 200)); }
  const data = await zuB64(out);
  await env.DB.prepare("INSERT INTO bilder (uid, day, n) VALUES (?, ?, 1) ON CONFLICT(uid, day) DO UPDATE SET n = n + 1").bind(uid, heute()).run();
  return json({ mime: "image/jpeg", data, rest: st.bilderTag - ((z && z.n) || 0) - 1 });
}

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return json({});
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "/v1/health") return json({ ok: true, name: "Sentinel-Server" });
    const uid = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(uid)) return fehler(401, "authentication_error", "Unbekanntes Gerät");
    try {
      if (url.pathname === "/v1/me" && req.method === "GET") { const u = await nutzerLaden(env, uid); await speichern(env, u); return json(stand(u)); }
      if (url.pathname === "/v1/messages" && req.method === "POST") return await chat(req, env, uid);
      if (url.pathname === "/v1/bild" && req.method === "POST") return await bild(req, env, uid);
      return fehler(404, "not_found", "Gibt es nicht");
    } catch (e) { return fehler(500, "api_error", "Serverfehler: " + e.message); }
  },
};
