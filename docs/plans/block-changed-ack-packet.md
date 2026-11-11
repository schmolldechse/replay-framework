# Implementierungsplan: `block_changed_ack` (`0x04`)

Status: **Umgesetzt**

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel

Das PLAY-Clientbound-Paket `minecraft:block_changed_ack` mit der dezimalen ID
`4` beziehungsweise `0x04` soll in beiden Paper-Adaptern als explizite
Paketdefinition umgesetzt werden.

Das Paket ist kein Blockzustand und keine Aussage darüber, ob eine
Blockinteraktion erfolgreich war. Es bestätigt ausschließlich, bis zu welcher
verbindungslokalen Sequenz der Server die vom Client ausgelösten und lokal
vorhergesagten Blockänderungen verarbeitet hat. Die Sequenz des aufgenommenen
Clients besitzt auf der Verbindung des neutralen Replay-Beobachters keine
Bedeutung.

Die vollständige Implementierung muss deshalb:

1. ID, Identifier, native Klasse und Wire-Format beider Versionen verifizieren,
2. das Paket über den vorhandenen finalen Wire-Capture erfassen,
3. seinen kumulativen und verbindungslokalen Charakter dokumentieren,
4. es als nicht replaybares `CONTROL`-Paket klassifizieren,
5. es vor jeder Playback-Decodierung und vor dem Replay-Writer herausfiltern,
6. die eigenen Live-Acknowledgements des Beobachters weiterhin passieren
   lassen,
7. es aus Checkpoints ausschließen,
8. gültige, ungewöhnliche und fehlerhafte Payloadvarianten testen.

## 2. Abgrenzung

Im Aufgabenbereich liegen ausschließlich:

- `Id004BlockChangedAckPacketDefinition` für Paper 26.2 und 26.3,
- die neue Domain-Aggregation unter `definition.world`,
- Katalog- und Registry-Coverage für ID `4`,
- Tests des vollständigen nativen Wire-Formats,
- Tests der Decode-freien Playback-Sperre,
- Tests, dass der Playback-Bridge-Filter aufgezeichnete Acknowledgements in
  beiden Emissionsmodi verwirft,
- die unmittelbar betroffene Adapterdokumentation.

Nicht Bestandteil sind:

- Wiedergabe oder Umschreibung aufgezeichneter Sequenznummern,
- Synthese eigener Acknowledgements während der Wiedergabe,
- Änderungen an der clientseitigen Block-Prediction,
- Änderungen an `block_update`, `block_entity_data` oder späteren Blockpaketen,
- ein neuer PacketEvents- oder Bukkit-Listener,
- ein neuer Capture- oder Playback-ChannelHandler,
- Änderungen an Recording- oder Datenbankformaten,
- Migration alter Recordings,
- allgemeine Refactorings außerhalb der notwendigen Katalogerweiterung.

## 3. Analysierter Projektzustand

Der Branch `codex/implement-block-changed-ack-packet` basiert auf dem aktuellen
Stand von `codex/refactor-packet-capture-rewrite`. Die bisherigen expliziten
PLAY-Definitionen decken die IDs `0` bis `3` ab:

| ID | Paket | Domain | Klassifikation |
|---:|---|---|---|
| 0 | `bundle_delimiter` | `connection` | `CONTROL` / `UNSAFE` |
| 1 | `add_entity` | `entity` | `STATEFUL` / `REWRITE_IDENTITIES` |
| 2 | `animate` | `effect` | `EPHEMERAL` / `REWRITE_IDENTITIES` |
| 3 | `award_stats` | `player` | `STATEFUL` / `VIEWER_LOCAL` |

Das Layout-Dokument ordnet Blöcke der Domain `world` zu. Die Ablage richtet
sich nach der Protokollverantwortung und nicht nach der aktuellen
Playback-Policy. `block_changed_ack` kommt deshalb trotz seiner
verbindungslokalen Sequenzsemantik unter `definition.world` und nicht unter
`connection` oder `playback`.

