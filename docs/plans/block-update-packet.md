# Implementierungsplan: `block_update` (`0x08`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Umfang

Das PLAY-Clientbound-Paket `minecraft:block_update` mit der dezimalen ID `8`
beziehungsweise `0x08` wird in beiden Paper-Adaptern als explizite,
versionslokale Paketdefinition umgesetzt.

Die Implementierung soll:

1. das vollständige Wire-Format beider angehefteten Paper-Versionen bewahren,
2. jede in der jeweiligen Version registrierte `BlockState`-Variante erhalten,
3. die absolute Blockposition für räumlich begrenzte Recordings verwenden,
4. das Paket als dauerhaften und checkpoint-relevanten Weltzustand behandeln,
5. Live-Wiedergabe und Rekonstruktion nach einem Seek unterstützen,
6. keine Blockeigenschaften aus Namen, Material oder Live-Weltzustand neu
   ableiten,
7. die bestehende Reihenfolge zu Chunk-, Block-Entity-, Block-Event- und
   Section-Update-Paketen unverändert bewahren,
8. den finalen PacketEvents-Wire-Capture als primäre Erfassung verwenden und
9. keinen unvollständigen Bukkit-/Paper-Event-Fallback einführen.

Eine Migration alter Recordings oder eine Wiederbelebung des entfernten
Paketsystems ist ausdrücklich nicht Teil des Umfangs.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur und Ablage

Die bereits implementierten PLAY-Pakete liegen versionslokal unter
`packet.definition.<domain>`. `block_update` beschreibt den dauerhaften
Zustand genau eines Blocks und gehört deshalb in die vorhandene Domain
`world`.

Die synchrone Benennung beider Versionen lautet:

```text
Id008BlockUpdatePacketDefinition
```

Vorgesehene Ablage:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/world/
        └── Id008BlockUpdatePacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/world/
        └── Id008BlockUpdatePacketDefinition.java
