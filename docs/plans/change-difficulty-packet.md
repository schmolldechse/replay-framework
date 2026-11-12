# Implementierungsplan: `change_difficulty` (`0x0A`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Umfang

Das PLAY-Clientbound-Paket `minecraft:change_difficulty` mit der dezimalen
ID `10` beziehungsweise `0x0A` wird in beiden Paper-Adaptern als explizite,
versionslokale Paketdefinition umgesetzt.

Die Umsetzung soll:

1. alle vier definierten Schwierigkeitswerte und beide Zustände des
   Difficulty-Lock-Flags berücksichtigen,
2. den finalen Paketinhalt weiterhin über den vorhandenen PacketEvents-Wire-
   Pfad erfassen,
3. den aufgezeichneten Wert als Zustand der ursprünglichen
   Clientverbindung behandeln,
4. verhindern, dass dieser Wert die aktuelle Schwierigkeit beziehungsweise
   deren Lock-Anzeige beim neutralen Beobachter überschreibt,
5. das Paket vor jedem Payload-Decode kontrolliert als `DROP_LOCAL`
   verwerfen,
6. keine Identität, Position oder andere nicht vorhandene Semantik erfinden,
7. beide Paper-Versionen strukturell und namentlich synchron halten und
8. die vollständigen, neutralen Adapter-Inventare um eine präzise
   Beschreibung von Inhalt und Policy ergänzen.

Eine Migration alter Recordings, das Beibehalten des entfernten
Paketsystems, ein allgemeiner Umbau der Checkpoint-Architektur oder Änderungen
an anderen Paketdefinitionen sind nicht Teil dieser Aufgabe.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur und Ablage

Die Paketdefinitionen sind versionslokal nach ihrer Protokollverantwortung
geordnet. `change_difficulty` beschreibt die vom Server an den Client
gemeldeten Einstellungen der aktuellen Welt. Es gehört deshalb in die Domain
`world` und nicht in `player` oder `connection`.

Diese Zuordnung folgt ausdrücklich der bestehenden Layout-Regel: Die Domain
wird nicht aus der aktuellen Playback-Policy abgeleitet. Dass der Inhalt bei
neutraler Wiedergabe viewer-lokal behandelt wird, ändert seine fachliche
Zugehörigkeit zum Weltprotokoll nicht.

Die synchrone Benennung beider Versionen lautet:

```text
Id010ChangeDifficultyPacketDefinition
```

Vorgesehene Ablage:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/world/
        └── Id010ChangeDifficultyPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/world/
        └── Id010ChangeDifficultyPacketDefinition.java
