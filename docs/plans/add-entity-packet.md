# Implementierungsplan: `minecraft:add_entity`

## Status und Ziel

Dieser Plan beschreibt die vollständige Implementierung des clientbound PLAY-
Pakets `minecraft:add_entity` für Paper 26.2 und Paper 26.3. Die Implementierung
beginnt erst nach ausdrücklicher Freigabe dieses Plans.

Das Paket erhält in beiden Versionen den Namen
`Id001AddEntityPacketDefinition` und liegt im fachlichen Bereich `entity`. Es
soll den endgültigen Wire-Stand erfassen, als checkpoint-relevanten
Entity-Spawn wiedergeben, alle identitätsbehafteten Felder sessionsicher
umschreiben und anschließend ein neues natives Paper-Paket aufbauen.

Nicht Bestandteil dieser Aufgabe sind Implementierungen der nachfolgenden
Entity-Pakete wie Metadaten, Ausrüstung, Passagiere oder Bewegungen. Deren
spätere Implementierungen müssen jedoch dieselbe Identity Map verwenden.

## Analysierte Grundlage

### Projektstand

- `master` und `origin/master` zeigen auf denselben Stand `648168b`.
- Die benötigte Paketabstraktion und die Implementierung von
  `bundle_delimiter` liegen auf dem aktuellen Arbeitsbranch.
- Paper 26.2 ist an `26.2.build.135-stable`, Paper 26.3 an
  `26.3.build.244-stable` gebunden.
- Beide Versionen führen `minecraft:add_entity` im PLAY-clientbound-Register
  unter ID `1` beziehungsweise `0x01`.
- Der aktuelle Katalog kennt nur `bundle_delimiter`. `add_entity` fällt deshalb
  momentan auf `UNSUPPORTED` zurück und kann weder wiedergegeben noch in einen
  Checkpoint übernommen werden.

### Verwendete Protokollquellen

Die technische Entscheidung beruht auf den durch `paperweight-userdev`
bezogenen, Mojang-benannten Paper-Dev-Bundles beider exakt gebundener Builds.
Paper beschreibt den Dev-Bundle-Zugriff als den unterstützten Zugang zu diesen
internen Klassen:

- [PaperMC: paperweight-userdev](https://docs.papermc.io/paper/dev/userdev/)
- `ClientboundAddEntityPacket` aus `26.2.build.135-stable`
- `ClientboundAddEntityPacket` aus `26.3.build.244-stable`
- `Entity`, `Projectile`, `FishingHook`, `FallingBlockEntity` und `ItemFrame`
  aus denselben Bundles

Die Bytecode- und API-Prüfung ergibt in beiden Versionen dieselben öffentlichen
Felder und denselben nativen Konstruktor. Der native Registry-Codec bleibt die
einzige Instanz, die das Wireformat tatsächlich decodiert und encodiert.

## Protokollmodell

Der Body beider Versionen enthält in dieser Reihenfolge:

| Feld | Native Darstellung | Wire-Darstellung | Bedeutung |
|---|---|---|---|
| Entity-ID | `int` | VarInt | laufzeitlokale Referenz für alle Folgepakete |
| Entity-UUID | `UUID` | 16 Byte | semantische Identität der dargestellten Entity |
| Entity-Typ | `EntityType<?>` | Registry-Codec | bestimmt Darstellung und Interpretation von `data` |
| X, Y, Z | je `double` | je 64 Bit | initiale Weltposition |
| Bewegung | `Vec3` | `Vec3.LP_STREAM_CODEC` | initialer Bewegungsvektor |
| X-Rotation | `float` | gepacktes Byte | Pitch |
| Y-Rotation | `float` | gepacktes Byte | Körper-Yaw |
| Kopfrotation | `float`/`double` | gepacktes Byte | initialer Head-Yaw |
| Data | `int` | VarInt | typabhängige Zusatzdaten |

Die Paketdefinition implementiert das Wireformat nicht erneut. Sie liest die
Werte aus `ClientboundAddEntityPacket`, arbeitet auf einem unveränderlichen
semantischen Value und baut mit dem nativen Konstruktor ein neues
`ClientboundAddEntityPacket` auf. Dadurch bleiben Registry-Auflösung,
Rotationsquantisierung und die versionsgebundene Bewegungsdarstellung bei
Mojang/Paper.

## Typabhängige Bedeutung von `data`

`data` darf nicht pauschal als Entity-ID behandelt und ebenso wenig pauschal
unverändert kopiert werden.

Die geprüften NMS-Quellen zeigen folgende Kategorien:

| Kategorie | Bedeutung von `data` | Behandlung |
|---|---|---|
| `Projectile` und Unterklassen | Owner-/Shooter-Entity-ID; `0` bedeutet kein Owner | jeden Wert ungleich `0` über dieselbe Entity-ID-Map umschreiben |
| `FishingHook` | Owner-ID; bei fehlendem Owner kann der Server die eigene ID einsetzen | wie jedes Projektil umschreiben, damit auch dieser Sonderfall konsistent bleibt |
| `FallingBlockEntity` | laufzeitgebundene Block-State-ID | unverändert erhalten |
| Item Frame / Glow Item Frame | 3D-Richtung | unverändert erhalten |
| andere Entity-Typen | `0` oder weiterer typspezifischer Wert | unverändert erhalten |

Die Projektilerkennung erfolgt nicht über eine manuell gepflegte Liste von
Minecraft-Identifiern. Beide gebundenen Versionen stellen
`EntityType#getBaseClass()` bereit. Die Definition prüft deshalb:

```text
Projectile.class.isAssignableFrom(entityType.getBaseClass())
```

Damit werden auch sämtliche Projektil-Untertypen der jeweiligen Version
abgedeckt, ohne versehentlich numerisch identische Block-State- oder
Richtungswerte umzuschreiben. Ein unbekannter Nicht-Projektil-Typ wird
verlustfrei behandelt; sein Zusatzwert bleibt erhalten.

## Paketklassifikation

Die Definition verwendet:

```text
disposition        = STATEFUL
captureByDefault   = true
replayable         = true
checkpointRelevant = true
playbackScope      = REWRITE_IDENTITIES
```

Begründung:

- Der Spawn eröffnet den Lebenszyklus einer Entity auf dem Client.
- Ein Checkpoint benötigt den Spawn vor Metadaten, Ausrüstung, Effekten und
  Bewegungszustand.
- Die ursprüngliche Entity-ID und UUID dürfen nicht in die aktive
  Observer-Verbindung übernommen werden.
- Position, Typ, Rotation, Bewegung und nicht identitätsbehaftete Zusatzdaten
  sind sichtbarer Szenenzustand und werden erhalten.

## Geplante Architekturänderungen

### 1. Versionale Paketdefinitionen

In beiden Versionsmodulen entsteht:

```text
packet/definition/entity/
├── EntityPacketDefinitions.java
└── Id001AddEntityPacketDefinition.java
```

Die konkrete Klasse bleibt `final` und package-private. Sie erweitert
`RebuildingPacketDefinition` und besitzt:

- `PACKET_ID = 0x01`;
- `IDENTIFIER = "minecraft:add_entity"`;
- den nativen Typ `ClientboundAddEntityPacket`;
- ein unveränderliches `Value` mit allen elf semantischen Bestandteilen;
- `read`, `rewrite` und `rebuild` als vollständig getrennte Schritte.

`EntityPacketDefinitions` ist der öffentliche, verhaltensfreie Aggregator des
Entity-Bereichs. Beide `Paper26_*PacketCatalog` führen anschließend die Listen
aus `connection` und `entity` in einer unveränderlichen Gesamtliste zusammen.

### 2. Sessionsichere Identity Map

`add_entity` ist das erste implementierte Paket, das eine persistente
Playback-Identity benötigt. Der bisher leere Rewriter-Kontext reicht dafür
nicht aus.

Eine NMS-freie `ReplayIdentityMap` wird deshalb einmalig in `paper-common`
angelegt und von beiden Versionsrewritern verwendet. Sie verwaltet pro
Playback-Session:

- `recorded UUID -> replay UUID`;
- `recorded entity ID -> replay entity ID`;
- bereits verwendete UUIDs und Entity-IDs;
- die reservierte UUID und Entity-ID des Beobachters.

Eigenschaften der Abbildung:

- innerhalb einer Session deterministisch und stabil;
- zwischen Playback-Sessions durch `playbackSessionId` getrennt;
- niemals gleich der Observer-UUID;
- Entity-IDs werden aus einem negativen Bereich vergeben, während Paper reale
  Server-Entities positiv vergibt;
- Kollisionen mit reservierten oder bereits vergebenen Werten werden
  deterministisch aufgelöst;
- `clear()` entfernt die Sessiondaten beim Schließen des Playback-Kontexts;
- `resetView()` löscht die Identity Map nicht, damit Seek und anschließender
  Wiederaufbau innerhalb derselben Playback-Session dieselben IDs verwenden.

Die beiden versionsgebundenen Rewriter stellen der Paketdefinition nur die
schmalen Operationen `mapUuid` und `mapEntityId` bereit. Die Definition besitzt
weder Viewer-, Channel- noch Lifecycle-Zustand.

### 3. Katalogbasierter Rewrite-Dispatch

Die derzeitigen `Paper26_2ReplayPacketRewriter` und
`Paper26_3ReplayPacketRewriter` lehnen jedes Paket pauschal ab. Sie werden so
verdrahtet, dass sie:

1. die Definition anhand des verifizierten Descriptor-Typnamens aus dem
   jeweiligen Katalog anfordern;
2. `handlePlayback` zum policy-gesteuerten Decode/Read/Rewrite/Rebuild aufrufen;
3. das Ergebnis in den bestehenden Bridge-Result-Typ übersetzen;
4. beim Senden über dieselbe Definition und denselben Descriptor encodieren.

Descriptor, Phase, Richtung, Paket-ID, Playback-Scope, nativer Pakettyp und
Encodergebnis werden weiterhin von den finalen Methoden der gemeinsamen
Abstraktion validiert. Unbekannte oder falsch gebundene Pakete scheitern
geschlossen.

## Feldgenaue Rewrite-Regeln

| Feld | Rewrite-Regel |
|---|---|
| Entity-ID | immer über die Session-Entity-ID-Map ersetzen |
| UUID | immer über die Session-UUID-Map ersetzen |
| Entity-Typ | unverändert übernehmen |
| X/Y/Z | bit-/wertgetreu übernehmen |
| X-/Y-/Kopfrotation | semantisch unverändert über nativen Konstruktor übernehmen |
| Bewegung | unverändert übernehmen |
| `data == 0` | unverändert als Sentinel übernehmen |
| `data != 0` bei Projektil | über dieselbe Entity-ID-Map ersetzen |
| sonstiges `data` | unverändert übernehmen |

Die Definition prüft nach dem Decode zusätzlich, dass Position, Rotation und
Bewegung endlich sind und dass UUID, Entity-Typ, Basisklasse und Bewegung
vorhanden sind. Korrupte oder nicht sicher rekonstruierbare Werte werden nicht
an den Client gesendet, sondern führen zu einer klaren
`IncompatibleAdapterException`. `data` bleibt absichtlich ein beliebiger
`int`, weil sein Wertebereich vom Entity-Typ abhängt.

## Capture-, Channel- und Event-Grenzen

### Capture

Für `add_entity` bleibt das endgültige clientbound Paket die autoritative
Quelle:

1. Paper erzeugt das native Paket.
2. Paper encodiert es über den versionsgebundenen Registry-Codec.
3. PacketEvents darf es durch andere Listener verändern oder neu encodieren.
4. Der vorhandene PacketEvents-Post-Task kopiert erst danach ID und finalen
   Body.
5. `PaperConnectionAccessor` decodiert denselben Body ausschließlich zur
   Ermittlung des Capture-Kontexts; die Spawnposition liefert die räumliche
   Scope-Zuordnung.

Es wird kein Bukkit-/Paper-Spawn-Event als zweite Spawnquelle eingeführt. Ein
solches Event kennt weder die endgültigen per Empfänger sichtbaren Bytes noch
nachträgliche Paketänderungen anderer Plugins und würde Duplikate erzeugen.
Paper-Events bleiben nur dort Fallback, wo kein autoritativer Paketstrom
existiert, etwa bei bestimmten Zustandsänderungen nicht-playergebundener
Entities. Für den Entity-Spawn existiert dieser Bedarf nicht.

### Playback

Der vorhandene Paper-ChannelHandler bleibt die Isolationsgrenze:

- Decode, Rewrite und Encode laufen im bestehenden Outbound-Drain auf dem
  Channel-Event-Loop.
- Das neu encodierte Paket wird weiter über `PacketEventsReplayWriter` hinter
  Paper-Encoder, PacketEvents-Encoder und Replay-Gate injiziert.
- Es wird dadurch weder erneut erfasst noch ein zweites Mal von fremden
  PacketEvents-Listenern verändert.
- Live-Pakete des realen Beobachters bleiben weiterhin vom Gate getrennt.

Für diese Paketimplementierung ist kein neuer Netty-Handler und kein neuer
PacketEvents-Listener erforderlich.

## Checkpoint-Verhalten

Die bestehenden Snapshot-Provider erzeugen Spawns bereits über
`entity.getAddEntityPacket(...)` und klassifizieren sie als
`ENTITY_SPAWN`. Durch `checkpointRelevant = true` wird dieser vorhandene Pfad
erstmals für `add_entity` freigeschaltet.

Zu prüfen ist:

- Spawnpakete bleiben vor Metadaten, Attribute, Ausrüstung, Effekte und
  Passagierbeziehungen sortiert.
- Seek-Rekonstruktion übernimmt den Spawn, normale Timeline-Wiedergabe ebenso.
- Scope-Filterung verwendet Welt und Blockposition des Spawnpakets.
- Die Identity Map ist für Live- und Checkpoint-Frames identisch.
- Ein View-Reset beobachtet die bereits umgeschriebene Entity-ID, damit genau
  die Replay-Entity wieder entfernt wird.

## Tests

### Gemeinsame Identity-Tests

- gleiche Eingabe innerhalb einer Session ergibt immer dieselbe Ausgabe;
- verschiedene Recorded-IDs und UUIDs ergeben unterschiedliche Ausgaben;
- Observer-UUID und reservierte Entity-ID werden nie vergeben;
- zwei Playback-Sessions erzeugen getrennte UUID-Abbildungen;
- negative, positive sowie Grenzwert-Entity-IDs können als Schlüssel dienen;
- `clear()` entfernt sämtliche Zuordnungen.

### Definitionstests pro Paper-Version

- ID, Typname und vollständige `STATEFUL`-/`REWRITE_IDENTITIES`-
  Klassifikation;
- nativer Codec decodiert ID `1` ohne verbleibende Bytes;
- alle Felder werden gelesen und nach dem Rewrite wieder aufgebaut;
- Entity-ID und UUID werden geändert, während Typ, Position, Rotationen und
  Bewegung erhalten bleiben;
- `data = 0` bleibt `0`;
- Projektil-Owner-ID wird umgeschrieben;
- Fishing-Hook-Sonderfall wird umgeschrieben;
- Falling-Block-State-ID bleibt selbst bei numerischer Gleichheit zu einer
  Recorded-Entity-ID unverändert;
- Item-Frame-Richtung bleibt unverändert;
- unbekannte/nicht projektilbasierte typabhängige Daten bleiben unverändert;
- nicht endliche Koordinaten oder Bewegung scheitern geschlossen;
- falscher nativer Pakettyp, falscher Descriptor und falsche Paket-ID werden
  abgelehnt;
- Encode validiert erneut PLAY, clientbound und ID `1`.

### Integrations- und Registrytests

- beide Live-Registries erkennen `add_entity` exakt unter ID `1`;
- der Descriptor ist capture-, replay- und checkpoint-fähig;
- nur `bundle_delimiter` und `add_entity` sind danach explizit klassifiziert;
- der versionsgebundene Rewriter dispatcht über den Katalog;
- die Playback-Bridge sendet ein umgeschriebenes Spawnpaket;
- der View-Reset verfolgt die umgeschriebene Entity-ID;
- Checkpoint-Fixtures behalten Entity-Spawns bei;
- bestehende PacketEvents-Capture-, Gate- und Writer-Tests bleiben unverändert
  erfolgreich.

Auszuführende Gradle-Prüfungen:

```text
:replay-adapter:paper-common:test
:replay-adapter:paper-26_2:test
:replay-adapter:paper-26_3:test
```

## Betroffene Komponenten

Geplante neue Dateien:

- gemeinsame NMS-freie Identity Map plus Tests in `paper-common`;
- `EntityPacketDefinitions` in beiden Versionsmodulen;
- `Id001AddEntityPacketDefinition` und spiegelnde Tests in beiden
  Versionsmodulen.

Geplante Änderungen:

- beide versionsgebundenen Paketkataloge;
- beide versionsgebundenen Replay-Rewriter;
- gezielte Registry-, Playback-, Checkpoint- und Coverage-Tests;
- die beiden Adapterreferenzen und die Paketstatus-Dokumentation.

Nicht geändert werden:

- Recording-Dateiformat oder Migrationslogik;
- PacketEvents-Listener-Priorität und Capture-Post-Task;
- Paper-/PacketEvents-Pipeline-Reihenfolge;
- öffentliche Adapter-API;
- Verhalten noch nicht implementierter Pakete;
- Bukkit-/Paper-Event-Fallbacks.

## Auswirkungen und Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Kollision mit Live-Observer oder Live-Entity | reservierte Identitäten und eigener negativer Entity-ID-Bereich |
| `data` fälschlich als Entity-ID interpretiert | Projektilerkennung über die native Basisklasse statt Zahlenheuristik |
| Owner-Referenz und Spawn verwenden verschiedene Maps | genau eine Session-Identity-Map für alle Definitionen |
| versionsfalscher nativer Typ oder Codec | separate Definition pro Version und bestehende Descriptor-/Encode-Prüfungen |
| Replay wird erneut gecaptured | bestehender Write ab PacketEvents-Encoder-Kontext |
| Event und Wire-Paket erzeugen doppelte Spawns | keine Event-Fallback-Quelle für Entity-Spawns |
| korrupte NaN-/Infinity-Werte erreichen den Client | explizite Finite-Validierung vor dem Rebuild |
| spätere Entity-Pakete referenzieren Original-ID | gemeinsame Identity Map wird als verbindliche Grundlage ihrer Implementierungen dokumentiert |

## Abnahmekriterien

Die Aufgabe ist abgeschlossen, wenn:

1. beide Versionen `Id001AddEntityPacketDefinition` unter
   `packet.definition.entity` besitzen;
2. ID `0x01`, stabiler Typname und Live-Registry in beiden Versionen
   übereinstimmen;
3. sämtliche Paketfelder vollständig gelesen und wieder aufgebaut werden;
4. Entity-ID, UUID und jede Projektil-Owner-ID sessionsicher umgeschrieben
   werden;
5. sämtliche anderen typabhängigen `data`-Werte unverändert bleiben;
6. Capture und Checkpoint das Paket verwenden, ohne einen Event-Fallback oder
   zusätzlichen ChannelHandler einzuführen;
7. Replay nur über Katalog, verifizierten Codec und bestehenden
   PacketEvents-Bypass ausgegeben wird;
8. die gemeinsamen sowie beide versionsspezifischen Tests erfolgreich sind;
9. ausschließlich aufgabenbezogene Dateien im Commit enthalten sind.
