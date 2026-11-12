# Implementierungsplan: `boss_event` (`0x09`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Umfang

Das PLAY-Clientbound-Paket `minecraft:boss_event` mit der dezimalen ID `9`
beziehungsweise `0x09` wird in beiden Paper-Adaptern als explizite,
versionslokale Paketdefinition umgesetzt.

Die Implementierung soll:

1. alle sechs definierten Bossbar-Operationen vollständig lesen, semantisch
   abbilden, umschreiben und neu aufbauen,
2. die Bossbar-UUID in den isolierten Identitätsraum der Playback-Sitzung
   übertragen,
3. dieselbe UUID-Zuordnung für `add`, sämtliche Updates und `remove`
   verwenden,
4. Namen als vollständige Minecraft-Textkomponenten erhalten,
5. alle sieben Farben, alle fünf Overlay-Arten und sämtliche acht
   Kombinationen der drei definierten Eigenschaftenflags unterstützen,
6. Fortschrittswerte bitgetreu erhalten, ohne den Wire-Vertrag künstlich auf
   `0.0..1.0` einzuschränken,
7. den dauerhaften, bis `remove` gültigen Bossbar-Zustand bei Live-Wiedergabe,
   Rekonstruktion und View-Reset korrekt behandeln,
8. den finalen PacketEvents-Wire-Capture als primäre Erfassungsquelle
   beibehalten und
9. keinen unvollständigen Bukkit-/Paper-Event-Fallback einführen.

Eine Migration alter Recordings, eine Wiederbelebung des entfernten
Paketsystems oder ein allgemeiner Umbau der Checkpoint-Architektur ist nicht
Teil des Umfangs.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur und Ablage

Die verbindliche Paketablage ordnet Bossbars der Domain `effect` zu. Die
Zuordnung richtet sich nach der Protokollverantwortung und nicht danach, ob
ein Paket flüchtig oder checkpoint-relevant ist. Die synchrone Benennung
beider Versionen lautet daher:

```text
Id009BossEventPacketDefinition
```

Vorgesehene Ablage:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/effect/
        └── Id009BossEventPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/effect/
        └── Id009BossEventPacketDefinition.java