Die vorhandene gemeinsame Basisklasse
`AbstractBlockedPacketDefinition` bildet den erforderlichen Vertrag bereits
vollständig ab:

- Disposition `CONTROL`,
- Capture standardmäßig aktiv,
- nicht replaybar,
- nicht checkpoint-relevant,
- Playback-Scope `UNSAFE`.

Die Playback-Bridge filtert nicht replaybare Descriptoren in `shouldReplay`
aus, bevor Rewriter und Codec aufgerufen werden. Der Channel-lokale
`ReplayViewerOutboundGate` unterdrückt dagegen nur replaybare Szene-Pakete.
Live-`CONTROL`-Pakete des Beobachters passieren ihn unverändert. Damit werden
aufgezeichnete Acknowledgements verworfen, während die eigenen
Acknowledgements für reale Observer-Interaktionen funktionsfähig bleiben.

Die Checkpoint-Encoder besitzen bereits eine defensive
`block_changed_ack`-Fehlergrenze mit der Begründung, dass dieses Paket keinen
rekonstruierbaren Checkpoint-Zustand darstellt. Die neue Definition verhindert
durch `checkpointRelevant == false`, dass ein regulärer Frame diese Grenze
überhaupt erreicht. Die Encoder müssen nicht verändert werden.

## 4. Verifizierter Protokollvertrag

### 4.1 Paket-ID und native Klasse

Die live gebundenen Paper-Registries und die exakten Paperweight-Artefakte
beider Builds verwenden:

| Eigenschaft | Wert |
|---|---|
| Phase | PLAY |
| Richtung | clientbound |
| ID | `4` / `0x04` |
| Identifier | `minecraft:block_changed_ack` |
| Native Klasse | `ClientboundBlockChangedAckPacket` |

Die native Klasse ist in beiden Zielversionen strukturell identisch und als
Record definiert:

```java
ClientboundBlockChangedAckPacket(int sequence)
```

### 4.2 Wire-Format

Nach der Paket-ID enthält der Payload genau ein Feld:

| Reihenfolge | Feld | Wire-Typ | Bedeutung |
|---:|---|---|---|
| 1 | `sequence` | VarInt | höchste kumulativ bestätigte Prediction-Sequenz |

Der native `STREAM_CODEC` verwendet ausschließlich `readVarInt()` und
`writeVarInt(sequence)`. Es gibt:

- keine Blockposition,
- keinen Blockzustand,
- keinen Entity- oder Player-Identifier,
- keinen Aktionstyp,
- kein Erfolgs- oder Ablehnungsbit,
- keine Liste einzelner Sequenzen.

Der Wire-Typ kann technisch jeden Java-`int` darstellen. Der reguläre
Serverpfad akzeptiert jedoch nur nichtnegative Serverbound-Sequenzen. Paper
trennt den Spieler bei einer negativen Sequenz als `ILLEGAL_ACTION` und wirft
zusätzlich eine `IllegalArgumentException`. Der legitime servererzeugte
Wertebereich ist damit `0` bis `Integer.MAX_VALUE`.

Die Tests unterscheiden bewusst zwischen:

- regulären Werten `0`, `1`, `127`, `128`, `16383`, `16384` und
  `Integer.MAX_VALUE`,
- technisch vom clientbound Codec darstellbaren negativen `int`-Werten, die
  Vanilla/Paper nicht regulär erzeugen, aber durch direkt konstruierte
  Plugin-Pakete auftreten könnten,
- leeren, abgeschnittenen oder überlangen VarInt-Payloads.

Für die Replay-Policy ist der Inhalt in allen drei Gruppen gleich: Der Frame
darf den Observer-Codec nicht erreichen.

### 4.3 Erzeugung auf dem Server

`ServerGamePacketListenerImpl` übernimmt Sequenzen aus mindestens diesen
serverbound Interaktionsfamilien:

- Blockabbau-/Blockaktionspakete,
- `use_item_on`,
- `use_item`.

