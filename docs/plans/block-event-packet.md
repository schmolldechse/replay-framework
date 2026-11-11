# Implementierungsplan: `block_event` (`0x07`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Umfang

Das PLAY-Clientbound-Paket `minecraft:block_event` mit der dezimalen ID `7`
beziehungsweise `0x07` wird in beiden Paper-Adaptern als explizite,
versionslokale Paketdefinition umgesetzt.

Die Implementierung soll:

1. das vollständige native Wire-Format beider angehefteten Paper-Versionen
   erhalten,
2. alle möglichen Werte für Action ID und Action Parameter verlustfrei
   verarbeiten,
3. bekannte und unbekannte Blocktypen ohne künstliche Allowlist unterstützen,
4. die zeitliche Reihenfolge zu Block- und Chunk-Updates bewahren,
5. das Paket während normaler Wiedergabe senden, aber nicht aus Checkpoints
   rekonstruieren,
6. seine exakte Blockposition für räumlich begrenzte Recordings bereitstellen,
7. den finalen PacketEvents-Wire-Capture und den bestehenden Paper-
   ChannelHandler-Pfad unverändert nutzen und
8. keinen unvollständigen Bukkit-/Paper-Event-Fallback einführen.

Alte Recordings oder das entfernte Paketsystem werden gemäß der bereits
getroffenen Projektentscheidung nicht migriert.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur

Explizite PLAY-Paketdefinitionen liegen versionslokal unter
`packet.definition.<domain>`. Der jeweilige Versionskatalog vereinigt die
Domain-Listen und sortiert sie numerisch nach Paket-ID.

`block_event` gehört in die Domain `world`:

- es ist durch genau eine absolute Blockposition räumlich verankert,
- seine Bedeutung wird vom Blockzustand an dieser Position bestimmt und
- es trägt zwar überwiegend Animationen und Trigger, aber keinen eigenständigen
  Entity-, Player-, Connection- oder globalen Effektvertrag.

Die synchrone Benennung beider Versionen lautet:

```text
Id007BlockEventPacketDefinition
```

Vorgesehene Ablage:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/world/
        └── Id007BlockEventPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/world/
        └── Id007BlockEventPacketDefinition.java
