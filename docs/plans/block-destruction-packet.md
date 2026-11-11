# Implementierungsplan: `block_destruction` (`0x05`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel

Das PLAY-Clientbound-Paket `minecraft:block_destruction` mit der dezimalen ID
`5` beziehungsweise `0x05` wird in beiden Paper-Adaptern vollständig und
versionslokal implementiert.

Das Paket verändert keinen Blockzustand. Es verwaltet ausschließlich die
clientseitige Rissdarstellung eines Blockabbauvorgangs. Die Darstellung ist
kurzlebig, nach einer Abbau-ID adressiert und muss bei der Wiedergabe von den
IDs der Live-Welt und des neutralen Beobachters isoliert werden.

Die Umsetzung soll deshalb:

1. das native Wire-Format beider angehefteten Paper-Versionen abbilden,
2. alle möglichen Wire-Werte verlustfrei lesen, umschreiben und neu aufbauen,
3. die Abbau-ID deterministisch in den Playback-ID-Raum übertragen,
4. die Blockposition unverändert erhalten und als Capture-Position ausweisen,
5. aktive Overlays bei einem View-Reset zuverlässig entfernen,
6. das Paket als kurzlebigen, nicht checkpoint-relevanten Effekt behandeln,
7. den vorhandenen finalen PacketEvents-Wire-Capture weiterverwenden und
8. bewusst keinen verlustbehafteten Bukkit-/Paper-Event-Fallback einführen.

## 2. Analyse des Protokollvertrags

### 2.1 Registry und native Klasse

Die in den angehefteten Dev-Bundles enthaltenen Implementierungen von
`ClientboundBlockDestructionPacket` sind in Paper 26.2 und 26.3 identisch.

| Eigenschaft | Wert |
|---|---|
| Phase | PLAY |
| Richtung | Clientbound |
| ID | `5` / `0x05` |
| Identifier | `minecraft:block_destruction` |
| Native Klasse | `ClientboundBlockDestructionPacket` |
| Listener | `ClientGamePacketListener.handleBlockDestruction` |

Der Client leitet das Paket ohne zusätzliche Semantik an
`ClientLevel.destroyBlockProgress(id, pos, progress)` weiter.

### 2.2 Wire-Felder

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | `id` | VarInt | `int` | Schlüssel des clientseitigen Abbau-Overlays |
| 2 | `pos` | gepackte Blockposition | `BlockPos` | Block, auf dem die Risse dargestellt werden |
| 3 | `progress` | unsigned byte | `int` | Stufe oder Löschsignal |

Die Position belegt die üblichen 64 Bit mit 26 Bit für X, 12 Bit für Y und
26 Bit für Z. Damit sind die kanonischen Wire-Grenzen:

- X/Z: `-33_554_432` bis `33_554_431`,
- Y: `-2_048` bis `2_047`.

Der native Konstruktor nimmt für `progress` zwar einen beliebigen `int`
entgegen, schreibt aber nur das niederwertige Byte. Der Decoder liest dieses
Byte ausdrücklich vorzeichenlos. Ein aus einer Recording-Payload decodierter
Wert liegt deshalb immer zwischen `0` und `255`.

### 2.3 Vollständige Progress-Semantik

Der aktuelle Client behandelt die 256 möglichen Bytewerte wie folgt:

| Wire-Wert | Wirkung |
|---:|---|
| `0..9` | Overlay für die ID anlegen oder aktualisieren |
| `10..255` | Overlay für die ID entfernen; die übertragene Position ist dafür unerheblich |

Wichtige Konsequenzen:

- Es gibt nicht nur den verbreiteten Löschwert `255` beziehungsweise den vor
  dem Encoding verwendeten Konstruktorwert `-1`. Jeder Wert ab `10` entfernt
  den Eintrag.
- Unbekannte oder ungewöhnliche Löschwerte dürfen nicht normalisiert oder
  verworfen werden. Die Definition erhält das ursprüngliche Wire-Byte.
- Derselbe aktive Schlüssel an einer anderen Position verschiebt das Overlay:
  Der Client entfernt zuerst den alten Positionseintrag und legt ihn an der
  neuen Position wieder an.
- Mehrere IDs können gleichzeitig dieselbe Position adressieren.
- Die ID muss nicht zu einer geladenen Entity gehören. Papers
  `sendBlockDamage(..., int sourceId)` erlaubt ausdrücklich künstliche IDs.
