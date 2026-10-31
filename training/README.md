# Waddeln – Simulator & KI-Training

Python-Nachbau der Spielregeln (`waddeln/engine.py`), regelbasierter Bot (`waddeln/bots.py`) und
Deep-Monte-Carlo-Training (DMC, wie DouZero) für die KI-Gegner. Läuft nur auf der CPU – gedacht
für den Raspberry Pi 5.

## Aufbau

| Datei | Inhalt |
|---|---|
| `waddeln/engine.py` | Regeln als Zustandsautomat – identisch zu `rules-core` (Kotlin) |
| `waddeln/bots.py` | Zufalls-Bot und Regel-Bot (Port von `RuleBasedAgent.kt`) |
| `waddeln/features.py` | Netz-Eingabe – identisch zu `Encoding.kt` (`FeatureEncoder`) |
| `waddeln/model.py` | Q-Netz (MLP) und Agent, der damit spielt |
| `waddeln/match_value.py` | Siegchance je Spielstand → Lernziel des Netzes |
| `make_match_value.py` | Baut die Tabelle `waddeln/match_value.json` |
| `waddeln/selfplay.py` | Matches spielen, Trainingsbeispiele sammeln, Bewertung |
| `train_dmc.py` | Training (parallele Self-Play-Prozesse + Lernprozess) |
| `export_onnx.py` | Checkpoint → ONNX für die App |
| `tests/test_parity.py` | Spielt Kotlin-Protokolle nach und vergleicht alles |

## Regeln Kotlin ↔ Python abgleichen

Nach **jeder** Regel- oder Bot-Änderung, auf dem PC:

```bash
./gradlew :rules-core:generateProtocols         # schreibt training/protocols/protocols.jsonl
cd training
.venv/Scripts/python -m pytest tests -q          # Windows; auf Linux: .venv/bin/python
```

Geprüft werden Austeilen, legale Aktionen aller Spieler in jedem Schritt, Stichgewinner,
Spielwert, Punkte, Hinlegen bei gespannt gegen gespannt, die Entscheidungen des Regel-Bots und
die Merkmalsvektoren fürs Netz.

## Einrichtung auf dem Raspberry Pi 5

Voraussetzungen: Raspberry Pi OS **64-bit**, aktive Kühlung (das Training lastet alle Kerne
stundenlang aus), 4 GB RAM reichen.

```bash
sudo apt install python3-venv tmux
git clone <repo> waddeln      # oder den Ordner training/ per scp/rsync kopieren
cd waddeln/training
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest tests -q   # Paritätstest (protocols/ vom PC mitkopieren, sonst wird er übersprungen)
```

## Lernziel: Siegchance statt Punkte

Das Netz lernt für jede Entscheidung, wie stark das laufende Spiel die **Chance des eigenen Teams
verändert, das Match zu gewinnen** – nicht die Punkte des Spiels. Sonst sieht z. B. „Gehen“ bei
7 Punkten des Gegners (er kommt auf 9) besser aus als „Bleiben und verlieren“ (10), obwohl er in
beiden Fällen gespannt ist. Die Siegchance je Spielstand steht in `waddeln/match_value.json`
(aus Partien des Regel-Bots gezählt); der Faktor `scale` macht die Werte im Schnitt so groß wie
Spielpunkte, damit die Temperaturen der Schwierigkeitsstufen in der App passen. Neu bauen (dauert
einige Minuten), z. B. mit einem fertig trainierten Netz statt des Regel-Bots:

```bash
.venv/bin/python make_match_value.py --matches 400000 --workers 16
.venv/bin/python make_match_value.py --model checkpoints/run1/best.pt --matches 100000 --workers 16
```

## Training

In `tmux` starten, damit es weiterläuft, wenn die SSH-Verbindung abbricht:

```bash
tmux new -s training
.venv/bin/python train_dmc.py --actors 1 --learner-threads 3 --hours 12 --out checkpoints/run1
# Strg+B loslassen, dann D zum Abkoppeln; später: tmux attach -t training
```

- Jeder Self-Play-Prozess spielt `--games 32` Partien gleichzeitig und wertet deren Entscheidungen
  in einem Netzaufruf aus – auf dem Pi 8× schneller als Partie für Partie (~3 900 Beispiele/s).
- Pi 5: `--actors 1 --learner-threads 3`. Laptop (i7, 20 Threads): `--actors 6 --learner-threads 6
  --batch 1024` (~7 500 Beispiele/s). Faustregel: Sind die Self-Play-Prozesse voll ausgelastet und
  der Lernprozess nicht, mehr `--actors`; umgekehrt mehr `--learner-threads`.
- **Vorzeitig beenden:** `touch checkpoints/run1/STOP` (oder Strg+C im tmux-Fenster) – das Training
  speichert dann `latest.pt`, bewertet ein letztes Mal und beendet sich sauber.
- Fortsetzen: `--resume checkpoints/run1/latest.pt --out checkpoints/run1` (Log wird angehängt).
- Alle `--eval-every` Beispiele (Standard 5 000 000) wird gegen den Regel-Bot getestet und ein
  Checkpoint `model_<N>k.pt` gespeichert; der beste landet in `best.pt`.
- Fortschritt: `checkpoints/run1/log.csv` (Loss, Siegquote gegen Regel-Bot, Punkte pro Spiel).
- Abgestürzte Self-Play-Prozesse werden automatisch neu gestartet.
- Weniger RAM: `--buffer 100000` (Standard 200 000 Beispiele ≈ 420 MB).

