# Implementierungsplan: `chunks_biomes` (`0x0D`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und verbindliche Benennung

Das PLAY-Clientbound-Paket mit ID `13` beziehungsweise `0x0D` wird in den
beiden Paper-Adaptern als explizite, versionslokale Paketdefinition
umgesetzt.

Die vom Protokoll und von Paper verwendete Kennung lautet entgegen der
singulären Aufgabenbezeichnung:

```text
minecraft:chunks_biomes
```

Das Plural ist semantisch relevant: Ein Paket enthält eine Liste und kann
Biome-Daten für null, einen oder mehrere Chunks transportieren. Die
Definition erhält deshalb synchron in beiden Versionen den Namen:

```text
Id013ChunksBiomesPacketDefinition
```

Die Umsetzung soll:

1. den vollständigen nativen Wire- und Java-Vertrag beider Paper-Versionen
   abbilden,
2. alle Listengrößen und Eintragsformen behandeln, die der native Codec
   akzeptiert,
3. Chunkkoordinaten ohne Bereichseinschränkung, Umordnung oder
   Normalisierung erhalten,
4. die serialisierten Biome-Paletten bytegenau und ohne verlustbehaftete
   Interpretation bewahren,
5. alle veränderlichen Listen und `byte[]` defensiv kopieren,
6. die native Grenze von 2 MiB je Chunk-Biome-Puffer erzwingen,
7. leere Listen, leere Puffer, mehrere Einträge und doppelte
   Chunkkoordinaten unverändert erhalten,
8. das Paket als sicheren, dauerhaften und checkpoint-relevanten
   Weltzustand klassifizieren,
9. keine Playback-Identitäten umschreiben,
10. keine einzelne, irreführende Blockposition für das Mehrpositionspaket
    erfinden und
11. keinen unvollständigen Paper-/Bukkit-Event-Fallback einführen.

Eine Migration alter Recordings, eine Änderung des Replay-Formats, ein
allgemeiner Umbau der Chunk-Pipeline oder die Implementierung benachbarter
Paket-IDs ist nicht Teil dieses Vorhabens.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur und Ablage

Die bestehenden Definitionen sind nach Protokollverantwortung organisiert.
`chunks_biomes` verändert den sichtbaren Zustand bereits bekannter Chunks
und gehört daher zur Domain `world`.

Vorgesehene Produktionsdateien:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/world/
        └── Id013ChunksBiomesPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/world/
        └── Id013ChunksBiomesPacketDefinition.java