`ackBlockChangesUpTo(sequence)` speichert das Maximum aus der neuen und der
bereits ausstehenden Sequenz. Mehrere Interaktionen innerhalb eines
Server-Ticks können deshalb in einem einzigen kumulativen Ack zusammenfallen.
Beim regulären Tick sendet der Server höchstens den aktuell vorgemerkten
Maximalwert und setzt den internen Marker anschließend auf `-1` zurück.

Paper ergänzt in beiden Ziel-Builds einen wichtigen Sonderpfad: Wenn eine
vorhergesagte Blockzerstörung ein Block-Entity verändert, wird das Ack sofort
vor dem Block-Entity-Update gesendet und der vorgemerkte Wert zurückgesetzt.
Dadurch verhindert Paper, dass die spätere Prediction-Auflösung das
Block-Entity-Update überschreibt. Der exakte Zeitpunkt und die Reihenfolge des
Pakets können daher nicht zuverlässig aus Bukkit-Gameplay-Events abgeleitet
werden.

Ein Ack enthält kein Urteil. Akzeptierte und abgelehnte Vorhersagen verwenden
dasselbe Paket. Notwendige Korrekturen werden als Block-/Block-Entity-Updates
vor dem Ack gesendet; erst die Kombination aus Stream-Reihenfolge und
kumulativer Sequenz beendet die betreffenden Client-Predictions.

### 4.4 Wirkung auf dem Client

Der Client leitet `packet.sequence()` über
`ClientPacketListener#handleBlockChangedAck` an
`ClientLevel#handleBlockChangedAck` weiter. Dort beendet
`BlockStatePredictionHandler#endPredictionsUpTo` alle passenden ausstehenden
Predictions bis zu dieser Sequenz und synchronisiert die gespeicherten,
serververifizierten Blockzustände. Bei kollisionsrelevanten Korrekturen kann
auch die zuvor gespeicherte Spielerposition wiederhergestellt werden.

Ein aufgezeichneter Wert ist damit gefährlich für den neutralen Beobachter:

- die Sequenz gehört zum ursprünglichen Client und nicht zum Observer,
- derselbe Zahlenwert kann beim Observer eine völlig andere Interaktion
  bezeichnen,
- ein zu hoher Wert kann mehrere noch ausstehende Observer-Predictions
  vorzeitig abschließen,
- ein alter oder niedriger Wert besitzt keine rekonstruierbare Szene-Wirkung,
- ohne die zugehörigen serverbound Aktionen und Korrekturpakete ist die
  Bedeutung nicht wiederherstellbar.

Es existiert deshalb keine korrekte Identity- oder Sequenz-Umschreibung. Das
Paket muss als aufgezeichnetes Connection-Control vollständig blockiert
bleiben.

### 4.5 Informationsgehalt

Das Paket gibt ausschließlich preis, bis zu welcher Sequenz der Server die
Interaktions-Pipeline des Empfängers verarbeitet hat. Aus zeitlicher Folge und
steigenden Werten kann ein Diagnosewerkzeug Aktivität und Batching erkennen.
Nicht ableitbar sind dagegen:

- betroffener Block oder Chunk,
- verwendete Hand oder verwendetes Item,
- Art der Interaktion,
- Annahme oder Ablehnung der Aktion,
- resultierender Blockzustand,
- konkrete Anzahl einzelner Aktionen innerhalb eines kumulierten Bereichs.

Es ist daher als Diagnose- und Stream-Ordnungsinformation erfassbar, aber
nicht als eigenständiger Replay-Zustand verwendbar.

### 4.6 Quellen

- Minecraft Wiki, Paket-ID, einzelnes VarInt-Feld und
  Prediction-Synchronisation:
  <https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Acknowledge_Block_Change>
- Decompiled Minecraft 26.3 `ClientboundBlockChangedAckPacket` mit identischem
  Read-/Write-Codec:
  <https://mc-packet-reference.netlify.app/26.x/packets/clientboundblockchangedackpacket/>