```

Die vorhandenen `WorldPacketDefinitions` enthalten derzeit ID `0x04` und
`0x06`. ID `0x07` wird dort ergänzt; die Katalogstruktur selbst muss nicht
verändert werden.

### 2.2 Capture-Pfad und Paper-/PacketEvents-Grenze

Der bestehende `Paper26_*CaptureBridge` beobachtet ausgehende Pakete über
PacketEvents auf `MONITOR`-Ebene und kopiert die Payload erst in einer
Post-Task. Dadurch wird der tatsächlich an den Empfänger gehende Buffer nach
vorherigen PacketEvents-Rewrites aufgezeichnet.

Für `block_event` ist dieser Pfad vollständig:

- alle vier Wire-Felder bleiben erhalten,
- plugin-erzeugte Pakete werden ebenso erfasst wie Vanilla-Pakete,
- empfängerabhängige Änderungen bleiben sichtbar,
- Reihenfolge und Capture-Zeitpunkt werden über `Paper26_*CaptureOrder`
  vergeben und
- der PacketEvents-Wrapper muss nicht als zweites Datenmodell eingeführt
  werden.

`PaperConnectionAccessor.packetPosition(...)` erkennt
`ClientboundBlockEventPacket` aktuell noch nicht. Ohne Ergänzung wäre ein
eindeutig positionsgebundenes Paket im `CaptureContext` räumlich unbekannt.
Beide Versionen benötigen daher einen expliziten Zugriff auf `getPos()`.

### 2.3 Playback- und Checkpoint-Pfad

Die vorhandene Playback-Pipeline arbeitet registry-gesteuert:

1. gespeicherte Bytes werden über den verbindungsgebundenen nativen Codec
   decodiert,
2. die Paketdefinition liest und validiert den semantischen Wert,
3. ein neues natives Paket wird aufgebaut,
4. der Codec prüft Phase, Richtung und Paket-ID erneut und
5. der Replay-Writer injiziert die finalen Bytes hinter Papers Encoder.

`block_event` besitzt keine Replay-Entity-ID, UUID oder andere
sitzungsgebundene Identität. Es benötigt daher keine Identity-Map.

Das Paket ist ein geordneter Trigger und kein vollständiger Snapshot des
betroffenen Blocks. Bei einem Seek muss der stabile Zustand aus Chunk-,
Block- und Block-Entity-Daten entstehen. Ein historisches `block_event` nach
dem Checkpoint nachzuspielen würde eine alte Animation beziehungsweise Aktion
neu starten und könnte bei Pistons sogar kurzzeitig falsche clientseitige
Zwischenzustände erzeugen.

### 2.4 Reset-Verhalten

Die bekannten Aktionen sind zeitlich begrenzt oder werden vom stabilen
Weltzustand überholt:

- Noten, Glocken, End-Gateway-Strahl, Decorated-Pot-Wobble und
  Potent-Sulfur-Eruption laufen clientseitig aus.
- Piston-Zwischenzustände werden durch die nachfolgenden Blockupdates
  abgeschlossen.
- Container-Lidanimationen gehören zu den geladenen Block Entities.

Der vorhandene View-Reset entfernt die Replay-Chunks und damit auch deren
Block Entities und animationsbezogenen Clientzustand. Es existiert kein
allgemeines inverses `block_event`, mit dem jede Aktion sicher aufgehoben
werden könnte. Ein neuer paketbezogener Reset-Tracker ist daher weder nötig
noch semantisch korrekt.

## 3. Verifizierter nativer Protokollvertrag

Die lokal angehefteten Paper-Dev-Bundles 26.2 und 26.3 enthalten denselben
öffentlichen Vertrag:

```java
ClientboundBlockEventPacket(
        BlockPos pos,
        Block block,
        int actionId,
        int actionParameter)