```

`WorldPacketDefinitions` enthält derzeit die Weltpakete `0x04`, `0x06`,
`0x07` und `0x08`. ID `0x0A` wird dort ergänzt. Der versionslokale
Gesamtkatalog sortiert alle Definitionen bereits numerisch; seine Struktur
muss nicht geändert werden.

### 2.2 Bereits vorhandene Neutral-Observer-Policy

`NeutralObserverPolicy` führt `change_difficulty` bereits als lokalen
Zustand. Beide Adapter-Dokumentationen deklarieren das Paket ebenfalls mit
`DROP_LOCAL` und ohne Checkpoint-Relevanz. Diese Entscheidung wird durch die
neue Definition ausführbar und testbar gemacht; die globale Policy-Liste muss
nicht verändert werden.

Das Paket verändert den beim empfangenden Client hinterlegten
Schwierigkeitswert sowie die Anzeige, ob die Schwierigkeit gesperrt ist. Bei
Playback läuft der neutrale Beobachter weiterhin auf seiner echten
Serververbindung. Ein aufgezeichneter Wert darf deren aktuellen Zustand nicht
überschreiben, auch wenn die Aufnahme aus derselben Dimension oder Welt
stammt. Insbesondere ist der aufgezeichnete Lock-Zustand keine sichtbare
Szenenentität und besitzt keinen sicheren Reset-Vertrag.

`PacketDefinition` behandelt den Scope `VIEWER_LOCAL` bereits vor dem Decode:

1. der Descriptor wird gegen ID, Typname, Phase, Richtung und Scope geprüft,
2. die Definition liefert `DROP_LOCAL`,
3. der native Codec wird nicht aufgerufen und
4. es wird kein Paket in den Replay-Channel geschrieben.

Dieser frühe Policy-Abbruch ist für `change_difficulty` die vollständige
Playback-Implementierung. Eine zusätzliche Read-/Rewrite-/Rebuild-Schicht
wäre nicht nur überflüssig, sondern würde den Schutz vor dem Decode
abschwächen.

### 2.3 Capture- und ChannelHandler-Pfad

Der vorhandene `Paper26_*CaptureBridge` erfasst ausgehende Pakete über
PacketEvents auf `MONITOR`-Ebene und kopiert den Wire-Buffer in der Post-Task.
Damit bleibt die tatsächlich an den ursprünglichen Empfänger gesendete
Kombination aus Schwierigkeit und Lock-Flag erhalten, einschließlich
vorheriger PacketEvents- oder ChannelHandler-Rewrites.

Dieser Pfad bleibt die maßgebliche Erfassungsquelle:

- Vanilla- und plugin-erzeugte Pakete werden gleichermaßen beobachtet,
- die Reihenfolge gegenüber Login-, Respawn- und anderen Weltpaketen bleibt
  erhalten,
- es wird kein Wert aus einer möglicherweise später veränderten Bukkit-Welt
  zurückgelesen und
- das Paket bleibt für Analyse, Diagnose und künftige nicht-neutrale
  Wiedergabemodi im Recording vorhanden.

`change_difficulty` enthält keine Position. Der Capture-Kontext darf deshalb
keine Block-, Chunk- oder Entity-Position ableiten. Ebenso enthalten weder
der Difficulty-Wert noch das Lock-Flag eine Entity-ID, Profil-UUID oder andere
Playback-Identität.

### 2.4 Checkpoint-Verhalten

`PacketClassification.stateful(VIEWER_LOCAL)` ergibt im bestehenden Modell:

- Disposition `STATEFUL`,
- Capture standardmäßig aktiv,
- kontrollierte Übergabe an die Playback-Policy,
- keine Checkpoint-Relevanz und
- Scope `VIEWER_LOCAL`.

Der `Paper26_*SnapshotProvider` kann bereits ein natives
`ClientboundChangeDifficultyPacket` aus der aktuellen Serverwelt als
`GLOBAL_STATE`-Blueprint erzeugen. Der Checkpoint-Encoder filtert Blueprints
jedoch anhand des verifizierten Descriptors und übernimmt ausschließlich
Pakete, die replaybar und checkpoint-relevant sind. Nach der expliziten
Viewer-Local-Klassifikation bleibt der Difficulty-Blueprint daher bewusst
außerhalb des Replay-Checkpoints.

Am Snapshot-Provider und an `CheckpointEncoder.familyFor(...)` ist keine
Änderung nötig. Der vorhandene Blueprint bleibt eine versionsnative
Beschreibung der Serverwelt, wird in diesem neutralen Playback-Modell aber
nicht an den Beobachter gesendet. Ein Entfernen oder allgemeines Refactoring
dieser vorbereiteten Checkpoint-Funktionalität wäre außerhalb des
Aufgabenumfangs.

### 2.5 Kein View-Reset erforderlich

Da das aufgezeichnete Paket nie an den Beobachter gesendet wird, entsteht
kein Replay-eigener Difficulty-Zustand, den `Paper26_*ViewResetter` entfernen
oder zurücksetzen müsste. Ein Reset-Paket mit einem geratenen aktuellen Wert
wäre sogar riskant: Es könnte zwischenzeitliche Änderungen der echten
Serververbindung überschreiben.

Der View-Resetter wird deshalb weder erweitert noch mit einer parallelen
Difficulty-Zustandsverwaltung belastet.

## 3. Verifizierter nativer Protokollvertrag

Die lokal angehefteten Paper-Dev-Bundles 26.2 und 26.3 besitzen denselben
öffentlichen Record-Vertrag:

```java
public record ClientboundChangeDifficultyPacket(
        Difficulty difficulty,
        boolean locked)