- Decompiled Minecraft 26.3 `ClientPacketListener#handleBlockChangedAck`:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>
- Decompiled Minecraft 26.3 `ClientLevel#handleBlockChangedAck` und
  serververifizierte Blockzustände:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientlevel/>
- Decompiled Minecraft 26.3 `ServerGamePacketListenerImpl`, kumulative
  Maximalbildung und Tick-Flush:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/server/network/servergamepacketlistenerimpl/>
- Lokale Paperweight-Artefakte der exakt gepinnten Builds, insbesondere
  `ClientboundBlockChangedAckPacket`, `ServerGamePacketListenerImpl` und die
  Paper-Patches für die vorgezogene Block-Entity-Bestätigung.
- Lokale PacketEvents-2.14.0-Quelle
  `WrapperPlayServerAcknowledgeBlockChanges` für das einzelne `sequence`-Feld.

## 5. Technische Umsetzung

### 5.1 Ablage und Benennung

Gemäß `docs/packet-definition-layout.md` wird das Paket der Domain `world`
zugeordnet:

```text
replay-adapter/paper-26_2/src/main/java/.../
└── packet/definition/world/
    ├── WorldPacketDefinitions.java
    └── Id004BlockChangedAckPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/.../
└── packet/definition/world/
    ├── WorldPacketDefinitions.java
    └── Id004BlockChangedAckPacketDefinition.java
```

Der Name wird mechanisch aus ID und Identifier gebildet:

```text
Id004BlockChangedAckPacketDefinition
```

### 5.2 Definition

Beide package-private, finalen Klassen erweitern:

```java
AbstractBlockedPacketDefinition<Paper26_XReplayPacketRewriter>
```

und definieren:

```java
static final int PACKET_ID = 0x04;
static final String IDENTIFIER = "minecraft:block_changed_ack";
```

Die Javadokumentation erläutert ausdrücklich:

- den kumulativen Sequenzvertrag,
- das Fehlen von Blockzustand und Erfolgsaussage,
- die Bindung an die ursprüngliche Client-Verbindung,
- die Gefahr für ausstehende Observer-Predictions,
- die beabsichtigte Sperre vor Playback-Decodierung.

Es werden keine Read-, Rewrite- oder Rebuild-Hooks implementiert. Eine solche
Logik würde eine nicht vorhandene sichere Abbildung suggerieren. Die
vollständige Paketimplementierung besteht hier aus dem expliziten Protokoll-
und Sicherheitsvertrag.

### 5.3 Aggregatoren und Kataloge

Pro Version wird ein öffentlicher, verhaltensloser
`WorldPacketDefinitions`-Aggregator mit einer unveränderlichen Liste angelegt.
Die beiden versionslokalen Kataloge werden minimal um diese Domain erweitert.

Die resultierende sichtbare Reihenfolge bleibt nach IDs geordnet:

1. `bundle_delimiter` (`0`),
2. `add_entity` (`1`),
3. `animate` (`2`),
4. `award_stats` (`3`),
5. `block_changed_ack` (`4`).

Es wird kein allgemeines Katalog-Refactoring vorgenommen.

### 5.4 Capture, Paper-Channel und PacketEvents

Der bestehende Capture-Pfad bleibt unverändert:

- PacketEvents beobachtet clientbound Pakete auf `MONITOR`,
- die Post-Task kopiert den finalen ByteBuf erst nach allen PacketEvents-
  Listenern und Wrapper-Änderungen,
- die live gebundene Paper-Registry ordnet ID `4` dem Descriptor zu,
- der native Codec des aktiven Paper-Channels validiert den Payload für den
  Capture-Kontext,
- Capture-Reihenfolge und Paper-Sonderpfade bleiben exakt erhalten.