```

Auslesbare Felder:

```java
BlockPos getPos()
int getB0()
int getB1()
Block getBlock()
```

### 3.1 Wire-Reihenfolge

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | Position | gepackte Blockposition | `BlockPos` | Ort der Aktion |
| 2 | Action ID | unsigned byte | `int` | blocktypspezifischer Aktionsschlüssel |
| 3 | Action Parameter | unsigned byte | `int` | blocktypspezifischer Parameter |
| 4 | Block Type | Registry-VarInt | `Block` | Eintrag aus `minecraft:block`, kein Blockzustand |

Die gepackte Position besitzt die kanonischen Wire-Grenzen:

- X/Z: `-33_554_432` bis `33_554_431`,
- Y: `-2_048` bis `2_047`.

Der native Konstruktor speichert beide Aktionswerte als beliebige `int`-Werte.
Der Encoder schreibt jedoch jeweils nur das niederwertige Byte, während der
Decoder ausdrücklich `readUnsignedByte()` verwendet. Ein aus einer
Recording-Payload decodierter Wert liegt deshalb immer in `0..255`.

Direkt im Speicher konstruierte Werte wie `-1`, `256`, `Integer.MIN_VALUE`
oder `Integer.MAX_VALUE` müssen beim Lesen genauso kanonisiert werden, wie sie
auf dem Draht erscheinen würden. Die Definition darf Konstruktorwerte nicht
anders behandeln als einen Encode-/Decode-Roundtrip.

### 3.2 Tatsächliche Client-Auswertung

Der Client-Handler ruft auf:

```text
level.blockEvent(position, transmittedBlock, actionId, actionParameter)
```

Die aktuelle `Level.blockEvent(...)`-Implementierung ignoriert den Parameter
`transmittedBlock` und führt stattdessen aus:

```text
getBlockState(position).triggerEvent(level, position, actionId, actionParameter)
```

Damit bestätigt der Clientcode die Protokolldokumentation:

- Entscheidend ist der beim Client bereits vorhandene Blockzustand an der
  Position.
- Der übertragene Registry-Block wählt die Aktion nicht aus.
- Ein nicht passender oder unbekannter Registry-Block darf trotzdem nicht aus
  dem Paket entfernt oder anhand des Weltzustands ersetzt werden; er ist Teil
  des aufgezeichneten Wire-Vertrags und wird unverändert erhalten.
- Die Reihenfolge zu Chunk- und Blockupdates ist semantisch relevant.

### 3.3 Dokumentierte und in den Ziel-Builds vorhandene Aktionen

Die Minecraft-Protokolldokumentation beschreibt die folgenden klassischen
Blockaktionen. Die lokalen 26.2-/26.3-Artefakte bestätigen dieselben
`triggerEvent`-Pfade und ergänzen die in diesen Versionen vorhandene
Potent-Sulfur-Aktion.

| Blockfamilie | Action ID | Action Parameter | Wirkung |
|---|---:|---|---|
| Note Block | üblicherweise `0` | üblicherweise `0` | Instrument und Ton werden aus dem aktuellen Blockzustand gelesen; die Bytes werden nicht benötigt |
| Piston / Sticky Piston | `0`, `1`, `2` | Richtungswert | Ausfahren, Einfahren oder Abbruch einer laufenden Extension; der Parameter beeinflusst insbesondere Retraktionen |
| Chest-Familie | `1` | Anzahl offener Betrachter | Lid öffnen beziehungsweise schließen; dies umfasst die passenden Chest-Block-Entity-Varianten der Zielversion |
| Spawner | `1` | ignoriert | clientseitige Spawnverzögerung zurücksetzen |
| End Gateway | `1` | ignoriert | Teleport-/Beam-Cooldown starten |
| Shulker Box | `1` | typischerweise `0` oder `1` | Schließ- oder Öffnungsanimation steuern |
| Bell | `1` | Richtung | Ringanimation und Resonanzablauf starten; der Ton wird separat übertragen |
| Decorated Pot | `1` | `0` oder `1` | positives oder negatives Wobble starten |
| Potent Sulfur | Werte werden vom Handler nicht ausgewertet | Werte werden vom Handler nicht ausgewertet | clientseitigen Eruptionszeitpunkt setzen |

Weitere Blockzustände delegieren entweder an einen Block-Entity-Handler oder
ignorieren das Ereignis. Unbekannte Action IDs, Parameterwerte und
Registry-Blöcke sind auf dem Wire trotzdem gültige Byte-/Registrywerte.

Diese Tabelle dient der Begründung und den gezielten Beispielen in Tests. Sie
wird **nicht** als Validierungs-Allowlist in Produktionscode abgebildet. Das
ist notwendig, weil:

- beide Parameter vollständig blocktypspezifisch sind,
- Plugins absichtlich ungewöhnliche Werte senden können,
- Vanilla-Handler Werte teilweise maskieren oder ignorieren,
- neue Blocktypen zusätzliche Semantik erhalten können und
- der Replay-Adapter die tatsächlich gesendete Nachricht erhalten soll, nicht
  nur heute bekannte Vanilla-Kombinationen.

## 4. Klassifikation und Policy

Die Definition erhält:

| Eigenschaft | Wert |
|---|---|
| Disposition | `EPHEMERAL` |
| Standard-Capture | `true` |
| Replay | `true` |
| Checkpoint | `false` |
| Playback-Scope | `SAFE` |

Begründung:

- Die Nachricht ist während zeitlicher Live-Wiedergabe sichtbar und relevant.
- Sie ist ein Ereignis beziehungsweise Animationsimpuls, kein eigenständiger
  dauerhafter Snapshot.
- Eine Checkpoint-Rekonstruktion darf vergangene Aktionen nicht neu auslösen.
- Position, Aktionsbytes und Block-Registrywert enthalten keine
  sitzungsgebundenen Identitäten.
- Alle Felder können unverändert in derselben angehefteten Protokollversion
  wieder aufgebaut werden.

`PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE)` bildet
diesen Vertrag direkt ab.

## 5. Geplante Implementierung

### 5.1 Versionslokale Paketdefinitionen

Beide Klassen erweitern:

```text
RebuildingPacketDefinition<
    Paper26_*ReplayPacketRewriter,
    ClientboundBlockEventPacket,
    Value>
