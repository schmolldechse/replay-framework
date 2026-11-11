# Implementierungsplan: `award_stats` (`0x03`)

Status: **Zur Freigabe**

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel

Das PLAY-Clientbound-Paket `minecraft:award_stats` mit der dezimalen ID `3`
beziehungsweise `0x03` soll in beiden Paper-Adaptern als explizite
Paketdefinition umgesetzt werden.

Die vollständige Implementierung bedeutet bei diesem Paket bewusst **nicht**,
dass sein Payload während der Wiedergabe decodiert, verändert und erneut
gesendet wird. Das Paket enthält ausschließlich Statistikzustand des Spielers,
an dessen Verbindung es gesendet wurde. Der Vanilla-Client schreibt die Werte
direkt in den lokalen `StatsCounter` seines eigenen Spielers und aktualisiert
den geöffneten Statistikbildschirm. Bei einer Replay-Sitzung ist dieser
Empfänger der neutrale Live-Beobachter und nicht der aufgezeichnete Spieler.

Die korrekte Implementierung muss deshalb:

1. das Paket für beide Versionen explizit erkennen,
2. seinen vollständigen Protokollvertrag dokumentieren und testen,
3. es weiterhin über den nativen Channel-Pfad erfassen können,
4. es an der Playback-Grenze deterministisch als viewer-lokal verwerfen,
5. den Payload dabei nicht einmal decodieren,
6. es niemals in einen Replay-Checkpoint aufnehmen.

## 2. Abgrenzung

Im Aufgabenbereich liegen ausschließlich:

- `Id003AwardStatsPacketDefinition` für Paper 26.2 und 26.3,
- die neue Domain-Aggregation unter `definition.player`,
- Katalog- und Registry-Coverage für ID `3`,
- Tests des vollständigen nativen Wire-Formats,
- Tests der Decode-freien `DROP_LOCAL`-Behandlung,
- Rewriter-Integration für beide Versionen.

Nicht Bestandteil sind:

- Wiedergabe der Statistiken des aufgenommenen Spielers,
- Synchronisation der Statistiken mit dem neutralen Beobachter,
- eine Statistikansicht innerhalb des Replay-Frameworks,
- Verarbeitung des serverbound `client_command`/`REQUEST_STATS`,
- Bukkit-/Paper-Statistik-Events als Capture-Fallback,
- Änderungen an anderen viewer-lokalen Paketfamilien,
- Migration alter Recordings oder Beibehaltung früherer Paketpfade,
- allgemeine Katalog- oder Capture-Refactorings.

## 3. Analysierter Projektzustand

Die bestehenden Paketdefinitionen für IDs `0`, `1` und `2` sind nach
Protokollverantwortung getrennt und werden in den versionslokalen
`Paper26_XPacketCatalog` aufgenommen. Nicht implementierte PLAY-Pakete werden
als `UNSUPPORTED` klassifiziert.

Für viewer-lokale Pakete existiert bereits der notwendige generische Ablauf:

- `PacketClassification.stateful(VIEWER_LOCAL)` ergibt einen explizit
  behandelten, zustandsbehafteten Descriptor,
- Capture bleibt standardmäßig aktiv,
- das Paket ist strukturell durch die Playback-Pipeline behandelbar,
- `checkpointRelevant` ist wegen `VIEWER_LOCAL` automatisch `false`,
- `PacketDefinition#handlePlayback` liefert `DROP_LOCAL`, bevor der Codec
  aufgerufen wird,
- `PacketDefinition#rewrite` verwirft auch ein bereits vorliegendes Objekt,
  ohne paketindividuelle Payload-Logik auszuführen.

`NeutralObserverPolicy` führt `award_stats` bereits ausdrücklich als lokalen
Zustand. Die Adapterdokumentation beider Versionen beschreibt es ebenfalls mit
`DROP_LOCAL`. Diese vorhandenen Architekturentscheidungen werden durch die
neue Definition verbindlich in den expliziten Paketkatalog überführt; sie
werden nicht neu erfunden oder umgangen.

