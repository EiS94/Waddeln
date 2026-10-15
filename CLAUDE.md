# Waddeln – Projektkontext

Android-App für das fränkische Kartenspiel **Waddeln** (4 Spieler, 2 Teams).
Die verbindlichen Spielregeln stehen in `WADDELN_RULES.md` im Projektroot. Bei Unklarheiten in den Regeln: nachfragen, nicht raten.

## Ziele
- **Solospiel komplett offline**: 1 Mensch + 3 KI-Gegner. Die App braucht dafür keinen Server und kein Internet.
- **Online-Modus** mit bis zu 4 echten Spielern; freie Plätze werden mit KI-Bots aufgefüllt.
- **KI-Gegner per Machine Learning**, trainiert auf einem Raspberry Pi 5 (nur CPU).
- Ansprechende UI mit eigenen Kartenbildern (die UI-Gestaltung beschreibt der Nutzer später gesondert).
- Nur kostenlose Tools und Dienste.

## Architektur-Entscheidungen
- **Android Studio, Kotlin, Jetpack Compose** (keine Spiele-Engine).
- Gradle-Multimodul-Projekt:
  - `rules-core/` – reine Kotlin/JVM-Bibliothek ohne Android-Abhängigkeit. Enthält die gesamte Spiellogik
    als Zustandsautomat: `GameState` + Aktion → neuer `GameState`; `legalActions(state, player)`;
    Stich-Auswertung, Trumpf-Hierarchie, Schub, Punkte, Matchende. Wird von App und Server genutzt.
    Jede Regel und jeder Sonderfall bekommt Unit-Tests.
  - `app/` – Android-App (Compose): UI, Offline-Spiel, KI-Inferenz auf dem Gerät.
  - `server/` – Ktor-WebSocket-Server (Kotlin/JVM), läuft auf dem Raspberry Pi.
  - `training/` – Python: schneller Simulator + ML-Training.
- **Spieler-Schnittstelle**: `Agent.chooseAction(observation, legalActions)`. Implementierungen:
  regelbasierter Bot (zuerst bauen; Fallback und Trainingsgegner), ML-Bot (ONNX).
- **Beobachtung statt Gesamtzustand**: Jeder Spieler/Agent bekommt nur, was er wissen darf
  (eigene Hand, gespielte Karten, Anzahl der vom Abheber genommenen Kritten – nicht welche –,
  offene unterste Deckkarte, bei gespannt gegen gespannt die Anzahl der hingelegten Karten,
  alles am Tisch Gesagte: Schübe, Partner-Hinweise, „Schadder?“ – das ist Teil von `GameState`).

## Online-Modus
- Autoritativer Server: Er mischt, verteilt und hält den echten Zustand; Clients sehen nur ihre eigenen Karten.
- Zunächst **private Räume mit Raum-Code**; Platz-/Teamwahl; leere Plätze = Bots.
- Identität: Spitzname + zufällige Geräte-ID, keine Accounts.
- Reconnect: Spieler kann auf seinen Platz zurück; in der Zwischenzeit spielt ein Bot.
- Erreichbarkeit von außen über Cloudflare Tunnel (gratis).
- So bauen, dass eine **öffentliche Spielersuche** (Warteschlange → automatische Räume) später nachrüstbar ist.

## KI / Machine Learning
- Ansatz: **Deep Monte-Carlo (DMC)** mit Self-Play (wie DouZero); Alternative PPO mit Action-Masking.
- Entscheidungen: Schlag ansagen (8), Farbe ansagen (4), Karte spielen, Schub ansagen / bleiben / gehen.
- Kleine Netze (MLP), Training mit PyTorch auf der CPU des Pi 5.
- Export: PyTorch → ONNX → ONNX Runtime for Android. Schwierigkeitsstufen über verschiedene Checkpoints bzw. Zufall bei der Zugwahl.
- **Zwei Regel-Implementierungen (Kotlin + Python) müssen identisch sein**: Die Kotlin-Engine erzeugt zufällige
  Partien als JSON-Protokolle; die Python-Engine spielt sie nach und prüft legale Aktionen, Stichgewinner und Punktestände.

## Kartenbilder
- Liegen beim Nutzer vor; als WebP (2–3-fache Anzeigegröße) einbinden.

## Vorgehen (Phasen)
1. `rules-core` in Kotlin mit umfassenden Unit-Tests  ← **hier starten**
2. Minimale App mit einfacher UI + regelbasierten Bots (offline spielbar)
3. Python-Simulator (abgeglichen über JSON-Protokolle) + Training auf dem Pi
4. Ausgearbeitete UI nach Vorgaben des Nutzers
5. Server + Online-Modus

## Konventionen
- Code, Bezeichner und Kommentare auf Englisch; Spielbegriffe als Fachbegriffe beibehalten
  (`Kritte`, `Haubi`, `Schlag`, `Schub`, `Geber`, `Abheber`, `gespannt`).
- UI-Texte auf Deutsch.
- Kommunikation mit dem Nutzer auf Deutsch.