Richtwert vom ersten Lauf auf dem PC (`checkpoints/pc_run1`): nach ~2 Mio. Beispielen gewinnt das
Netz ~55 % der Matches gegen den Regel-Bot. Weitertrainieren auf dem Pi:

```bash
.venv/bin/python train_dmc.py --resume checkpoints/pc_run1/latest.pt --actors 1 --learner-threads 3 --hours 12 --out checkpoints/pi_run1
```

## Modell in die App bringen

```bash
.venv/bin/python export_onnx.py checkpoints/run1/best.pt waddeln.onnx
# waddeln.onnx nach app/src/main/assets/models/waddeln.onnx kopieren
```

Der Export prüft selbst, dass ONNX dieselben Werte liefert wie PyTorch. Fehlt das Modell in der
App, spielen die Bots automatisch regelbasiert.

**Bester Checkpoint:** Die Bewertung während des Trainings nutzt wenige Matches und schwankt.
Vor dem Export die Kandidaten genauer vergleichen:

```bash
.venv/bin/python evaluate.py checkpoints/run1/model_*.pt --matches 2000 --workers 4
```

**Schwierigkeitsstufen:** frühere Checkpoints (`model_<N>k.pt`) sind schwächer; zusätzlich steuert
`MlAgent(temperature = …)` in der App, wie oft ein Bot schlechtere Züge wählt (0 = stärkste Stufe).

## Tischgespräche

Was am Tisch gesagt wird, hören alle und es gehört zum Spielzustand (`GameState`/`Observation`,
in Python `State`/`Observation`): jeder Schub mit Spieler und Zeitpunkt, die Partner-Hinweise beim
Schub, „Schadder?“ samt Antwort und der Schlagwechsel. Bots reden überall gleich (`BotTalk.kt` ↔ `waddeln/talk.py`):

- Bevor ein Bot auf einen Schub antwortet, fragt er immer seinen Partner (Hinweis per Faustregel aus
  `PartnerHint.kt`).
- Will er im ersten Stich den Haubi anspielen, fragt er vorher „Schadder?“ und entscheidet mit der
  Antwort neu (bei „Ja“ ohne Haubi). Im Training zählt nur diese zweite Entscheidung.
- Ohne Haubi, aber mit mindestens 2 Trümpfen blufft er in 20 % der Fälle mit „Schadder?“
  (`Schadet.canBluff`, `BLUFF_CHANCE`). Hat der Partner den Haubi selbst, antwortet er „Ja“.
- Vor dem Schlag schlägt der Spieler nach dem Geber einen Schlagwechsel vor, wenn er kein Paar, aber
  mindestens 3 Karten einer Farbe hat (Kritten zählen nicht); der Geber nimmt mit einem Paar an.

Das Netz bekommt dazu 39 Merkmale (Zustand 478 → 517): letzter Hinweis je Platz, wer „Schadder?“
gefragt hat (hat den Haubi oder blufft) und die Antwort, wer beim Haubi-Anspiel nicht bedient hat (hat keine
Haubi-Farbe), Schübe je Platz und ob vor der ersten Karte. Seit Zustand 571 außerdem alles, was ein
Mensch am Tisch auch weiß: wie viele Kritten der Geber genommen hat, die Karte, die nur der Geber unter
dem Rest gesehen hat, „Was soll ich machen?“ (wer gefragt hat, letzte Antwort je Platz) und der
Schlagwechsel (vorgeschlagen, angenommen, abgelehnt).

Die Tischgespräche führen alle Bots nach festen Regeln (`BotTalk.kt` ↔ `waddeln/talk.py`); das Netz
entscheidet Schlag, Farbe, Karte, Schub, Bleiben und Gehen.

**Weitertrainieren statt neu:** Alte Checkpoints werden mit `migrate_checkpoint.py` erweitert; die
neuen Eingaben starten mit Gewicht 0, das Netz spielt also zunächst genau wie vorher.

```bash
.venv/bin/python migrate_checkpoint.py checkpoints/pi_run2/model_00189806k.pt \
    checkpoints/migrated/model_00189806k_v3.pt --old-state 478 --old-action 51
.venv/bin/python train_dmc.py --resume checkpoints/migrated/model_00189806k_v3.pt \
    --actors 1 --learner-threads 3 --hours 24 --out checkpoints/pi_run3
# Gegen das alte Netz vergleichen (alte Checkpoints spielen mit dem alten Eingabe-Layout weiter):
.venv/bin/python evaluate.py checkpoints/pi_run3/model_*.pt --vs checkpoints/pi_run2/model_00189806k.pt \
    --matches 2000 --workers 4
```

Gegen den Regel-Bot gewinnt das Netz schon ~96 %; Fortschritt zeigt erst der direkte Vergleich.

App und Server erkennen an der Eingabegröße des ONNX-Modells, ob es vor den Tischgesprächen trainiert
wurde, und füttern es dann mit dem alten Layout – das ausgelieferte Modell läuft also weiter, bis ein
neues exportiert ist.

## Wichtig bei Änderungen

Die Merkmals-Kodierung (`features.py` / `Encoding.kt`) und die Aktions-Codes sind Teil des
Modells: Ändert man sie, passen alte Checkpoints nicht mehr. Neue Merkmale daher nur **hinten an den
Zustandsblock anhängen** und alte Checkpoints migrieren (siehe oben); sonst neu trainieren.