```

Beide `WorldPacketDefinitions` registrieren die neue Definition nach
`Id010ChangeDifficultyPacketDefinition`. Der versionslokale Gesamtkatalog
sortiert domainübergreifend weiterhin numerisch nach Paket-ID. Eine neue
Domain oder eine Änderung der Katalogabstraktion ist nicht erforderlich.

### 2.2 Bestehende Read-/Rewrite-/Rebuild-Abstraktion

`RebuildingPacketDefinition` ist für dieses Paket passend:

- `read(...)` löst den nativen, veränderlichen Objektgraphen in einen
  adaptereigenen unveränderlichen Wert auf,
- `rewrite(...)` entscheidet ohne erneute Wire-Interpretation über die
  Wiedergabe und
- `rebuild(...)` erzeugt stets ein neues natives Paket.

Eine einfache Weitergabe des decodierten
`ClientboundChunksBiomesPacket` wäre nicht ausreichend. Der native Record
stellt zwar seine Liste direkt bereit, die Listenelemente enthalten jedoch
veränderliche `byte[]`. Weder der Java-Record noch `List.copyOf(...)` kopiert
diese Arrays tief. Ein später veränderter Quellpuffer könnte dadurch den
Replayinhalt nach dem Read-Schritt verändern.

Die Definition benötigt deshalb ein tief unveränderliches Wertmodell statt
einer `SimplePacketDefinition`.

### 2.3 Capture- und ChannelHandler-Pfad

Der bestehende `Paper26_*CaptureBridge` beobachtet ausgehende Pakete über
PacketEvents auf `MONITOR`-Ebene und übernimmt in seiner Post-Task den
finalen Wire-Puffer. Dieser Pfad erfasst damit:

- die tatsächlich an den Empfänger gesendete Eintragsreihenfolge,
- die tatsächlichen Chunkkoordinaten,
- die nach Paper-, ChannelHandler- und PacketEvents-Verarbeitung
  übertragenen Palettenbytes,
- Mehrfachnennungen desselben Chunks und
- die Position des Pakets relativ zu Chunk-Load- und Block-Update-Paketen.

Es ist keine Änderung des Capture-Listeners vorgesehen. Die Definition
arbeitet beim Playback auf dem nativen Decode-Ergebnis des gespeicherten
Wire-Frames.

### 2.4 Räumlicher Capture-Kontext

`PaperConnectionAccessor.packetPosition(...)` liefert für Pakete mit
mehreren Positionen bewusst keine einzelne `BlockPosition`. Diese
Entscheidung trifft auf `chunks_biomes` zu:

- ein Paket kann mehrere Chunks enthalten,
- die Chunks müssen nicht benachbart sein,
- ein repräsentativer Mittelpunkt ist nicht Teil des Protokolls,
- ein Chunkursprung würde nur einen Eintrag abbilden und
- die Auswahl eines beliebigen Eintrags könnte einen begrenzten
  Recording-Bereich fälschlich annehmen oder ablehnen.

Deshalb wird `packetPosition(...)` nicht erweitert. Die Chunkkoordinaten
bleiben vollständig im Payload erhalten; es wird keine erfundene
Einzelposition in den Capture-Kontext geschrieben.

### 2.5 Checkpoint-Architektur

`Paper26_*CheckpointEncoder.familyFor(...)` ordnet
`play.clientbound.minecraft:chunks_biomes` bereits explizit der Familie
`CHUNK_LIGHT` zu. Die Adapterdokumentation führt das Paket bereits als
`SEND` und checkpoint-relevant.

Der aktuelle `Paper26_*SnapshotProvider` erzeugt für einen Checkpoint einen
`ClientboundLevelChunkWithLightPacket` je geladenem Chunk. Dieses Paket
enthält bereits die vollständigen Biome-Paletten des Chunks. Daher muss der
Snapshot-Provider nicht zusätzlich ein redundantes `chunks_biomes`-Paket
erzeugen.

Die Checkpoint-Relevanz der neuen Definition bleibt dennoch korrekt:

- aufgezeichnete beziehungsweise synthetisch bereitgestellte
  `chunks_biomes`-Zustände gehören zur Chunk-Familie,
- der Checkpoint-Encoder darf sie als Weltzustand validieren und ordnen,
- Live-Playback und Rekonstruktion dürfen das Paket senden und
- die konkrete Vollsnapshot-Erzeugung kann denselben Endzustand bereits
  durch `level_chunk_with_light` liefern.

Es ist daher keine Änderung an Snapshot-Provider, Checkpoint-Familien oder
Checkpoint-Reihenfolge vorgesehen.

## 3. Verifizierter nativer Protokollvertrag

### 3.1 Native Klasse

Beide angehefteten Paper-Builds verwenden:

```java
public record ClientboundChunksBiomesPacket(
        List<ClientboundChunksBiomesPacket.ChunkBiomeData> chunkBiomeData)
        implements Packet<ClientGamePacketListener>
