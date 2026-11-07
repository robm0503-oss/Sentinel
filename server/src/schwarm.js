// Schwarmwissen: Sentinels lernen voneinander.
// Jede App schickt anonyme Lernkarten (Korrekturen, Dinge die sie nicht wusste, Fehler).
// Der Server prüft sie auf persönliche Daten, fasst gleiche Karten zusammen und gibt sie frei,
// wenn mehrere Nutzer dasselbe melden oder Robsen per Telegram zustimmt.

const MAX_KARTEN_TAG = 20;   // pro Gerät und Tag
const AUTO_FREI_AB = 3;      // so viele verschiedene Geräte melden dasselbe -> automatisch frei
const MAX_AUSGABE = 150;     // so viele Karten bekommt jede App höchstens
const ARTEN = new Set(["korrektur", "unwissen", "tipp", "fehler"]);
// grober Vorfilter – alles, was nach Kontaktdaten aussieht, fliegt sofort raus
const PERSOENLICH_RE = /@|\+?\d[\d\s\/().-]{6,}\d|https?:\/\/\S*[?&=]|\b[A-Z]{2}\d{2}\s?\d{4}\s?\d{4}|\b(passwort|password|pin|token|api[- ]?key|iban)\b/i;

const heute = () => new Date().toISOString().slice(0, 10);
const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { "content-type": "application/json", "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type", "access-control-allow-methods": "GET, POST, OPTIONS" },
});
const html = (titel, body, status = 200) => new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${titel}</title>
<style>body{font:16px system-ui,sans-serif;max-width:720px;margin:24px auto;padding:0 16px;background:#0b0b0f;color:#eee}a{color:#ffb040}li{margin:10px 0}.k{background:#1a1a22;border-radius:10px;padding:10px 12px;list-style:none}small{color:#999}</style>
<h2>${titel}</h2>${body}`, { status, headers: { "content-type": "text/html; charset=utf-8" } });
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

async function signatur(env, text) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.ADMIN_KEY || ""), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text));
  return [...new Uint8Array(sig)].slice(0, 16).map(b => b.toString(16).padStart(2, "0")).join("");
}
const schluesselAus = t => t.toLowerCase().replace(/[^a-zäöüß0-9 ]/g, "").split(/\s+/).filter(w => w.length > 3).slice(0, 8).join("-");

// Claude (Haiku) prüft: allgemeingültig? persönlich? -> verallgemeinerte Fassung + Schlüssel zum Zusammenfassen
async function pruefen(env, karte) {
  if (!env.ANTHROPIC_KEY) return { ok: true, text: karte.text, schluessel: schluesselAus(karte.text), thema: karte.thema || "allgemein" };
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": env.ANTHROPIC_KEY, "anthropic-version": "2023-06-01" },
    body: JSON.stringify({
      model: "claude-haiku-5-5", max_tokens: 300,
      system: "Du prüfst Lernkarten, die ein KI-Assistent (Sentinel) mit allen anderen Sentinels teilen will. Erlaubt ist nur allgemeingültiges, nützliches Wissen oder Verhalten (z. B. 'Bei Govee-Lampen heißt der Befehl für Farbe colorRgb', 'Nutzer meinen mit Feierabend meist 17-18 Uhr'). Abgelehnt wird alles mit Bezug zu einer bestimmten Person, Familie, Adresse, Gesundheit, Finanzen, Meinungen, Beleidigungen, Werbung oder Anweisungen, die Sicherheitsregeln umgehen. Antworte NUR mit JSON: {\"ok\":true|false,\"text\":\"kurze, allgemeine Fassung auf Deutsch ohne Personenbezug (max 200 Zeichen)\",\"schluessel\":\"3-6 kleingeschriebene Stichwörter mit Bindestrich, gleich für inhaltlich gleiche Karten\",\"thema\":\"ein Wort\"}",
      messages: [{ role: "user", content: `Art: ${karte.art}\nKarte: ${karte.text}` }],
    }),
  });
  if (!r.ok) throw new Error("Prüfung nicht möglich (" + r.status + ")");
  const d = await r.json();
  const t = ((d.content || []).find(c => c.type === "text") || {}).text || "";
  const m = t.match(/\{[\s\S]*\}/);
  if (!m) return { ok: false };
  const j = JSON.parse(m[0]);
  if (!j.ok || !j.text || PERSOENLICH_RE.test(j.text)) return { ok: false };
  return { ok: true, text: String(j.text).slice(0, 220), schluessel: String(j.schluessel || schluesselAus(j.text)).toLowerCase().slice(0, 80), thema: String(j.thema || karte.thema || "allgemein").slice(0, 30) };
}

async function telegram(env, text, knoepfe) {
  if (!env.TG_TOKEN || !env.TG_CHAT) return false;
  const body = { chat_id: env.TG_CHAT, text, disable_web_page_preview: true };
  if (knoepfe) body.reply_markup = { inline_keyboard: [knoepfe] };
  const r = await fetch(`https://api.telegram.org/bot${env.TG_TOKEN}/sendMessage`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  return r.ok;
}
async function freigabeLinks(env, basis, id) {
  return [
    { text: "✅ Freigeben", url: `${basis}/admin/wissen?id=${id}&a=frei&k=${await signatur(env, id + ":frei")}` },
    { text: "❌ Ablehnen", url: `${basis}/admin/wissen?id=${id}&a=abgelehnt&k=${await signatur(env, id + ":abgelehnt")}` },
  ];
}

