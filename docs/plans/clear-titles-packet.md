# Implementierungsplan: `clear_titles` (`0x0E`)

Status: **Zur Freigabe**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Abgrenzung

Das PLAY-Clientbound-Paket mit ID `14` beziehungsweise `0x0E` wird in beiden
Paper-Adaptern als explizite, versionslokale Paketdefinition umgesetzt:

```text
minecraft:clear_titles
Id014ClearTitlesPacketDefinition
```

Die Umsetzung soll den vollständigen Vertrag des Pakets abdecken:

1. beide semantischen Boolean-Zustände (`clear` und `reset`),
2. das native Ein-Byte-Wire-Format einschließlich der Decoder-
   Kanonisierung nichtnuller Boolean-Bytes,
3. die korrekte Einordnung als Zustand der aufgezeichneten Verbindung,
4. vollständiges Standard-Capture ohne semantisch unvollständigen Event-
   Ersatz,
5. das sichere Verwerfen aufgezeichneter Pakete vor Decode und Encode,
6. das unveränderte Durchlassen echter Live-UI-Pakete des neutralen
   Zuschauers durch den Playback-ChannelHandler und
7. symmetrische Definitionen und Tests für Paper 26.2 und 26.3.

Nicht Bestandteil sind die benachbarten Pakete `set_title_text`,
`set_subtitle_text` und `set_titles_animation`, eine Migration alter
Recordings, eine Formatänderung oder ein allgemeiner Umbau der UI-Pipeline.

## 2. Analysierter Ist-Zustand

### 2.1 Paketdomain und Ablage

`docs/packet-definition-layout.md` ordnet Titel, Actionbar, Screens und
ähnliche Präsentationszustände ausdrücklich der Domain `ui` zu. Der Ablageort
richtet sich nach der Protokollverantwortung und nicht nach der aktuellen
Playback-Policy. Deshalb entstehen erstmals die versionslokalen UI-Domains:

```text
replay-adapter/paper-26_2/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_2/
    └── packet/definition/ui/
        ├── Id014ClearTitlesPacketDefinition.java
        └── UiPacketDefinitions.java

replay-adapter/paper-26_3/src/main/java/
└── dev/voldechse/replayframework/adapter/paper/v26_3/
    └── packet/definition/ui/
        ├── Id014ClearTitlesPacketDefinition.java
        └── UiPacketDefinitions.java
```

Die beiden `Paper26_*PacketCatalog`-Klassen nehmen ausschließlich den neuen
UI-Aggregator auf. Der Katalog sortiert weiterhin domainübergreifend nach
Paket-ID. Es wird keine Definition in eine fachlich falsche bestehende Domain
verschoben und keine allgemeine Katalogabstraktion verändert.

### 2.2 Vorhandene Viewer-Local-Policy

`NeutralObserverPolicy` führt `clear_titles` bereits als lokalen Zustand der
aufgezeichneten Verbindung. `PacketClassification.stateful(VIEWER_LOCAL)`
bildet diesen Vertrag ab:

- Capture ist standardmäßig aktiv,
- der Frame darf den Live-Playback-Policy-Pfad erreichen,
- die Paketdefinition verwirft ihn dort vor jedem Payload-Decode,
- das Paket ist nicht checkpoint-relevant und
- eine Rekonstruktion selektiert den Frame gar nicht erst.

Dieses zweistufige Verhalten ist beabsichtigt. `replayable = true` bedeutet
hier nicht, dass der Payload an den Zuschauer gesendet wird. Es bedeutet,
dass der Frame die explizite Definition und damit deren `DROP_LOCAL`-
Entscheidung erreicht. Dadurch wird ein lokales Paket nicht wie ein
unbekanntes oder unsicheres Paket behandelt.

### 2.3 ChannelHandler-Verhalten

Der vorhandene `ReplayViewerOutboundGate` unterdrückt echte Serverpakete nur,
wenn der Replayzustand die sichtbare Szene übernimmt. Für
`PlaybackScope.VIEWER_LOCAL` liefert seine Suppressionsentscheidung bewusst
`false`. Daraus folgen zwei getrennte Pfade:

1. Ein **aufgezeichnetes** `clear_titles` läuft im Live-Playback zur
   Paketdefinition und endet als `DROP_LOCAL`.