```

`WorldPacketDefinitions` enthält derzeit die IDs `0x04`, `0x06` und `0x07`.
ID `0x08` wird dort ergänzt. Der übergeordnete Katalog sortiert Definitionen
bereits nach numerischer Paket-ID; seine Struktur muss nicht geändert werden.

### 2.2 Capture-Pfad und ChannelHandler

Der bestehende `Paper26_*CaptureBridge` zeichnet ausgehende Pakete über
PacketEvents auf `MONITOR`-Ebene auf und kopiert die Payload erst in der
Post-Task. Maßgeblich ist dadurch der tatsächlich an den Empfänger gehende
Wire-Buffer nach vorherigen PacketEvents-Rewrites.

Dieser Pfad deckt `block_update` vollständig ab:

- Vanilla- und plugin-erzeugte Pakete werden gleichermaßen erfasst,
- empfängerabhängige Paketänderungen bleiben erhalten,
- Position und globale Block-State-ID werden nicht durch ein Wrapper-Modell
  normalisiert,
- Capture-Zeitpunkt und Reihenfolge bleiben im bestehenden Sequenzmodell und
- das Paket wird nicht aus einem Bukkit-Event rekonstruiert.

`PaperConnectionAccessor.packetPosition(...)` erkennt
`ClientboundBlockUpdatePacket` in beiden Versionen bereits und gibt
`getPos()` über die gemeinsame `BlockPosition`-Konvertierung zurück. Für die
Produktion ist dort keine Änderung nötig. Ein expliziter Test soll den bereits
vorhandenen Vertrag jetzt der Paketimplementierung zuordnen und gegen
Regression absichern.

### 2.3 Playback-Pfad

Der bestehende Playback-Pfad arbeitet registry- und codec-gesteuert:

1. die gespeicherte Payload wird mit dem nativen, an die Verbindung
   gebundenen PLAY-Codec decodiert,
2. die Paketdefinition liest und validiert Position und `BlockState`,
3. die Rewrite-Stufe lässt beide Werte unverändert,
4. ein neues `ClientboundBlockUpdatePacket` wird aufgebaut und
5. der native Codec validiert Phase, Richtung und Paket-ID beim erneuten
   Encode.

Das Paket enthält weder Entity-ID noch Profil-UUID oder eine andere
sitzungsgebundene Identität. Eine Identity-Map ist nicht erforderlich.

### 2.4 Checkpoint-Vertrag

Die vorhandene Checkpoint-Architektur ist auf `block_update` vorbereitet:

- `Paper26_*CheckpointEncoder.familyFor(...)` ordnet den Descriptor bereits
  der Familie `BLOCK_ENTITY` zu. Der Familienname umfasst historisch sowohl
  Blockzustände als auch Block-Entity-Inhalte und wird in dieser Aufgabe nicht
  umbenannt.
- `ChunkCheckpointEncoder` gibt diese Familie nach dem vollständigen
  Chunk-/Light-Zustand aus.
- `Paper26_*SnapshotProvider` erzeugt vollständige Chunk-Pakete und danach
  Block-Entity-Daten. Der Grundzustand aller Blocks steckt bereits im
  Chunk-Paket; es ist daher kein redundantes `block_update` für jeden Block
  eines Snapshots nötig.
- Als checkpoint-relevante Definition darf ein aufgezeichnetes
  `block_update` bei der Rekonstruktion von Änderungen nach dem gewählten
  Checkpoint wiedergegeben werden.

Diese Trennung ist wichtig: `checkpointRelevant = true` bedeutet, dass ein
dauerhafter Blockzustandswechsel während Rekonstruktion nicht verworfen wird.
Es bedeutet nicht, dass der Snapshot-Provider jeden Block zusätzlich als
Einzelpaket duplizieren soll.

### 2.5 Verhältnis zu benachbarten Paketen

`block_update` ersetzt keine anderen Weltpakete:

- `level_chunk_with_light` liefert den vollständigen Ausgangszustand eines
  Chunks.
- `section_blocks_update` überträgt mehrere Blockzustände derselben Section
  effizient als Gruppe.
- `block_entity_data` überträgt zusätzliche, typabhängige Block-Entity-Daten;
  der Blockzustand allein enthält beispielsweise keinen Inventar- oder
  Sign-Inhalt.
- `block_event` überträgt einen geordneten, meist flüchtigen Trigger und
  keinen dauerhaften Ersatzblockzustand.
- `block_destruction` zeichnet nur die clientseitige Abbau-Overlaystufe.

Die Pakete dürfen nicht zusammengeführt, dedupliziert oder aus der Live-Welt
neu abgeleitet werden. Insbesondere kann eine Folge mehrerer Blockzustände an
derselben Position sichtbare Übergänge, Block-Entity-Lebenszyklen,
Nachbarzustände und Client-Prediction beeinflussen.

## 3. Verifizierter nativer Protokollvertrag

Die lokal angehefteten Paper-Dev-Bundles 26.2 und 26.3 enthalten denselben
öffentlichen Vertrag:

```java
ClientboundBlockUpdatePacket(BlockPos position, BlockState blockState)
ClientboundBlockUpdatePacket(BlockGetter level, BlockPos position)