```

Das interne unveränderliche Wertmodell enthält:

```java
record Value(
        BlockPos position,
        int actionId,
        int actionParameter,
        Block block) {}
```

Verarbeitung:

1. `read(...)` übernimmt Position und Block und kanonisiert beide
   Aktionswerte mit `& 0xFF`.
2. `rewrite(...)` gibt den Wert ohne Identity-Änderung als `SEND` zurück.
3. `rebuild(...)` erzeugt ein neues `ClientboundBlockEventPacket` in der
   nativen Konstruktorreihenfolge Position, Block, Action ID, Parameter.
4. `Value` lehnt `null` für Position und Block ab.
5. `Value` akzeptiert für beide Aktionswerte ausschließlich die kanonischen
   unsigned-byte-Grenzen `0..255`.

Ein Kommentar an der Definition erläutert ausdrücklich, dass Action ID und
Action Parameter nur zusammen mit dem Blockzustand an der Position Bedeutung
besitzen. Dadurch wird vermieden, dass spätere Änderungen die Bytes
fälschlicherweise global als Enum interpretieren.

### 5.2 Aggregatoren und Registry

`WorldPacketDefinitions` beider Versionen wird um ID `0x07` ergänzt. Die
bereits vorhandene Gesamtkatalog-Sortierung stellt die Reihenfolge `0x00` bis
`0x07` sicher.

`PacketCoverageTest` wird synchron erweitert:

- Katalog enthält ID `0x07` an der erwarteten Stelle,
- Disposition ist `EPHEMERAL`,
- Capture und Live-Replay sind aktiv,
- Checkpoint-Relevanz ist aus,
- Scope ist `SAFE`,
- alle späteren, noch nicht definierten PLAY-Pakete bleiben `UNSUPPORTED`.

### 5.3 Räumlicher Capture-Kontext

`PaperConnectionAccessor` erhält je Version:

- den Import von `ClientboundBlockEventPacket` und
- einen einzelnen `packetPosition(...)`-Fall, der `getPos()` über die
  vorhandene `toApiPosition(...)`-Konvertierung zurückgibt.

Es werden keine Heuristiken aus den Action-Bytes oder dem Blocktyp abgeleitet.
Das Paket beschreibt genau einen Ort, daher ist diese Position vollständig
und für regionenbegrenztes Capture geeignet.

### 5.4 Playback, ChannelHandler und Reset

Es ist keine neue Playback- oder ChannelHandler-Klasse vorgesehen:

- `Paper26_*PlaybackBridge.shouldReplay` lässt die Definition im Modus `LIVE`
  zu und verwirft sie bei `RECONSTRUCTION`.
- Der vorhandene Outbound-Gate unterdrückt während Replay-Isolation die
  registry-basiert klassifizierten Live-Welt-Pakete des Beobachters.
- Der Replay-Writer sendet das neu aufgebaute Paket über den bestehenden
  validierten Pfad.
- Der bestehende Chunk-Reset entfernt betroffenen temporären Block-Entity-
  Zustand; es wird kein künstliches inverses Ereignis erzeugt.

Falls ein Test eine Abweichung dieser generischen Verträge zeigt, wird sie vor
einer zusätzlichen Produktionsänderung dokumentiert und neu bewertet.

## 6. Event-Fallback-Entscheidung

Die bekannten Aktionen entstehen in unterschiedlichen Serverpfaden, darunter
beispielsweise Note-, Piston-, Container-, Bell- und Block-Entity-Logik.
Einzelne Bukkit-/Paper-Events können Teil dieser Pfade sein, bilden das
Netzwerkpaket aber nicht vollständig ab.

Ein Event-Fallback würde mindestens folgende Informationen beziehungsweise
Fälle verlieren:

- beliebige Action IDs und Parameterwerte,
- direkt von Plugins gesendete native oder PacketEvents-Pakete,
- Empfängerauswahl und empfängerbezogene Änderungen,
- Pakete für Blocktypen ohne passendes Bukkit-Event,
- Änderungen vorheriger Channel-Handler,
- exakte Wire-Reihenfolge und
- den tatsächlich übertragenen Block-Registrywert.

Mehrere Events könnten außerdem dasselbe ausgehende Paket indirekt
repräsentieren und damit Duplikate erzeugen. Da der finale Wire-Capture
verfügbar ist, wird kein Event als primärer oder sekundärer Capture-Pfad
registriert. Events bleiben lediglich eine theoretische Notfallebene für eine
Umgebung ohne vertrauenswürdigen Paketpfad; diese Voraussetzung liegt hier
nicht vor.

## 7. Adapter-Dokumentationen

Die vollständigen Paket-Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig erhalten. Nur die Zeile für `minecraft:block_event` wird
inhaltlich präzisiert:

- Beschreibung und Relevanz: positionsgebundener, blocktypspezifischer
  Aktions-/Animationsimpuls mit zwei unsigned Parametern,
- Playback-Policy: Senden während normaler Wiedergabe,
- Checkpoint-Policy: nicht aufnehmen beziehungsweise nicht rekonstruieren.

Die Dokumente erhalten weiterhin **keine** Aussage über Implementierungsstand,
Fortschritt oder Tests einzelner Pakete. Alle anderen Paketzeilen und die
neutrale Adapterarchitektur bleiben unverändert.

## 8. Teststrategie

### 8.1 Definitionstests je Version

Je Version entsteht ein
`Id007BlockEventPacketDefinitionTest` mit mindestens folgenden Fällen:

1. ID `7`/`0x07`, exakter Identifier und nativer Pakettyp.
2. Vollständige Klassifikation `EPHEMERAL`, Capture an, Replay an,
   Checkpoint aus und `SAFE`.
3. Read/Rewrite/Rebuild mit repräsentativen dokumentierten Aktionen für Note
   Block, Piston, Chest-Familie, Spawner, End Gateway, Shulker Box, Bell,
   Decorated Pot und Potent Sulfur, soweit die Registrytypen der Zielversion
   dies direkt abbilden.
4. Alle Action-ID-Werte `0..255` bleiben exakt erhalten.
5. Alle Action-Parameter-Werte `0..255` bleiben exakt erhalten.
6. Direkt konstruierte native Werte außerhalb des Bytebereichs werden auf ihr
   tatsächliches niederwertiges Wire-Byte kanonisiert.
7. Sämtliche registrierten `Block`-Werte werden vom nativen Registry-Codec
   akzeptiert und unverändert wieder aufgebaut.
8. Gepackte Blockpositionsgrenzen für X, Y und Z.
9. Ablehnung von `null`-Position, `null`-Block und nicht kanonischen
   `Value`-Aktionswerten.
10. Ablehnung einer falschen decodierten Java-Klasse.
11. Vollständiger `handlePlayback`- und validierter Encode-Pfad.

### 8.2 Native Wire-Tests

Die native Protokollprüfung bestätigt je Version:

- Paket-ID `0x07`,
- exakte Feldreihenfolge Position, Action ID, Action Parameter, Block,
- unsigned Decode beider Bytes,
- Registry-ID des übertragenen Blocks,
- vollständigen Verbrauch einer gültigen Payload und
- Roundtrip ohne Normalisierung gültiger Wire-Werte.

Negative Fälle umfassen mindestens:

- fehlende oder abgeschnittene Blockposition,
- fehlende Action ID,
- fehlenden Action Parameter,
- fehlende, überlange oder unbekannte Block-Registry-ID und
- gegebenenfalls nachlaufende Bytes über den vorhandenen
  verbindungsgebundenen Codecvertrag.

### 8.3 Capture- und Playback-Tests

`Paper26_*CaptureBridgeTest` prüft die exakte Umwandlung einer extremen
`BlockPos` in `CaptureContext.position`. Action-Werte und Blocktyp dürfen die
räumliche Zuordnung nicht verändern.

`Paper26_*PlaybackBridgeTest` prüft:

- ID `0x07` wird im Modus `LIVE` gesendet,
- ID `0x07` wird bei `RECONSTRUCTION` verworfen und
- die Regeln der bereits implementierten dauerhaften, flüchtigen und
  Control-Pakete bleiben unverändert.

Der bestehende PacketEvents-Capture-Test bleibt die Absicherung dafür, dass
der finale, möglicherweise umgeschriebene Buffer maßgeblich ist. Eine zweite
paketspezifische Listener-Implementierung wird nicht getestet, weil sie nicht
eingeführt wird.

### 8.4 Registry und Dokumentation

`PacketCoverageTest` prüft den expliziten Katalogvertrag bis einschließlich
ID `0x07` in beiden Versionen.

Die Dokumentationsänderung wird darauf geprüft, dass:

- alle Paketzeilen beider Versionen erhalten bleiben,
- die `block_event`-Zeile Beschreibung, Playback und Checkpoint enthält und
- keine Status- oder Testaussage ergänzt wurde.

### 8.5 Auszuführende Prüfungen

Nach ausdrücklicher Genehmigung und Implementierung:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test `
  :replay-adapter:paper-26_2:test `
  :replay-adapter:paper-26_3:test --console=plain
```