```

Ein Eintrag ist:

```java
public record ChunkBiomeData(ChunkPos pos, byte[] buffer)
```

Paper stellt außerdem `forChunks(List<LevelChunk>)` bereit. Dabei wird für
jeden Chunk die serialisierte Biome-Palette aller `LevelChunkSection`s in
einen zusammenhängenden Puffer geschrieben. Der Paketinhalt beschreibt
somit Biome-Container, nicht Blockzustände, Licht oder Block-Entities.

### 3.2 Wire-Format

Der vollständige Body lautet:

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | Anzahl Chunk-Einträge | VarInt | `int`/Listengröße | Zahl nachfolgender Einträge |
| 2.n | Chunkposition | gepacktes Big-Endian-Long | `ChunkPos` | X in den unteren, Z in den oberen 32 Bit |
| 3.n | Biome-Pufferlänge | VarInt | `int` | Bytezahl des folgenden Puffers |
| 4.n | Biome-Puffer | Bytefolge | `byte[]` | serialisierte Biome-Paletten aller Chunksektionen |

Die Protokolldokumentation stellt die Long-Hälften als Z vor X dar, weil Z
in den oberen 32 Bit liegt und daher im Big-Endian-Long zuerst auf dem Wire
erscheint. Die Definition verwendet `ChunkPos` und darf die beiden
Koordinaten nicht manuell vertauschen.

### 3.3 Version 26.2 gegenüber 26.3

Das Wire-Format und die semantischen Felder sind identisch. Nur die interne
Codec-Implementierung unterscheidet sich:

- Paper 26.2 verwendet einen `StreamCodec<FriendlyByteBuf, ...>`, eine
  private Decode-Konstruktion über `readList(...)` und eine eigene
  `write(...)`-Methode.
- Paper 26.3 verwendet einen `StreamCodec<ByteBuf, ...>` aus
  `StreamCodec.composite(...)`, `ChunkPos.STREAM_CODEC`,
  `ByteBufCodecs.list()` und `ByteBufCodecs.byteArray(2_097_152)`.

Die Definitionen bleiben absichtlich versionslokal und strukturell
symmetrisch. Die nativen Tests verwenden den jeweiligen durch
`GameProtocols.CLIENTBOUND_TEMPLATE` gebundenen Codec und testen damit die
reale Implementierung jeder Version statt einen gemeinsamen Handparser.

### 3.4 Zulässige Listenformen

Der native Vertrag legt keine semantische Mindestgröße fest. Deshalb sind
zu erhalten:

- eine leere Liste,
- genau ein Chunk,
- mehrere Chunks,
- beliebige Eintragsreihenfolgen,
- nicht benachbarte Chunks und
- mehrfach vorkommende identische `ChunkPos`-Werte.

Die Definition dedupliziert oder sortiert nicht. Bei doppelten Positionen
ist die Reihenfolge Teil des übertragenen Zustands; eine Normalisierung
könnte ändern, welcher Eintrag clientseitig zuletzt angewandt wird.

Der unbeschränkte native Listen-Codec verwendet eine VarInt-Anzahl. Negative
Anzahlen, abgeschnittene VarInts oder eine Anzahl ohne genügend vollständige
Einträge sind malformed und müssen im nativen Decode scheitern. Ein eigenes
willkürliches Listenlimit wird nicht eingeführt; die allgemeine maximale
Paketgröße und der native Codec begrenzen praktisch den Wire-Frame.

### 3.5 Chunkkoordinaten

`ChunkPos` besteht aus zwei vorzeichenbehafteten 32-Bit-Integern. Die
Definition bewahrt daher insbesondere:

- `(0, 0)`,
- negative und positive Koordinaten,
- `Integer.MIN_VALUE` und `Integer.MAX_VALUE` in beiden Achsen und
- beliebige Kombinationen der Grenzwerte.

Es erfolgt keine Umrechnung in Blockkoordinaten, keine View-Distance-
Validierung und kein Abgleich mit der aktuellen Serverwelt. Ob ein Client
den Zielchunk aktuell kennt oder rendert, ist Sequenz- und
Verbindungszustand, nicht Bestandteil des Paketwerts.

### 3.6 Biome-Puffer

Der Puffer enthält pro Chunksektion ausschließlich die serialisierte
palettierte Biome-Containerstruktur. Deren Interpretation hängt unter
anderem ab von:

- der Zahl der Sektionen des Zielchunks beziehungsweise Dimensionstyps,
- der während Configuration übertragenen Biome-Registry,
- dem Palettenmodus und der Bitbreite jedes Containers sowie
- der Reihenfolge der Sektionen.

Die Definition behandelt den Puffer deshalb als opakes, aber bytegenau zu
erhaltendes Protokollfeld. Sie darf nicht:

- Biome-IDs anhand der aktuellen Serverwelt neu auflösen,
- die Paletten neu serialisieren,
- leere oder ungewöhnliche Puffer ersetzen,
- Sektionen zählen oder ergänzen oder
- vermeintliche UUIDs beziehungsweise IDs im Puffer umschreiben.

Der native Codec erlaubt eine Pufferlänge von `0` bis einschließlich
`2_097_152` Bytes je Eintrag. Die 26.3-Encode- und beide Decode-Pfade
erzwingen dieses Limit direkt. Der 26.2-Encode-Helfer prüft das Limit nicht
symmetrisch, obwohl sein Decode-Pfad es tut. Das adaptereigene Wertmodell
erzwingt deshalb in beiden Versionen dieselbe protokollkonforme Obergrenze,
damit kein Paket gebaut wird, das die Gegenstelle mit demselben nativen
Vertrag nicht wieder decodieren kann.

Die maximale innere Puffergröße ist von der äußeren maximalen Paketgröße zu
unterscheiden. Die Definition erfindet hierfür kein niedrigeres Limit; die
verbindungsgebundene Minecraft-Pipeline bleibt für Framing und Kompression
zuständig.

## 4. Klassifikation und Playback-Policy

Die Definition erhält:

| Eigenschaft | Wert | Begründung |
|---|---|---|
| Disposition | `STATEFUL` | ersetzt sichtbare Biome-Paletten von Chunks |
| Standard-Capture | `true` | notwendig für korrekte spätere Biomeänderungen |
| Replay | `true` | der Inhalt ist selbstgenügsamer clientbound Weltzustand |
| Checkpoint | `true` | Biome-Zustand gehört zur Chunk-Rekonstruktion |
| Playback-Scope | `SAFE` | enthält keine Viewer-, Entity- oder Sessionidentität |
| Rewrite-Ergebnis | `SEND` | unveränderten semantischen Wert neu aufbauen |

Biome-Registry-IDs sind Inhaltsreferenzen des versionsgebundenen
Minecraft-Protokolls und keine Playback-Identitäten. Sie werden nicht durch
`PlaybackIdentityContext` gemappt.

Ein Paket kann wirkungslos sein, wenn sein Chunk beim Client nicht geladen
ist. Es darf dennoch nicht pauschal verworfen werden: In einer gültigen
Aufnahmesequenz folgt es auf den passenden Chunkzustand; während einer
Checkpoint-Rekonstruktion stellt die Chunk-Familienreihenfolge sicher, dass
Chunkdaten vor nachgelagertem Weltzustand behandelt werden. Die Definition
selbst fügt keine versteckte Chunk-Existenzprüfung gegen den Live-Server ein.

## 5. Geplante Implementierung

### 5.1 Definition

Je Version entsteht eine package-private `final` Klasse:

```java
final class Id013ChunksBiomesPacketDefinition extends
        RebuildingPacketDefinition<
                Paper26_*ReplayPacketRewriter,
                ClientboundChunksBiomesPacket,
                Id013ChunksBiomesPacketDefinition.Value> {

    static final int PACKET_ID = 0x0D;
    static final String IDENTIFIER = "minecraft:chunks_biomes";
}
```

Die Javadoc beschreibt insbesondere:

- dass ein Paket mehrere Chunks aktualisiert,
- dass die Bytepuffer serialisierte Biome-Paletten darstellen,
- warum diese Puffer opak bleiben,
- warum Listen und Arrays tief kopiert werden und
- warum keine Identity-Rewrite-Logik angewandt wird.

### 5.2 Unveränderliches Wertmodell

Vorgesehen ist ein adapterlokales Modell aus einem Paketwert und einem
Eintragswert, sinngemäß:

```java
record Value(List<ChunkBiomeValue> chunks) { ... }

