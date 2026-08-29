# GHOSTS — DIY Looper

Eigene 4-Spur-Loopstation für Android. Kotlin + Jetpack Compose, kein Gradle-Wrapper
im Repo nötig - GitHub Actions installiert Gradle & Android SDK selbst.

## Features
- 4 unabhängige Loop-Tracks, kontinuierliches Streaming-Playback
- **Boss-Pedal-Style Overdub**: REC/OVERDUB ist ein Toggle - einmal drücken startet
  die Aufnahme, sie mischt sich live und fortlaufend in den Loop (über beliebig
  viele Durchläufe hinweg), nochmal drücken stoppt sie. Kein festes Zeitfenster.
- **½× / 1× / 2× Loop-Länge pro Track**: sobald die Masterlänge (Track 1) steht,
  kann jeder leere Track halb oder doppelt so lang aufgenommen werden - bleibt
  durch ganzzahlige Verhältnisse exakt synchron zu den anderen Tracks
- Undo (letzte Aufnahme-Session rückgängig), Clear pro Track
- **Echtes Pinch-Zoom**: alle Wellenform-Editoren (Trim, Edit, Latenz-Test) lassen sich
  jetzt mit zwei Fingern zoomen/verschieben wie in der Foto-App - ein Finger auf einem
  Handle bewegt genau das, ein Finger daneben verschiebt die Ansicht (Pan)
- **Sync-Balken**: sobald die Loop-Länge steht, zeigt eine 16-geteilte Leiste live die
  aktuelle Position im Loop-Zyklus (grau → füllt sich farbig von links nach rechts)
- **Einstellungen bleiben gespeichert**: Latenz, Low-Latency-Modus, Akzentfarbe und
  Sprache überstehen jetzt das Schließen der App (SharedPreferences)
- **Auto-Latenz-Test (echte akustische Messung)**: spielt mehrere kurze Klick-Bursts
  über den Lautsprecher ab und erkennt automatisch per Schwellwert-Analyse, wann sie
  im Mikrofon ankommen - genau die Technik, die Android selbst für seinen offiziellen
  Loopback-Latenztest verwendet. Kein Klatschen, kein Ziehen, kein menschlicher
  Reaktionsfehler in der Messung. Ergebnis bleibt gespeichert, bis du neu testest.
- **Export zentral im SETUP**: kein Export-Button mehr pro Track - stattdessen unten
  im SETUP-Panel einzeln pro Track oder als Mixdown aller Tracks exportierbar
- **Aufnahme-Indikatoren**: der aktive REC/OVERDUB-Button und der Sync-Balken wechseln
  während einer laufenden Aufnahme automatisch in eine Kontrastfarbe (Komplementärfarbe
  zur gewählten Akzentfarbe für frische Aufnahmen, eine andere Kontrastfarbe fürs
  Overdubben) und zurück, sobald sie fertig ist
- **Automatischer Stopp**: eine frische Aufnahme auf Track 2-4 läuft jetzt exakt so
  lange wie die eingestellte Länge (½×/1×/2×) und stoppt danach von selbst - kein
  Timing-Judgement mehr nötig. Ein fortlaufendes Overdub auf einem bereits belegten
  Track läuft weiterhin, bis du manuell stoppst
- **Sync-Balken reagiert jetzt korrekt auf Play/Stop Alle** und läuft während einer
  Aufnahme normal weiter (lief vorher fälschlich mit ein)
- Metronom stoppt automatisch nach der ersten (längenbestimmenden) Aufnahme
- Mute + Lautstärke-Regler pro Track
- Dezentes, einklappbares Metronom (40-220 BPM), akzentuierter erster Schlag
- **Metronom-Sync**: läuft das Metronom, wartet die erste Aufnahme automatisch
  auf den nächsten Takt-1-Schlag, bevor sie wirklich startet
- WAV-Export pro Track (Musik-Ordner der App)
- **Latenz-Offset per Slider** (±150ms) im SETUP-Panel
- **Auto-Latenz-Test**: 2 leise Vorzähl-Klicks, dann 4 laute Klicks während
  gleichzeitig aufgenommen wird. Du klatschst im Takt mit; danach zeigt eine
  zoombare Wellenform gestrichelte Linien an jedem der 4 Klicks - die gezogene
  Marker-Linie wird automatisch gegen den nächstgelegenen Click verrechnet und
  ergibt die Latenz in ms. Das Ergebnis bleibt gespeichert, bis du einen neuen
  Test startest oder manuell am Slider drehst.
- **Low-Latency-Modus**: VOICE_COMMUNICATION-Input + Low-Latency-Performance-Mode
- **Live-Akzentfarbe**: ein Hue-Slider im SETUP-Panel färbt sofort die komplette
  UI um (Buttons, Wellenformen, Marker, Icon) - kein Übernehmen-Klick nötig
- **Sprache**: Deutsch/Englisch umschaltbar im SETUP-Panel
- Dunkles UI im Teenage-Engineering/Nothing-Stil, Space Mono (Bold/Regular),
  simples Geist-Icon im Header

## Bedienung
1. Optional im **SETUP**-Panel: Latenz manuell einstellen ODER Auto-Test starten,
   Metronom-Sync aktivieren, Akzentfarbe/Sprache anpassen.
2. Track 1 → "REC" tippen, spielen/singen, "STOP" tippen (bei aktivem Metronom
   startet die Aufnahme automatisch erst auf den nächsten Takt-1-Schlag).
3. Trim-Editor öffnet sich: Start-/End-Handle setzen (mit Zoom für Feinjustage),
   "ÜBERNEHMEN". Legt die endgültige Loop-Länge fest.
4. Track 2-4 → optional ½×/1×/2× wählen, dann "REC" tippen. Startet automatisch
   exakt auf dem nächsten Loop-Anfang.
5. Nochmal auf "OVERDUB" drücken auf einem belegten Track → Aufnahme läuft
   fortlaufend über beliebig viele Loop-Durchgänge, bis erneut gedrückt wird.
6. "EDIT" auf einem belegten Track → Start/Ende nachträglich mit Zoom feinjustieren
   (Loop-Länge bleibt dabei unverändert, Sync bleibt erhalten).
7. "▶ ALLE / ■ ALLE" steuert die Gesamt-Wiedergabe, "RESET" löscht alles.

## Bekannte Grenzen (DIY-Version)
- Kein automatisches Signieren für eine Release-Version - die Debug-APK reicht
  zum Selbst-Installieren aber völlig aus.
- Loop-Synchronisation ist software-basiert; bei sehr kurzen Loops (<1s) kann
  es minimal jittern.