```

Öffentlich verfügbar sind:

```java
new ClientboundChangeDifficultyPacket(Difficulty, boolean)
Difficulty difficulty()
boolean locked()
```

Der Stream-Codec ist in beiden Builds aus diesen Bestandteilen aufgebaut:

```text
Difficulty.STREAM_CODEC
ByteBufCodecs.BOOL
```

Es gibt keine weiteren Varianten, optionalen Felder, IDs, Positionen oder
Identitäten.

### 3.1 Semantische Kombinationen

`Difficulty` besitzt genau vier definierte Werte:

| Native ID | Java-Wert | Bedeutung |
|---:|---|---|
| 0 | `PEACEFUL` | Friedlich |
| 1 | `EASY` | Einfach |
| 2 | `NORMAL` | Normal |
| 3 | `HARD` | Schwer |

Das zweite Feld ist ein Boolean:

| Wert | Bedeutung |
|---|---|
| `false` | Schwierigkeit ist nicht gesperrt |
| `true` | Schwierigkeit ist gesperrt |

Damit existieren acht gültige semantische Kombinationen. Alle acht werden in
den nativen Protokolltests beider Adapter abgedeckt.

### 3.2 Wire-Reihenfolge

Nach der Paket-ID `0x0A` folgt der Body in dieser Reihenfolge:

| Reihenfolge | Feld | Java-Typ | Bedeutung |
|---:|---|---|---|
| 1 | Difficulty | `Difficulty` | einer der Werte mit ID `0..3` |
| 2 | Locked | `boolean` | Lock-Zustand |

Die Minecraft-Protokolldokumentation bezeichnet das Difficulty-Feld als
Unsigned-Byte-Enum. Die angehefteten Paper-Builds implementieren
`Difficulty.STREAM_CODEC` über `ByteBufCodecs.idMapper(...)`; dieser Mapper
liest und schreibt eine VarInt-ID. Für sämtliche definierten Werte `0..3`
ist die Wire-Repräsentation in beiden Fällen dasselbe einzelne Byte.

Die Implementierung führt deshalb keinen manuellen Byte- oder VarInt-Codec
ein. Maßgeblich bleiben der native Codec des angehefteten Builds und Tests
der tatsächlich erzeugten Bytes. So wird weder eine Dokumentationsannahme
noch eine momentan unsichtbare Codec-Eigenschaft in Produktionscode
dupliziert.

### 3.3 Native Rand- und Fehlersemantik

Der native Difficulty-ID-Mapper verwendet in beiden Ziel-Builds die
Out-of-Bounds-Strategie `WRAP`. Numerische IDs außerhalb `0..3` sind nicht
Teil des dokumentierten Protokollvertrags, können vom angehefteten Decoder
aber modulo vier auf einen definierten Difficulty-Wert projiziert werden.
Ein anschließender nativer Re-Encode schreibt dessen kanonische ID `0..3`.

`ByteBufCodecs.BOOL` delegiert an Nettys `readBoolean()` und
`writeBoolean(...)`: `0x00` ist `false`, jeder andere gelesene Bytewert ist
`true`; beim Encode entstehen kanonisch `0x00` oder `0x01`.

Diese permissiven Decoder-Eigenschaften werden ausschließlich als nativer
Randvertrag getestet und nicht in die Paketdefinition nachgebaut. Für
Playback ist die Payload ohnehin vor dem Decode zu verwerfen. Abgeschnittene
Felder und überlange VarInts müssen beim direkten nativen Codec-Test weiterhin
scheitern.

### 3.4 Clientwirkung

Die Protokolldokumentation beschreibt das Paket als Aktualisierung der
Schwierigkeitseinstellung im Optionsmenü des Clients. Das Lock-Flag teilt dem
Client zusätzlich mit, ob die Schwierigkeit geändert werden kann. Der Inhalt
ist somit keine bloße Servermetrik; er mutiert empfangsseitigen Zustand.

Gerade diese Clientwirkung begründet `VIEWER_LOCAL`: Die Replay-Szene benötigt
keine Difficulty-Anzeige, während der neutrale Beobachter seine aktuelle
Live-Verbindung und deren Einstellungen behalten muss.

## 4. Klassifikation und Policy

Die Definition erhält:

| Eigenschaft | Wert |
|---|---|
| Disposition | `STATEFUL` |
| Standard-Capture | `true` |
| Playback-Zulassung | `true`, damit die explizite lokale Policy greift |
| Checkpoint | `false` |
| Playback-Scope | `VIEWER_LOCAL` |
| Ergebnis bei Live-Playback | `DROP_LOCAL` vor Decode |
| Ergebnis bei Rekonstruktion | bereits wegen fehlender Checkpoint-Relevanz ausgeschlossen |

Die scheinbare Unterscheidung zwischen „Playback-Zulassung“ und
`DROP_LOCAL` ist im bestehenden Modell beabsichtigt: `replayable = true`
führt ein Live-Frame bis zur expliziten Definition; dort wird es mit einem
nachvollziehbaren lokalen Grund verworfen. Nicht implementierte oder
gefährliche Pakete bleiben dagegen `UNSUPPORTED` beziehungsweise `UNSAFE`.

Die Definition verwendet:

```java
PacketClassification.stateful(
        PacketDescriptor.PlaybackScope.VIEWER_LOCAL)
