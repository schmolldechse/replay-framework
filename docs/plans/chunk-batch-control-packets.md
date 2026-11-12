# Implementierungsplan: `chunk_batch_finished` (`0x0B`) und `chunk_batch_start` (`0x0C`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Umfang

Die PLAY-Clientbound-Pakete

- `minecraft:chunk_batch_finished` mit ID `11` beziehungsweise `0x0B` und
- `minecraft:chunk_batch_start` mit ID `12` beziehungsweise `0x0C`

werden in beiden Paper-Adaptern als explizite, versionslokale
Paketdefinitionen umgesetzt.

Die Pakete bilden gemeinsam die clientbound Hälfte der Chunk-Batch-
Flusssteuerung. Ihre Implementierung soll:

1. den vollständigen nativen Vertrag beider Pakete und beider Paper-
   Versionen dokumentieren und testen,
2. `chunk_batch_start` als payload-freien Beginn einer clientseitigen
   Durchsatzmessung behandeln,
3. das vollständige native VarInt-Wertespektrum des `batchSize`-Felds von
   `chunk_batch_finished` abdecken,
4. die Rückkopplung über das serverbound Paket `chunk_batch_received`
   berücksichtigen,
5. beide aufgezeichneten Pakete als Transport-/Verbindungssteuerung
   klassifizieren,
6. verhindern, dass aufgezeichnete Batch-Marker die echte Chunk-Senderate
   oder Acknowledgement-Zählung der Live-Verbindung des Beobachters
   beeinflussen,
7. die Marker weiterhin am finalen PacketEvents-Wire-Pfad erfassen,
8. echte Live-Control-Pakete des Servers weiterhin durch den Playback-
   ChannelHandler passieren lassen und
9. keinen Paper-/Bukkit-Event-Fallback für Transportzustand einführen.

Eine Migration alter Recordings, eine Änderung des Replay-Formats, eine
Wiederbelebung des entfernten Paketsystems oder ein allgemeiner Umbau des
Chunk-/Checkpoint-Pfads sind nicht Teil des Umfangs.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur, Namen und Ablage

Die verbindliche Ablagestruktur richtet die Domain nach der
Protokollverantwortung aus, nicht nach einem Wort im Paketnamen. Obwohl beide
Pakete „chunk“ heißen, übertragen sie weder Chunk-Inhalt noch Weltzustand.
Sie begrenzen und vermessen die Übertragung anderer Chunk-Pakete auf genau
einer Verbindung.

Damit gehören sie in die Domain `connection`, deren dokumentierte
Verantwortung Transport- und Sessionpakete umfasst. Eine Ablage unter
`world` würde fälschlich suggerieren, die Marker seien für die
Weltrekonstruktion erforderlich.

Die synchronen Namen beider Versionen lauten:

```text
Id011ChunkBatchFinishedPacketDefinition
Id012ChunkBatchStartPacketDefinition
```

Vorgesehene Ablage:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/connection/
        ├── Id011ChunkBatchFinishedPacketDefinition.java
        └── Id012ChunkBatchStartPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/connection/
        ├── Id011ChunkBatchFinishedPacketDefinition.java
        └── Id012ChunkBatchStartPacketDefinition.java
```

`ConnectionPacketDefinitions` enthält derzeit nur
`bundle_delimiter` (`0x00`). Die beiden Batch-Definitionen werden dort in
numerischer Reihenfolge ergänzt. Der versionslokale Gesamtkatalog sortiert
anschließend weiterhin domainübergreifend nach Paket-ID; seine Struktur muss
nicht geändert werden.

### 2.2 Gemeinsamer Transport-Lebenszyklus

Vanilla/Paper sendet einen Chunk-Batch in dieser Form:

```text
chunk_batch_start
  level_chunk_with_light (ein- oder mehrfach)
chunk_batch_finished(batchSize)
  -> Client misst Zeit pro Chunk
  -> Client sendet chunk_batch_received(desiredChunksPerTick)
  -> Server passt seine verbindungslokale Chunk-Flusssteuerung an