record ChunkBiomeValue(ChunkPos position, byte[] buffer) { ... }
```

Die Konstruktoren validieren und kopieren:

- Liste nicht `null`,
- kein Listenelement `null`,
- `ChunkPos` nicht `null`,
- Puffer nicht `null`,
- Pufferlänge höchstens `2_097_152`,
- unveränderliche Listenkopie und
- Clone jedes Puffers.

Da Java-Record-Accessoren für Arrays sonst die interne Referenz freigeben,
überschreibt `ChunkBiomeValue.buffer()` den Accessor und liefert ebenfalls
einen Clone. Das Modell bleibt dadurch auch gegenüber Testcode und späteren
Erweiterungen tief unveränderlich.

`ChunkPos` selbst wird als unveränderlicher Wert behandelt. Ein Ersatz durch
zwei rohe Integer ist nicht nötig und würde den nativen Long-Vertrag eher
verschleiern.

### 5.3 Read, Rewrite und Rebuild

`read(...)`:

- übernimmt alle nativen Einträge in ursprünglicher Reihenfolge,
- erzeugt für jeden Eintrag einen `ChunkBiomeValue`,
- klont dabei jeden nativen Puffer und
- liefert einen tief unveränderlichen `Value`.

`rewrite(...)`:

- führt keine Identitäts- oder Weltauflösung aus,
- gibt `ValueRewriteResult.send(value)` zurück und
- dokumentiert, dass Registry-Paletteninhalt nicht aus der Live-Welt
  ersetzt werden darf.

`rebuild(...)`:

- erstellt für jeden Wert einen neuen nativen `ChunkBiomeData`,
- verwendet erneut geklonte Puffer,
- bewahrt leere Listen, Reihenfolge und Duplikate und
- erstellt einen neuen `ClientboundChunksBiomesPacket`.

Dadurch teilen Originalpaket, adaptereigener Wert und Rebuild-Paket keine
veränderliche Arrayreferenz.

### 5.4 Registrierung

Beide `WorldPacketDefinitions` werden ausschließlich um die neue Definition
ergänzt. Weitere Registry-, Catalog- oder Abstraktionsänderungen sind nicht
vorgesehen.

`PacketCoverageTest` wird bis ID `0x0D` erweitert:

- exakter Typname `play.clientbound.minecraft:chunks_biomes`,
- Disposition `STATEFUL`,
- Capture, Replay und Checkpoint aktiv,
- Scope `SAFE` und
- alle späteren Pakettypen weiterhin `UNSUPPORTED`.

### 5.5 Playback- und ChannelHandler-Integration

`Paper26_*PlaybackBridgeTest` wird um ID `13` ergänzt:

- im Modus `LIVE` replaybar,
- im Modus `RECONSTRUCTION` wegen Checkpoint-Relevanz replaybar und
- keine Identity-Rewrite-Anforderung.

Der produktive Playback-ChannelHandler benötigt voraussichtlich keine
Änderung. Er routet replaybare Definitionen bereits über den Decoder,
Definition-Handler und Encoder. Der allgemeine Outbound-Gate-Vertrag bleibt
unverändert: echte Serverpakete und Replaypakete werden anhand der
bestehenden Emissionsquelle behandelt, nicht durch paketlokale Sonderlogik.

## 6. Event-Fallback-Entscheidung

Für `chunks_biomes` wird kein Paper-/Bukkit-Event-Fallback eingeführt.

Chunk-Load-, Populate- oder World-Events sind keine gleichwertige Quelle für
dieses Paket. Sie liefern nicht zuverlässig:

- den finalen empfängerbezogenen Paketzeitpunkt,
- die tatsächliche Gruppierung mehrerer Chunks in einem Paket,
- die Wire-Reihenfolge der Einträge,
- die exakt von Paper serialisierten Palettenbytes,
- vorherige ChannelHandler-/PacketEvents-Rewrites oder
- spätere Biome-Neusendungen ohne entsprechenden Chunk-Load-Lebenszyklus.

Ein eventbasierter Nachbau müsste außerdem Dimensionstyp, Sektionen,
Registry-IDs und Palettenformat selbst rekonstruieren. Das wäre weniger
präzise als der bereits vorhandene finale Wire-Capture und könnte Duplikate
erzeugen. Events bleiben damit allenfalls eine theoretische Fallback-Ebene,
werden für dieses Paket aber bewusst nicht verwendet.

## 7. Adapter-Dokumentationen

Die vollständigen, neutralen Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig. Nur die Zeile für PLAY ID 13 wird fachlich präzisiert,
sinngemäß:

| ID | Type | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 13 | `minecraft:chunks_biomes` | Replaces the serialized biome palettes of zero or more chunks while preserving chunk order and coordinates | SEND | yes |

Die Dokumentation nennt weder Implementierungsstatus noch konkrete Klassen
oder Tests. Alle übrigen Paketzeilen bleiben unangetastet.

## 8. Teststrategie

### 8.1 Klassifikation und Definition je Version

Je Version entsteht ein
`Id013ChunksBiomesPacketDefinitionTest` mit mindestens:

1. ID `13`/`0x0D` und exaktem pluralem Typnamen,
2. `STATEFUL`, Capture an, Replay an, Checkpoint an und Scope `SAFE`,
3. Ablehnung eines falschen decodierten Nativtyps,
4. erfolgreichem Definition-Playback und validiertem Encode,
5. Nachweis, dass `rewrite(...)` denselben unveränderten Wert sendet und
6. Nachweis, dass keine Playback-Identity benötigt oder verändert wird.

### 8.2 Vollständige Wertformen

Die Read-/Rewrite-/Rebuild-Tests decken ab:

- leere Paketliste,
- einen Eintrag mit leerem Puffer,
- einen Eintrag mit allen 256 möglichen Bytewerten,
- mehrere, nicht benachbarte Chunks,
- erhaltene Eintragsreihenfolge,
- doppelte Chunkkoordinaten mit unterschiedlichen Puffern,
- negative und positive Chunkkoordinaten,
- X/Z jeweils an `Integer.MIN_VALUE` und `Integer.MAX_VALUE`,
- Puffer genau an der 2-MiB-Grenze und
- Ablehnung eines Puffers mit 2 MiB plus einem Byte.

Die Tests prüfen außerdem tiefe Isolation:

1. Mutation der ursprünglichen nativen Liste nach `read(...)` verändert den
   Wert nicht.
2. Mutation eines ursprünglichen nativen `byte[]` verändert den Wert nicht.
3. Mutation eines über `buffer()` erhaltenen Arrays verändert den Wert
   nicht.
4. Mutation eines Arrays aus dem neu gebauten nativen Paket verändert weder
   den Wert noch einen späteren Rebuild.
5. Original- und Rebuild-Paket teilen keine Pufferreferenzen.

### 8.3 Nativer Wire-Codec je Version

Die Tests binden den echten clientbound PLAY-Codec der jeweiligen
Paper-Version und verifizieren:

- Paket-ID `0x0D`,
- VarInt-Listengröße,
- gepacktes ChunkPos-Long und korrekte X-/Z-Rückgewinnung,
- VarInt-Pufferlänge,
- bytegenauen Pufferinhalt,
- vollständigen Verbrauch eines gültigen Frames,
- Roundtrip von leerer, einzelner und mehrfacher Liste,
- Reihenfolge und Duplikate,
- Koordinatengrenzen,
- Pufferlängengrenzen `0`, `1`, `127`, `128`, `16_383`, `16_384` und
  `2_097_152`, soweit speicher- und laufzeitverträglich in einem
  zusammengefassten Test,
- fehlende beziehungsweise abgeschnittene Listengröße,
- negative oder überlange VarInt-Anzahlen,
- abgeschnittenes `ChunkPos`-Long,
- fehlende beziehungsweise abgeschnittene Pufferlänge,
- deklarierte Pufferlänge ohne genügend Bytes,
- Pufferlänge `2_097_153` und
- überlange VarInts.

Für 26.2 und 26.3 wird nicht behauptet, dass jeder Encoderfehler identisch
entsteht. Entscheidend ist:

- beide Decoder lehnen übergroße Puffer ab,
- das adaptereigene Wertmodell verhindert den Rebuild eines übergroßen
  Puffers in beiden Versionen und
- gültige Werte erzeugen dasselbe Wire-Format.

### 8.4 Katalog, Playback und Checkpoint

`PacketCoverageTest` beider Versionen prüft die explizite Katalogreihenfolge
bis einschließlich `chunks_biomes` sowie die vollständige Policy.

`Paper26_*PlaybackBridgeTest` prüft die Auswahl in Live- und
Rekonstruktionsmodus.

Die bestehende `familyFor(...)`-Zuordnung zu `CHUNK_LIGHT` wird in den
vorhandenen Checkpoint-Tests bereits katalogweit validiert. Ein zusätzlicher
Test wird nur ergänzt, falls die neue explizite Definition eine konkrete
Lücke sichtbar macht. Es wird kein redundantes Snapshot-Paket erzeugt.

### 8.5 Versionssymmetrie und Dokumentationsprüfung

Definitionen und Tests werden nach Normalisierung von `26_2`/`26_3` und den
versionsspezifischen Codec-Typen verglichen. Die fachliche Testmatrix bleibt
identisch.

Die Dokumentationsprüfung stellt sicher, dass:

- beide vollständigen Paketlisten erhalten bleiben,
- nur die betroffene Zeile präzisiert wird,
- Beschreibung/Relevanz, Playback- und Checkpoint-Policy enthalten sind und
- keine Aussage zum Implementierungs- oder Teststatus ergänzt wird.

### 8.6 Auszuführende Prüfungen nach Genehmigung

```powershell
.\gradlew.bat :replay-adapter:paper-common:test `
  :replay-adapter:paper-26_2:test `
  :replay-adapter:paper-26_3:test --console=plain