Die Checkpoint-Encoder kennen den Typnamen zwar in ihrer allgemeinen
Familienzuordnung. Da die neue Definition `checkpointRelevant == false`
liefert, erreicht ein `award_stats`-Frame die Checkpoint-Selektion nicht. Die
Familienzuordnung muss daher nicht verändert werden.

## 4. Verifizierter Protokollvertrag

### 4.1 Paket-ID und native Klasse

Die exakten, durch Paperweight bereitgestellten Mojang-mapped Quellen beider
gepinnten Builds registrieren
`GamePacketTypes.CLIENTBOUND_AWARD_STATS` unmittelbar nach `animate`. In
beiden Versionen gilt daher:

| Eigenschaft | Wert |
|---|---|
| Phase | PLAY |
| Richtung | clientbound |
| ID | `3` / `0x03` |
| Identifier | `minecraft:award_stats` |
| Native Klasse | `ClientboundAwardStatsPacket` |

Die native Klasse und ihr `STREAM_CODEC` sind in 26.2 und 26.3 strukturell
identisch.

### 4.2 Payload

`ClientboundAwardStatsPacket` enthält eine
`Object2IntMap<Stat<?>>`. Das Wire-Format lautet:

| Reihenfolge | Feld | Wire-Typ | Bedeutung |
|---:|---|---|---|
| 1 | Anzahl | VarInt | Anzahl der Statistikzuordnungen; `0` ist zulässig |
| 2a | Statistikkategorie | VarInt | ID im Registry `minecraft:stat_type` |
| 2b | Statistik | VarInt | ID im Registry, das von der Kategorie ausgewählt wird |
| 2c | Wert | VarInt | Neuer absoluter Integer-Wert dieser Statistik |

Die Felder 2a bis 2c werden für jeden Eintrag wiederholt. `Stat.STREAM_CODEC`
decodiert zuerst den `StatType` und dispatcht danach auf dessen
Registry-Codec. Der Wert ist kein Delta: Der Client ruft für jeden Eintrag
`setValue(...)` auf und ersetzt damit den bisherigen lokalen Wert.

Der native Map-Codec erlaubt:

- eine leere Antwort,
- einen einzelnen Eintrag,
- beliebig viele unterschiedliche Statistikschlüssel innerhalb des
  protokollseitigen VarInt-/Payload-Limits,
- jeden durch einen Java-`int` darstellbaren Wert, einschließlich `0`,
  negativer Wire-Werte und `Integer.MAX_VALUE`,
- partielle Updates, bei denen nicht enthaltene Statistiken unverändert
  bleiben.

Die native Repräsentation ist eine Map. Ein regulär konstruierter Server-Packet
enthält deshalb jeden `Stat<?>`-Schlüssel höchstens einmal. Ein manipuliertes
Wire-Payload mit demselben Schlüssel mehrfach würde beim nativen Decode durch
den jeweils letzten Wert im Map-Ergebnis überschrieben. Für die Replay-Policy
ist auch dieser Sonderfall irrelevant, weil viewer-lokale Payloads nicht
decodiert werden.

### 4.3 Sämtliche Kategorien

Beide Zielversionen besitzen dieselben neun `minecraft:stat_type`-Kategorien:

| Kategorie | Ziel-Registry | Beispiele |
|---|---|---|
| `minecraft:mined` | `minecraft:block` | abgebauter Blocktyp |
| `minecraft:crafted` | `minecraft:item` | hergestellter Gegenstand |
| `minecraft:used` | `minecraft:item` | benutzter Gegenstand |
| `minecraft:broken` | `minecraft:item` | verbrauchtes Werkzeug/Item |
| `minecraft:picked_up` | `minecraft:item` | aufgenommenes Item |
| `minecraft:dropped` | `minecraft:item` | fallengelassenes Item |
| `minecraft:killed` | `minecraft:entity_type` | getöteter Entity-Typ |
| `minecraft:killed_by` | `minecraft:entity_type` | Entity-Typ des Killers |
| `minecraft:custom` | `minecraft:custom_stat` | Spielzeit, Distanz, Schaden, Interaktionen usw. |

`custom` umfasst alle Einträge der versionsgebundenen
`minecraft:custom_stat`-Registry. Eine Paketdefinition darf diese Liste nicht
hart codieren, weil der native Registry-Codec die maßgebliche Quelle ist.