- Nicht erneut aktualisierte Einträge verfallen im Client nach mehr als 400
  Client-Level-Ticks. Ein zusätzliches Löschpaket beim Replay-Reset bleibt
  trotzdem korrekt und nebenwirkungsfrei.

### 2.4 Bedeutung der ID

Im Vanilla-Serverpfad übergibt `ServerLevel.destroyBlockProgress` gewöhnlich
die Entity-ID des abbauenden Akteurs. Paper und Plugins dürfen jedoch einen
beliebigen, auch nicht mit einer Entity verbundenen `sourceId` verwenden.

Für die Wiedergabe ist die ID daher als **identity-bearing overlay key** zu
behandeln:

- Gleiche aufgezeichnete IDs müssen während einer Playback-Sitzung gleich
  abgebildet werden.
- Verschiedene aufgezeichnete IDs müssen verschieden bleiben.
- Kollisionen mit dem Beobachter, Live-Entities und anderen Replay-Entities
  müssen ausgeschlossen werden.
- Wenn die ID tatsächlich einer aufgezeichneten Entity entspricht, muss sie
  denselben sitzungslokalen Wert wie andere Entity-referenzierende Pakete
  erhalten.

Der vorhandene `ReplayIdentityMap`-Pfad über
`Paper26_2ReplayPacketRewriter.mapEntityId` beziehungsweise
`Paper26_3ReplayPacketRewriter.mapEntityId` erfüllt diese Anforderungen auch
für `Integer.MIN_VALUE`, negative IDs, `0` und `Integer.MAX_VALUE`.

## 3. Einordnung in die Paketarchitektur

### 3.1 Name und Ablage

Die konkrete Klasse heißt in beiden Versionen:

```text
Id005BlockDestructionPacketDefinition
```

Sie wird unter `packet.definition.effect` abgelegt:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/effect/
        └── Id005BlockDestructionPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/effect/
        └── Id005BlockDestructionPacketDefinition.java