```

Zusätzlich:

- `git diff --check`,
- normalisierter Symmetrievergleich beider Definitionen und Tests,
- Katalogprüfung für alle expliziten IDs `0x00..0x0D`,
- Prüfung des geänderten Dateiumfangs,
- Prüfung, dass `.idea/` unberührt und unversioniert bleibt,
- Prüfung des aktiven Branchs vor jedem Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/world/Id013ChunksBiomesPacketDefinition.java`
- `packet/definition/world/WorldPacketDefinitions.java`

Tests je Version:

- `packet/definition/world/Id013ChunksBiomesPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`
- gegebenenfalls ein vorhandener Checkpoint-Test, aber nur bei einer
  nachgewiesenen Abdeckungslücke.

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser temporäre Implementierungsplan.

Nicht vorgesehen sind Änderungen an:

- Replay-Core oder Replay-Format,
- allgemeinen Paketabstraktionen,
- PacketEvents-Capture-Listenern,
- `PaperConnectionAccessor.packetPosition(...)`,
- Bukkit-/Paper-Event-Listenern,
- Replay-Identity-Mapping,
- View-Resettern,
- Snapshot-Providern,
- Checkpoint-Paketfamilien oder deren Reihenfolge,
- produktiven Playback-ChannelHandlern,
- Chunk-Batch-Control-Definitionen oder
- anderen Paket-IDs.