2. Ein **echtes Live-Paket** der aktuellen Zuschauer-Verbindung passiert den
   Outbound-Gate unverändert.

Damit kann ein Replay weder Titel, Untertitel noch Titelzeiten des neutralen
Zuschauers löschen. Gleichzeitig bleiben serverseitige UI-Steuerung und
Statusanzeigen des echten Zuschauers funktionsfähig. Für dieses Paket ist
keine ChannelHandler-Sonderlogik erforderlich.

### 2.4 Vergleichbare bestehende Definitionen

`award_stats` und `change_difficulty` verwenden bereits denselben
viewer-lokalen Definitionsstil: eine konkrete `PacketDefinition` mit
vollständiger Policy, aber ohne `read`/`rewrite`/`rebuild` des Payloads. Eine
`RebuildingPacketDefinition` ist hier nicht passend, weil deren Konstruktor
absichtlich nur `SAFE` und `REWRITE_IDENTITIES` akzeptiert.

Das Auslassen eines semantischen Rebuilds ist keine fehlende
Paketimplementierung. Die Sicherheitsgarantie verlangt gerade, dass selbst
ein gültiger oder bösartig fehlerhafter aufgezeichneter Payload nicht
dekodiert, rekonstruiert oder encodiert wird. Der native Protokollvertrag wird
separat mit dem echten Paper-Codec vollständig getestet.

## 3. Verifizierter Protokollvertrag

### 3.1 Native Paketklasse

Beide angehefteten Paper-Builds verwenden dieselbe Klasse:

```java
public class ClientboundClearTitlesPacket
        implements Packet<ClientGamePacketListener> {
    private final boolean resetTimes;

    public ClientboundClearTitlesPacket(boolean resetTimes);
    public boolean shouldResetTimes();
}
```

Der Codec liest und schreibt genau einen Boolean über `FriendlyByteBuf`.
Beide Versionen verwenden denselben `StreamCodec`-Vertrag und denselben
Handler `ClientGamePacketListener.handleTitlesClear(...)`.

### 3.2 Wire-Format

Der vollständige Paketbody enthält genau ein Feld:

| Reihenfolge | Feld | Wire-Typ | Java-Typ | Bedeutung |
|---:|---|---|---|---|
| 1 | Reset | Boolean / ein Byte | `boolean` | Titelzustand löschen; bei `true` zusätzlich Titelanimationszeiten zurücksetzen |

Der native Encoder schreibt kanonisch:

- `0x00` für `false`,
- `0x01` für `true`.

Nettys `ByteBuf.readBoolean()` interpretiert `0x00` als `false` und jedes
andere Byte als `true`. Ein Decode-Reencode-Roundtrip kanonisiert deshalb
beispielsweise `0x02`, `0x7F`, `0x80` und `0xFF` zu `0x01`. Diese
Wire-Eigenschaft wird getestet, aber nicht als zusätzlicher semantischer
Zustand in der Definition modelliert: Java und der native Pakettyp kennen nur
`false` und `true`.

Ein leerer Body ist abgeschnitten und muss im nativen Decode scheitern. Ein
zusätzliches Byte gehört nicht zum Paketfeld; der feldbezogene StreamCodec
liest nur das erste Byte. Ob überzählige Daten auf Framing-Ebene abgelehnt
werden, ist Aufgabe der Minecraft-Paketpipeline und nicht der Definition.

### 3.3 Bedeutung beider Zustände

Die lokale `TitleCommand`-Implementierung belegt die Zuordnung unmittelbar:

- `/title <targets> clear` erzeugt
  `new ClientboundClearTitlesPacket(false)`.
- `/title <targets> reset` erzeugt
  `new ClientboundClearTitlesPacket(true)`.

Beide Varianten entfernen die aktuell clientseitig gehaltene Titelanzeige,
also den Titel-/Untertitelzustand. Nur die Reset-Variante setzt zusätzlich
die clientseitigen Werte für Fade-In, Stay und Fade-Out auf ihre
Standardwerte zurück. Das Paket enthält selbst keinen Text und keine drei
Zeitwerte; es ist eine Operation auf bereits vorhandenem UI-Zustand.

