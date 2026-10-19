# Waddeln

Android-App für das fränkische Kartenspiel **Waddeln**: 4 Spieler in 2 Teams, gespielt mit dem
fränkischen Blatt. Allein gegen drei KI-Gegner (komplett offline) oder online mit bis zu vier
Mitspielern in privaten Räumen – freie Plätze übernehmen Bots.

*An Android app for Waddeln, a traditional card game from Franconia (Germany). Play offline
against three AI opponents or online with friends. The app itself is in German.*

## Funktionen

- **Offline-Spiel**: 1 Mensch + 3 KI-Gegner, ohne Internet. Drei Schwierigkeitsstufen.
- **Online-Modus**: private Räume mit Raum-Code, Platzwahl, Bots für freie Plätze, Wiederverbinden
  nach Verbindungsabbruch. Keine Accounts – nur ein Spitzname.
- **KI-Gegner** per Deep Monte-Carlo (Self-Play, wie DouZero), trainiert auf einem Raspberry Pi 5
  und als ONNX-Modell in die App eingebaut.

Die Spielregeln stehen in [`WADDELN_RULES.md`](WADDELN_RULES.md).

## Aufbau

| Modul | Inhalt |
|---|---|
| `rules-core/` | Spiellogik als reine Kotlin/JVM-Bibliothek: Zustandsautomat, legale Aktionen, Stiche, Schub, Punkte; regelbasierter Bot und Merkmale fürs Netz |
| `protocol/` | Nachrichten zwischen App und Server (kotlinx.serialization) |
| `app/` | Android-App (Jetpack Compose), Offline-Spiel, KI-Inferenz mit ONNX Runtime |
| `server/` | Spielserver (Ktor, WebSocket), läuft in Docker |
| `training/` | Python-Nachbau der Regeln und KI-Training mit PyTorch – siehe [`training/README.md`](training/README.md) |
| `design/` | Kartenbilder und Skripte, die daraus die App-Grafiken und das Launcher-Icon erzeugen |

Jeder Spieler – Mensch, Bot oder Netz – bekommt nur eine Beobachtung dessen, was er wissen darf;
der Server hält den echten Spielzustand.

## Bauen und testen

Voraussetzungen: JDK 21 (z. B. das von Android Studio) und das Android SDK.

```bash
./gradlew test                    # alle Unit-Tests (Regeln, Protokoll, Server, App)
./gradlew :app:assembleDebug      # APK unter app/build/outputs/apk/debug/
```

Kotlin- und Python-Regeln müssen identisch sein; der Abgleich über zufällig erzeugte Partien ist in
[`training/README.md`](training/README.md) beschrieben.

## Eigenen Server betreiben

```bash
./gradlew :server:installDist
cd server && cp -r build/install . && docker compose up -d --build
```

Der Server lauscht auf Port 8765 (`/ws` für WebSocket, `/health` für einen Statuscheck) und ist
nur lokal erreichbar; nach außen geht es z. B. über einen Cloudflare Tunnel. `server/deploy.sh`
baut und startet ihn per SSH auf einem anderen Rechner. Die Server-Adresse lässt sich in der App
unter *Einstellungen* ändern.

## Lizenz

Der Code steht unter der [GNU General Public License v3.0](LICENSE) oder (nach deiner Wahl) jeder späteren Version.

## Unterstützen

Gefällt dir die App? Über einen Kaffee freue ich mich:

<a href="https://www.buymeacoffee.com/eis94" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>