BlockPos getPos()
BlockState getBlockState()
```

Die zweite Konstruktorform liest den Zustand sofort aus dem übergebenen
`BlockGetter`. Für Replay wird ausschließlich die erste Form verwendet, damit
der aufgezeichnete Zustand nicht durch die aktuelle Serverwelt ersetzt wird.

### 3.1 Wire-Reihenfolge

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | Position | gepackte Blockposition | `BlockPos` | absolute Zielposition |
| 2 | Block State | VarInt | `BlockState` | ID aus `Block.BLOCK_STATE_REGISTRY` |

Die gepackte Position besitzt die kanonischen Wire-Grenzen:

- X/Z: `-33_554_432` bis `33_554_431`,
- Y: `-2_048` bis `2_047`.

Die Zustands-ID ist **keine** ID aus `minecraft:block`. Sie verweist auf die
globale Menge sämtlicher registrierter Blockzustände. Verschiedene
Property-Kombinationen desselben Blocks besitzen unterschiedliche IDs.

Beispiele für zu erhaltende Properties sind unter anderem:

- `facing`, `axis`, `rotation` und andere Orientierungen,
- `waterlogged`, `lit`, `powered`, `open`, `locked` und `enabled`,
- Alters-, Feuchtigkeits-, Honig-, Schnee- und Redstone-Level,
- Slab-, Stair-, Wall-, Door-, Trapdoor- und Rail-Formen,
- Verbindungen von Zäunen, Wänden, Drähten und Mehrfachblöcken,
- Instrument-, Attachment-, Thickness- und vergleichbare Enum-Properties und
- alle neuen, versionsspezifischen Zustände der beiden angehefteten Builds.

Produktionscode darf diese Properties weder einzeln auflisten noch auf eine
bekannte Teilmenge beschränken. Das native `BlockState`-Objekt ist bereits die
vollständige, kanonische Darstellung.

### 3.2 Registry- und Fehlerverhalten

Der Stream-Codec verwendet:

```java
ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)
```

Beim Encode wird die ID des konkreten `BlockState`-Singletons über den
strikten `getIdOrThrow(...)`-Vertrag geschrieben. Beim Decode löst der native
Codec die VarInt-ID über `byIdOrThrow(...)` auf.

Wichtige Konsequenzen:

- jeder registrierte Zustand muss ohne Sonderbehandlung unterstützt werden,
- `null` ist kein gültiger Zustand,
- ein nicht registriertes `BlockState`-Objekt darf nicht als ID `-1`
  serialisiert werden und
- eine unbekannte Wire-ID wird bereits vom nativen Codec abgewiesen und kann
  den Read-/Value-Vertrag nicht als `null`-Zustand erreichen.

Zusätzlich bleiben die bestehenden Codec-Grenzen maßgeblich: abgeschnittene
Positionen, fehlende State-VarInts und überlange VarInts müssen scheitern.

### 3.3 Tatsächliche Client-Auswertung

Der 26.3-Client-Handler führt nach dem Threadwechsel aus:

```java
level.setServerVerifiedBlockState(packet.getPos(), packet.getBlockState(), 19);
```

Der Client behandelt das Paket damit als serververifizierten, dauerhaften
Blockzustand und nicht als reine Renderanimation. Abhängig vom Wechsel kann
dies unter anderem:

- die gerenderte Geometrie und Kollision ändern,
- Neighbor-/Shape-Aktualisierungen auslösen,
- Licht- und Renderbereiche invalidieren,
- Block Entities anlegen, entfernen oder auf einen anderen Typ umstellen und
- eine clientseitig vorhergesagte Blockänderung mit dem Serverstand
  abgleichen.

Die Zahl `19` ist Client-Implementierungsdetail und kein Wire-Feld. Sie wird
nicht im Replayformat gespeichert und nicht serverseitig nachgebildet.

## 4. Klassifikation und Policy

Die Definition erhält:

| Eigenschaft | Wert |
|---|---|
| Disposition | `STATEFUL` |
| Standard-Capture | `true` |
| Replay | `true` |
| Checkpoint | `true` |
| Playback-Scope | `SAFE` |

Begründung:

- Das Paket ersetzt den dauerhaften Blockzustand einer Position.
- Seine Wirkung bleibt bestehen, bis ein späteres Block-, Section- oder
  Chunk-Paket den Zustand erneut ersetzt.
- Änderungen nach einem Checkpoint müssen bei einer Seek-Rekonstruktion
  wiedergegeben werden.
- Position und Blockzustand enthalten keine Playback-Identitäten.
- Ein vollständiger Snapshot liefert den Ausgangszustand bereits über das
  Chunk-Paket; spätere Einzelupdates ergänzen diesen geordnet.

`PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE)` bildet
diesen Vertrag direkt ab.

## 5. Geplante Implementierung

### 5.1 Versionslokale Paketdefinitionen

Beide Klassen erweitern:

```text
RebuildingPacketDefinition<
    Paper26_*ReplayPacketRewriter,
    ClientboundBlockUpdatePacket,
    Value>