Das Paket betrifft nicht die Actionbar. Diese wird über
`set_action_bar_text` separat gesteuert. Es enthält außerdem keine Player-,
Entity-, UUID-, Welt- oder Registry-Referenz.

## 4. Klassifikation und fachliche Entscheidung

Die Definition erhält in beiden Versionen:

| Eigenschaft | Wert | Begründung |
|---|---|---|
| Disposition | `STATEFUL` | verändert den persistent gehaltenen Titel-/Timingzustand des Clients |
| Standard-Capture | `true` | beide Operationen müssen in der Aufnahme erhalten bleiben |
| Replay-Policy-Pfad | `true` | der Frame muss die explizite lokale Drop-Entscheidung erreichen |
| Checkpoint | `false` | Zustand gehört zur Verbindung des aufgezeichneten Spielers, nicht zur Szene |
| Playback-Scope | `VIEWER_LOCAL` | Ziel ist ausschließlich der empfangende Client |
| Ergebnis bei Live-Playback | `DROP_LOCAL` | Titelzustand des neutralen Zuschauers nicht verändern |
| Rekonstruktion | nicht selektiert | viewer-lokale, nicht checkpoint-relevante Operation |

`STATEFUL` ist gegenüber `EPHEMERAL` korrekt, weil insbesondere
`resetTimes = true` die gespeicherten Titelzeiten über den Moment des Pakets
hinaus verändert. Eine spätere `set_title_text`-Operation verwendet diesen
veränderten clientseitigen Zustand. Diese Dauerhaftigkeit macht das Paket
jedoch nicht zu einer Szenen-Checkpoint-Komponente.

Die Definition benötigt weder Identity-Rewrite noch Reset-Tracking im
`Paper26_*ViewResetter`: Der aufgezeichnete Zustand wird nie auf dem
Zuschauer installiert. Beim Ende des Replays gibt es daher nichts
paketspezifisch zurückzusetzen.

## 5. Geplante Implementierung

### 5.1 Paketdefinitionen

Je Version entsteht eine package-private finale Klasse:

```java
final class Id014ClearTitlesPacketDefinition
        extends PacketDefinition<Paper26_*ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0E;
    static final String IDENTIFIER = "minecraft:clear_titles";

    Id014ClearTitlesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
```

Die Javadoc beschreibt:

- den Unterschied zwischen Clear und Reset,
- die Wirkung auf Titel-/Untertitel- und Animationszeiten,
- die Abgrenzung zur Actionbar,
- weshalb der Zustand viewer-lokal ist und
- weshalb das Basistemplate vor Decode verwirft.

Es wird bewusst kein ungenutztes `Value`-Record, kein eigener Boolean-
Parser und kein Rebuild-Pfad eingeführt.

### 5.2 UI-Aggregatoren und Kataloge

Beide Versionen erhalten einen `UiPacketDefinitions`-Aggregator mit einer
unveränderlichen Liste, die zunächst ausschließlich
`Id014ClearTitlesPacketDefinition` enthält.

`Paper26_2PacketCatalog` und `Paper26_3PacketCatalog` importieren den
jeweiligen UI-Aggregator und führen dessen Stream in den vorhandenen
Gesamtkatalog ein. Die resultierende Sortierung bleibt numerisch; die
Katalogreihenfolge endet nach dieser Aufgabe bei ID `0x0E`.

Die Stream-Komposition wird nur so weit angepasst, wie die neue Domain dies
erfordert. Eine allgemeinere Refaktorierung oder neue Hilfsabstraktion ist
nicht vorgesehen.

### 5.3 Unveränderte Komponenten

Keine Produktionsänderung ist vorgesehen an:

- `Paper26_*CaptureBridge` und PacketEvents-Listenern,
- `PaperConnectionAccessor`,
- `NeutralObserverPolicy` (enthält `clear_titles` bereits),
- `ReplayViewerOutboundGate`,
- `Paper26_*ReplayPacketRewriter`,
- `Paper26_*ViewResetter`,
- Checkpoint-Encoder und Snapshot-Provider,
- Replay-Core und Replay-Format oder
- den Definitionen anderer Paket-IDs.

## 6. Capture und Event-Fallback

