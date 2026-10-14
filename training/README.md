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

## Wichtig bei Änderungen

Die Merkmals-Kodierung (`features.py` / `Encoding.kt`) und die Aktions-Codes sind Teil des
Modells: Ändert man sie, passen alte Checkpoints nicht mehr – neu trainieren.