```

Das interne Wertmodell lautet:

```java
record Value(BlockPos position, BlockState blockState) {}
```

Verarbeitung:

1. `read(...)` übernimmt `getPos()` und `getBlockState()` ohne Umwandlung.
2. `rewrite(...)` gibt exakt denselben Wert als `SEND` zurück.
3. `rebuild(...)` verwendet
   `new ClientboundBlockUpdatePacket(position, blockState)`.
4. `Value` lehnt eine `null`-Position und einen `null`-Zustand ab.
5. `Value` prüft, dass `Block.BLOCK_STATE_REGISTRY.getId(blockState)` eine
   gültige nichtnegative ID liefert. Dadurch wird ein künstlich erzeugter,
   unregistrierter Zustand vor dem Encode abgewiesen.

Die Definition interpretiert keine Property und ruft insbesondere nicht
`level.getBlockState(position)` auf. Damit bleibt auch ein Zustand erhalten,
der von der zur Playback-Zeit aktuellen Serverwelt abweicht.

### 5.2 Registrierung und Coverage

`WorldPacketDefinitions` beider Versionen wird um ID `0x08` ergänzt. Der
Gesamtkatalog enthält danach die expliziten PLAY-Definitionen `0x00` bis
einschließlich `0x08` in numerischer Reihenfolge.

`PacketCoverageTest` wird synchron erweitert:

- exakter Typname `play.clientbound.minecraft:block_update`,
- Disposition `STATEFUL`,
- Capture aktiv,
- Replay aktiv,
- Checkpoint-Relevanz aktiv,
- Scope `SAFE` und
- alle späteren, noch nicht implementierten Pakete bleiben `UNSUPPORTED`.

### 5.3 Capture-Position

Der vorhandene `PaperConnectionAccessor` unterstützt
`ClientboundBlockUpdatePacket` bereits vollständig. Es ist keine Änderung an
dieser Produktionsklasse vorgesehen.

`Paper26_*CaptureBridgeTest` erhält jedoch einen expliziten Fall mit einer
Position an den Wire-Grenzen. Der Test bestätigt, dass:

- genau die Paketposition in `CaptureContext.position` landet,
- keine Position aus Chunk oder Blockzustand abgeleitet wird und
- die Klassifikation `STATEFUL/SAFE` die räumliche Zuordnung nicht verändert.

### 5.4 Playback und Checkpoint

`Paper26_*PlaybackBridgeTest` prüft:

- `block_update` wird im Modus `LIVE` gesendet,
- `block_update` wird im Modus `RECONSTRUCTION` gesendet und
- der Descriptor ist checkpoint-relevant.

An `Paper26_*CheckpointEncoder`, `ChunkCheckpointEncoder` und
`Paper26_*SnapshotProvider` ist keine Produktionsänderung geplant. Die
vorhandene Familie und Reihenfolge sind bereits passend:

1. vollständiger Chunk-/Light-Zustand,
2. blockbezogene Zustände beziehungsweise Block-Entity-Daten,
3. weitere globale und Entity-Zustände.

Ein zusätzlicher Snapshot-Generator für jedes einzelne `block_update` wäre
redundant, würde Checkpoints massiv vergrößern und könnte mit dem bereits
enthaltenen Chunkzustand konkurrieren.

### 5.5 Reset-Verhalten

Ein `block_update` verändert den Replay-Chunk dauerhaft. Beim Ende oder
Wechsel der Wiedergabe entfernt der vorhandene View-Reset die Replay-Chunks
und stellt danach den Live-View wieder her. Ein inverses Paket pro Änderung
ist weder nötig noch sicher, da dafür der vorherige Zustand und die korrekte
Block-Entity-Reihenfolge benötigt würden.

Es wird daher kein paketbezogener Reset-Tracker ergänzt.

## 6. Event-Fallback-Entscheidung

Ein Bukkit-/Paper-Event ist für dieses Paket kein vollständiger Ersatz.
Blockzustandsänderungen können unter anderem aus folgenden Quellen stammen:

- Spielerinteraktion,
- Physik, Redstone und Flüssigkeiten,
- Wachstum, Alterung und zufällige Ticks,
- Pistons, Explosionen und Weltgeneration,
- Chunk- oder Section-Synchronisierung,
- direkte NMS-/PacketEvents-Pakete von Plugins,
- empfängerabhängige Fake-Block-Zustände und
- vorherige ChannelHandler-Rewrites.

Ein Event-Fallback würde mindestens Empfänger, exakte globale State-ID,
Paketform, finale Rewrite-Ergebnisse und Wire-Reihenfolge verlieren. Einige
Ereignisse erzeugen zudem Section-Pakete statt Einzelupdates oder gar kein
Bukkit-Event.

Deshalb bleibt ausschließlich der vorhandene finale Wire-Pfad aktiv. Events
sind nur eine theoretische Notfallebene für Umgebungen ohne funktionsfähigen
Paketpfad; diese Voraussetzung liegt in den unterstützten Adaptern nicht vor.

## 7. Adapter-Dokumentationen

Die vollständigen Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig erhalten. Nur die Zeile für `minecraft:block_update`
wird präzisiert:

- Beschreibung/Relevanz: absolute, dauerhafte Ersetzung genau eines globalen
  Blockzustands einschließlich sämtlicher Properties,
- Playback-Policy: unverändert senden,
- Checkpoint-Policy: relevant.

Die Dokumente erhalten weiterhin keine Informationen über
Implementierungsfortschritt oder Tests einzelner Pakete. Alle übrigen
Paketzeilen bleiben unangetastet.

## 8. Teststrategie

### 8.1 Definitionstests je Version

Je Version entsteht ein
`Id008BlockUpdatePacketDefinitionTest` mit folgenden Fällen:

1. ID `8`/`0x08`, exakter Identifier und nativer Pakettyp.
2. Klassifikation `STATEFUL`, Capture an, Replay an, Checkpoint an und
   Playback-Scope `SAFE`.
3. Read/Rewrite/Rebuild mit repräsentativen Zustandsfamilien:
   - Luft und Vollblock,
   - orientierter Block,
   - wasserfüllbarer Zustand,
   - Redstone-/Power-Level,
   - Alter-/Wachstumszustand,
   - Mehrfachblock beziehungsweise Shape-Property,
   - Block mit Block Entity.
4. Sämtliche Einträge aus `Block.BLOCK_STATE_REGISTRY` werden unverändert
   gelesen, rewritten und aufgebaut.
5. Objektidentität des kanonischen `BlockState` bleibt erhalten; es wird kein
   neuer oder aus Properties rekonstruierter Zustand erzeugt.
6. Positionen an allen gepackten Wire-Grenzen.
7. Ablehnung von `null` für Position und Zustand.
8. Ablehnung unbekannter State-IDs durch den nativen strikten Codec. Ein
   unregistrierter Blockzustand kann nach dem erforderlichen Minecraft-
   Bootstrap nicht künstlich erzeugt werden, weil die Registry eingefroren
   ist.
9. Ablehnung einer falschen decodierten Java-Klasse.
10. Vollständiger `handlePlayback`- und validierter Encode-Pfad.

### 8.2 Native Wire-Tests

Die nativen Codec-Tests bestätigen je Version:

- Paket-ID `0x08`,
- exakte Reihenfolge `BlockPos` vor State-VarInt,
- State-ID entspricht `Block.BLOCK_STATE_REGISTRY.getId(state)`,
- Roundtrip aller registrierten State-IDs,
- Roundtrip der Positionsgrenzen,
- vollständiger Verbrauch gültiger Payloads und
- keine Normalisierung eines gültigen Zustands.

Negative und Grenzfälle umfassen:

- vollständig fehlenden Body,
- abgeschnittene gepackte Position,
- fehlende State-ID,
- überlange VarInt-State-ID,
- negative und außerhalb der Registry liegende IDs, die der native
  `byIdOrThrow(...)`-Vertrag abweist, und
- nachlaufende Bytes über den vorhandenen verbindungsgebundenen Decoder, der
  vollständigen Payloadverbrauch verlangt.

### 8.3 Capture-, Playback- und Checkpoint-Tests

`Paper26_*CaptureBridgeTest` sichert die bereits vorhandene exakte
Positionsableitung ab.

`Paper26_*PlaybackBridgeTest` prüft Live- und Rekonstruktionsmodus.

Die vorhandenen Checkpoint-Fixtures werden nur dann angepasst, wenn der neue
explizite Descriptor ihre bisher abstrakten `block_update`-Annahmen berührt.
Eine neue Produktionslogik wird daraus nicht abgeleitet. Mindestens wird
verifiziert, dass die bestehende Familie `BLOCK_ENTITY` akzeptiert bleibt und
der Snapshot nicht mit Einzelupdates für jeden Chunkblock aufgebläht wird.

Der bestehende PacketEvents-Test bleibt maßgeblich für den finalen,
möglicherweise umgeschriebenen Wire-Buffer. Es wird kein zweiter
paketspezifischer Listener eingeführt.

### 8.4 Registry und Dokumentation

`PacketCoverageTest` prüft den expliziten Katalogvertrag bis ID `0x08`.

Die Dokumentationsprüfung stellt sicher, dass:

- alle Paketzeilen beider Versionen erhalten bleiben,
- die `block_update`-Zeile Beschreibung, Playback- und Checkpoint-Policy
  enthält und
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
- Synchronitätsvergleich der versionslokalen Definitionen und Tests,
- Prüfung aller geänderten Dateien gegen den Aufgabenumfang,
- Prüfung, dass `.idea/` unberührt und unversioniert bleibt,
- Prüfung des aktiven Branchs vor dem Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/world/Id008BlockUpdatePacketDefinition.java`
- `packet/definition/world/WorldPacketDefinitions.java`