Die vorhandene PacketEvents-Klasse
`WrapperPlayServerAcknowledgeBlockChanges` kann das Feld lesen und schreiben.
Sie wird nicht paketindividuell eingebunden, weil die bereits erfassten finalen
Wire-Bytes genauer sind und auch Änderungen anderer Plugins enthalten. Ein
zweiter Listenerpfad würde Duplikate und abweichende Reihenfolge riskieren.

Bukkit-/Paper-Gameplay-Events werden nicht als Fallback verwendet. Sie liefern
weder die clientseitige Sequenz noch den kumulierten Maximalwert, den genauen
Tick-Flush, Acks mit Sequenz `0` oder Papers vorgezogenen Block-Entity-Pfad.
Sollte der finale Wire-Capture fehlen, ist ein Integritätsfehler korrekter als
ein synthetisiertes, möglicherweise falsches Ack.

### 5.5 Playback und Observer-Isolation

Die neue Definition aktiviert zwei bereits vorhandene Grenzen:

1. `Paper26_XPlaybackBridge.shouldReplay` verwirft den aufgezeichneten Frame,
   weil `replayable == false`. Codec, Rewriter und PacketEvents-Replay-Writer
   werden nicht aufgerufen.
2. Der `ReplayViewerOutboundGate` lässt das live vom Server erzeugte
   `CONTROL`-Paket für den Observer passieren. Dessen eigene Blockinteraktionen
   bleiben damit korrekt synchronisiert.

Das Paket erhält keinen Reset-Hook und wird nicht in einen Checkpoint
aufgenommen. Seine Wirkung ist weder dauerhafter Weltzustand noch ein
reproduzierbarer Effekt.

## 6. Tests

### 6.1 Definitionstests pro Version

Neu:

```text
src/test/java/.../packet/definition/world/
└── Id004BlockChangedAckPacketDefinitionTest.java
```

Geprüft werden:

1. ID `4` und `0x04`, Identifier und vollständiger Typname.
2. `CONTROL`, Capture aktiv, Replay aus, Checkpoint aus und Scope `UNSAFE`.
3. Die Playback-Grenze lehnt gültige, leere, abgeschnittene und überlange
   Payloads ab, ohne den Codec aufzurufen.
4. Der native PLAY-Clientbound-Codec registriert die Klasse unter ID `4`.
5. Der native Codec roundtrippt alle relevanten VarInt-Grenzen:
   `0`, `1`, `127`, `128`, `16383`, `16384`, `Integer.MAX_VALUE`.
6. Negative `int`-Payloads werden als technisch codierbare, aber nicht regulär
   servererzeugte Sonderfälle dokumentiert und roundtrippt.
7. Ein überlanger VarInt wird vom nativen Codec abgelehnt.
8. Nach jedem erfolgreichen Decode ist der Payload vollständig verbraucht.

### 6.2 Coverage-Tests

Beide `PacketCoverageTest`-Klassen werden erweitert:

- `block_changed_ack` ist der fünfte explizite Katalogeintrag,
- Disposition ist `CONTROL`,
- Capture bleibt aktiv,
- Replay und Checkpoint-Relevanz sind aus,
- Scope ist `UNSAFE`,
- alle übrigen noch nicht implementierten PLAY-Pakete bleiben
  `UNSUPPORTED`.

### 6.3 Playback-Bridge-Tests

Beide versionslokalen `Paper26_XPlaybackBridgeTest`-Klassen erhalten einen
paketspezifischen Filtertest. Er verwendet den realen Descriptor für ID `4`
und prüft, dass der Frame sowohl bei normaler Timeline-Wiedergabe als auch bei
Checkpoint-Rekonstruktion nicht angenommen wird.

Der bereits vorhandene `ReplayViewerOutboundGateTest#forwardsControlPackets`
sichert weiterhin die Gegenrichtung ab: Live-Control des Observers wird nicht
unterdrückt. Da diese Logik vollständig dispositionsbasiert ist, ist keine
duplizierte paketindividuelle Gate-Implementierung erforderlich.

### 6.4 Dokumentation