## 10. Risiken und Gegenmaßnahmen

### Veränderliche native Arrays

Risiko: Das native Paket oder ein Rebuild teilt `byte[]` mit dem
adaptereigenen Wert; spätere Mutationen verändern die Aufnahme semantisch.

Gegenmaßnahme: Tiefes Kopieren bei Read, Wertkonstruktion, Accessor und
Rebuild; gezielte Mutationsisolationstests.

### Verlust von Mehrfacheinträgen

Risiko: Eine Map-basierte Darstellung dedupliziert gleiche Chunkpositionen
oder verändert die Reihenfolge.

Gegenmaßnahme: Ausschließlich geordnete Liste verwenden; Duplikate explizit
testen und unverändert neu aufbauen.

### X-/Z-Vertauschung

Risiko: Die dokumentierte Z-vor-X-Wire-Darstellung wird als zwei separate
Integer missverstanden.

Gegenmaßnahme: Natives `ChunkPos` und dessen gepacktes Long verwenden;
Grenzwerte beider Achsen im Wire-Test kontrollieren.

### Überdimensionierte Puffer

Risiko: Der 26.2-Encoder schreibt einen Puffer, den der native Decoder wegen
der 2-MiB-Grenze ablehnt.

Gegenmaßnahme: Versionsübergreifende Validierung im Wertmodell vor dem
Rebuild; Decode-Grenze in beiden Versionen testen.