```

## 5. Geplante Implementierung

### 5.1 Versionslokale Definitionen

Beide Klassen erweitern direkt:

```text
PacketDefinition<Paper26_*ReplayPacketRewriter>
```

Sie definieren ausschließlich:

```java
static final int PACKET_ID = 0x0A;
static final String IDENTIFIER = "minecraft:change_difficulty";
```

sowie die oben beschriebene `STATEFUL`-/`VIEWER_LOCAL`-Klassifikation.

Es werden bewusst keine `read`, `rewrite` oder `rebuild`-Hooks ergänzt:

- `VIEWER_LOCAL` wird von der Basisklasse vor dem Decode behandelt,
- alle Payloadvarianten haben dieselbe lokale Policy,
- ein Rebuild dürfte nie den Replay-Writer erreichen,
- es gibt keine Teilvariante, die sicher gesendet werden könnte und
- die native Paketform wird separat durch Codec-Tests abgesichert.

Die Javadoc der Definition erläutert, dass Difficulty und Lock-Flag zwar
aufgezeichnet werden, aber den Zustand des ursprünglichen Empfängers
darstellen und daher nicht in die Live-Verbindung des neutralen Beobachters
injiziert werden dürfen.

### 5.2 Registrierung und Coverage

`WorldPacketDefinitions` beider Versionen wird um
`Id010ChangeDifficultyPacketDefinition` ergänzt. Der Gesamtkatalog enthält
danach die expliziten Definitionen `0x00` bis einschließlich `0x0A` in
numerischer Reihenfolge.

`PacketCoverageTest` wird synchron erweitert:

- Typname `play.clientbound.minecraft:change_difficulty`,
- ID `10`/`0x0A`,
- Disposition `STATEFUL`,
- Capture aktiv,
- Replay-Policy aktiv,
- Checkpoint-Relevanz aus,
- Scope `VIEWER_LOCAL`,
- explizite Klassifikation vorhanden und
- alle späteren, noch nicht implementierten Pakete bleiben unverändert
  `UNSUPPORTED`.

### 5.3 Playback- und Checkpoint-Integration

`Paper26_*PlaybackBridgeTest` wird um den Routing-Vertrag ergänzt:

- im Modus `LIVE` erreicht das Frame die explizite Definition,
- im Modus `RECONSTRUCTION` wird es wegen
  `checkpointRelevant = false` nicht ausgewählt und
- die Definition liefert im Live-Fall `DROP_LOCAL`, ohne den Codec
  aufzurufen.

Ein eng begrenzter Checkpoint-Test wird nur ergänzt, falls die bestehende
Fixture die neue explizite Klassifikation nicht bereits über
`PacketCoverageTest` und die generische Eligibility-Logik abdeckt. In jedem
Fall bleibt der vorhandene Snapshot-Blueprint gefiltert; es ist keine
Produktionsänderung am Snapshot- oder Checkpoint-Code vorgesehen.

### 5.4 Capture-Kontext und Reset

Es wird kein Fall in `PaperConnectionAccessor.packetPosition(...)` ergänzt,
weil das Paket keine Position trägt. Es wird keine Identity-Map aufgerufen.
Der `ViewResetter` bleibt unverändert, da niemals Replay-eigener
Difficulty-Zustand erzeugt wird.

Bestehende generische Capture-Tests bleiben die Absicherung dafür, dass der
finale PacketEvents-Wire-Buffer erfasst wird. Ein zusätzlicher
paketspezifischer Listener oder ChannelHandler ist nicht vorgesehen.

## 6. Event-Fallback-Entscheidung

Für `change_difficulty` wird kein Paper-/Bukkit-Event-Fallback eingeführt.

Der bestehende Paketpfad ist vollständiger als ein Event:

- er enthält Difficulty und Lock-Flag gemeinsam,
- er bewahrt empfängerabhängige Werte,
- er sieht direkt gesendete NMS- oder Plugin-Pakete,
- er übernimmt vorherige PacketEvents-Rewrites,
- er erhält die Reihenfolge auf dem finalen ausgehenden Stream und
- er vermeidet einen Serverzustands-Read auf einem Netty-Thread.

Ein World-Difficulty-Event oder nachträgliches Sampling könnte außerdem einen
anderen Zeitpunkt oder einen anderen Wert als das tatsächlich gesendete
Paket liefern. Ein solcher Fallback wäre nur gerechtfertigt, wenn der
Wire-Pfad die Information nicht zuverlässig bereitstellen könnte. Diese
Voraussetzung liegt in beiden unterstützten Adaptern nicht vor.

## 7. Adapter-Dokumentationen

Die vollständigen Paket-Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig und neutral. Nur die Zeile für
`minecraft:change_difficulty` wird fachlich präzisiert:

- Beschreibung/Relevanz: synchronisiert die vierstufige
  Weltschwierigkeit und deren Lock-Flag mit dem empfangenden Client; der
  aufgezeichnete Verbindungszustand darf den neutralen Beobachter nicht
  verändern,
- Playback-Policy: `DROP_LOCAL`,
- Checkpoint-Policy: `no`.

Die Dokumente erhalten keine Aussage über Implementierungsfortschritt,
konkrete Klassen oder Tests. Alle anderen Paketzeilen bleiben unangetastet.

## 8. Teststrategie

### 8.1 Definitionstests je Version

Je Version entsteht ein
`Id010ChangeDifficultyPacketDefinitionTest` mit mindestens diesen Fällen:

1. exakte ID `10`/`0x0A` und exakter Typname,
2. Klassifikation `STATEFUL`, Capture an, Playback-Zulassung an, Checkpoint
   aus und Scope `VIEWER_LOCAL`,
3. `handlePlayback(...)` liefert für jede der acht gültigen Kombinationen
   `DROP_LOCAL`,
4. der Codec wird dabei kein einziges Mal zum Decode oder Encode aufgerufen,
5. leere, abgeschnittene und anderweitig malformed Payloads werden ebenfalls
   vor dem Decode lokal verworfen,
6. `rewrite(...)` verwirft auch eine falsche decodierte Java-Klasse, ohne
   deren Typ oder Felder zu untersuchen,
7. Descriptor-Abweichungen bei Typname, ID, Phase, Richtung oder Scope werden
   weiterhin von der Basisklasse abgelehnt und
8. `null`-Argumente folgen den bestehenden Basisklassenverträgen.

Damit wird nicht nur ein repräsentativer Difficulty-Wert getestet, sondern
die komplette gültige Wertematrix aus vier Enums mal zwei Boolean-Zuständen.

### 8.2 Native Wire-Tests

Die native Protokollprüfung bestätigt je Version unabhängig von der
Drop-Policy:

- Paket-ID `0x0A`,
- Feldreihenfolge Difficulty vor Locked,
- native Difficulty-IDs `0`, `1`, `2`, `3`,
- kanonische Boolean-Bytes `0x00` und `0x01`,
- alle acht gültigen Kombinationen,
- Rückgabe derselben Werte über `difficulty()` und `locked()`,
- vollständigen Verbrauch jeder gültigen Payload und
- einen erfolgreichen nativen Encode-/Decode-Roundtrip.

Die Randtests des angehefteten Codecs umfassen:

- fehlendes Difficulty-Feld,
- fehlendes Locked-Feld,
- abgeschnittene und überlange Difficulty-VarInts,
- mindestens repräsentative Out-of-Range-IDs zur Bestätigung der nativen
  `WRAP`-Semantik,
- nichtkanonische, von Netty als `true` gelesene Boolean-Bytes und deren
  kanonischen Re-Encode sowie
- nachlaufende Bytes entsprechend dem vorhandenen
  verbindungsgebundenen Payload-Verbrauchsvertrag.

Die Tests dokumentieren die permissiven Decoder-Randfälle, ohne sie als
zulässige neue Paketwerte in Produktionscode zu modellieren.

### 8.3 Integration und Versionssymmetrie

`PacketCoverageTest` prüft den expliziten Katalogvertrag bis ID `0x0A`.

`Paper26_*PlaybackBridgeTest` prüft die Trennung zwischen Live-Routing zur
lokalen Policy und dem Ausschluss aus der Rekonstruktion.

Definitionen und Tests werden nach Normalisierung der Versionsbezeichner
verglichen. Abweichungen sind nur zulässig, wenn die angehefteten Paper-
Builds einen unterschiedlichen Vertrag zeigen; die Analyse hat keine solche
Abweichung ergeben.

### 8.4 Auszuführende Prüfungen

Nach ausdrücklicher Genehmigung und Implementierung:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test `
  :replay-adapter:paper-26_2:test `
  :replay-adapter:paper-26_3:test --console=plain
```