```

Obwohl das Paket eine Blockposition enthält, besitzt es keinen dauerhaften
Weltzustand. Seine Protokollverantwortung ist die geordnete, kurzlebige
Rissdarstellung. Damit passt es zum bereits dokumentierten `effect`-Bereich
und nicht zu dauerhaften Block-/Chunk-Definitionen im `world`-Bereich.

### 3.2 Klassifizierung

Die Definition verwendet:

```text
PacketDisposition.EPHEMERAL
captureByDefault = true
replayable = true
checkpointRelevant = false
PlaybackScope.REWRITE_IDENTITIES
```

Begründung:

- Das Paket ist sichtbar und soll während normaler zeitlicher Wiedergabe
  gesendet werden.
- Es ist kein rekonstruierbarer Zustand eines Checkpoints. Bei einem Seek
  werden ältere kurzlebige Effekte bewusst nicht nachträglich abgespielt.
- Die ID ist nicht direkt sicher übertragbar und wird vor dem Senden isoliert.
- Die Position und das Progress-Byte benötigen keine semantische Änderung.

### 3.3 Definitionstyp und Wertmodell

Die Klasse erweitert den vorhandenen
`RebuildingPacketDefinition<..., ClientboundBlockDestructionPacket, Value>`.

Das interne unveränderliche Wertmodell enthält:

```java
record Value(int sourceId, BlockPos position, int progress) {}
```

Verantwortlichkeiten:

- `read`: Felder aus dem nativen Paket lesen; `progress` auf die kanonische
  unsigned-byte-Darstellung abbilden.
- `rewrite`: ausschließlich `sourceId` über `mapEntityId` abbilden.
- `rebuild`: ein neues `ClientboundBlockDestructionPacket` mit gemappter ID,
  unveränderter Position und unverändertem Progress erzeugen.

`Value` akzeptiert nur `0..255` und keine `null`-Position. Die Normalisierung
in `read` bildet auch direkt konstruierte native Pakete so ab, wie deren
`writeByte` sie tatsächlich auf den Draht schreiben würde. Der produktive
Recording-Pfad liefert bereits kanonische Werte.

## 4. Capture- und Fallback-Entscheidung

### 4.1 PacketEvents als maßgebliche Capture-Ebene

Der vorhandene `Paper26_*CaptureBridge` registriert genau einen
PacketEvents-Listener mit `MONITOR`-Priorität und kopiert den Buffer in einer
Post-Task. Dadurch wird die endgültige, empfängerbezogene Payload nach allen
PacketEvents-Rewrites erfasst.

Für `block_destruction` ist deshalb kein eigener Listener und kein Wrapper
erforderlich. Dieser Pfad erhält:

- die tatsächliche Empfängerentscheidung,
- künstliche und echte source IDs,
- die genaue Blockposition,
- alle 256 Progress-Bytewerte,
- Reihenfolge und Timing sowie
- Änderungen vorheriger PacketEvents-Listener.

Der PacketEvents-Wrapper
`WrapperPlayServerBlockBreakAnimation` modelliert dieselben Felder, exponiert
die Stufe aber als vorzeichenbehaftetes `byte`. Ihn zusätzlich in die
Paketdefinition einzubauen würde keine Information ergänzen und eine zweite
Interpretationsebene schaffen.

### 4.2 Paper-Event ist kein geeigneter Fallback

`BlockBreakProgressUpdateEvent` wird im Paper-Serverpfad nur ausgelöst, wenn
die ID zu einer existierenden Entity aufgelöst werden kann. Paper dokumentiert
ausdrücklich, dass bei clientseitigen beziehungsweise künstlichen IDs kein
solches Event entsteht.

Zusätzlich fehlen dem Event:

- der konkrete Netzwerkempfänger,
- der originale unsigned-byte-Wert,
- die vollständige Löschwertmenge `10..255`,
- PacketEvents-Rewrites und
- die Garantie, exakt das tatsächlich gesendete Paket abzubilden.

Es wird daher weder primär noch als Fallback verwendet. Ein Event-Fallback
würde unvollständige oder doppelte Frames erzeugen und die Recording-Treue
verschlechtern.

### 4.3 Räumlicher Capture-Kontext

Das Paket trägt genau eine absolute Blockposition. Die vorhandene
`PaperConnectionAccessor.packetPosition`-Logik kennt diesen Typ derzeit noch
nicht. Beide Versionen werden um einen expliziten
`ClientboundBlockDestructionPacket`-Fall ergänzt.

Damit erhalten regionenbegrenzte Recordings die echte Paketposition. Ohne
diese Ergänzung wäre das Paket trotz eindeutiger Position als räumlich
unbekannt klassifiziert und könnte je nach Membership-Policy falsch ein- oder
ausgeschlossen werden.

## 5. Playback und ChannelHandler

### 5.1 Normaler Replay-Pfad

Der bestehende Playback-Ablauf bleibt erhalten:

1. `Paper26_*PlaybackBridge.shouldReplay` lässt das Paket nur im Modus `LIVE`
   passieren, weil es replaybar, aber nicht checkpoint-relevant ist.
2. Der verbindungsgebundene native Codec decodiert die gespeicherte Payload
   und lehnt fehlende, überlange, abgeschnittene oder nachlaufende Bytes ab.
3. Die Definition liest, mappt die ID und baut das native Paket neu auf.
4. Der Codec validiert, dass das neue Paket wieder als PLAY-Clientbound-ID `5`
   encodiert wurde.
5. `PacketEventsReplayWriter` injiziert die finalen Bytes hinter dem nativen
   Packet-Encoder.

### 5.2 Outbound-Gate des Replay-Beobachters

`ReplayViewerOutboundGate` liegt unmittelbar vor Papers Packet-Encoder und
behandelt native Live-Pakete getrennt von injizierten Replay-Bytes.

Nach der expliziten Klassifizierung als replaybarer `EPHEMERAL`-Effekt gilt:

- Blockabbau-Overlays aus der Live-Welt werden während aktiver
  Replay-Isolation auf dem Beobachterkanal unterdrückt.
- Die neu aufgebauten Replay-Pakete umgehen den Gate-Pfad wie vorgesehen über
  den Replay-Writer.
- Viewer-lokale Kontrollpakete und der allgemeine Live-Kanal bleiben
  unverändert.
- Vor Aktivierung der Isolation bleibt das bisherige Durchlassverhalten für
  Teleport-/Initialisierungsverkehr bestehen.

Es ist keine neue ChannelHandler-Implementierung erforderlich. Die neue
Definition aktiviert den bereits vorhandenen, registry-gesteuerten Vertrag.

## 6. Overlay-Lebenszyklus und Reset

`Paper26_2ViewResetter` und `Paper26_3ViewResetter` besitzen bereits die
grundlegende Beobachtung für `ClientboundBlockDestructionPacket`:

- Stufen `0..9` merken `mappedId -> position`.
- Werte außerhalb dieses Bereichs entfernen die ID aus dem Tracker.
- Beim Reset wird für jede weiterhin aktive ID ein Löschpaket erzeugt.

Diese Logik wird im Rahmen der Paketaktivierung vervollständigt und geprüft:

1. Der Tracker verwendet eine deterministische Einfügereihenfolge statt einer
   `HashMap`, passend zum dokumentierten deterministischen Reset-Vertrag.
2. Ein Positionswechsel derselben ID ersetzt nur die gespeicherte Position;
   der Client entfernt den alten Eintrag bereits beim aktiven Update.
3. Mehrere IDs an derselben Position bleiben getrennte Einträge.
4. Jeder Löschwert `10..255` entfernt die ID im Tracker.
5. Reset-Pakete verwenden den üblichen Konstruktorwert `-1`, der auf dem Wire
   als `255` übertragen wird.
6. Erst nach vollständig erfolgreichen Writes wird der Tracker geleert. Bei
   einem Teilfehler bleiben die Einträge für einen sicheren erneuten Cleanup
   erhalten; wiederholte Löschpakete sind idempotent.
7. Ein nach 400 Client-Ticks bereits automatisch verfallener Eintrag kann
   noch im serverseitigen Tracker stehen. Das spätere Löschpaket ist harmlos
   und verhindert keinen korrekten Reset.

Die Clear-Pakete durchlaufen `writeResetPacket`, werden nativ als ID `5`
encodiert, gegen die jetzt replaybare Registry-Definition validiert und über
den Replay-Writer gesendet.

## 7. Geplante Codeänderungen

### Paper 26.2

- `packet/definition/effect/Id005BlockDestructionPacketDefinition.java`
  neu anlegen.
- `EffectPacketDefinitions` um ID `5` ergänzen.
- `PaperConnectionAccessor` um die einzelne Blockposition ergänzen.
- `Paper26_2ViewResetter` auf deterministische Overlay-Reihenfolge festlegen.
- Registry-/Coverage-, Definition-, Capture-Kontext- und Reset-Tests ergänzen.

### Paper 26.3

- Dieselben Änderungen mit den synchronen 26.3-Typen und Paketnamen.

### Gemeinsame Schicht

Es ist keine neue gemeinsame Abstraktion geplant. Der vorhandene
`RebuildingPacketDefinition`-Vertrag deckt den vollständigen Ablauf ab. Eine
zusätzliche Abstraktion nur für zwei versionslokale, sehr kleine Definitionen
würde die NMS-Grenzen verwischen und keinen Wartungsvorteil liefern.

### Dokumentation

`docs/adapters/paper-26.2.md` und `docs/adapters/paper-26.3.md` werden
aktualisiert:

- expliziter Implementierungsstand IDs `0..5`,
- `block_destruction` als `EPHEMERAL`/`REWRITE_IDENTITIES`,
- nicht checkpoint-relevant,
- räumlich filterbar und
- mit deterministischem Reset-Cleanup.

## 8. Teststrategie

### 8.1 Definition und nativer Codec

Je Version prüft ein
`Id005BlockDestructionPacketDefinitionTest` mindestens:

- ID `5` / `0x05` und exakten Identifier,
- `EPHEMERAL`, Capture aktiv, Replay aktiv, Checkpoint aus,
  `REWRITE_IDENTITIES`,
- nativen Pakettyp und vollständigen Decode-/Rewrite-/Rebuild-/Encode-Pfad,
- stabile ID-Abbildung innerhalb einer Sitzung,
- unterschiedliche Abbildung verschiedener IDs,
- Kollisionstrennung zu reservierten Beobachter-/Entity-IDs,
- VarInt-ID-Grenzen einschließlich negativer Werte, `0`,
  `Integer.MAX_VALUE` und `Integer.MIN_VALUE`,
- kanonische Blockpositionsgrenzen für X, Y und Z,
- jede Progress-Stufe `0..255`, nicht nur `0..9` und `255`,
- Konstruktorwerte wie `-1` und `256`, die auf ihr tatsächliches Wire-Byte
  kanonisiert werden,
- Ablehnung einer falschen nativen Paketklasse,
- fehlende oder überlange ID-VarInts,
- abgeschnittene Blockposition,
- fehlendes Progress-Byte und
- nachlaufende Payload-Bytes über den verbindungsgebundenen Codecvertrag.

### 8.2 Capture-Kontext

Je Version wird geprüft, dass ein natives `block_destruction`-Paket seine
exakte `BlockPosition` in `CaptureContext.position` bereitstellt. ID und
Progress dürfen die räumliche Zuordnung nicht verändern.

Der bestehende Capture-Bridge-Test bleibt die Absicherung dafür, dass der
finale PacketEvents-Buffer nach Rewrites unverändert kopiert wird.

### 8.3 Registry und Emissionsmodi

`PacketCoverageTest` prüft:

- die explizite Katalogreihenfolge IDs `0..5`,
- Disposition und Scope von ID `5`,
- Replay nur für implementierte Pakete und
- weiterhin sicheren `UNSUPPORTED`-Fallback für alle übrigen PLAY-Pakete.

`Paper26_*PlaybackBridgeTest` prüft explizit:

- ID `5` wird im `LIVE`-Modus akzeptiert,
- ID `5` wird bei `RECONSTRUCTION` verworfen und
- die bereits vorhandenen Regeln für dauerhafte und andere kurzlebige Pakete
  bleiben unverändert.

### 8.4 Reset

Ein fokussierter `Paper26_*ViewResetterTest` prüft:

- aktive Stufen `0` und `9`,
- alle Löschstufen `10..255`,
- mehrere IDs an derselben Position,
- Positionswechsel derselben ID,
- deterministische Reihenfolge der Löschpakete,
- Löschpaket mit Wire-Wert `255`,
- Leeren nach erfolgreichem Reset,
- Beibehalten des Trackers bei Writer-Fehler und
- idempotenten wiederholten Reset.

### 8.5 Auszuführende Prüfungen

Nach Freigabe und Implementierung:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test `
  :replay-adapter:paper-26_2:test `
  :replay-adapter:paper-26_3:test --console=plain
```