```

Der lokale Paper-Bytecode bestätigt:

- `PlayerChunkSender` erhöht vor dem Start die Zahl nicht bestätigter
  Batches,
- sendet das Start-Singleton,
- sendet die ausgewählten Chunk-Pakete,
- sendet `chunk_batch_finished` mit der tatsächlichen Listengröße,
- wartet anfangs auf das clientseitige Acknowledgement,
- reduziert beim Empfang von `chunk_batch_received` die Zahl nicht
  bestätigter Batches,
- begrenzt die gewünschte Rate auf `0.01..64.0` Chunks pro Tick und
- erlaubt nach der ersten Bestätigung bis zu zehn ausstehende Batches.

Die beiden clientbound Marker besitzen deshalb nur innerhalb des aktuellen
Live-Transportzyklus eine Bedeutung. Ihre Semantik hängt ab von:

- der realen Empfangszeit auf diesem Client,
- der Anzahl tatsächlich zwischen den Markern empfangener Chunk-Pakete,
- dem aktuellen Zustand des clientseitigen Batch-Kalkulators,
- dem Acknowledgement-Zähler der aktuellen Serververbindung und
- der Chunk-Sendequote, die der echte Server für genau diesen Beobachter
  verwaltet.

Ein einzelner gespeicherter Frame ist nicht selbstgenügsam. Auch ein
aufgezeichnetes Start-/Finish-Paar darf nicht erneut in die Live-Verbindung
injiziert werden, weil der Client daraus eine neue serverbound Antwort
erzeugt, die den aktuellen Serverzustand statt der Aufnahme beeinflusst.

### 2.3 Capture- und ChannelHandler-Pfad

Der vorhandene `Paper26_*CaptureBridge` erfasst ausgehende Pakete über
PacketEvents auf `MONITOR`-Ebene und kopiert den finalen Wire-Buffer in der
Post-Task. Dieser Pfad bewahrt:

- die tatsächliche Start-/Finish-Reihenfolge,
- die tatsächlich an den Empfänger gesendete Batchgröße,
- vorherige PacketEvents- oder ChannelHandler-Rewrites,
- den Zeit- und Sequenzbezug zu den enthaltenen Chunk-Paketen und
- ungewöhnliche beziehungsweise malformed Werte für Diagnosezwecke, soweit
  sie den finalen Wire-Pfad erreichen.

Die Marker bleiben daher mit `captureByDefault = true` im Recording. Das
bedeutet nicht, dass sie replaybar sind: Die Aufnahme dient hier
Streamanalyse, Verifikation und Diagnose.

Beide Pakete besitzen keine Weltposition. Der Capture-Kontext darf deshalb
keine Chunkkoordinate aus benachbarten Paketen übernehmen oder erfinden. Sie
enthalten außerdem keine Entity-ID, UUID oder andere Playback-Identität.

### 2.4 Playback- und Outbound-Gate-Verhalten

`AbstractBlockedPacketDefinition` bildet bereits den erforderlichen Vertrag:

- Disposition `CONTROL`,
- Capture aktiv,
- Replay deaktiviert,
- Checkpoint-Relevanz deaktiviert und
- Playback-Scope `UNSAFE`.

Die allgemeine `PacketDefinition` lehnt einen nicht replaybaren Descriptor
bereits bei der Descriptorvalidierung ab. Damit wird weder der gespeicherte
Payload decodiert noch ein Rewrite-/Rebuild-Hook aufgerufen.

Diese Richtung muss vom Verhalten des Live-ChannelHandlers unterschieden
werden:

- **aufgezeichnete Batch-Marker:** werden nie in Playback oder
  Rekonstruktion ausgewählt;
- **echte Batch-Marker des aktuell verbundenen Servers:** bleiben
  `CONTROL` und werden vom `ReplayViewerOutboundGate` nicht unterdrückt.

Das ist notwendig, weil der echte Server seine aktuelle Chunkübertragung
weiterhin korrekt mit dem Beobachter koordinieren muss. Ein allgemeines
Unterdrücken von Control-Paketen während Playback könnte den Live-Chunk-
Sender blockieren; ein Replay aufgezeichneter Control-Pakete könnte dagegen
seine Zähler fälschlich verändern. Der vorhandene Gate-Vertrag trennt diese
beiden Richtungen bereits korrekt.

### 2.5 Verhältnis zu Chunk-Inhalt

Die Control-Pakete transportieren keinen Chunkzustand. Insbesondere ersetzen
sie nicht:

- `level_chunk_with_light` für Block-, Biome-, Heightmap-, Light- und
  Block-Entity-Ausgangsdaten,
- `chunks_biomes` für Biome-Updates,
- `light_update` für spätere Lichtänderungen,
- `block_update` und `section_blocks_update` für Blockänderungen oder
- `forget_level_chunk` für das Entfernen eines Chunks aus der Clientansicht.

Das Blockieren der Batch-Marker verwirft daher keine Szeneninformation. Die
eigentlichen Chunk- und Weltpakete werden nach ihren eigenen Definitionen
gesendet beziehungsweise später implementiert. Der Replay-Pfad erzeugt keine
synthetischen Batchgrenzen um Checkpoint-Chunks, weil hierfür ein eigener,
verbindungssicherer Durchsatz- und Acknowledgement-Vertrag nötig wäre.

### 2.6 Checkpoint-Verhalten

Weder `chunk_batch_start` noch `chunk_batch_finished` beschreibt dauerhaften
Weltzustand. Ein Checkpoint muss den Zustand nach einer abgeschlossenen
Rekonstruktion enthalten, nicht die historische Übertragungsdauer oder die
damalige Chunk-Sendequote.

Beide Definitionen bleiben daher:

- nicht replaybar,
- nicht checkpoint-relevant und
- außerhalb aller Checkpoint-Paketfamilien.

An `Paper26_*SnapshotProvider`, `Paper26_*CheckpointEncoder.familyFor(...)`
und der Checkpoint-Reihenfolge ist keine Änderung vorgesehen. Die
Checkpoint-Chunks bleiben selbstständige, inhaltstragende Pakete.

## 3. Verifizierter nativer Protokollvertrag

Die lokalen Paper-Dev-Bundles 26.2 und 26.3 enthalten für beide Pakete
denselben Vertrag.

### 3.1 `chunk_batch_start` (`0x0C`)

Die native Klasse ist ein zustandsloses Singleton:

```java
public class ClientboundChunkBatchStartPacket
        implements Packet<ClientGamePacketListener> {
    public static final ClientboundChunkBatchStartPacket INSTANCE;
    public static final StreamCodec<ByteBuf,
            ClientboundChunkBatchStartPacket> STREAM_CODEC;
}
```

Der Konstruktor ist privat. `STREAM_CODEC` ist ein Unit-Codec auf `INSTANCE`.
Der vollständige gültige Wire-Vertrag besteht daher ausschließlich aus der
Paket-ID:

| Reihenfolge | Feld | Wire-Typ |
|---:|---|---|
| 1 | Paket-ID | VarInt `0x0C` |
| 2 | Body | keine Bytes |

Der Client markiert beim Empfang den Beginn der Batch-Zeitmessung. Mehrere
Starts, ein Start ohne Finish oder ein Finish ohne passenden Start sind keine
neuen Paketvarianten, sondern sequenzabhängige beziehungsweise malformed
Transportabläufe. Die Paketdefinition darf dafür keinen lokalen Zustand
rekonstruieren.

### 3.2 `chunk_batch_finished` (`0x0B`)

Die native Klasse ist ein Record:

```java
public record ClientboundChunkBatchFinishedPacket(int batchSize)
        implements Packet<ClientGamePacketListener>