Die Statusabschnitte von `docs/adapters/paper-26.2.md` und
`docs/adapters/paper-26.3.md` sind durch die bisherigen inkrementellen
Implementierungen bereits veraltet. Sie werden ausschließlich soweit
aktualisiert, dass die nun expliziten IDs `0` bis `4`, ihre Domains und die
zugehörigen Definitionstests korrekt beschrieben sind. Die bestehende
Protokollinventar-Zeile für `block_changed_ack` bleibt inhaltlich bei
`BLOCKED` und `Checkpoint: no`.

## 7. Voraussichtlich betroffene Dateien

Neu:

- `paper-26_2/.../packet/definition/world/WorldPacketDefinitions.java`
- `paper-26_2/.../packet/definition/world/Id004BlockChangedAckPacketDefinition.java`
- `paper-26_2/.../packet/definition/world/Id004BlockChangedAckPacketDefinitionTest.java`
- `paper-26_3/.../packet/definition/world/WorldPacketDefinitions.java`
- `paper-26_3/.../packet/definition/world/Id004BlockChangedAckPacketDefinition.java`
- `paper-26_3/.../packet/definition/world/Id004BlockChangedAckPacketDefinitionTest.java`

Geändert:

- `Paper26_2PacketCatalog.java`
- `Paper26_3PacketCatalog.java`
- beide `PacketCoverageTest.java`
- beide `Paper26_XPlaybackBridgeTest.java`
- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- Status dieser Plan-Datei nach erfolgreicher Umsetzung

Nicht geändert werden insbesondere:

- Capture-Bridges und PacketEvents-Listener,
- `PaperConnectionAccessor`,
- Playback-ChannelHandler und Replay-Writer,
- Rewriter-Implementierungen,
- Checkpoint-Encoder,
- `NeutralObserverPolicy`,
- öffentliche Adapter-APIs,
- Recording-, Datenbank- oder Konfigurationsformate.

## 8. Versionsverwaltung und Umsetzungsschritte

Der separate Branch `codex/implement-block-changed-ack-packet` wurde auf dem
aktuellen Refactor-Stand angelegt. Nach Freigabe dieses Plans:

1. `origin/master` abrufen und mit dem lokalen `master` vergleichen.
2. Keine lokalen oder benutzereigenen Dateien überschreiben; `.idea/` bleibt
   unberührt.
3. Beide versionslokalen Definitionen und World-Aggregatoren anlegen.
4. Kataloge um ID `4` erweitern.
5. Definitionstests einschließlich nativer Codec-Grenzwertmatrix umsetzen.
6. Coverage- und Playback-Bridge-Tests ergänzen.
7. Die unmittelbar betroffene Adapterdokumentation aktualisieren.
8. Alle betroffenen Adaptermodule testen.
9. Staged Diff und `git diff --check` kontrollieren.
10. Ausschließlich aufgabenbezogene Dateien mit einer semantischen,
    englischsprachigen Commit-Nachricht committen.

## 9. Qualitätsprüfung und Abnahmekriterien

Geplante Prüfkommandos:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test :replay-adapter:paper-26_2:test :replay-adapter:paper-26_3:test --console=plain
git diff --check
git status --short
```

Die Aufgabe ist abgeschlossen, wenn:

- ID `4` in beiden Live-Registries exakt zu
  `minecraft:block_changed_ack` passt,
- beide Kataloge eine explizite World-Domain-Definition besitzen,
- legitime und technisch mögliche Sequenzwerte im nativen Codec abgedeckt
  sind,
- ungültige Payloads die Playback-Decodierung nicht erreichen,
- aufgezeichnete Acks weder in normaler Wiedergabe noch Rekonstruktion
  emittiert werden,
- Live-Acks des neutralen Beobachters weiterhin passieren,
- kein Ack checkpoint-relevant ist,
- keine Event-Fallbacks oder paketfremden Änderungen hinzukommen,
- alle verfügbaren Tests erfolgreich sind,
- ausschließlich Task-Dateien committed werden und `.idea/` unangetastet
  bleibt.