Der vorhandene PacketEvents-Capture-Pfad beobachtet den final ausgehenden
Wire-Payload. Damit erhält die Aufnahme exakt:

- den tatsächlich gesendeten Boolean,
- die Reihenfolge relativ zu `set_title_text`, `set_subtitle_text` und
  `set_titles_animation`,
- Änderungen vorheriger Paper-/Plugin-/ChannelHandler-Stufen und
- direkte Paketsendungen, die nicht aus dem Vanilla-`/title`-Befehl stammen.

Für `clear_titles` wird kein Bukkit-/Paper-Event-Fallback ergänzt. Es gibt
kein Event, das den vollständigen Wire-Vertrag mit Empfänger, Reihenfolge,
Clear-vs.-Reset-Flag und vorherigen Packet-Rewrites gleichwertig abbildet.
Ein Command-Event wäre insbesondere ungeeignet:

- Plugins können das native Paket direkt senden,
- andere APIs können Titel löschen, ohne `/title` auszuführen,
- ein Befehl kann abgebrochen oder umgeschrieben werden,
- Zielselektoren können mehrere Empfänger ergeben und
- Eventzeitpunkt und finaler Wirezeitpunkt sind nicht identisch.

Events bleiben damit nur eine theoretische Notfallquelle. Der vorhandene
finale Paket-Capture ist für dieses Paket vollständig und wird nicht durch
einen ungenaueren Fallback ergänzt.

## 7. Adapter-Dokumentation

Die vollständigen neutralen Inventare in

- `docs/adapters/paper-26.2.md` und
- `docs/adapters/paper-26.3.md`

bleiben vollständig. Ausschließlich die Beschreibung von PLAY ID 14 wird
fachlich präzisiert, sinngemäß:

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 14 | `minecraft:clear_titles` | Clears the recorded connection's title and subtitle, optionally resetting its animation timings; never apply it to the neutral observer | DROP_LOCAL | no |

Die Dokumentation enthält weiterhin Beschreibung/Relevanz sowie Playback-
und Checkpoint-Policy, aber keine Aussage zu Implementierungsstatus,
Klassenname oder Tests. Alle übrigen Paketzeilen bleiben unverändert.

## 8. Teststrategie

### 8.1 Definitionsvertrag je Version

Je Version entsteht
`packet/definition/ui/Id014ClearTitlesPacketDefinitionTest.java`. Die Tests
prüfen:

1. ID `14`/`0x0E` und Typname
   `play.clientbound.minecraft:clear_titles`,
2. `STATEFUL`, Standard-Capture aktiv, Replay-Policy-Pfad aktiv,
   Checkpoint aus und Scope `VIEWER_LOCAL`,
3. `DROP_LOCAL` für die kanonischen Payloads `0x00` und `0x01`,
4. `DROP_LOCAL` für alle 256 möglichen einzelnen Wire-Bytes,
5. `DROP_LOCAL` auch für leere, abgeschnittene und überlange Payloads,
6. null beziehungsweise falsche Descriptoren werden vor der lokalen Policy
   weiterhin durch die allgemeinen Vertragsprüfungen abgelehnt,
7. direkter `rewrite(...)` verwirft jeden decodierten Objekttyp und
8. Decode und Encode des übergebenen `PacketCodec` werden in keinem
   viewer-lokalen Pfad aufgerufen.

Der Test aller 256 Bytes bedeutet nicht, dass 256 semantische Varianten
existieren. Er beweist, dass die Sicherheitsgrenze unabhängig vom
Payloadinhalt und damit auch bei nichtkanonischen oder fehlerhaften
Aufnahmen vor dem Decode greift.

### 8.2 Nativer Codec je Version

Mit dem echten, über `GameProtocols.CLIENTBOUND_TEMPLATE` gebundenen
Paper-Codec werden geprüft:

- native Paket-ID `0x0E`,
- Roundtrip von `new ClientboundClearTitlesPacket(false)`,
- Roundtrip von `new ClientboundClearTitlesPacket(true)`,
- exakt ein kanonisches Bodybyte `0x00` beziehungsweise `0x01`,
- `shouldResetTimes()` nach dem Decode,
- vollständiger Verbrauch eines gültigen Ein-Byte-Bodys,
- Decoder-Kanonisierung aller 255 nichtnullen Bytewerte zu `true`,
- Reencode solcher Werte als `0x01`,
- Ablehnung eines leeren beziehungsweise abgeschnittenen Bodys und
- dokumentiertes Verhalten bei einem zusätzlichen Byte, ohne dem
  feldbezogenen Codec eine nicht vorhandene Framing-Prüfung zuzuschreiben.

