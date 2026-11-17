# Sentinel – Fehler- und Wunschliste aus dem Testbetrieb

Robsen schickt tagsüber alles rein, abends wird gefixt und gesammelt hochgeladen.

## Offen (für heute Abend)
- 17.11. prompt_kopieren schlägt fehl (3× in der Fehler-Hitliste): Kopieren in die Zwischenablage klappt in der Android-App nicht, ohne Telegram kommt dann gar nichts an. → Native Zwischenablage nutzen (Capacitor-Clipboard o. Ä.) und den Text zusätzlich gut sichtbar zum Abschreiben/Teilen anzeigen.
- 17.11. bild_erstellen: Tageslimit (3 Bilder gratis) wird als Werkzeug-Fehler gezählt → kein echter Fehler, nicht in die Fehler-Hitliste aufnehmen, Hinweis freundlich formulieren.

## Wünsche (größer, nicht für die Abend-Runde)
- 17.11. Fitness-Daten: Schritte zählen etc. → Variante A: eigener Schrittzähler über den Handy-Sensor (einfach). Variante B: Health Connect (liest Samsung Health/Google Fit: Schritte, Schlaf, Puls) – braucht extra Google-Freigabe und Anpassung der Datensicherheit, daher erst nach dem Store-Start.
- Geplant (von Robsen ausgewählt, 17.11.):
  - Kühlschrank-Foto → Rezeptvorschläge
  - Kassenzettel scannen → automatisch ins Haushaltsbuch
  - Trink- und Pausen-Erinnerung (passt zum Fitness-Coach)
  - Quiz & Spiele für die Familie (Familienquiz, Wortspiele mit Kindern)
  - Sentinel teilen / NFC-Übergabe (Handy ans Tablet → Sentinel zieht mit Gedächtnis um)
- Geplant (17.11.): **Fitness- & Ernährungs-Coach Pro** – soll Ernährungs-Apps komplett ersetzen:
  - Trainingspläne und Ernährungspläne (auf Ziel, Alltag und Vorlieben zugeschnitten)
  - Schritte im Blick (Schrittzähler, Tagesziel, Motivation)
  - Abnehmen begleiten: Gewicht/Ziel verfolgen, Wochen-Check-in
  - Fasten-Timer (z. B. 16:8) mit Erinnerungen für Start/Ende
  - Essen per Foto oder Zuruf erfassen → Kalorien/Nährwerte schätzen, Tagesbilanz
  - verbindet sich mit Trink-Erinnerung, Rezepten (Kühlschrank-Foto) und Einkaufsliste
  - Hinweis: keine medizinischen Versprechen, bei Vorerkrankungen Arzt; Google-Gesundheitsrichtlinie beachten
- Weitere Ideen (noch offen): Morgen-Briefing · Live-Dolmetscher · Homescreen-Widget · Pakete vom Server nachladen · Video-KI voll automatisch

## Erledigt
- 17.11. Piepton beim Zuhören (v74, Store 1.73)
- 17.11. „Da kam gerade keine Antwort an“ bei schwierigen Fragen im Gratis-Modus (v75)
- 17.11. Gratisfragen zählten jeden Zwischenschritt mit (v76 + Server)
- 17.11. Fragen-Zähler oben links (v77)
- 17.11. Antwortet „einen Tick zu langsam“ → 1 s statt 1,6 s Wartezeit, Antwortzeit im Protokoll (v78, Store 1.77 – in Prüfung)