Zusätzlich:

- `git diff --check`,
- Synchronitätsvergleich der versionslokalen Definitionen und Tests,
- Prüfung des Katalogs auf IDs `0x00..0x0A`,
- Prüfung aller geänderten Dateien gegen den Aufgabenumfang,
- Prüfung, dass `.idea/` unberührt und unversioniert bleibt,
- Prüfung des aktiven Branchs vor dem Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/world/Id010ChangeDifficultyPacketDefinition.java`
- `packet/definition/world/WorldPacketDefinitions.java`

Tests je Version:

- `packet/definition/world/Id010ChangeDifficultyPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`
- gegebenenfalls eine eng begrenzte bestehende native Codec- oder
  Checkpoint-Fixture, sofern dies zur Absicherung des verifizierten
  Wire-/Eligibility-Vertrags erforderlich ist.

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser Implementierungsplan

Nicht vorgesehen sind Änderungen an:

- Core-Format oder Recording-Migration,
- allgemeinen Paketabstraktionen,
- `NeutralObserverPolicy`, weil `change_difficulty` dort bereits enthalten
  ist,
- PacketEvents-Listenern oder Capture-ChannelHandlern,
- Bukkit-/Paper-Event-Listenern,
- `PaperConnectionAccessor`,
- `ReplayIdentityMap` oder versionslokalen Rewritern,
- `Paper26_*ViewResetter`,
- `Paper26_*SnapshotProvider`,
- `Paper26_*CheckpointEncoder`,
- allgemeinen Playback-ChannelHandlern oder
- anderen Paketdefinitionen.

## 10. Risiken und Gegenmaßnahmen

### Versehentliches Überschreiben des Beobachterzustands

Risiko: Ein scheinbar harmloses Weiterreichen aktualisiert die
Difficulty-/Lock-Anzeige des neutralen Beobachters und kann mit seiner echten
Serververbindung konkurrieren.

Gegenmaßnahme: `VIEWER_LOCAL` mit Drop vor Decode; Definitionstests verwenden
einen Codec, der bei jedem Decode-/Encode-Aufruf sofort fehlschlägt.

### Falsche Domain-Zuordnung

Risiko: Wegen der lokalen Playback-Policy könnte das Paket unter `player`
oder `connection` abgelegt werden, obwohl es fachlich Weltdaten beschreibt.

Gegenmaßnahme: Ablage unter `world` gemäß dokumentierter Regel „Domain nach
Protokollverantwortung, nicht nach Playback-Policy“.

### Verwechslung von Wire-Dokumentation und nativem Codec

Risiko: Eine manuelle Unsigned-Byte-Implementierung driftet vom angehefteten
Paper-Codec ab oder eine manuelle VarInt-Implementierung verankert ein
internes Detail unnötig im Produktionscode.

Gegenmaßnahme: Keine eigene Serialisierung. Die acht definierten Werte werden
über den nativen Codec getestet; der Dokumentationsunterschied wird im Plan
und in den Tests transparent behandelt.

### Unbeabsichtigte Checkpoint-Injektion

Risiko: Weil der Snapshot-Provider bereits einen Difficulty-Blueprint
erzeugt, könnte das Paket trotz Viewer-Local-Policy in einen Checkpoint
gelangen.

Gegenmaßnahme: `checkpointRelevant = false`, Coverage- und Routing-Tests sowie
Beibehaltung des vorhandenen Eligibility-Filters.

### Unnötiger Event-Fallback

Risiko: Ein zusätzliches Difficulty-Event oder Serverzustands-Sampling
dupliziert beziehungsweise zeitlich verschiebt das final gesendete Paket.

Gegenmaßnahme: Ausschließlich den vorhandenen finalen PacketEvents-Wire-Pfad
nutzen; keine neuen Event- oder Sampling-Komponenten.

### Versionsdrift

Risiko: 26.2 und 26.3 erhalten unterschiedliche Namen, Policies oder
Testmatrizen, obwohl ihr nativer Vertrag gleich ist.

Gegenmaßnahme: Gleiche Klasse `Id010ChangeDifficultyPacketDefinition`,
symmetrische Tests und automatischer Textvergleich nach
Versionsnormalisierung.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- `change_difficulty` ist in beiden Adaptern explizit klassifiziert.
- Alle acht definierten Payloadkombinationen sind nativ verifiziert.
- Recordings behalten den tatsächlich versendeten Wert.
- Neutraler Live-Playback verändert Difficulty und Lock-Flag des Beobachters
  nicht.
- Rekonstruktionen enthalten keinen viewer-lokalen Difficulty-Zustand.
- Der Registry-Fingerprint ändert sich durch die neue explizite Definition;
  ohne Altaufnahme-Migration ist dies beabsichtigt.

### Bewusste Grenzen

- Die Definition stellt die aufgezeichnete Weltschwierigkeit nicht visuell
  oder anderweitig in der Replay-Szene dar.
- Sie verändert nicht die echte Serverschwierigkeit.
- Sie erzeugt kein Reset-Paket, weil sie zuvor nichts injiziert.
- Nicht dokumentierte numerische Difficulty-IDs werden nicht als eigene
  semantische Werte modelliert.
- Die permissive native Decoder-Semantik wird nicht durch handgeschriebenen
  Produktionscode dupliziert.
- Andere Paket-IDs und nicht unmittelbar erforderliche Refactorings bleiben
  außerhalb des Umfangs.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- dem Bytecode von `ClientboundChangeDifficultyPacket`, `Difficulty`,
  `ByteBufCodecs` und `ByIdMap` in beiden Builds,
- der bestehenden Paketdefinitions- und Neutral-Observer-Architektur,
- dem vorhandenen Snapshot-/Checkpoint-Eligibility-Vertrag und
- der Minecraft-Protokolldokumentation zur Clientwirkung und Feldsemantik.

Relevante externe Quellen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Change_Difficulty>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundChangeDifficultyPacket.html>
- <https://mappings.dev/1.21.11/net/minecraft/world/Difficulty.html>

Die Online-Dokumentation beschreibt ID, Felder und Clientwirkung. Maßgeblich
für die konkrete Java-Form, den Codec und die Versionsgleichheit bleiben die
lokal angehefteten Paper-Artefakte sowie die nativen Protokolltests.

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