```

Der vollständige Wire-Vertrag lautet:

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | Paket-ID | VarInt `0x0B` | — | Pakettyp |
| 2 | Batch size | VarInt | `int` | Zahl der Chunks im beendeten Batch |

Der öffentliche Konstruktor und der native Codec enthalten keine
Bereichsprüfung. Der Vanilla-/Paper-Sender verwendet zwar die Größe einer
nicht leeren Chunkliste und erzeugt damit regulär einen positiven Wert, aber
der Wire- und Java-Vertrag kann sämtliche 32-Bit-Integerformen darstellen:

- `0`,
- kleine positive Werte,
- VarInt-Grenzen `127/128` und `16383/16384`,
- `Integer.MAX_VALUE`,
- negative Werte einschließlich `-1` und
- `Integer.MIN_VALUE`.

Die Paketdefinition darf diese Werte nicht clampen, normalisieren oder als
Weltzustand interpretieren. Weil das Paket vollständig blockiert wird, kann
auch ein ungewöhnlicher, aber nativ decodierbarer Wert die Live-Verbindung
nicht beeinflussen. Die nativen Tests dokumentieren dennoch den vollständigen
Codec-Vertrag.

Fehlende, abgeschnittene oder übergroße VarInts sind malformed und müssen im
direkten nativen Codec-Test scheitern.

### 3.3 Clientseitige Berechnung und Rückantwort

Der Client besitzt einen `ChunkBatchSizeCalculator` mit diesen öffentlichen
Operationen:

```java
void onBatchStart()
void onBatchFinished(int batchSize)
float getDesiredChunksPerTick()
```

Die Protokolldokumentation beschreibt folgenden Ablauf:

1. Start speichert den Empfangszeitpunkt.
2. Finish berechnet aus verstrichener Zeit und `batchSize` die Zeit pro
   Chunk.
3. Der Client gewichtet die jüngsten Messwerte; dokumentiert sind bis zu 15
   alte Samples.
4. Aus der Messung wird eine gewünschte Chunkrate berechnet.
5. Diese Rate wird über `chunk_batch_received` an den echten Server gesendet.

Die genaue gemessene Zeit ist nicht Bestandteil des Recordings und wäre bei
Replay ohnehin eine neue Laufzeitmessung. Ein semantisch „korrekter“ Rebuild
der zwei Pakete allein ist deshalb unmöglich, ohne in die Live-
Flusssteuerung einzugreifen.

## 4. Klassifikation und Policy

Beide Definitionen erhalten denselben Policy-Vertrag:

| Eigenschaft | Wert |
|---|---|
| Disposition | `CONTROL` |
| Standard-Capture | `true` |
| Replay | `false` |
| Checkpoint | `false` |
| Playback-Scope | `UNSAFE` |
| Playback-Ergebnis | Descriptor wird vor Decode abgelehnt |

Beide Klassen erweitern:

```java
AbstractBlockedPacketDefinition<Paper26_*ReplayPacketRewriter>
```

Die Verwendung derselben Abstraktion wie bei `bundle_delimiter` und
`block_changed_ack` ist fachlich passend: Alle drei Paketfamilien besitzen
eine verbindungs- oder sequenzlokale Bedeutung, bleiben für Diagnose
aufgezeichnet und dürfen nicht paketlokal wiedergegeben werden.

## 5. Geplante Implementierung

### 5.1 Versionslokale Definitionen

Je Version entstehen zwei kleine, package-private und `final` Definitionen.

`Id011ChunkBatchFinishedPacketDefinition` definiert:

```java
static final int PACKET_ID = 0x0B;
static final String IDENTIFIER = "minecraft:chunk_batch_finished";
```

Die Javadoc erklärt, dass `batchSize` eine Mess- und Acknowledgement-
Rückkopplung auf der empfangenden Verbindung auslöst und deshalb nicht als
Chunkzustand replayt werden darf.

`Id012ChunkBatchStartPacketDefinition` definiert:

```java
static final int PACKET_ID = 0x0C;
static final String IDENTIFIER = "minecraft:chunk_batch_start";
```

Die Javadoc erklärt, dass das payload-freie Paket eine verbindungslokale
Zeitmessung startet und nur im Live-Batchzyklus Bedeutung besitzt.

Keine der Klassen implementiert Read-, Rewrite- oder Rebuild-Hooks. Das ist
beabsichtigt:

- beide Descriptoren sind nicht replaybar,
- die Basisklasse stoppt vor dem Decode,
- es gibt keine sichere Payload-Teilvariante,
- ein Rebuild würde eine clientseitige Rückantwort provozieren und
- der native Wire-Vertrag wird separat vollständig getestet.

### 5.2 Registrierung und Katalog

`ConnectionPacketDefinitions` beider Versionen wird zu einer unveränderlichen
Liste aus:

```text
0x00 bundle_delimiter
0x0B chunk_batch_finished
0x0C chunk_batch_start
```

Der domainübergreifende Katalog enthält danach alle expliziten IDs von
`0x00` bis einschließlich `0x0C` in numerischer Reihenfolge. Es wird keine
Sonderlogik im Katalog ergänzt.

### 5.3 Playback-Routing und Live-Gate

`Paper26_*PlaybackBridgeTest` wird um beide IDs erweitert:

- `shouldReplay(..., LIVE, ...)` ist `false`,
- `shouldReplay(..., RECONSTRUCTION, ...)` ist `false`,
- beide Descriptoren sind `CONTROL`, nicht replaybar und nicht
  checkpoint-relevant und
- beide besitzen Scope `UNSAFE`.

Das allgemeine `ReplayViewerOutboundGateTest` deckt bereits ab, dass echte
Live-Control-Pakete durchgereicht werden. Eine produktive Gate-Änderung ist
nicht vorgesehen. Ein paketspezifischer Test wird nur ergänzt, wenn die
Descriptor-Integration eine bisher nicht abgedeckte Lücke zeigt; redundante
Gate-Logik wird vermieden.

### 5.4 Capture, Position und Checkpoint

Es werden keine Fälle in `PaperConnectionAccessor.packetPosition(...)`
ergänzt, da beide Pakete positionslos sind. Es wird keine Identity-Map
aufgerufen und kein View-Reset-Zustand angelegt.

Der vorhandene PacketEvents-Capture bleibt unverändert. Snapshot-Provider,
Checkpoint-Encoder und Checkpoint-Paketfamilien bleiben unverändert, weil die
Marker keinen rekonstruierbaren Zustand darstellen.

## 6. Event-Fallback-Entscheidung

Für die Batch-Marker wird kein Paper-/Bukkit-Event-Fallback eingeführt.

Paper besitzt zwar Chunk-Load-/Unload-Events und interne Ereignisse rund um
das Senden von Chunks. Diese Ereignisse bilden den Transportvertrag nicht
vollständig ab. Sie liefern insbesondere nicht zuverlässig:

- den finalen empfängerbezogenen Start-/Finish-Wire-Strom,
- die nach PacketEvents-Rewrites tatsächlich gesendete Batchgröße,
- die genaue Lage der Marker relativ zu anderen ausgehenden Paketen,
- den clientseitig gemessenen Empfangszeitraum,
- den Zustand des verbindungslokalen Batch-Kalkulators oder
- die daraus erzeugte serverbound Rate.

Ein Event-Fallback könnte zudem Batch-Marker doppelt erfassen oder Marker für
Chunks erzeugen, die auf dem finalen Wire-Pfad verändert beziehungsweise
nicht gesendet wurden. Da PacketEvents und der bestehende ChannelHandler den
vollständigen Transportpfad bereits beobachten, liegt kein Grund für eine
Fallback-Ebene vor.

## 7. Adapter-Dokumentationen

Die vollständigen Paket-Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig und neutral. Ausschließlich die Zeilen für IDs 11 und 12
werden fachlich präzisiert.

Für `chunk_batch_finished`:

- Beschreibung/Relevanz: beendet die clientseitige Chunk-Durchsatzmessung
  mit einer Batchgröße und löst eine serverbound Ratenbestätigung aus,
- Playback: `BLOCKED`,
- Checkpoint: `no`.

Für `chunk_batch_start`:

- Beschreibung/Relevanz: startet die verbindungslokale Zeitmessung eines
  Chunk-Batches und besitzt keinen Payload,
- Playback: `BLOCKED`,
- Checkpoint: `no`.

Die Dokumente enthalten keine Aussage über Implementierungsstatus, konkrete
Klassen oder Tests. Alle übrigen Paketzeilen bleiben unangetastet.

## 8. Teststrategie

### 8.1 Definitionstest für `chunk_batch_finished` je Version

Je Version entsteht ein
`Id011ChunkBatchFinishedPacketDefinitionTest` mit mindestens:

1. ID `11`/`0x0B` und exaktem Typnamen,
2. Klassifikation `CONTROL`, Capture an, Replay aus, Checkpoint aus und Scope
   `UNSAFE`,
3. Ablehnung sämtlicher Payloadformen vor dem Playback-Decode,
4. Nachweis durch einen Codec, der bei jedem Decode-/Encode-Aufruf
   fehlschlägt,
5. Ablehnung bereits decodierter Objekte ohne Typinspektion,
6. nativer Paket-ID- und Feldreihenfolge,
7. Roundtrip für `0`, `1`, `127`, `128`, `16383`, `16384`,
   `Integer.MAX_VALUE`, `-1` und `Integer.MIN_VALUE`,
8. bit-/wertgetreuer Rückgabe über `batchSize()`,
9. vollständigem Verbrauch gültiger Payloads und
10. Ablehnung fehlender, abgeschnittener und übergroßer VarInts.

Diese Matrix deckt reguläre Werte, sämtliche relevanten VarInt-Längengrenzen
und die vollständigen Vorzeichen-/Integer-Grenzklassen ab. Produktionscode
führt dennoch keine eigene Wertevalidierung ein, weil der Frame blockiert
wird und der native Codec den vollständigen Wire-Vertrag definiert.

### 8.2 Definitionstest für `chunk_batch_start` je Version

Je Version entsteht ein `Id012ChunkBatchStartPacketDefinitionTest` mit:

1. ID `12`/`0x0C` und exaktem Typnamen,
2. derselben vollständigen Control-Klassifikation,
3. Blockierung vor dem Playback-Decode,
4. Ablehnung beliebiger beziehungsweise malformed gespeicherter Payloads vor
   Codec-Zugriff,
5. Ablehnung bereits decodierter Objekte ohne Typinspektion,
6. nativem Encode ausschließlich als Paket-ID ohne Body,
7. nativem Decode auf `ClientboundChunkBatchStartPacket.INSTANCE`,
8. Identität desselben Singletons bei wiederholtem Decode und
9. vollständigem Verbrauch des gültigen payload-freien Frames.

Nachlaufende Bodybytes werden durch den vorhandenen verbindungsgebundenen
Replay-Decoder generell abgelehnt. Die Definition selbst erreicht diesen
Decoder wegen `CONTROL` nicht; es wird kein zweiter manueller Bodyparser
eingeführt.

### 8.3 Katalog-, Playback- und Channel-Integration

`PacketCoverageTest` beider Versionen wird erweitert:

- explizite Katalogreihenfolge bis `0x0C`,
- beide neuen Pakete als `CONTROL`,
- Capture aktiv,
- Replay und Checkpoint aus,
- Scope `UNSAFE` und
- sämtliche späteren Pakete weiterhin `UNSUPPORTED`.

`Paper26_*PlaybackBridgeTest` prüft, dass beide Marker weder im Live-Modus
noch in der Rekonstruktion aus dem Recording ausgewählt werden.

Der bestehende Outbound-Gate-Test bleibt die Absicherung, dass echte
Live-Control-Pakete den ChannelHandler passieren. Falls ein zusätzlicher
paketspezifischer Test entsteht, verwendet er ausschließlich die neuen
Descriptoren und verändert keine Produktionslogik.

### 8.4 Versionssymmetrie und Dokumentation

Definitionen und Tests werden nach Normalisierung der Versionsbezeichner
verglichen. Die Analyse der angehefteten Builds hat keine Abweichung zwischen
26.2 und 26.3 ergeben.

Die Dokumentationsprüfung stellt sicher, dass:

- beide vollständigen Paketlisten erhalten bleiben,
- nur die beiden betroffenen Zeilen fachlich geändert werden,
- Beschreibung/Relevanz, Playback- und Checkpoint-Policy enthalten sind und
- keine Status- oder Testaussage ergänzt wird.

### 8.5 Auszuführende Prüfungen

Nach ausdrücklicher Genehmigung und Implementierung:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test `
  :replay-adapter:paper-26_2:test `
  :replay-adapter:paper-26_3:test --console=plain
```