### Falsche Inhaltsinterpretation

Risiko: Die Definition versucht ohne Dimension- und Registry-Kontext,
Biome-Paletten zu dekodieren oder aus der aktuellen Welt neu aufzubauen.

Gegenmaßnahme: Puffer als opakes Protokollfeld erhalten; Zweck und
Einschränkung in Javadoc dokumentieren.

### Irreführender räumlicher Filter

Risiko: Eine einzelne Chunkposition wird als Position des Gesamtpakets
gemeldet und führt bei begrenzten Aufnahmebereichen zu falscher Auswahl.

Gegenmaßnahme: Keine Änderung an `packetPosition(...)`; Mehrpositionsvertrag
beibehalten.

### Redundanter Checkpoint-Zustand

Risiko: Der Snapshot-Provider sendet Biome-Daten doppelt durch
`level_chunk_with_light` und ein zusätzliches `chunks_biomes`.

Gegenmaßnahme: Definition checkpoint-relevant registrieren, aber vorhandene
vollständige Chunk-Snapshots nicht duplizieren.

### Event-basierte Abweichung

Risiko: Chunk-Events rekonstruieren andere Puffer oder eine andere
Gruppierung als tatsächlich auf dem Wire gesendet.

Gegenmaßnahme: Bestehenden finalen PacketEvents-/Channel-Wire-Capture nutzen;
keinen Event-Fallback ergänzen.