```

`EffectPacketDefinitions` enthält derzeit `animate` (`0x02`) und
`block_destruction` (`0x05`). `boss_event` wird dort ergänzt. Der
versionslokale Gesamtkatalog sortiert alle Domainlisten anschließend bereits
nach numerischer Paket-ID; seine Struktur muss nicht geändert werden.

### 2.2 Capture- und ChannelHandler-Pfad

Der vorhandene `Paper26_*CaptureBridge` erfasst ausgehende Pakete über
PacketEvents auf `MONITOR`-Ebene. Der Wire-Buffer wird erst in einer Post-Task
kopiert. Damit bleibt der tatsächlich an den jeweiligen Empfänger gesendete
Zustand nach vorherigen PacketEvents- und ChannelHandler-Rewrites erhalten.

Dieser Pfad ist für `boss_event` wesentlich, weil Bossbars:

- empfängerbezogen sichtbar sein können,
- direkt durch Vanilla, Bukkit/Paper, Adventure, NMS oder Plugins erzeugt
  werden können,
- von vorherigen PacketEvents-Listenern in Name, Fortschritt, Stil,
  Eigenschaften oder UUID verändert werden können und
- mehrere Operationen in genauer zeitlicher Reihenfolge benötigen.

Das Paket besitzt keine räumliche Position. Der vorhandene Capture-Kontext
darf daher keine Position erfinden. Eine bekannte Empfängerwelt kann wie bei
anderen nicht räumlichen Paketen erhalten bleiben; die Paketdefinition selbst
benötigt keine Änderung am `PaperConnectionAccessor`.

### 2.3 Playback und Identitätsisolation

`ClientboundBossEventPacket` verwendet eine UUID als alleinigen Schlüssel
einer clientseitigen Bossbar. Diese UUID ist keine Entity- oder Profil-UUID,
kann aber mit anderen Live- oder Replay-Bossbars kollidieren. Sie wird deshalb
für **jede** Operation über `Paper26_*ReplayPacketRewriter.mapUuid(...)`
abgebildet.

Die vorhandene `ReplayIdentityMap` garantiert innerhalb einer Playback-
Sitzung eine stabile Abbildung. Daraus folgt:

- `add(recordedId)` und spätere Updates verwenden dieselbe neue UUID,
- `remove(recordedId)` entfernt genau die zuvor hinzugefügte Replay-Bossbar,
- unterschiedliche aufgezeichnete UUIDs bleiben getrennt und
- reservierte beziehungsweise bereits live verwendete UUIDs werden nicht
  übernommen.

Ein Entity-ID-Rewrite ist nicht erforderlich. Name, Fortschritt, Farbe,
Overlay und Eigenschaften enthalten keine weitere Playback-Identität.

### 2.4 Bestehendes Reset-Verhalten

`Paper26_*ViewResetter` versteht `ClientboundBossEventPacket` bereits:

- `add` nimmt die nach dem Rewrite tatsächlich gesendete UUID in die
  geordnete Menge aktiver Bossbars auf,
- `remove` entfernt sie wieder und
- `reset()` erzeugt für jede verbleibende UUID ein natives Remove-Paket.

Update-Operationen verändern die Menge aktiver IDs nicht. Dieses Verhalten
ist semantisch korrekt und wird durch neue Tests explizit abgesichert. Eine
zweite Bossbar-Zustandsverwaltung im Playback-Pfad wird nicht eingeführt.

### 2.5 Checkpoint-Vertrag und Grenze des Live-Snapshots

Die Adapter ordnen `minecraft:boss_event` bereits der Checkpoint-Familie
`GLOBAL_STATE` zu. Bossbars sind zustandsbehaftet: Nach `add` bleibt eine Bar
mit ihrem zuletzt aktualisierten Zustand sichtbar, bis `remove` empfangen
wird. Die Definition muss daher checkpoint-relevant und im
`RECONSTRUCTION`-Modus replaybar sein.

Der aktuelle Paper-Snapshot-Provider erzeugt jedoch keinen synthetischen
Bossbar-Snapshot. Das ist keine Information, die sich vollständig aus Welt-
oder Entity-Zustand ableiten lässt: plugin-erzeugte und empfängerbezogene
Bossbars sowie finale PacketEvents-Rewrites sind nur im aufgezeichneten
Wire-Strom vorhanden. Eine wirklich vollständige Verdichtung aktiver
Bossbar-Lebenszyklen in periodische Checkpoints würde einen
recording-sitzungslokalen, empfängerbewussten Protokollzustandsindex und eine
Erweiterung der allgemeinen Checkpoint-Schnittstelle erfordern.

Diese Aufgabe ändert die Checkpoint-Architektur nicht. Sie stellt sicher,
dass im Rekonstruktionsbereich vorhandene Bossbar-Operationen nicht
verworfen werden und dass die bestehende `GLOBAL_STATE`-Zuordnung gültig
bleibt. Die beschriebene Grenze wird nicht durch einen unvollständigen
Bukkit- oder Serverzustands-Snapshot kaschiert.

## 3. Verifizierter nativer Protokollvertrag

Die lokal angehefteten Paper-Dev-Bundles 26.2 und 26.3 enthalten denselben
öffentlichen Vertrag:

```java
ClientboundBossEventPacket.createAddPacket(BossEvent)
ClientboundBossEventPacket.createRemovePacket(UUID)
ClientboundBossEventPacket.createUpdateProgressPacket(BossEvent)
ClientboundBossEventPacket.createUpdateNamePacket(BossEvent)
ClientboundBossEventPacket.createUpdateStylePacket(BossEvent)
ClientboundBossEventPacket.createUpdatePropertiesPacket(BossEvent)

void dispatch(ClientboundBossEventPacket.Handler)
```

`ClientboundBossEventPacket.Handler` stellt genau diese Callbacks bereit:

```java
add(UUID, Component, float, BossBarColor, BossBarOverlay,
    boolean darkenSky, boolean playMusic, boolean createWorldFog)