Zusätzlich:

- `git diff --check`,
- Synchronitätsvergleich der beiden versionslokalen Definitionen und Tests,
- Kontrolle aller geänderten Dateien gegen den Aufgabenumfang,
- Prüfung, dass `.idea/` unverändert und unversioniert bleibt,
- Prüfung des aktiven Branchs vor jedem Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/world/Id007BlockEventPacketDefinition.java`
- `packet/definition/world/WorldPacketDefinitions.java`
- `capture/PaperConnectionAccessor.java`

Tests je Version:

- `packet/definition/world/Id007BlockEventPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `capture/Paper26_2CaptureBridgeTest.java` beziehungsweise
  `capture/Paper26_3CaptureBridgeTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser Implementierungsplan

Nicht vorgesehen sind Änderungen an:

- Core-Format oder Recording-Migration,
- PacketEvents-Listener oder PacketEvents-Wrappern,
- Paper-/Bukkit-Event-Listenern,
- Identity-Map,
- Checkpoint-Encodern oder Snapshot-Providern,
- View-Resetter,
- allgemeinen ChannelHandlern oder
- anderen Paketdefinitionen.

## 10. Risiken und Gegenmaßnahmen

### Blocktypspezifische Semantik

Risiko: Eine globale Interpretation von Action ID oder Parameter würde
gültige Werte anderer beziehungsweise zukünftiger Blocktypen ablehnen.

Gegenmaßnahme: Beide Bytes bleiben opak und werden nur als unsigned Werte
kanonisiert. Dokumentierte Kombinationen sind Beispiele, keine Allowlist.

### Nicht kanonische native Konstruktorwerte

Risiko: Ein direkt konstruiertes Paket kann `int`-Werte außerhalb `0..255`
enthalten, obwohl der Wire-Codec nur ein Byte überträgt.

Gegenmaßnahme: `read(...)` kanonisiert beide Werte mit `& 0xFF`; das Value-
Modell erlaubt anschließend ausschließlich den echten Wirebereich.

### Abhängigkeit vom Client-Blockzustand

Risiko: Ein Event vor dem zugehörigen Chunk- oder Blockzustand wird ignoriert
oder gegen den falschen Block ausgewertet.

Gegenmaßnahme: Live-Frames behalten ihre Capture-Reihenfolge. Das Paket wird
nicht separat synthetisiert und nicht in Checkpoints verschoben.

### Registry-Kompatibilität

Risiko: Der Blocktyp ist eine versionsgebundene Registry-ID.

Gegenmaßnahme: Decode und Encode verwenden ausschließlich den nativen
`RegistryFriendlyByteBuf`-Codec der angehefteten Version. Die bestehende
Adapter- und Registry-Fingerprint-Prüfung bleibt die Aktivierungsgrenze.

### Event-basierte Doppel- oder Untererfassung

Risiko: Ein zusätzlicher Event-Fallback könnte Pakete doppelt erzeugen und
plugin- oder empfängerbezogene Pakete trotzdem übersehen.

Gegenmaßnahme: Ausschließlich der vorhandene finale Wire-Pfad wird verwendet.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- Blockaktionen und zugehörige Animationen werden in normaler Wiedergabe
  sichtbar.
- Ungewöhnliche Pluginwerte und alle 256 Werte beider Aktionsbytes bleiben
  erhalten.
- Räumliche Recording-Scopes können das Paket über seine echte Position
  filtern.
- Der Registry-Fingerprint ändert sich durch die neue explizite Definition;
  dies ist ohne Altaufnahme-Migration beabsichtigt.
- Beide Paper-Versionen behalten synchrone Namen, Struktur und Policies.

### Bewusste Grenzen

- Der Adapter versucht nicht, die semantische Wirkung eines unbekannten
  Blocktyps serverseitig nachzubilden.
- Ein Seek startet keine vor dem Zielzeitpunkt liegende Animation erneut.
- Der übertragene Blocktyp wird trotz aktueller Vanilla-Nichtverwendung nicht
  korrigiert oder aus dem Weltzustand abgeleitet.
- Blockzustand, Block-Entity-Inhalt und separat übertragene Sounds bleiben
  Verantwortung ihrer jeweiligen Pakete.
- Andere Paket-IDs und nicht unmittelbar erforderliche Refactorings bleiben
  außerhalb des Umfangs.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- dem dekompilierten nativen `ClientboundBlockEventPacket` beider Builds,
- den lokalen `triggerEvent`-Implementierungen der relevanten Block- und
  Block-Entity-Klassen,
- dem Minecraft-Protokollinventar für ID, Felder und Block-Registryvertrag und
- der vom Auftrag referenzierten Block-Actions-Dokumentation.

Relevante externe Quellen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Block_Action>
- <https://minecraft.wiki/w/Java_Edition_protocol/Block_actions>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundBlockEventPacket.html>
- <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>
- <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/world/level/level/>

Die Online-Dokumentation beschreibt die öffentliche Semantik. Maßgeblich für
Klassenform, Codec und die versionsspezifischen Aktionen bleiben die lokal
angehefteten Paper-Artefakte und die nativen Protokolltests.

## 13. Versionsverwaltungs- und Freigabepunkt

Vor der Implementierung wird ohne Branchwechsel geprüft, ob `master` und
`origin/master` synchron sind; ein Fetch-Problem oder eine Abweichung wird
transparent gemeldet und überschreibt keine lokalen Änderungen. Wegen der
ausdrücklichen Vorgabe wird kein separater Aufgabenbranch erstellt und
ausschließlich auf `codex/refactor-packet-capture-rewrite` gearbeitet.

Bis zur ausdrücklichen Genehmigung werden keine Produktions- oder Testklassen
verändert. Nach der Freigabe wird der oben beschriebene Umfang implementiert,
validiert, in den neutralen vollständigen Adapter-Dokumentationen nachgeführt
und mit einem englischen semantischen Commit bereitgestellt.