Zusätzlich:

- `git diff --check`,
- Symmetrievergleich beider Definitionen und ihrer Tests,
- Katalogprüfung für die expliziten IDs `0x00..0x0C`,
- Prüfung des geänderten Dateiumfangs,
- Prüfung, dass `.idea/` unberührt und unversioniert bleibt,
- Prüfung des aktiven Branchs vor dem Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/connection/Id011ChunkBatchFinishedPacketDefinition.java`
- `packet/definition/connection/Id012ChunkBatchStartPacketDefinition.java`
- `packet/definition/connection/ConnectionPacketDefinitions.java`

Tests je Version:

- `packet/definition/connection/Id011ChunkBatchFinishedPacketDefinitionTest.java`
- `packet/definition/connection/Id012ChunkBatchStartPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`
- gegebenenfalls der bestehende `ReplayViewerOutboundGateTest`, falls zur
  Absicherung der konkreten Descriptorintegration erforderlich.

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser Implementierungsplan

Nicht vorgesehen sind Änderungen an:

- Replay-Core oder Format,
- allgemeinen Paketabstraktionen,
- PacketEvents-Capture-Listenern,
- `PaperConnectionAccessor`,
- Bukkit-/Paper-Event-Listenern,
- Replay-Identity-Mapping,
- View-Resettern,
- Snapshot-Providern,
- Checkpoint-Encodern oder deren Familien,
- produktiven Playback-ChannelHandlern,
- dem serverbound Capture-/Sampling-Pfad oder
- anderen Paketdefinitionen.

## 10. Risiken und Gegenmaßnahmen

### Spurious Serverbound-Acknowledgement

Risiko: Ein replaytes Finish veranlasst den Client, eine neue
`chunk_batch_received`-Antwort an den echten Server zu senden.

Gegenmaßnahme: Beide Marker als nicht replaybares `CONTROL` blockieren und
ihre Auswahl in beiden Playback-Modi testen.

### Beschädigte Live-Chunk-Flusssteuerung

Risiko: Eine zusätzliche Antwort reduziert den serverseitigen Zähler
ausstehender Batches oder verändert `desiredChunksPerTick` für die echte
Verbindung.

Gegenmaßnahme: Keine aufgezeichneten Marker injizieren; echte Live-Control-
Pakete weiterhin durch das Outbound-Gate lassen.

### Falsche Weltzustandsannahme

Risiko: Die Marker werden wegen „chunk“ unter `world` abgelegt oder als
checkpoint-relevanter Chunkzustand behandelt.

Gegenmaßnahme: Ablage unter `connection`, ausführliche Javadoc und
`CONTROL`-/`UNSAFE`-Klassifikation ohne Checkpoint-Relevanz.

### Unvollständige Batchpaarung

Risiko: Ein Start oder Finish wird isoliert replayt oder anhand der Aufnahme
neu gepaart, obwohl reale Laufzeit und aktuelle Verbindung nicht
übereinstimmen.

Gegenmaßnahme: Keine paketlokale oder sitzungslokale Pairing-Logik. Beide
Typen bleiben vollständig blockiert.

### Verlust ungewöhnlicher Batchgrößen

Risiko: Tests beschränken `batchSize` auf positive Vanilla-Werte und
übersehen den vollständigen nativen `int`-/VarInt-Vertrag.

Gegenmaßnahme: Native Tests über Null, Vorzeichen, Integergrenzen und alle
relevanten VarInt-Längenübergänge; keine Normalisierung.

### Event-Duplikate

Risiko: Ein Chunk-Event-Fallback erzeugt zusätzliche Marker neben dem finalen
Wire-Strom.

Gegenmaßnahme: Ausschließlich PacketEvents-/Channel-Wire-Capture verwenden;
keine Events oder Serverzustandsableitung ergänzen.

### Versionsdrift

Risiko: Beide Paper-Versionen erhalten unterschiedliche Namen, Policies oder
Testmatrizen trotz identischem Vertrag.

Gegenmaßnahme: Identische Klassennamen, symmetrische Tests und normalisierter
Dateivergleich.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- Beide Chunk-Batch-Marker werden in 26.2 und 26.3 explizit klassifiziert.
- Ihr vollständiger nativer Wire-Vertrag ist getestet.
- Recordings behalten Start, Finish, Reihenfolge und Batchgröße für Diagnose.
- Playback und Seek-Rekonstruktion verändern die Live-Chunk-Senderate des
  Beobachters nicht.
- Echte Server-Control-Pakete funktionieren während Playback weiter.
- Der Registry-Fingerprint ändert sich durch zwei neue explizite
  Definitionen; ohne Altaufnahme-Migration ist dies beabsichtigt.

### Bewusste Grenzen

- Der Replay-Pfad reproduziert keine historische Chunk-Übertragungsdauer.
- Er sendet keine synthetischen `chunk_batch_received`-Pakete.
- Er baut keine neuen Batchgrenzen um Checkpoint-Chunks.
- Er rekonstruiert keinen clientseitigen `ChunkBatchSizeCalculator`.
- Ungewöhnliche Batchgrößen werden nativ dokumentiert, aber niemals replayt.
- Andere Paket-IDs und nicht unmittelbar erforderliche Refactorings bleiben
  außerhalb des Umfangs.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- dem Bytecode von `ClientboundChunkBatchStartPacket`,
  `ClientboundChunkBatchFinishedPacket`,
  `ServerboundChunkBatchReceivedPacket`, `PlayerChunkSender` und
  `ServerGamePacketListenerImpl`,
- der bestehenden `AbstractBlockedPacketDefinition`,
- dem versionslokalen Packet-Catalog und Playback-Routing,
- dem vorhandenen `ReplayViewerOutboundGate` und
- der Minecraft-Protokolldokumentation für Batchmessung und
  Acknowledgement-Rückkopplung.

Relevante externe Quellen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Chunk_Batch_Finished>
- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Chunk_Batch_Start>
- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Chunk_Batch_Received>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundChunkBatchFinishedPacket.html>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundChunkBatchStartPacket.html>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket.html>
- <https://mappings.dev/1.21.11/net/minecraft/client/multiplayer/ChunkBatchSizeCalculator.html>

Die Online-Dokumentation beschreibt Semantik und Client-/Server-
Rückkopplung. Maßgeblich für Klassenform, Codec, Versionsgleichheit und
serverseitige Verarbeitung bleiben die lokal angehefteten Paper-Artefakte
sowie die nativen Protokolltests.

## 13. Versionsverwaltungs- und Freigabepunkt

Vor der Implementierung wird ohne Branchwechsel geprüft, ob `master` und
`origin/master` synchron sind. Ein Fetch-Problem oder eine Abweichung wird
transparent gemeldet und überschreibt keine lokalen Änderungen. Wegen der
ausdrücklichen Vorgabe wird kein separater Aufgabenbranch erstellt und
ausschließlich auf `codex/refactor-packet-capture-rewrite` gearbeitet.

Bis zur ausdrücklichen Genehmigung werden keine Produktions- oder Testklassen
verändert. Nach der Freigabe wird der beschriebene Umfang implementiert,
validiert, in den neutralen vollständigen Adapter-Dokumentationen
nachgeführt und mit einem englischen semantischen Commit bereitgestellt.