remove(UUID)
updateProgress(UUID, float)
updateName(UUID, Component)
updateStyle(UUID, BossBarColor, BossBarOverlay)
updateProperties(UUID, boolean darkenSky,
    boolean playMusic, boolean createWorldFog)
```

Die Paketfelder selbst sind privat. `dispatch(...)` ist der vorgesehene,
typsichere Leseweg; die statischen Fabriken sind der vorgesehene Aufbauweg.
Reflection oder ein Zugriff auf interne Operation-Klassen ist nicht nötig.

### 3.1 Gemeinsamer Wire-Präfix

Jede Variante beginnt mit:

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | Bossbar ID | UUID, 16 Bytes | `UUID` | clientseitiger Lebenszyklus-Schlüssel |
| 2 | Action | VarInt Enum | Operation `0..5` | bestimmt alle folgenden Felder |

Die Aktionswerte sind:

| Action | Operation | Folgende Felder |
|---:|---|---|
| 0 | Add | Name, Fortschritt, Farbe, Overlay, Eigenschaften |
| 1 | Remove | keine |
| 2 | Update Progress | Fortschritt |
| 3 | Update Name | Name |
| 4 | Update Style | Farbe, Overlay |
| 5 | Update Properties | Eigenschaften |

Eine unbekannte Action-ID besitzt keine definierte Reststruktur und wird vom
nativen Enum-Decoder abgewiesen.

### 3.2 Name

Der Name wird mit `ComponentSerialization.TRUSTED_STREAM_CODEC` übertragen.
Er ist deshalb keine einfache UTF-8-Zeichenkette, sondern eine vollständige
Minecraft-Textkomponente. Unter anderem müssen erhalten bleiben:

- literale und übersetzbare Komponenten,
- verschachtelte `extra`-Komponenten,
- Farben und Textdekorationen,
- Hover- und Click-Events sowie
- weitere vom nativen Komponenten-Codec unterstützte Inhalte.

Die Paketdefinition behält das native `Component`-Objekt bei und versucht
nicht, es in Plaintext oder ein eigenes Teilmodell zu konvertieren.

### 3.3 Fortschritt

Fortschritt ist ein IEEE-754-`float`. Die Dokumentation beschreibt `0.0` bis
`1.0` als normalen Bereich, weist aber ausdrücklich darauf hin, dass Werte
größer als `1.0` von Vanilla verarbeitet und teilweise als weitere Leiste
gerendert werden. Der native Codec enthält keine Bereichs- oder
Endlichkeitsprüfung.

Die Definition darf daher weder clampen noch ablehnen. Sie bewahrt die
`float`-Bits einschließlich:

- `-0.0`, `0.0` und `1.0`,
- negativer Werte,
- Werte größer als `1.0`,
- Subnormalwerte,
- positiver und negativer Unendlichkeit und
- NaN-Repräsentationen, soweit der native ByteBuf-Roundtrip sie erhält.

Dies ist ein Wire-Treue-Vertrag, keine Empfehlung für das Erzeugen neuer
Bossbars.

### 3.4 Farbe und Overlay

Farbe und Overlay werden als ordinale VarInt-Enums übertragen.

Definierte Farben:

| ID | Farbe |
|---:|---|
| 0 | Pink |
| 1 | Blue |
| 2 | Red |
| 3 | Green |
| 4 | Yellow |
| 5 | Purple |
| 6 | White |

Definierte Overlays:

| ID | Overlay |
|---:|---|
| 0 | Progress, keine Unterteilung |
| 1 | 6 Notches |
| 2 | 10 Notches |
| 3 | 12 Notches |
| 4 | 20 Notches |

Unbekannte Enum-IDs werden vom nativen Codec abgewiesen. Produktionscode
bildet keine eigene nummerische Allowlist, sondern behält die nativen Enum-
Werte bei. Die Tests iterieren sämtliche Werte der beiden Ziel-Builds, damit
neue Werte bei einem späteren Adapter-Upgrade sichtbar werden.

### 3.5 Eigenschaftenflags

Das einzelne unsigned Byte verwendet drei definierte Bits:

| Bit | Maske | Native Bedeutung |
|---:|---:|---|
| 0 | `0x01` | Himmel beziehungsweise Bildschirm abdunkeln |
| 1 | `0x02` | Bossmusik abspielen; historisch Dragon-Bar-Eigenschaft |
| 2 | `0x04` | Weltnebel erzeugen |

Damit existieren acht definierte Kombinationen. Der native Decoder projiziert
das Byte sofort auf drei Booleans; unbekannte reservierte Bits `0x08..0x80`
werden verworfen. Die statischen Fabriken schreiben anschließend die
kanonische Maske `0..7`. Die Paketdefinition folgt bewusst diesem nativen
semantischen Vertrag und erfindet kein paralleles Raw-Flag-Feld, das über die
öffentliche Paket-API weder ausgelesen noch wieder aufgebaut werden könnte.

### 3.6 Tatsächliche Client-Auswertung

Der Client leitet das Paket an sein Boss-Overlay weiter. Dort gilt:

- `add` erzeugt eine neue clientseitige Bossbar unter der UUID,
- `remove` entfernt sie,
- die vier Update-Varianten verändern ausschließlich den jeweils
  übertragenen Teilzustand,
- Fortschritt wird clientseitig interpoliert und
- die Eigenschaften beeinflussen globale Präsentation wie Abdunklung,
  Bossmusik und Nebel.

Die Reihenfolge ist damit Teil der Semantik. Updates werden nicht in `add`
zusammengezogen, `remove` wird nicht vorgezogen und zwei Bars mit
verschiedenen UUIDs werden nicht dedupliziert.

## 4. Klassifikation und Policy

Die Definition erhält:

| Eigenschaft | Wert |
|---|---|
| Disposition | `STATEFUL` |
| Standard-Capture | `true` |
| Replay | `true` |
| Checkpoint | `true` |
| Playback-Scope | `REWRITE_IDENTITIES` |

Begründung:

- Ein `add` erzeugt sichtbaren Zustand, der bis `remove` fortbesteht.
- Update-Operationen verändern diesen dauerhaften Zwischenzustand.
- Bossbar-UUIDs müssen gegen Live- und andere Replay-Bars isoliert werden.
- Die Operationen sind in Live-Wiedergabe und Seek-Rekonstruktion relevant.
- Der Zustand ist nicht viewer-lokal im Sinne einer zu schützenden
  Beobachteroberfläche: Es handelt sich um aufzuzeichnende Szenenpräsentation,
  die nach der Wiedergabe gezielt wieder entfernt werden kann.

`PacketClassification.stateful(
PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES)` bildet diesen Vertrag ab.

## 5. Geplante Implementierung

### 5.1 Versionslokale Paketdefinitionen

Beide Klassen erweitern:

```text
RebuildingPacketDefinition<
    Paper26_*ReplayPacketRewriter,
    ClientboundBossEventPacket,
    Value>