### Versionsdrift

Risiko: 26.2 und 26.3 erhalten wegen unterschiedlicher Codec-APIs
abweichende Semantik.

Gegenmaßnahme: Identisches Wertmodell und Policy, versionslokale native
Codec-Tests und normalisierter Symmetrievergleich.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- PLAY ID `0x0D` wird in beiden Adaptern explizit klassifiziert.
- Aufgezeichnete Biome-Updates werden in Live-Playback und Rekonstruktion
  bytegenau wiedergegeben.
- Mehrere Chunks, Duplikate, Koordinatengrenzen und die vollständige native
  Puffergrenze werden abgedeckt.
- Der Wert bleibt trotz nativer `byte[]` tief unveränderlich.
- Die vorhandene Chunk-Checkpoint-Familie kann die Definition validieren.
- Der Registry-Fingerprint ändert sich durch eine weitere explizite
  Definition; ohne Altaufnahme-Migration ist dies beabsichtigt.

### Bewusste Grenzen

- Die Definition dekodiert Biome-Paletten nicht in fachliche Biomeobjekte.
- Sie validiert nicht, ob der Puffer zur Höhe der aktuellen Dimension passt.
- Sie prüft nicht, ob der Zielchunk beim Client oder Server gerade geladen
  ist.
- Sie erzeugt keine zusätzlichen Biome-Pakete im Snapshot-Provider.
- Sie ordnet dem Mehrpositionspaket keine einzelne Capture-Position zu.
- Sie ersetzt weder `level_chunk_with_light` noch spätere
  `section_blocks_update`- oder Lichtpakete.
- Andere Paket-IDs und allgemeine Chunk-Refactorings bleiben außerhalb des
  Umfangs.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- den darin enthaltenen Quellen von
  `ClientboundChunksBiomesPacket`, `ChunkPos`, `FriendlyByteBuf`,
  `ByteBufCodecs` und `ChunkMap`,
- der bestehenden `RebuildingPacketDefinition`,
- den versionslokalen Packet-Catalogs und Playback-Bridges,
- `PaperConnectionAccessor` und dessen Mehrpositionsvertrag,
- `Paper26_*CheckpointEncoder.familyFor(...)`,
- dem aktuellen Snapshot-Provider und
- der Minecraft-Protokolldokumentation für `chunks_biomes` und das
  Chunkformat.

Externe Referenzen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Chunk_Biomes>
- <https://minecraft.wiki/w/Java_Edition_protocol/Chunk_format>

Die Online-Dokumentation beschreibt Feldreihenfolge, Chunkkoordinaten und
Biome-Payload. Maßgeblich für Klassenform, konkrete Codec-Grenzen und die
Unterschiede zwischen 26.2 und 26.3 bleiben die lokal angehefteten
Paper-Artefakte sowie die geplanten nativen Protokolltests.

## 13. Versionsverwaltung und Freigabepunkt

Vor der Implementierung wird ohne Branchwechsel geprüft, ob `master` und
`origin/master` synchron sind. Eine Abweichung oder ein Fetch-Problem wird
transparent gemeldet und überschreibt keine lokalen Änderungen. Wegen der
ausdrücklichen Vorgabe wird kein separater Aufgabenbranch erstellt und
ausschließlich auf `codex/refactor-packet-capture-rewrite` gearbeitet.

Bis zur ausdrücklichen Genehmigung werden keine Produktions-, Test- oder
Adapterinventardateien verändert. Nach der Freigabe wird der beschriebene
Umfang implementiert, validiert, mit einem englischen semantischen Commit
bereitgestellt und der temporäre Implementierungsplan gemäß dem bisherigen
Arbeitsablauf wieder entfernt.
