# Sentinel – Fehler- und Wunschliste aus dem Testbetrieb

Robsen schickt tagsüber alles rein, abends wird gefixt und gesammelt hochgeladen.

## Offen (für heute Abend)
- 24.11. Sentinel als Standard-Assistent: Eintrag als Assistent-App (Intent ASSIST / VoiceInteractionService), damit langes Drücken der Ein/Aus-Taste bzw. Wischen aus der Ecke Sentinel startet und er sofort zuhört – auch aus anderen Apps. Store-konform. Einrichtungsschritt „Als Assistent einstellen“ mit Knopf zu den Android-Einstellungen.
- Absturz-Ursache 1.88: wartet auf ersten Absturzbericht per Telegram (Wächter seit 1.90)

## Wünsche (größer, nicht für die Abend-Runde)
- Noch offen: Health Connect (Samsung Health/Google Fit: Schlaf, Puls) · Sentinel teilen / NFC-Übergabe · WhatsApp Business als Plus-Funktion (Cloud-API, Meta-Verifizierung, Kosten pro Nachricht) · Homescreen-Widget · Pakete vom Server nachladen · Video-KI voll automatisch · Preisvergleich mit echter Prospekt-Datenquelle (Lizenz/Partner) statt Websuche
- Neuheiten-Regel: Bei jeder neuen Funktion APP_VERSION erhöhen und in NEUHEITEN eintragen → Sentinel fragt beim nächsten Öffnen „Möchtest du meine neuen Funktionen kennenlernen?“

## Erledigt
- 26.11. (1.92) „Keine Antwort“ beim Ernährungsplan: Ursache = Antwortlänge (Gratis max. 1000 Tokens, Plan wurde mitten im Werkzeugaufruf abgeschnitten). Fix: Limit Gratis 2000 / Basis 3000, abgeschnittene Antworten → automatisch kürzere Fassung holen (zählt nicht), Längen-Hinweis je Abo im Prompt
- 24.11. (v83, Store 1.91): „Was ich kann“ kompakt – Kästchen pro Fähigkeit, sortiert nach Gratis / Basis / Plus (Pakete) / Pro / Business, eigenes Abo markiert, ohne „Neu in Version“ · 💬-Knopf oben + Einstellung: Text auf dem Display ein/aus · Gezeigte Übersicht + „Was möchtest du einrichten?“ landen im Verlauf (kein „hab ich nicht gezeigt“ mehr) · Behauptete Aktionen ohne Werkzeug → einmal automatisch nachfassen (ausführen oder ehrlich sagen) · alte Fragen nicht nochmal beantworten (Regel seit v82)
- 23.11. (v82, Store 1.89) nach Google-Entfernung („stürzt nach dem Öffnen ab“): Absturz-Wächter (Stacktrace + Gerät + Android-Version → Server → Telegram an Robsen), Spracherkennung/Sprachausgabe/Start abgesichert (keine Abstürze mehr durch Fehler in Rückrufen), WLAN-Hardware optional · Jede Frage zählt, gratis nur Name & Wohnort (Server) · Ausreden lassen: 2 s Wartezeit nach Satzende, Einstellung „Ich spreche mit Pausen“ (3 s) · Korrekturen ersetzen alte Infos inkl. Terminen · keine ungefragten Wiederholungen (Wetter, Rezept per Telegram)
- 18.11. abends (v81, Store 1.88): Antworten als Text/Untertitel (abschaltbar) · Antippen während Sentinel spricht = sofort still · Telegram-Assistent in den Einstellungen (BotFather öffnen, Token einfügen, Chat-ID automatisch + Begrüßung) · Gratis beim Kennenlernen nur noch fürs Kennenlernen/Einrichten (fremde Themen per [extra] oder Nutz-Werkzeug zählen normal, Server)
- 17./18.11. nachts – **v80 „Was die Großen können“** (Store 1.87):
  - Update-Ansage: nach jedem Update fragt Sentinel, ob man die neuen Funktionen kennenlernen will, zählt sie auf und fragt „Was möchtest du davon einrichten?“
  - Anrufen (Telefon-App mit Nummer), WhatsApp/SMS fertig vorschreiben
  - Radio (tausende Sender, radio-browser.info), leiser beim Sprechen
  - Morgen-Briefing zur Wunschzeit (sonst per Telegram)
  - Live-Dolmetscher Deutsch ↔ ~25 Sprachen (hört ohne Weckwort, spricht in der Fremdsprache)
  - Schrittzähler (Handy-Sensor, neue Berechtigung „Körperliche Aktivität“)
  - Ernährung & Körper: Kalorien/Protein/KH/Fett per Zuruf oder Foto, Wasser, Gewicht, Ziele, Fasten-Timer
  - Wiederkehrende Erinnerungen (stündlich mit Zeitfenster, täglich, werktags, wöchentlich) → Trink-/Pausen-Erinnerung
  - Sprach-Tagebuch (privat, auf dem Gerät)
  - MHD-Wächter privat + Laden (Warnung + Telegram)
  - Ablage: Verträge, Versicherungen, Garantien, Belege (mit Foto als PDF)
  - Dokumenten-Scanner: Foto → PDF, mehrseitig
  - Kühlschrank-Koch, Hausaufgabenhilfe, Kassenzettel → Haushaltsbuch, Pflanzen-Doktor
  - Neue Pakete ab Plus: Fitness- & Ernährungs-Coach Pro, Sparfuchs (Preisvergleich per Websuche), Einzelhandel MHD & Abschriften, Spiele & Quiz, Schlaf & Entspannung, Geburtstage & Geschenke, Reise-Organizer, Verträge & Kündigungen
- 17.11. abends: Kopieren in die Zwischenablage über Android (prompt_kopieren), Notlösung Eingabefeld; Tageslimits (z. B. Bilder) zählen nicht mehr als Fehler (v79, Store 1.86 – eingereicht)
- 17.11. Piepton beim Zuhören (v74, Store 1.73)
- 17.11. „Da kam gerade keine Antwort an“ bei schwierigen Fragen im Gratis-Modus (v75)
- 17.11. Gratisfragen zählten jeden Zwischenschritt mit (v76 + Server)
- 17.11. Fragen-Zähler oben links (v77)
- 17.11. Antwortet „einen Tick zu langsam“ → 1 s statt 1,6 s Wartezeit, Antwortzeit im Protokoll (v78, Store 1.77 – in Prüfung)