Die Testmatrix ist in 26.2 und 26.3 fachlich identisch. Sie verwendet
jeweils die versionslokale native Klasse und den tatsächlichen gebundenen
Codec statt eines adaptereigenen Handparsers.

### 8.3 Katalog und Playback

Beide `PacketCoverageTest`-Klassen werden bis ID `0x0E` erweitert:

- exakter Katalogeintrag an Position 14,
- Disposition `STATEFUL`,
- Capture und Replay-Policy-Pfad aktiv,
- Checkpoint aus,
- Scope `VIEWER_LOCAL` und
- alle noch nicht implementierten Pakete bleiben `UNSUPPORTED`.

`Paper26_*PlaybackBridgeTest` prüft:

- Auswahl im Modus `LIVE`, damit die Definition ihre explizite
  `DROP_LOCAL`-Entscheidung treffen kann,
- keine Auswahl im Modus `RECONSTRUCTION` und
- die korrekte Descriptor-Policy.

Die Definitionstests beweisen anschließend, dass die Live-Auswahl nicht zu
einer Sendung führt. Ein produktiver ChannelHandler-Umbau ist dafür nicht
erforderlich.

### 8.4 Gate-Abdeckung

Die vorhandenen `ReplayViewerOutboundGateTest`-Tests decken bereits die
allgemeine Regel ab, dass viewer-lokale Live-Pakete nicht unterdrückt werden.
Falls die bestehende Parametermenge `clear_titles` noch nicht ausdrücklich
enthält, wird sie minimal darum ergänzt, um die konkrete UI-Operation gegen
Regressionen abzusichern. Produktivcode wird nur geändert, falls dieser Test
entgegen der Analyse eine reale Lücke zeigt.

### 8.5 Auszuführende Prüfungen nach Genehmigung

```powershell
.\gradlew.bat :replay-adapter:paper-common:test `
  :replay-adapter:paper-26_2:test `
  :replay-adapter:paper-26_3:test --console=plain
```

Zusätzlich:

- `git diff --check`,
- normalisierter Symmetrievergleich beider Definitionen und Tests,
- Katalogprüfung für alle expliziten IDs `0x00..0x0E`,
- Prüfung des geänderten Dateiumfangs,
- Prüfung, dass `.idea/` unberührt und unversioniert bleibt,
- erneute Branchprüfung vor jedem Commit und
- englischer semantischer Feature-Commit nach erfolgreicher Validierung.

## 9. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/ui/Id014ClearTitlesPacketDefinition.java`
- `packet/definition/ui/UiPacketDefinitions.java`
- `packet/Paper26_2PacketCatalog.java` beziehungsweise
  `packet/Paper26_3PacketCatalog.java`

Tests je Version:

- `packet/definition/ui/Id014ClearTitlesPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`
- gegebenenfalls ausschließlich die vorhandene Parametermenge in
  `playback/ReplayViewerOutboundGateTest.java`

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser temporäre Implementierungsplan

## 10. Risiken und Gegenmaßnahmen

### Verwechslung von `clear` und `reset`

Risiko: `resetTimes` wird als allgemeines Clear-Flag interpretiert oder die
beiden Zustände werden vertauscht.

Gegenmaßnahme: Native `TitleCommand`-Aufrufstellen dokumentieren und beide
Konstruktorwerte mit Wire- und Accessor-Assertions testen.

### Replay verändert Zuschauer-UI

Risiko: Ein aufgezeichneter Clear-/Reset-Befehl entfernt einen Live-Titel des
neutralen Zuschauers oder verändert dessen spätere Fade-Zeiten.

Gegenmaßnahme: `VIEWER_LOCAL`, Drop vor Decode/Encode, Live-vs.-
Reconstruction-Test und keine Aufnahme des Pakets in Checkpoints.

### Live-UI wird fälschlich unterdrückt