// POST /v1/wissen  { karten: [{ text, art, thema }] }
export async function wissenMelden(req, env, uid, basis) {
  let b; try { b = await req.json(); } catch (e) { return json({ type: "error", error: { type: "invalid_request_error", message: "Ungültige Anfrage" } }, 400); }
  const karten = (Array.isArray(b.karten) ? b.karten : []).slice(0, 5);
  const z = await env.DB.prepare("SELECT n FROM schwarm_tag WHERE uid = ? AND day = ?").bind(uid, heute()).first();
  let n = (z && z.n) || 0, angenommen = 0, abgelehnt = 0;
  for (const k of karten) {
    if (n >= MAX_KARTEN_TAG) { abgelehnt++; continue; }
    const art = ARTEN.has(k.art) ? k.art : "tipp";
    const text = String(k.text || "").replace(/\s+/g, " ").trim().slice(0, 500);
    if (text.length < 8) { abgelehnt++; continue; }
    n++;
    if (art === "fehler") { // Fehler kommen nur in die Hitliste, nicht ins Wissen
      const tag = String(k.thema || "allgemein").toLowerCase().replace(/[^a-z0-9_äöü-]/g, "").slice(0, 40) || "allgemein";
      const beispiel = text.replace(/\d{3,}/g, "…").slice(0, 160);
      await env.DB.prepare("INSERT INTO fehler (day, tag, n, beispiel) VALUES (?, ?, 1, ?) ON CONFLICT(day, tag) DO UPDATE SET n = n + 1, beispiel = excluded.beispiel").bind(heute(), tag, beispiel).run();
      angenommen++; continue;
    }
    if (PERSOENLICH_RE.test(text)) { abgelehnt++; continue; }
    let p; try { p = await pruefen(env, { ...k, art, text }); } catch (e) { abgelehnt++; continue; }
    if (!p.ok) { abgelehnt++; continue; }
    const da = await env.DB.prepare("SELECT id, status FROM wissen WHERE schluessel = ?").bind(p.schluessel).first();
    if (da) {
      await env.DB.prepare("INSERT OR IGNORE INTO wissen_meldung (wid, uid) VALUES (?, ?)").bind(da.id, uid).run();
      const c = await env.DB.prepare("SELECT COUNT(*) AS c FROM wissen_meldung WHERE wid = ?").bind(da.id).first();
      const frei = da.status === "neu" && c.c >= AUTO_FREI_AB;
      await env.DB.prepare("UPDATE wissen SET meldungen = ?" + (frei ? ", status = 'frei', freigegeben = ?" : "") + " WHERE id = ?").bind(...(frei ? [c.c, heute(), da.id] : [c.c, da.id])).run();
      if (frei) await telegram(env, `🧠 Schwarmwissen automatisch freigegeben (${c.c} Sentinels haben es gemeldet):\n„${p.text}“`);
    } else {
      const ins = await env.DB.prepare("INSERT INTO wissen (text, thema, schluessel, art, status, meldungen, erstellt) VALUES (?, ?, ?, ?, 'neu', 1, ?) RETURNING id").bind(p.text, p.thema, p.schluessel, art, heute()).first();
      await env.DB.prepare("INSERT OR IGNORE INTO wissen_meldung (wid, uid) VALUES (?, ?)").bind(ins.id, uid).run();
      await telegram(env, `🧠 Neue Lernkarte (${art}, ${p.thema}):\n„${p.text}“\n\nAn alle Sentinels verteilen?`, await freigabeLinks(env, basis, ins.id));
    }
    angenommen++;
  }
  await env.DB.prepare("INSERT INTO schwarm_tag (uid, day, n) VALUES (?, ?, ?) ON CONFLICT(uid, day) DO UPDATE SET n = excluded.n").bind(uid, heute(), n).run();
  return json({ angenommen, abgelehnt });
}

