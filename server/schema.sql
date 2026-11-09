-- Sentinel-Server: Nutzer, Verbrauch, Nachschub-Käufe
CREATE TABLE IF NOT EXISTS users (
  uid TEXT PRIMARY KEY,
  tier TEXT DEFAULT 'probe',
  tier_checked INTEGER DEFAULT 0,
  month TEXT DEFAULT '',
  used_usd REAL DEFAULT 0,
  topup_usd REAL DEFAULT 0,
  day TEXT DEFAULT '',
  req_day INTEGER DEFAULT 0,
  probe_used INTEGER DEFAULT 0,
  created INTEGER
);
CREATE TABLE IF NOT EXISTS topups (tx TEXT PRIMARY KEY, uid TEXT, month TEXT, usd REAL);
CREATE TABLE IF NOT EXISTS stats (day TEXT PRIMARY KEY, probe_req INTEGER DEFAULT 0, req INTEGER DEFAULT 0, usd REAL DEFAULT 0);
CREATE TABLE IF NOT EXISTS bilder (uid TEXT, day TEXT, n INTEGER DEFAULT 0, PRIMARY KEY (uid, day));
CREATE TABLE IF NOT EXISTS einrichtung (uid TEXT PRIMARY KEY, n INTEGER DEFAULT 0);
-- Schwarmwissen
CREATE TABLE IF NOT EXISTS wissen (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, thema TEXT, schluessel TEXT UNIQUE, art TEXT, status TEXT DEFAULT 'neu', meldungen INTEGER DEFAULT 1, erstellt TEXT, freigegeben TEXT);
CREATE TABLE IF NOT EXISTS wissen_meldung (wid INTEGER, uid TEXT, PRIMARY KEY (wid, uid));
CREATE TABLE IF NOT EXISTS schwarm_tag (uid TEXT, day TEXT, n INTEGER DEFAULT 0, PRIMARY KEY (uid, day));
CREATE TABLE IF NOT EXISTS fehler (day TEXT, tag TEXT, n INTEGER DEFAULT 0, beispiel TEXT, PRIMARY KEY (day, tag));
CREATE TABLE IF NOT EXISTS vip (uid TEXT PRIMARY KEY, seit TEXT);