### 4.4 Erzeugung und Clientwirkung

Der Vanilla-Server führt pro Spieler eine Menge geänderter (`dirty`)
Statistiken. `ServerStatsCounter#sendStats` erzeugt daraus eine Map mit den
aktuellen absoluten Werten, leert die Dirty-Menge und sendet
`ClientboundAwardStatsPacket`. Der normale Request-Pfad ist
`ServerboundClientCommandPacket.Action.REQUEST_STATS`, beispielsweise beim
Öffnen des Statistikbildschirms. Eine leere Dirty-Menge erzeugt ein gültiges
Paket mit leerer Map.

Der Vanilla-Client:

1. iteriert über alle Map-Einträge,
2. schreibt jeden Wert in `minecraft.player.getStats()`,
3. ruft bei geöffnetem `StatsScreen` anschließend `onStatsUpdated()` auf.

Das Paket offenbart damit unter anderem Spielzeit, zurückgelegte Distanzen,
abgebaute Blöcke, verwendete oder hergestellte Items, Kills, Tode, Schaden und
Interaktionen des ursprünglichen Spielers. Diese Daten beschreiben weder die
Replay-Welt noch eine beobachtete Entity und dürfen den Live-Beobachter nicht
verändern.

### 4.5 Quellen

- Minecraft Wiki, `Award Statistics`, ID, Wire-Felder, Kategorien und
  Statistikregistries:
  <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Award_Statistics>
- VoidMC, 26.2-kompatible Beschreibung von `0x03` und dem dreifachen
  VarInt-Eintrag:
  <https://voidminecraft.github.io/VoidMC/reference/protocol-specs/v26.1.2/play-clientbound.html>
- Decompiled Minecraft 26.3 `ClientboundAwardStatsPacket` mit dem nativen
  Map- und `Stat.STREAM_CODEC`:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/network/protocol/game/clientboundawardstatspacket/>
- Decompiled Minecraft 26.3 `ClientPacketListener#handleAwardStats` mit der
  Mutation des lokalen Player-`StatsCounter` und dem UI-Refresh:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>
- Lokale Paperweight/Mache-Quellen der exakt gepinnten Builds, insbesondere
  `GameProtocols`, `ClientboundAwardStatsPacket`, `Stat`, `StatType`, `Stats`,
  `StatsCounter`, `ServerStatsCounter` und `ServerGamePacketListenerImpl`.

## 5. Technische Umsetzung

### 5.1 Ablage und Benennung

Die Statistikwerte gehören dem ursprünglichen Spieler und nicht einer
allgemeinen Bildschirmdarstellung. Die Definition wird daher in der
Protokolldomäne `player` abgelegt:

```text
replay-adapter/paper-26_2/src/main/java/.../
└── packet/definition/player/
    ├── PlayerPacketDefinitions.java
    └── Id003AwardStatsPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/.../
└── packet/definition/player/
    ├── PlayerPacketDefinitions.java
    └── Id003AwardStatsPacketDefinition.java
```

Der Klassenname folgt dem festgelegten Schema aus
`docs/packet-definition-layout.md`:

```text
Id003AwardStatsPacketDefinition
```

### 5.2 Definition

Beide package-private, finalen Klassen erweitern direkt
`PacketDefinition<Paper26_XReplayPacketRewriter>` und definieren:

```java
static final int PACKET_ID = 0x03;
static final String IDENTIFIER = "minecraft:award_stats";
```

Der Konstruktor verwendet:

```java
PacketClassification.stateful(
        PacketDescriptor.PlaybackScope.VIEWER_LOCAL)
```

Es werden absichtlich keine Methoden für Read, Rewrite oder Rebuild
überschrieben. `PacketDefinition` behandelt `VIEWER_LOCAL` vor dem Decode und
liefert:

```text
DROP_LOCAL: recorded connection state does not belong to the observer
```

Diese kleine konkrete Definition ist vollständiger als eine scheinbar
umfangreichere Payload-Kopie: Jede Decode-/Rebuild-Logik wäre unerreichbar und
würde das Risiko schaffen, private Statistikwerte später versehentlich doch an
den Beobachter zu senden.