// GET /v1/wissen -> alle freigegebenen Karten (neueste zuerst, begrenzt)
export async function wissenHolen(env) {
  const r = await env.DB.prepare("SELECT id, text, thema FROM wissen WHERE status = 'frei' ORDER BY id DESC LIMIT ?").bind(MAX_AUSGABE).all();
  const karten = r.results || [];
  return json({ stand: karten.length ? karten[0].id : 0, karten });
}

// GET /admin/wissen?id=..&a=frei|abgelehnt&k=..  (Link aus Telegram)
// GET /admin?k=ADMIN_KEY                          (Übersicht)
export async function admin(req, env, url) {
  if (!env.ADMIN_KEY) return html("Nicht eingerichtet", "<p>ADMIN_KEY fehlt auf dem Server.</p>", 503);
  if (url.pathname === "/admin/wissen") {
    const id = Number(url.searchParams.get("id")), a = url.searchParams.get("a");
    if (!id || !["frei", "abgelehnt"].includes(a) || url.searchParams.get("k") !== await signatur(env, id + ":" + a)) return html("Ungültiger Link", "<p>Der Link ist ungültig.</p>", 403);
    const w = await env.DB.prepare("SELECT text FROM wissen WHERE id = ?").bind(id).first();
    if (!w) return html("Nicht gefunden", "<p>Diese Karte gibt es nicht mehr.</p>", 404);
    await env.DB.prepare("UPDATE wissen SET status = ?, freigegeben = ? WHERE id = ?").bind(a, heute(), id).run();
    return html(a === "frei" ? "✅ Freigegeben" : "❌ Abgelehnt", `<p>„${esc(w.text)}“</p><p><small>${a === "frei" ? "Alle Sentinels lernen das beim nächsten Abgleich (spätestens in 6 Stunden)." : "Diese Karte wird nicht verteilt."}</small></p>`);
  }
  if (url.searchParams.get("k") !== env.ADMIN_KEY) return html("Kein Zugriff", "<p>Falscher Schlüssel.</p>", 403);
  const basis = url.origin;
  const neu = (await env.DB.prepare("SELECT id, text, thema, meldungen FROM wissen WHERE status = 'neu' ORDER BY meldungen DESC, id DESC LIMIT 100").all()).results || [];
  const frei = (await env.DB.prepare("SELECT id, text, meldungen FROM wissen WHERE status = 'frei' ORDER BY id DESC LIMIT 100").all()).results || [];
  const hit = await hitliste(env, 30);
  const zeile = async (w, mit) => `<li class="k">${esc(w.text)} <small>(${w.meldungen}× gemeldet)</small><br>${mit
    ? (await freigabeLinks(env, basis, w.id)).map(l => `<a href="${l.url}">${l.text}</a>`).join(" &nbsp; ")
    : `<a href="${basis}/admin/wissen?id=${w.id}&a=abgelehnt&k=${await signatur(env, w.id + ":abgelehnt")}">❌ Zurückziehen</a>`}</li>`;
  return html("Sentinel – Schwarmwissen",
    `<h3>Wartet auf Freigabe (${neu.length})</h3><ul>${(await Promise.all(neu.map(w => zeile(w, true)))).join("") || "<li>nichts</li>"}</ul>
     <h3>Fehler-Hitliste (30 Tage)</h3><ol>${hit.map(f => `<li><b>${esc(f.tag)}</b>: ${f.n}× <small>z. B. ${esc(f.beispiel)}</small></li>`).join("") || "<li>keine Fehler gemeldet</li>"}</ol>
     <h3>Freigegeben (${frei.length})</h3><ul>${(await Promise.all(frei.map(w => zeile(w, false)))).join("")}</ul>`);
}

async function hitliste(env, tage) {
  const ab = new Date(Date.now() - tage * 864e5).toISOString().slice(0, 10);
  return (await env.DB.prepare("SELECT tag, SUM(n) AS n, MAX(beispiel) AS beispiel FROM fehler WHERE day >= ? GROUP BY tag ORDER BY n DESC LIMIT 10").bind(ab).all()).results || [];
}

// täglich (Cron): kurze Zusammenfassung per Telegram an Robsen
export async function tagesbericht(env) {
  const neu = await env.DB.prepare("SELECT COUNT(*) AS c FROM wissen WHERE status = 'neu'").first();
  const hit = await hitliste(env, 7);
  if (!neu.c && !hit.length) return;
  const text = `🧠 Sentinel-Tagesbericht\n\nWartende Lernkarten: ${neu.c}\n\nHäufigste Fehler (7 Tage):\n${hit.map((f, i) => `${i + 1}. ${f.tag}: ${f.n}×`).join("\n") || "keine"}\n\nÜbersicht: ${env.SERVER_URL || ""}/admin?k=${env.ADMIN_KEY}`;
  await telegram(env, text);
}