Risiko: Der Outbound-Gate behandelt die neue explizite Definition wie
Replay-Szenenzustand und blockiert echte Serverpakete.

Gegenmaßnahme: Vorhandene Viewer-Local-Ausnahme im Gate beibehalten und die
konkrete Kennung im Gate-Test absichern.

### Scheinbar unvollständige Definition

Risiko: Ein unnötiger Read-/Rebuild-Pfad wird ergänzt, um das einzelne Feld
sichtbar zu modellieren, obwohl er die Sicherheitsgrenze aufweicht oder nie
erreichbar ist.

Gegenmaßnahme: Payload-unabhängige Policy in `PacketDefinition`, vollständige
Feld- und Wire-Abdeckung im nativen Codec-Test und erklärende Javadoc.

### Falsche Domain

Risiko: Ablage in `player` wegen Viewer-Lokalität vermischt Policy und
Protokollverantwortung.

Gegenmaßnahme: Neue `ui`-Domain genau nach dem verbindlichen Layoutdokument;
nur minimale Katalogintegration.

### Event-basierte Doppel- oder Fehlaufzeichnung

Risiko: Ein Command-/Title-Event ergänzt einen zweiten Frame oder verliert
direkte Paketsendungen und finale Rewrites.

Gegenmaßnahme: Ausschließlich vorhandenen finalen PacketEvents-/Wire-Capture
verwenden; keinen Event-Fallback hinzufügen.

### Versionsdrift

Risiko: 26.2 und 26.3 erhalten trotz identischem nativen Vertrag
unterschiedliche Policy oder Tests.

Gegenmaßnahme: strukturell symmetrische versionslokale Dateien,
versionsgebundene native Codec-Tests und normalisierter Vergleich.

## 11. Auswirkungen und bewusste Grenzen

### Auswirkungen

- PLAY ID `0x0E` wird in beiden Adaptern explizit klassifiziert.
- Beide Protokolloperationen bleiben vollständig in Recordings erhalten.
- Aufgezeichnete Titeloperationen verändern nie den neutralen Zuschauer.
- Echte Live-Titeloperationen des Zuschauers bleiben durch den Gate-Pfad
  erlaubt.
- Die neue UI-Domain steht entsprechend dem festgelegten Layout für spätere
  UI-Paketdefinitionen bereit.
- Der Registry-Fingerprint ändert sich durch die zusätzliche explizite
  Definition; ohne Altaufnahme-Migration ist dies beabsichtigt.

### Bewusste Grenzen

- Der Replaypfad sendet weder Clear noch Reset an den neutralen Zuschauer.
- Es wird kein Titelzustand aus Live-Welt, Bukkit-API oder Events
  rekonstruiert.
- Es wird kein UI-Zustand in Checkpoints aufgenommen.
- Die Definition führt keinen eigenen Boolean-Decoder ein.
- Überzählige Framebytes werden nicht in der Paketdefinition validiert.
- Andere Titelpakete bleiben in dieser Aufgabe unimplementiert.

## 12. Quellen und Verifikation

Die Planung basiert auf:

- den lokalen Paper-Dev-Bundles `26.2.build.135-stable` und
  `26.3.build.244-stable`,
- der darin enthaltenen `ClientboundClearTitlesPacket`-Klasse und ihrem
  `StreamCodec`,
- den lokalen Aufrufstellen in `TitleCommand`,
- `FriendlyByteBuf` und Nettys Boolean-Wire-Vertrag,
- der bestehenden `PacketDefinition`-Policygrenze,
- `NeutralObserverPolicy`,
- den versionslokalen Packet-Catalogs, Playback-Bridges und
  `ReplayViewerOutboundGate`-Implementierungen sowie
- der Minecraft-Protokolldokumentation für `clear_titles`.

Externe Referenzen:

- <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Clear_Titles>
- <https://minecraft.wiki/w/Commands/title>

Die Online-Dokumentation bestätigt ID, Feld und allgemeine Wirkung. Für die
konkrete Java-Klasse, Codec-Kanonisierung, Clear-/Reset-Konstruktorwerte und
die Versionsgleichheit sind die angehefteten lokalen Paper-Artefakte sowie
die geplanten nativen Tests maßgeblich.

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