Tests je Version:

- `packet/definition/world/Id008BlockUpdatePacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `capture/Paper26_2CaptureBridgeTest.java` beziehungsweise
  `capture/Paper26_3CaptureBridgeTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`
- gegebenenfalls eine eng begrenzte Ergänzung bestehender Checkpoint-Fixtures,
  falls die Integration dies erfordert.

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser Implementierungsplan

Nicht vorgesehen sind Änderungen an:

- Core-Format oder Recording-Migration,
- PacketEvents-Listenern oder PacketEvents-Wrappern,
- Paper-/Bukkit-Event-Listenern,
- `PaperConnectionAccessor`-Produktionscode,
- Identity-Map,
- Checkpoint-Encodern, Snapshot-Providern oder deren Familiennamen,
- View-Resettern,
- allgemeinen ChannelHandlern oder
- anderen Paketdefinitionen.

## 10. Risiken und Gegenmaßnahmen

### Globale State-ID statt Block-ID

Risiko: Nur den Blocktyp zu speichern würde alle Properties verlieren und
beispielsweise Treppenrichtung, Wasserfüllung oder Redstone-Stufe verfälschen.

Gegenmaßnahme: Die Definition bewahrt das native `BlockState`-Objekt und der
Wire-Test prüft jede registrierte globale State-ID.

### Unregistrierter Zustand

Risiko: Ein künstlich konstruierter `BlockState`, der nicht im IdMapper liegt,
kann vom nativen `getIdOrThrow(...)`-Codec nicht serialisiert werden.

Gegenmaßnahme: Der Value-Vertrag verlangt eine gültige nichtnegative ID in
`Block.BLOCK_STATE_REGISTRY`.

### Reihenfolge zu Block Entities und Events

Risiko: Deduplizierung oder Umsortierung kann Daten auf einen falschen
Blocktyp anwenden, Block Entities zu früh erzeugen oder einen Trigger gegen
den falschen Zustand ausführen.

Gegenmaßnahme: Live-Frames behalten die Capture-Reihenfolge. Checkpoints
beginnen mit dem vollständigen Chunkzustand; keine neue Sortier- oder
Deduplizierungslogik wird eingeführt.

### Registry-Kompatibilität

Risiko: Globale Block-State-IDs sind versionsgebunden und können sich schon
bei hinzugefügten Zuständen verschieben.

Gegenmaßnahme: Decode und Encode verwenden ausschließlich den nativen Codec
der jeweils angehefteten Paper-Version. Die vorhandene Adapter- und
Registry-Fingerprint-Prüfung bleibt die Kompatibilitätsgrenze.

### Event-basierte Unter- oder Doppelerfassung

Risiko: Ein zusätzlicher Event-Fallback könnte dieselbe Änderung doppelt
aufzeichnen und dennoch Fake-Blocks, Pluginpakete oder Section-Updates
übersehen.

Gegenmaßnahme: Ausschließlich der vorhandene finale Wire-Pfad wird verwendet.

### Checkpoint-Größe

Risiko: Ein Einzelpaket pro Block würde den bereits vollständigen Chunkzustand
duplizieren und Checkpoints extrem vergrößern.

Gegenmaßnahme: Der Snapshot bleibt chunkbasiert. `checkpointRelevant` steuert
die Rekonstruktion nachfolgender Zustandsänderungen, nicht die Erzeugung einer
zweiten Blockliste.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- Einzelne Blockzustandswechsel werden in Live-Wiedergabe sichtbar.
- Seek-Rekonstruktionen behalten dauerhafte Änderungen nach dem Checkpoint.
- Sämtliche registrierten Property-Kombinationen der jeweiligen Version
  bleiben verlustfrei erhalten.
- Räumliche Recording-Scopes verwenden die exakte Blockposition.
- Der Registry-Fingerprint ändert sich durch die neue explizite Definition;
  dies ist ohne Altaufnahme-Migration beabsichtigt.
- Beide Paper-Versionen behalten synchrone Namen, Struktur und Policy.

### Bewusste Grenzen

- Block-Entity-NBT wird nicht aus `BlockState` abgeleitet; dafür bleibt
  `block_entity_data` zuständig.
- Mehrfachupdates werden nicht automatisch in `section_blocks_update`
  umgewandelt.
- Lichtdaten werden nicht serverseitig synthetisiert; Chunk- und
  Light-Pakete bleiben ihre eigene Zuständigkeit.
- Ungeladene Client-Chunks werden nicht durch ein Einzelupdate ersetzt; die
  korrekte Chunkbasis bleibt Voraussetzung der geordneten Wiedergabe.
- Andere Paket-IDs und nicht erforderliche Refactorings bleiben außerhalb des
  Umfangs.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- dem dekompilierten `ClientboundBlockUpdatePacket` beider Builds,
- dem lokalen `ByteBufCodecs.idMapper(...)`-, `getIdOrThrow(...)`- und
  `byIdOrThrow(...)`-Vertrag,
- dem 26.3-Client-Handler `handleBlockUpdate(...)`,
- der bestehenden Capture-, Playback- und Checkpoint-Architektur des
  Projekts und
- dem Java-Edition-Protokollinventar für ID, Position und globale
  Block-State-ID.

Relevante externe Quellen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Block_Update>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundBlockUpdatePacket.html>
- <https://mc-packet-reference.netlify.app/26.x/packets/clientboundblockupdatepacket/>
- <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>

Die externen Seiten dokumentieren Wire-Vertrag und Clientwirkung. Maßgeblich
für Klassenform, Codec und versionsspezifische State-Registry bleiben die
lokal angehefteten Paper-Artefakte und die geplanten nativen Roundtrip-Tests.

## 13. Versionsverwaltung und Freigabepunkt

Vor der Implementierung wird ohne Branchwechsel geprüft, ob `master` und
`origin/master` synchron sind. Eine Abweichung oder ein Fetch-Problem wird
transparent gemeldet und überschreibt keine lokalen Änderungen.

Aufgrund der ausdrücklichen Vorgabe wird kein neuer Aufgabenbranch erstellt.
Alle Arbeiten bleiben auf `codex/refactor-packet-capture-rewrite`.

Bis zur ausdrücklichen Genehmigung werden keine Produktions- oder Testklassen
verändert. Nach der Freigabe wird nur der oben beschriebene Umfang
implementiert, validiert und mit einem kurzen englischen semantischen Commit
bereitgestellt.
