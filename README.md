# GHOSTS — DIY Looper

Eigene 4-Spur-Loopstation für Android. Kotlin + Jetpack Compose, kein Gradle-Wrapper
im Repo nötig - GitHub Actions installiert Gradle & Android SDK selbst.

## Features
- 4 unabhängige Loop-Tracks, sample-synchron
- Overdub (auf einen Track draufsingen/-spielen, ohne den Loop zu verlieren)
- Undo (letzte Aufnahme rückgängig), Clear pro Track
- Mute + Lautstärke-Regler pro Track
- Eingebautes Metronom (40-220 BPM)
- WAV-Export pro Track (Musik-Ordner der App)
- **Latenz-Offset per Slider** (±150ms) im SETUP-Panel
- **Auto-Latenz-Test**: 2 leise Vorzähl-Klicks (nicht gewertet, geben dir den Takt),
  dann 4 laute Klicks während gleichzeitig aufgenommen wird. Du klatschst/tappst im
  Takt mit, danach zeigt eine Wellenform gestrichelte Linien an jedem der 4 lauten
  Klicks; die gezogene Marker-Linie wird automatisch gegen den nächstgelegenen
  Click verrechnet → Latenz in ms wird direkt übernommen und lässt sich mit dem
  Slider weiter feinjustieren
- **Low-Latency-Modus**: VOICE_COMMUNICATION-Input + Low-Latency-Performance-Mode
- **Start/End-Trim für Track 1**: nach der ersten Aufnahme erscheint eine
  Wellenform mit zwei ziehbaren Handles, um Loop-Start und -Ende exakt zu setzen
- Dunkles UI im Teenage-Engineering/Nothing-Stil, Space Mono (Bold/Regular),
  ein simples Geist-Icon im Header

## Bedienung
1. Optional im **SETUP**-Panel: Latenz manuell einstellen ODER "AUTO-LATENZ-TEST"
   starten (Click-Track abspielen, mitklatschen, Marker auf den Transienten ziehen,
   ÜBERNEHMEN).
2. Track 1 → "REC" tippen, spielen/singen, "STOP" tippen.
3. Trim-Editor öffnet sich: Start-/End-Handle setzen, "ÜBERNEHMEN". Legt die
   endgültige Loop-Länge fest.
4. Track 2-4 → "REC" tippen. Die App wartet automatisch auf den nächsten
   Loop-Start, bevor sie zu nehmen beginnt - alle Tracks bleiben im Takt.
5. Nochmal "REC"/"OVERDUB" auf einen belegten Track → mischt eine neue Schicht dazu.
6. "▶ ALLE / ■ ALLE" steuert die Gesamt-Wiedergabe, "RESET" löscht alles.

## APK bauen (automatisch über GitHub Actions)
1. Neues Repo auf GitHub anlegen (oder dein bestehendes Repo nehmen).
2. Diesen kompletten Ordner in das Repo pushen:
   ```
   git init
   git add .
   git commit -m "LoopStation DIY"
   git branch -M main
   git remote add origin <DEINE-REPO-URL>
   git push -u origin main
   ```
3. Im GitHub-Repo auf den Tab **Actions** gehen - der Workflow "Build APK" startet
   automatisch nach dem Push.
4. Nach ca. 2-4 Minuten: im abgeschlossenen Workflow-Run ganz unten bei
   **Artifacts** liegt `LoopStationDIY-debug-apk` zum Download bereit (als .zip,
   die APK ist darin).
5. APK aufs Handy laden, Installation aus unbekannten Quellen erlauben, installieren.

## Bekannte Grenzen (DIY-Version)
- Kein automatisches Signieren für eine Release-Version - die Debug-APK reicht
  aber zum Selbst-Installieren völlig aus.
- Loop-Synchronisation ist software-basiert (kein dedizierter Audio-DSP-Chip wie
  bei einem Hardware-Pedal) - bei sehr kurzen Loops (<1s) kann es minimal jittern.
- Kein eigenes App-Icon hinterlegt (nutzt das Standard-Icon).