Eine neue gemeinsame Abstraktion wird für dieses erste konkrete
viewer-lokale Paket nicht eingeführt. Die bestehende `PacketDefinition` bildet
die Policy bereits vollständig ab; eine zusätzliche Klasse hätte aktuell
keinen eigenen technischen Vertrag.

### 5.3 Aggregatoren und Kataloge

Pro Version wird ein öffentlicher, verhaltensloser
`PlayerPacketDefinitions`-Aggregator mit einer unveränderlichen Liste
angelegt. Die beiden versionslokalen Kataloge werden um diesen Aggregator
ergänzt.

Die sichtbare Katalogreihenfolge bleibt nach den aktuell implementierten IDs
geordnet:

1. `bundle_delimiter` (`0`),
2. `add_entity` (`1`),
3. `animate` (`2`),
4. `award_stats` (`3`).

Die bestehende Stream-Zusammenführung wird nur minimal um die vierte Liste
erweitert. Es erfolgt kein allgemeines Katalog-Refactoring.

### 5.4 Capture, ChannelHandler und Event-Fallback

Die Registrierung aktiviert die explizite Klassifikation des live entdeckten
Pakets. Die eigentliche Erfassung bleibt unverändert am nativen ausgehenden
Paper-ChannelHandler. Dort ist der vollständige, tatsächlich an den Spieler
gesendete Payload einschließlich pluginseitiger Pakete und exakter Reihenfolge
vorhanden.

PacketEvents oder Paper-Events werden nicht als Fallback ergänzt:

- der serverbound `REQUEST_STATS` beweist nur die Anfrage, nicht die später
  tatsächlich gesendete Map,
- `PlayerStatisticIncrementEvent` beschreibt einzelne Änderungen, aber keine
  vollständige Antwort und keine exakte Dirty-Menge,
- direkte Sets, negative Werte, leere Antworten und pluginseitig konstruierte
  Pakete wären über Events nicht vollständig rekonstruierbar,
- bei der Wiedergabe wird der Payload ohnehin aus Gründen der
  Beobachterisolation verworfen.

Events bleiben damit höchstens eine theoretische Notfallquelle; für dieses
Paket werden sie ausdrücklich nicht verwendet.

## 6. Tests

### 6.1 Definitionstests pro Version

Neu:

```text
src/test/java/.../packet/definition/player/
└── Id003AwardStatsPacketDefinitionTest.java
```

Geprüft werden:

1. ID `3` und `0x03`, Identifier und Typname.
2. `STATEFUL`, Capture aktiv, strukturelle Playback-Behandlung aktiv,
   Checkpoint-Relevanz aus und Scope `VIEWER_LOCAL`.
3. `handlePlayback` liefert `DROP_LOCAL`.
4. Der Codec wird dabei exakt nullmal zum Decode aufgerufen.
5. Auch ein leerer oder absichtlich ungültiger Raw-Payload wird sicher
   verworfen, weil kein Payload-Zugriff stattfindet.
6. Die direkte `rewrite(...)`-Grenze liefert ebenfalls `DROP_LOCAL`, ohne den
   übergebenen Objekttyp auszuwerten.
7. Das native Protokoll registriert `ClientboundAwardStatsPacket` unter ID `3`.
8. Der native Codec kann eine leere Statistikmap vollständig roundtrippen.
9. Der native Codec kann in einem Paket Einträge aller neun Statistikkategorien
   vollständig roundtrippen.
10. Die Tests verwenden verschiedene Werte einschließlich `0`, positiver
    Werte sowie der signed-VarInt-Grenzen und prüfen den vollständigen
    Payload-Verbrauch.

Die nativen Codec-Tests dokumentieren den vollständigen Paketvertrag. Sie
werden bewusst getrennt von der Definition ausgeführt; die Definition selbst
darf diesen Codec während der Wiedergabe niemals aufrufen.

### 6.2 Coverage-Tests

Beide `PacketCoverageTest`-Klassen werden erweitert:

- `award_stats` ist der vierte explizite Katalogeintrag,
- Disposition ist `STATEFUL`,
- Capture ist aktiv,
- Playback erreicht die explizite Drop-Grenze,
- Checkpoint-Relevanz ist aus,
- Scope ist `VIEWER_LOCAL`,
- alle übrigen noch nicht implementierten PLAY-Pakete bleiben
  `UNSUPPORTED`.

Die bestehende Coverage-Logik wird dabei lesbar um einen eigenen
`awardStats`-Fall ergänzt und nicht strukturell neu geschrieben.

### 6.3 Rewriter-Integration

Die versionslokalen `Paper26_XReplayPacketRewriterTest`-Klassen erhalten einen
separaten `award_stats`-Fall. Er prüft den realen Katalogpfad bis zum
`DROP_LOCAL`-Ergebnis und verwendet einen Codec, dessen Decode-/Encode-Aufruf
den Test sofort fehlschlagen ließe.

So wird abgesichert, dass weder der Payload noch die registry-abhängigen
Statistikschlüssel während der Replay-Verarbeitung angefasst werden.

## 7. Voraussichtlich betroffene Dateien

Neu:

- `paper-26_2/.../packet/definition/player/PlayerPacketDefinitions.java`
- `paper-26_2/.../packet/definition/player/Id003AwardStatsPacketDefinition.java`
- `paper-26_2/.../packet/definition/player/Id003AwardStatsPacketDefinitionTest.java`
- `paper-26_3/.../packet/definition/player/PlayerPacketDefinitions.java`
- `paper-26_3/.../packet/definition/player/Id003AwardStatsPacketDefinition.java`
- `paper-26_3/.../packet/definition/player/Id003AwardStatsPacketDefinitionTest.java`

Geändert:

- `Paper26_2PacketCatalog.java`
- `Paper26_3PacketCatalog.java`
- beide `PacketCoverageTest.java`
- beide `Paper26_XReplayPacketRewriterTest.java`
- Status dieser Plan-Datei nach erfolgreicher Umsetzung

Nicht geändert werden insbesondere:

- Capture-Bridges und ChannelHandler,
- Checkpoint-Encoder,
- `NeutralObserverPolicy`,
- Datenbank-, Recording- oder Konfigurationsformate,
- öffentliche Adapter-APIs.

## 8. Versionsverwaltung und Umsetzungsschritte

Der separate Branch `codex/implement-award-stats-packet` wurde auf dem
freigegebenen Stand der vorausgehenden Paketimplementierungen angelegt. Nach
Freigabe dieses Plans:

1. `origin/master` abrufen und mit dem lokalen `master` vergleichen.
2. Keine lokalen oder benutzereigenen Dateien überschreiben; `.idea/` bleibt
   unberührt.
3. Beide versionslokalen Definitionen und Player-Aggregatoren anlegen.
4. Kataloge um ID `3` erweitern.
5. Definitionstests einschließlich nativer Codec-Matrix implementieren.
6. Coverage- und Rewriter-Tests ergänzen.
7. Alle betroffenen Adaptermodule testen.
8. Staged Diff und `git diff --check` kontrollieren.
9. Ausschließlich aufgabenbezogene Dateien als
   `feat: implement award stats packet definitions` committen.

## 9. Qualitätsprüfung und Abnahmekriterien

Geplante Prüfkommandos:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test :replay-adapter:paper-26_2:test :replay-adapter:paper-26_3:test --console=plain
git diff --check
git status --short
```

Die Aufgabe ist abgeschlossen, wenn:

- ID `3` in beiden Live-Registries exakt zu `minecraft:award_stats` passt,
- beide Kataloge eine explizite Definition besitzen,
- sämtliche neun Statistikkategorien und leere Maps im nativen Codec-Test
  abgedeckt sind,
- beliebige gültige oder fehlerhafte Payloads vor dem Decode verworfen werden,
- keine Statistik des aufgenommenen Spielers den neutralen Beobachter
  verändert,
- kein `award_stats`-Frame checkpoint-relevant ist,
- keine Event-Fallbacks oder fachfremden Änderungen hinzukommen,
- alle verfügbaren Tests erfolgreich sind,
- ausschließlich Task-Dateien committed werden und `.idea/` unangetastet
  bleibt.