```

Das interne Modell besteht aus einer gemeinsamen UUID und einer geschlossenen
Operation-Hierarchie:

```java
record Value(UUID id, Operation operation) {}

sealed interface Operation permits
        Add, Remove, UpdateProgress, UpdateName,
        UpdateStyle, UpdateProperties {}
```

Die sechs Varianten tragen ausschließlich ihre Wire-Felder:

- `Add`: Komponente, Fortschritt, Farbe, Overlay und drei Booleans,
- `Remove`: keine Nutzdaten,
- `UpdateProgress`: Fortschritt,
- `UpdateName`: Komponente,
- `UpdateStyle`: Farbe und Overlay,
- `UpdateProperties`: drei Booleans.

Verarbeitung:

1. `read(...)` ruft `packet.dispatch(...)` mit einem Handler auf, der genau
   eine immutable Operation erzeugt.
2. Ein fehlender oder mehrfacher Callback wird als inkompatibler nativer
   Vertrag behandelt statt stillschweigend geraten.
3. `rewrite(...)` erzeugt `Value(context.mapUuid(value.id()), operation)`;
   die Operation selbst bleibt unverändert.
4. `rebuild(...)` verwendet für `remove` direkt
   `createRemovePacket(mappedId)`.
5. Für die übrigen Varianten wird ein kleines privates `BossEvent`-Snapshot-
   Objekt mit ausschließlich den benötigten Feldern erzeugt und an die
   passende native Fabrik übergeben.
6. Das Snapshot-Objekt wird nicht registriert, keinem Spieler hinzugefügt und
   unmittelbar von der Paketfabrik kopiert. Es dient nur dazu, die öffentliche
   native Aufbau-API ohne Reflection zu verwenden.

`Value` und die Operation-Records lehnen `null` für UUID, Operation,
Komponente, Farbe und Overlay ab. Fortschrittswerte werden ausdrücklich nicht
normalisiert oder validiert.

### 5.2 Registrierung und Coverage

`EffectPacketDefinitions` beider Versionen wird um ID `0x09` ergänzt. Der
Gesamtkatalog enthält danach die expliziten PLAY-Definitionen `0x00` bis
einschließlich `0x09` in numerischer Reihenfolge.

`PacketCoverageTest` wird synchron erweitert:

- exakter Typname `play.clientbound.minecraft:boss_event`,
- Disposition `STATEFUL`,
- Capture aktiv,
- Replay aktiv,
- Checkpoint-Relevanz aktiv,
- Scope `REWRITE_IDENTITIES` und
- alle späteren, noch nicht implementierten Pakete bleiben `UNSUPPORTED`.

### 5.3 Capture-Kontext

`boss_event` besitzt keine Weltposition, Entity-ID oder Profil-ID. Es wird
daher kein neuer `packetPosition(...)`-Fall ergänzt. Ein Definitionstest und
ein eng begrenzter Capture-Accessor-Test stellen sicher, dass:

- keine Position erfunden wird,
- keine Bossbar-UUID als Entity- oder Profilidentität im `CaptureContext`
  erscheint und
- der vorhandene Empfängerwelt-Kontext unberührt bleibt.

Falls die bestehende generische Capture-Testabdeckung diesen Vertrag bereits
vollständig prüft, wird kein redundanter Produktions- oder Testfall ergänzt.

### 5.4 Playback und Reset

`Paper26_*PlaybackBridgeTest` wird um `boss_event` erweitert:

- Live-Wiedergabe sendet das Paket,
- Rekonstruktion sendet das Paket,
- jede Operation trägt die gemappte UUID und
- wiederholte Operationen derselben aufgezeichneten UUID bleiben konsistent.

`Paper26_*ViewResetterTest` erhält Bossbar-Fälle:

- `add` gefolgt von Updates erzeugt beim Reset genau ein Remove,
- `add` gefolgt von `remove` erzeugt kein zusätzliches Remove,
- mehrere Bars werden deterministisch in Add-Reihenfolge entfernt,
- nur die bereits gemappten, tatsächlich beobachteten Playback-UUIDs werden
  entfernt und
- ein fehlgeschlagener Reset behält den Zustand für einen erneuten Versuch.

Am produktiven `ViewResetter` ist voraussichtlich keine Änderung nötig. Eine
Änderung erfolgt nur, falls die Tests eine konkrete Lücke im bereits
vorhandenen Vertrag nachweisen.

### 5.5 Checkpoint

An `Paper26_*CheckpointEncoder.familyFor(...)` ist keine Produktionsänderung
vorgesehen, weil `boss_event` bereits `GLOBAL_STATE` zugeordnet ist.

Die Tests bestätigen:

- die Definition ist checkpoint-relevant,
- der Descriptor wird von der vorhandenen Generator-Matrix akzeptiert und
- Bossbar-Frames im Rekonstruktionsbereich werden nicht als flüchtig
  verworfen.

Es wird kein Bossbar-Zustand aus Bukkit-Registries oder Entity-BossEvents
synthetisiert. Ein solcher Teil-Snapshot wäre für direkt gesendete,
empfängerbezogene oder nachträglich umgeschriebene Bars falsch.

## 6. Event-Fallback-Entscheidung

Bukkit/Paper bietet Bossbar-APIs und einzelne Gameplay-Ereignisse für Bosse.
Sie bilden den Netzwerkvertrag dennoch nicht vollständig ab. Ein Event- oder
API-Fallback würde insbesondere verlieren:

- direkt per NMS oder PacketEvents gesendete Bossbars,
- Adventure- oder plugininterne Bars ohne globalen Bukkit-Key,
- empfängerabhängige Sichtbarkeit,
- finale UUID-, Komponenten-, Fortschritts-, Stil- und Flag-Rewrites,
- reine Update- und Remove-Operationen in ihrer Wire-Reihenfolge,
- ungewöhnliche, aber gültige Fortschrittswerte und
- die exakte zeitliche Beziehung zu anderen Paketen.

Ein zusätzlicher Fallback könnte außerdem dieselbe Bar doppelt erfassen oder
verschiedene Empfängerzustände vermischen. Deshalb bleibt ausschließlich der
vorhandene finale Wire-Pfad aktiv. Events wären nur eine Notfallebene in einer
Umgebung ohne vertrauenswürdigen Paketpfad; diese Voraussetzung liegt bei den
unterstützten Paper-Adaptern nicht vor.

## 7. Adapter-Dokumentationen

Die vollständigen Paket-Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig und neutral. Nur die Zeile für
`minecraft:boss_event` wird präzisiert:

- Beschreibung/Relevanz: dauerhafter Bossbar-Lebenszyklus mit sechs
  Operationen, vollständiger Text-/Stil-/Eigenschaftserhaltung und
  sitzungsisolierter UUID,
- Playback-Policy: `REWRITE`,
- Checkpoint-Policy: `yes`.

Die Dokumente erhalten keine Aussage über Implementierungsfortschritt oder
Tests einzelner Pakete. Alle anderen Paketzeilen bleiben unangetastet.

## 8. Teststrategie

### 8.1 Definitionstests je Version

Je Version entsteht ein
`Id009BossEventPacketDefinitionTest` mit mindestens folgenden Fällen:

1. ID `9`/`0x09`, exakter Identifier und nativer Pakettyp.
2. Klassifikation `STATEFUL`, Capture an, Replay an, Checkpoint an und
   `REWRITE_IDENTITIES`.
3. Vollständiger Read-/Rewrite-/Rebuild-Roundtrip aller sechs Operationen.
4. Stabile UUID-Abbildung über `add`, alle vier Updates und `remove`.
5. Trennung zweier verschiedener aufgezeichneter Bossbar-UUIDs.
6. Konfliktfreie Abbildung gegenüber reservierten UUIDs.
7. Repräsentative komplexe Textkomponenten mit Stil und Verschachtelung.
8. Sämtliche nativen `BossBarColor`-Werte.
9. Sämtliche nativen `BossBarOverlay`-Werte.
10. Alle acht Kombinationen der drei Eigenschaftenflags bei `add` und
    `updateProperties`.
11. Fortschrittswerte `-0.0`, `0.0`, `1.0`, negativ, größer als `1.0`,
    kleinste/größte endliche Werte, Unendlichkeiten, Subnormalwerte und NaN.
12. Ablehnung von `null` für UUID, Operation, Namen, Farbe und Overlay.
13. Ablehnung einer falschen decodierten Java-Klasse.
14. Vollständiger `handlePlayback`- und validierter Encode-Pfad.

### 8.2 Native Wire-Tests

Die native Protokollprüfung bestätigt je Version:

- Paket-ID `0x09`,
- gemeinsamen Präfix UUID vor Action-VarInt,
- exakte Action-IDs `0..5`,
- variantspezifische Feldreihenfolge,
- Komponenten-Roundtrip über den nativen Trusted-Codec,
- bitgetreuen Fortschritts-Roundtrip im Rahmen des nativen ByteBuf-Vertrags,
- ordinale IDs sämtlicher Farben und Overlays,
- kanonische Flagbytes `0..7`,
- Projektion gesetzter reservierter Flagbits auf die drei definierten
  Booleans und kanonischen Re-Encode,
- vollständigen Verbrauch gültiger Payloads.

Negative Fälle umfassen mindestens:

- fehlende beziehungsweise abgeschnittene UUID,
- fehlende, negative, überlange oder unbekannte Action-ID,
- für jede Action abgeschnittene variantspezifische Nutzdaten,
- ungültige beziehungsweise unbekannte Farb- und Overlay-IDs,
- malformed Komponenten-Payload und
- nachlaufende Bytes über den vorhandenen verbindungsgebundenen Decoder, der
  vollständigen Payloadverbrauch verlangt.

### 8.3 Integrations- und Reset-Tests

`PacketCoverageTest` prüft den expliziten Katalogvertrag bis ID `0x09`.

`Paper26_*PlaybackBridgeTest` prüft Live- und Rekonstruktionsmodus sowie den
checkpoint-relevanten Descriptor.

`Paper26_*ViewResetterTest` prüft den vollständigen Add-/Update-/Remove-
Lebenszyklus und das Fehlerverhalten beim Zurücksetzen.

Der bestehende PacketEvents-Capture-Test bleibt die maßgebliche Absicherung
für den finalen, möglicherweise umgeschriebenen Wire-Buffer. Es wird kein
zweiter paketspezifischer Listener eingeführt.

### 8.4 Dokumentation und Versionssymmetrie

Die Dokumentationsprüfung stellt sicher, dass:

- alle Paketzeilen beider Versionen erhalten bleiben,
- die `boss_event`-Zeile Beschreibung, Playback- und Checkpoint-Policy
  enthält und
- keine Status- oder Testaussage ergänzt wurde.

Definitionen und Tests werden nach Normalisierung der Versionsbezeichner
verglichen. Abweichungen sind nur zulässig, wenn die angehefteten nativen
Builds tatsächlich unterschiedliche Verträge zeigen; die Analyse hat für
`boss_event` keine solche Abweichung ergeben.

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

- `packet/definition/effect/Id009BossEventPacketDefinition.java`
- `packet/definition/effect/EffectPacketDefinitions.java`

Tests je Version:

- `packet/definition/effect/Id009BossEventPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`
- `playback/Paper26_2ViewResetterTest.java` beziehungsweise
  `playback/Paper26_3ViewResetterTest.java`
- gegebenenfalls eng begrenzte bestehende Codec-/Checkpoint-Fixtures, falls
  der explizite Descriptor deren bisher abstrakten Vertrag berührt.

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser Implementierungsplan

Nicht vorgesehen sind Änderungen an:

- Core-Format oder Recording-Migration,
- PacketEvents-Listenern oder PacketEvents-Wrappern,
- Bukkit-/Paper-Event-Listenern,
- `ReplayIdentityMap` oder allgemeinen Identity-Schnittstellen,
- `PaperConnectionAccessor`, sofern die Analyseannahme durch Tests bestätigt
  wird,
- Snapshot-Providern oder der allgemeinen Checkpoint-Schnittstelle,
- allgemeinen ChannelHandlern oder
- anderen Paketdefinitionen.

## 10. Risiken und Gegenmaßnahmen

### Variantenverlust

Risiko: Eine generische Bossbar-Struktur könnte Update-Operationen in einen
vollständigen Zustand umwandeln und dadurch nicht übertragene Felder erfinden.

Gegenmaßnahme: Geschlossene, variantspezifische Operation-Records tragen nur
die tatsächlich auf dem Wire vorhandenen Felder.

### Inkonsistentes UUID-Rewrite

Risiko: Wenn nur `add` umgeschrieben wird, treffen Updates und `remove` die
falsche oder keine Bossbar.

Gegenmaßnahme: Die UUID liegt oberhalb der Operation und wird ausnahmslos vor
jedem Rebuild über dieselbe sitzungslokale Map geführt.

### Unzulässige Fortschrittsnormalisierung

Risiko: Clamping auf `0..1` verändert gültige Plugin-Pakete und sichtbare
Vanilla-Clienteffekte.

Gegenmaßnahme: Keine semantische Bereichsprüfung; Tests decken IEEE-754-
Grenzklassen und Raw-Bit-Roundtrips ab.

### Verlust reservierter Flagbits

Risiko: Ein Test könnte fälschlich verlangen, unbekannte Bits bitgetreu zu
erhalten, obwohl der native Paketdecoder sie bereits verwirft.

Gegenmaßnahme: Der dokumentierte native Boolean-Vertrag wird getestet. Raw-
Payload-Bits außerhalb `0x07` werden als native Kanonisierung und nicht als
Definitionseigenschaft behandelt.

### Unvollständiger Checkpoint-Snapshot

Risiko: Eine vor einem periodischen Checkpoint hinzugefügte, danach nicht
mehr aktualisierte Bossbar lässt sich aus dem aktuellen Welt-Snapshot nicht
rekonstruieren.

Gegenmaßnahme: Keine falsche Serverzustandsableitung. Die Definition bleibt
für Rekonstruktion korrekt klassifiziert; eine vollständige Wire-State-
Verdichtung wird als eigenständige, architekturweite Folgeaufgabe behandelt.

### Reset-Leak

Risiko: Eine beim Ende der Wiedergabe verbleibende Bossbar beeinflusst den
normalen Beobachter weiter, insbesondere durch Musik, Himmel oder Nebel.

Gegenmaßnahme: Bestehenden Add-/Remove-Tracker mit allen Updatevarianten,
mehreren Bars und Writer-Fehlern testen.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- Bossbars werden mit vollständigem Lebenszyklus wiedergegeben.
- Replay-Bossbars kollidieren nicht mit Live-Bossbars des Beobachters.
- Text, Fortschritt, Farbe, Overlay und Eigenschaften bleiben gemäß nativem
  Codec erhalten.
- Nach dem Replay verbleibende Bars werden gezielt entfernt.
- Der Registry-Fingerprint ändert sich durch die neue explizite Definition;
  dies ist ohne Altaufnahme-Migration beabsichtigt.
- Beide Paper-Versionen behalten synchrone Namen, Struktur und Policy.

### Bewusste Grenzen

- Der Adapter korrigiert keine semantisch ungewöhnlichen Fortschrittswerte.
- Reservierte Flagbits, die Mojangs Decoder nicht darstellt, werden nicht in
  einem parallelen Schattenmodell gespeichert.
- Ein Update ohne vorheriges `add` wird in Wire-Reihenfolge weitergegeben;
  der Adapter erfindet keinen Ausgangszustand.
- Bossbars werden nicht aus Entitytypen oder Bukkit-Bossbar-Registries
  abgeleitet.
- Die allgemeine periodische Checkpoint-Verdichtung empfängerbezogener
  Protokollzustände bleibt außerhalb dieser Paketimplementierung.
- Andere Paket-IDs und nicht unmittelbar erforderliche Refactorings bleiben
  außerhalb des Umfangs.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- dem Bytecode von `ClientboundBossEventPacket`, seinen Operationen,
  `Handler`, `OperationType` und `BossEvent` in beiden Builds,
- der bestehenden `ReplayIdentityMap`, dem Playback-Rewriter und dem
  `Paper26_*ViewResetter`,
- der vorhandenen `GLOBAL_STATE`-Checkpoint-Zuordnung und
- der Minecraft-Protokolldokumentation für Wire-Felder und Clientsemantik.

Relevante externe Quellen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Boss_Bar>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundBossEventPacket.html>
- <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundBossEventPacket%24Operation.html>
- <https://mappings.dev/1.21.11/net/minecraft/client/multiplayer/ClientPacketListener.html>
- <https://mc-packet-reference.netlify.app/26.x/start/>
- <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/network/protocol/game/gamepackettypes/>

Die Online-Dokumentation beschreibt das Wire-Format und die öffentliche
Semantik. Maßgeblich für Klassenform, Codec, Fabriken und Versionsgleichheit
bleiben die lokal angehefteten Paper-Artefakte und die nativen
Protokolltests.

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
