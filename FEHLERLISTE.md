# Sentinel – Fehler- und Wunschliste aus dem Testbetrieb

Robsen schickt tagsüber alles rein, abends wird gefixt und gesammelt hochgeladen.

## Offen (für heute Abend)
- 17.11. prompt_kopieren schlägt fehl (3× in der Fehler-Hitliste): Kopieren in die Zwischenablage klappt in der Android-App nicht, ohne Telegram kommt dann gar nichts an. → Native Zwischenablage nutzen (Capacitor-Clipboard o. Ä.) und den Text zusätzlich gut sichtbar zum Abschreiben/Teilen anzeigen.
- 17.11. bild_erstellen: Tageslimit (3 Bilder gratis) wird als Werkzeug-Fehler gezählt → kein echter Fehler, nicht in die Fehler-Hitliste aufnehmen, Hinweis freundlich formulieren.

## Erledigt
- 17.11. Piepton beim Zuhören (v74, Store 1.73)
- 17.11. „Da kam gerade keine Antwort an“ bei schwierigen Fragen im Gratis-Modus (v75)
- 17.11. Gratisfragen zählten jeden Zwischenschritt mit (v76 + Server)
- 17.11. Fragen-Zähler oben links (v77)
- 17.11. Antwortet „einen Tick zu langsam“ → 1 s statt 1,6 s Wartezeit, Antwortzeit im Protokoll (v78, Store 1.77 – in Prüfung)