Zusätzlich:

- `git diff --check`,
- Prüfung, dass ausschließlich aufgabenrelevante Dateien geändert sind,
- Prüfung des Branchs `codex/refactor-packet-capture-rewrite` vor Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Auswirkungen und bewusste Grenzen

### Auswirkungen

- Blockabbauanimationen werden erstmals sichtbar und replay-sicher.
- Live-Overlay-IDs können nicht mit Replay-/Observer-IDs kollidieren.
- Räumliche Recording-Scopes erhalten die konkrete Blockposition.
- Seek und View-Reset hinterlassen keine vom Replay erzeugten Riss-Overlays.
- Der Registry-Fingerprint ändert sich durch die neue explizite Definition;
  dies ist bei der bereits beschlossenen fehlenden Altaufnahme-Migration
  beabsichtigt.

### Bewusste Grenzen

- Das Paket rekonstruiert keinen Abbaufortschritt aus Weltzustand oder Events.
  Nur tatsächlich gesendete Pakete werden aufgezeichnet.
- Ein Seek rekonstruiert keine vor dem Zielzeitpunkt begonnene Rissanimation,
  weil das Paket bewusst nicht checkpoint-relevant ist.
- Clientseitiger automatischer Ablauf nach 400 Ticks wird nicht serverseitig
  nachsimuliert. Der Reset sendet bei Bedarf ein zusätzliches, harmloses
  Löschpaket.
- Es wird keine neue Event-basierte Capture-Ebene eingeführt.
- Andere Paket-IDs und nicht unmittelbar benötigte Refactorings bleiben
  außerhalb dieses Umfangs.

## 10. Recherchierte Grundlagen

Die Planung basiert auf:

- den lokalen, angehefteten Paper-Dev-Bundles 26.2 und 26.3 für native Klasse,
  Codec und Serverpfad,
- der dekompilierten 26.3-Clientimplementierung mit offiziellen Mojang-Namen
  für Handler, Overlay-Lebenszyklus und 400-Tick-Ablauf,
- der Paper-API-Dokumentation zu `Player.sendBlockDamage`, insbesondere der
  Zulässigkeit künstlicher `sourceId`-Werte,
- Papers `ServerLevel`-Patch zum Event-Verhalten bei clientseitigen IDs und
- dem PacketEvents-2.14.0-Quellcode des Block-Break-Animation-Wrappers.

Relevante externe Quellen:

- <https://mc-packet-reference.netlify.app/26.x/packets/clientboundblockdestructionpacket/>
- <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>
- <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientlevel/>
- <https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Player.html>
- <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/server/level/ServerLevel.java.patch>

## 11. Freigabepunkt

Bis zur ausdrücklichen Genehmigung werden keine Produktions- oder Testklassen
verändert. Nach der Freigabe wird ausschließlich auf
`codex/refactor-packet-capture-rewrite` implementiert, getestet, dokumentiert
und committed.
