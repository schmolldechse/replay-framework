# Implementierungsplan: `block_entity_data` (`0x06`)

Status: **Umgesetzt**

Zielbranch: `codex/refactor-packet-capture-rewrite`

Zielversionen:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

## 1. Ziel und Umfang

Das PLAY-Clientbound-Paket `minecraft:block_entity_data` mit der dezimalen ID
`6` beziehungsweise `0x06` wird in beiden Paper-Adaptern als explizite,
versionslokale Paketdefinition umgesetzt.

Die Implementierung soll:

1. das vollständige native Wire-Format beider angehefteten Paper-Versionen
   erhalten,
2. Position, Block-Entity-Typ und beliebiges gültiges Compound-NBT ohne
   semantischen Verlust verarbeiten,
3. Live-Wiedergabe und Checkpoint-Rekonstruktion unterstützen,
4. räumliche Capture-Filterung über die exakte Blockposition ermöglichen,
5. keine nicht belegbaren Identity-Rewrites innerhalb typabhängiger NBT-Daten
   vornehmen,
6. die vorhandene PacketEvents-/Paper-Channel-Architektur unverändert nutzen,
7. die Adapter-Dokumentationen von Implementierungsständen sowie
   paket- und testspezifischen Beschreibungen bereinigen.

Alte Recording-Formate oder das entfernte Paketsystem werden nicht migriert.

## 2. Analysierter Ist-Zustand

### 2.1 Paketarchitektur

Die expliziten PLAY-Paketdefinitionen liegen versionslokal unter
`packet.definition.<domain>`. Der Versionskatalog vereinigt die Domain-Listen
und sortiert die Definitionen nach ihrer numerischen Paket-ID.

`block_entity_data` gehört in die Domain `world`, weil es dauerhaften,
positionsgebundenen Weltzustand aktualisiert. Die vorgesehene Benennung gemäß
`docs/packet-definition-layout.md` lautet in beiden Versionen:

```text
Id006BlockEntityDataPacketDefinition
```

Die bestehenden `WorldPacketDefinitions` enthalten aktuell nur die explizite
Definition für ID `0x04`. ID `0x06` wird dort ergänzt; an der Struktur der
Kataloge ist keine weitere Änderung notwendig.

### 2.2 Capture-Pfad

Der vorhandene Paper-Capture-Pfad beobachtet clientbound PLAY-Pakete über
PacketEvents auf `MONITOR`-Ebene und übernimmt nachgelagert die finalen
Wire-Bytes. Damit bleiben Änderungen anderer Channel-Handler Bestandteil der
Aufzeichnung.

`PaperConnectionAccessor.packetPosition(...)` erkennt
`ClientboundBlockEntityDataPacket` in beiden Versionen bereits und gibt dessen
exakte `BlockPos` als API-`BlockPosition` zurück. Für räumlich begrenzte
Recordings ist daher keine zusätzliche Produktionslogik erforderlich; dieses
vorhandene Verhalten wird durch einen paketbezogenen Test abgesichert.

Paper-Events sind für dieses Paket kein gleichwertiger Fallback:

- es gibt kein einzelnes Event, das alle Block-Entity-Typen und alle
  clientbound Aktualisierungsgründe abdeckt,
- Events liefern nicht zwingend das tatsächlich an einen konkreten Empfänger
  gesendete Update-Tag,
- von Plugins oder anderen Channel-Handlern erzeugte beziehungsweise
  veränderte Pakete würden verloren gehen,
- die genaue Empfängerreihenfolge und die final serialisierten Bytes wären
  nicht rekonstruierbar.

Es wird deshalb weder ein neuer Event-Listener noch ein zusätzlicher
Channel-Handler eingeführt.

### 2.3 Checkpoint-Pfad

Die Checkpoint-Infrastruktur ist bereits vorbereitet:

- `Paper26_2SnapshotProvider` und `Paper26_3SnapshotProvider` erzeugen für
  geladene Block Entities native `ClientboundBlockEntityDataPacket`-Werte,
- beide Checkpoint-Encoder ordnen den stabilen Typnamen bereits der Familie
  `BLOCK_ENTITY` zu,
- diese Familie wird nach Chunk-/Lichtdaten und vor globalem sowie Entity-State
  ausgegeben.

Sobald der Registry-Deskriptor checkpoint-relevant wird, kann die vorhandene
Erzeugung ohne neue Snapshot- oder Ordering-Logik verwendet werden.

### 2.4 Playback und Reset

Der Client aktualisiert nur dann Zustand, wenn an der Paketposition bereits
eine Block Entity des mitgelieferten Typs existiert. Bei fehlender Position
oder einem Typkonflikt wird das Paket ignoriert. Bei einem Treffer lädt der
Client das Compound-NBT in die vorhandene Block Entity; spezielle Oberflächen
wie die Command-Block-Ansicht können anschließend aktualisiert werden.

Das Paket besitzt keine eigene Entfernen-Operation. Replay-Chunks werden vom
vorhandenen View-Resetter über `forget_level_chunk` entfernt, wodurch auch
deren Block Entities und geladener NBT-Zustand verschwinden. Eine separate
Reset-Nachricht für dieses Paket existiert nicht und wird nicht simuliert.

## 3. Verifiziertes Protokoll

Die angehefteten Paper-Server-JARs beider Zielversionen enthalten denselben
öffentlichen Vertrag:

```java
ClientboundBlockEntityDataPacket(
        BlockPos pos,
        BlockEntityType<?> type,
        CompoundTag tag)
```

Auslesbare Felder:

```java
BlockPos getPos()
BlockEntityType<?> getType()
CompoundTag getTag()
```

Wire-Reihenfolge:

| Reihenfolge | Feld | Codec | Bedeutung |
|---:|---|---|---|
| 1 | Position | `BlockPos.STREAM_CODEC` | gepackter 64-Bit-Blockort |
| 2 | Typ | Registry-Codec für `Registries.BLOCK_ENTITY_TYPE` | VarInt-basierte Registry-ID |
| 3 | Daten | `ByteBufCodecs.TRUSTED_COMPOUND_TAG` | typabhängiges Compound-NBT |

Der Paper-Patch macht den nativen Drei-Argument-Konstruktor öffentlich. Damit
kann die Paketdefinition einen neuen nativen Wert aufbauen, ohne Reflection,
synthetische Block Entities oder einen Codec-Roundtrip einzusetzen.

Die statische `create(...)`-Fabrik liest das Update-Tag einer realen Block
Entity und führt Paper-seitig `sanitizeSentNbt(...)` aus. Auf der
Playback-Seite darf diese Fabrik nicht erneut verwendet werden: Das Recording
enthält bereits das tatsächlich versendete, sanitierte Tag. Ein erneutes
Ableiten aus Live-Weltzustand wäre semantisch falsch und könnte Felder ändern
oder verlieren.

## 4. Klassifikation und Sicherheitsentscheidung

Die Definition erhält folgende Klassifikation:

| Eigenschaft | Wert |
|---|---|
| Disposition | `STATEFUL` |
| Standard-Capture | `true` |
| Replay | `true` |
| Checkpoint | `true` |
| Playback-Scope | `SAFE` |

Begründung:

- Die Nachricht verändert den dauerhaften, sichtbaren Zustand einer bereits
  geladenen Block Entity.
- Der Zustand muss bei einem Seek aus einem Checkpoint wiederhergestellt
  werden können.
- Keines der drei Wire-Felder ist eine Replay-Entity-ID oder eine
  Session-UUID.
- NBT-Inhalte sind vom Block-Entity-Typ abhängig. UUIDs, Profile,
  Blockpositionen oder numerische Werte innerhalb des Tags sind Inhaltsdaten
  der Block Entity und dürfen nicht anhand ihrer Form als Playback-Identitäten
  interpretiert werden.
- Eine generische rekursive Umschreibung würde beispielsweise Profile,
  Texturen, Zielpositionen, Item-/Komponentendaten oder modifizierte
  Serverdaten beschädigen.

Das Tag wird deshalb vollständig opak behandelt und nicht in eine Liste
vermeintlich bekannter Block-Entity-Schemata zerlegt.

## 5. Geplante Implementierung

### 5.1 Versionslokale Definitionen

Neu entstehen:

```text
replay-adapter/paper-26_2/src/main/java/.../packet/definition/world/
└── Id006BlockEntityDataPacketDefinition.java

replay-adapter/paper-26_3/src/main/java/.../packet/definition/world/
└── Id006BlockEntityDataPacketDefinition.java
```

Beide Klassen erweitern `RebuildingPacketDefinition` und verwenden ein
versionslokales `Value` mit:

```text
position : BlockPos
type     : BlockEntityType<?>
tag      : CompoundTag
```

Verarbeitung:

1. `read(...)` übernimmt Position und Typ und erstellt eine defensive tiefe
   Kopie des Compound-Tags.
2. `rewrite(...)` gibt denselben semantischen Wert ohne Identity-Änderung als
   `SEND` zurück.
3. `rebuild(...)` erstellt über den öffentlichen Paper-Konstruktor ein neues
   `ClientboundBlockEntityDataPacket`.
4. Position, Typ und Tag werden explizit gegen `null` validiert.
5. Das neue Paket erhält keine Verbindung zu einer Live-Block-Entity und liest
   keinen aktuellen Weltzustand nach.

Die defensive NBT-Kopie verhindert, dass eine nachträgliche Mutation des
decodierten nativen Pakets oder eines zwischengespeicherten Value-Objekts den
neu aufgebauten Playback-Wert verändert. Alle gültigen NBT-Typen und
unbekannten typabhängigen Schlüssel bleiben dabei erhalten.

### 5.2 Domain-Aggregatoren und Registry

`WorldPacketDefinitions` beider Versionen wird um die neue Definition
erweitert. Die vorhandene numerische Sortierung der Gesamtkataloge stellt die
Reihenfolge `0x00` bis `0x06` her.

`PacketCoverageTest` wird so erweitert, dass ID `0x06` explizit als
`STATEFUL`, replaybar, checkpoint-relevant und `SAFE` geprüft wird. Alle noch
nicht implementierten PLAY-Pakete bleiben weiterhin `UNSUPPORTED`.

### 5.3 Capture, Playback und Checkpoint

Produktionsänderungen außerhalb der Definition und des Aggregators sind nach
aktuellem Stand nicht erforderlich:

- Capture kennt die Blockposition bereits.
- Die Registry schaltet das Paket über die Klassifikation frei.
- Der generische Playback-Pfad decodiert, validiert, baut neu auf und encodiert
  über die Definition.
- Live-Wiedergabe und `RECONSTRUCTION` akzeptieren den checkpoint-relevanten
  Zustand.
- Snapshot-Erzeugung und Checkpoint-Familie sind bereits vorhanden.
- Der bestehende Chunk-Reset entfernt den clientseitigen Block-Entity-Zustand.

Falls die Implementierungstests eine Abweichung der angehefteten Paper-Builds
von diesen verifizierten Verträgen zeigen, wird die Abweichung vor einer
zusätzlichen Produktionsänderung dokumentiert und neu bewertet.

## 6. Neutrale Adapter-Dokumentationen

Die Dateien

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`

werden zu neutralen Adapterbeschreibungen überarbeitet. Entfernt werden:

- Aussagen darüber, welche Paket-IDs bereits implementiert sind,
- Namen und Verhaltensbeschreibungen einzelner Pakettests,
- Fortschrittsformulierungen wie „explicit“, „remaining“ oder Zielpolitiken
  für spätere Paketimplementierungen.

Erhalten beziehungsweise neutral formuliert werden nur:

- angeheftete Paper-/Java-/PacketEvents-Versionen,
- allgemeine Adapter- und Modulgrenzen,
- allgemeiner Capture-, Checkpoint- und Playback-Datenfluss,
- allgemeine Isolation-, Kompatibilitäts- und Reset-Prinzipien,
- allgemeine Qualitätssicherung ohne Bezug auf konkrete Pakete,
- versionsweite Einschränkungen, sofern sie nicht die Implementierung eines
  einzelnen Pakets erklären,
- vollständige, neutrale Inventare aller clientbound Pakete je
  Protokollphase mit numerischer ID, Identifier, Paketbeschreibung und
  Relevanz sowie Playback- und Checkpoint-Policy.

Die Policy beschreibt den fachlichen Zielvertrag unabhängig vom aktuellen
Implementierungsfortschritt. Die Inventare dokumentieren weder Implementierungs-
noch Teststatus und sind damit keine laufende Paket-Checkliste. Die Benennung
und Ablage von Definitionen bleibt ausschließlich in
`docs/packet-definition-layout.md` dokumentiert.

## 7. Teststrategie

### 7.1 Definitionstests je Version

Je Version entsteht
`Id006BlockEntityDataPacketDefinitionTest` mit mindestens folgenden Fällen:

1. ID `6`/`0x06`, stabiler Typname und vollständige Klassifikation.
2. Read/Rewrite/Rebuild für Position, Block-Entity-Typ und Compound-NBT.
3. Defensive tiefe NBT-Kopie; Mutationen der Quelle dürfen den aufgebauten
   Paketwert nicht verändern.
4. Leeres Compound-Tag.
5. Verschachteltes Compound-Tag mit allen Wire-relevanten NBT-Familien:
   numerische Skalare, String, Byte-/Int-/Long-Arrays, Listen und weitere
   Compound-Werte.
6. Sämtliche in der angehefteten Built-in-Registry vorhandenen
   `BlockEntityType`-Werte.
7. Grenzwerte der gepackten Blockposition.
8. Falscher decodierter Java-Typ und ungültige `null`-Felder.
9. Validierter Encode muss wieder ID `0x06` liefern.
10. Native Protokoll-Roundtrips müssen ID, Feldreihenfolge und vollständigen
    Byteverbrauch bestätigen.
11. Abgeschnittene Position, fehlende beziehungsweise ungültige Registry-ID
    und abgeschnittenes oder ungültiges NBT müssen vom nativen Decoder
    abgelehnt werden.

### 7.2 Integrationsnahe Tests je Version

- `PacketCoverageTest` prüft den expliziten Registry-Vertrag bis ID `0x06`.
- Der Capture-Test prüft, dass die exakte Paketposition im `CaptureContext`
  landet.
- Der Playback-Bridge-Test prüft die Zulassung sowohl im normalen Live-Modus
  als auch während der Rekonstruktion.
- Bestehende Checkpoint-/Snapshot-Tests stellen sicher, dass die bereits
  vorhandene `BLOCK_ENTITY`-Familie und deren Reihenfolge weiterhin gültig
  bleiben.

### 7.3 Auszuführende Prüfungen

```text
./gradlew :replay-adapter:paper-common:test \
          :replay-adapter:paper-26_2:test \
          :replay-adapter:paper-26_3:test
```

Zusätzlich:

- `git diff --check`
- Kontrolle aller geänderten Dateien gegen den Aufgabenumfang
- Prüfung, dass `.idea/` weder verändert noch versioniert wird
- Prüfung, dass ausschließlich
  `codex/refactor-packet-capture-rewrite` verwendet wurde

## 8. Voraussichtlich betroffene Dateien

Produktion je Version:

- `packet/definition/world/Id006BlockEntityDataPacketDefinition.java`
- `packet/definition/world/WorldPacketDefinitions.java`

Tests je Version:

- `packet/definition/world/Id006BlockEntityDataPacketDefinitionTest.java`
- `PacketCoverageTest.java`
- `capture/Paper26_2CaptureBridgeTest.java` beziehungsweise
  `capture/Paper26_3CaptureBridgeTest.java`
- `playback/Paper26_2PlaybackBridgeTest.java` beziehungsweise
  `playback/Paper26_3PlaybackBridgeTest.java`

Dokumentation:

- `docs/adapters/paper-26.2.md`
- `docs/adapters/paper-26.3.md`
- dieser Implementierungsplan

Nicht vorgesehen sind Änderungen am Core-Format, am PacketEvents-Listener, an
Paper-Events, an der Identity-Map, am View-Resetter oder an den
Checkpoint-Snapshot-Providern.

## 9. Risiken und Gegenmaßnahmen

### Typabhängiges NBT

Risiko: Eine feldweise Interpretation könnte unbekannte oder zukünftig
erweiterte Daten verlieren.

Gegenmaßnahme: Compound-NBT bleibt opak und wird vollständig tief kopiert.

### Vermeintliche Identitäten im NBT

Risiko: UUID- oder Integer-förmige Inhaltsdaten könnten irrtümlich als
Replay-Identitäten behandelt werden.

Gegenmaßnahme: Nur explizit dokumentierte Protokollreferenzen werden
umgeschrieben; dieses Paket besitzt keine solche Referenz auf Wire-Ebene.

### Registry-Kompatibilität

Risiko: Die Block-Entity-Typ-ID ist registry-gebunden.

Gegenmaßnahme: Es wird ausschließlich der native `RegistryFriendlyByteBuf`-
Codec der angehefteten Version verwendet. Die vorhandene Adapter-/Registry-
Kompatibilitätsprüfung bleibt die Aktivierungsgrenze.

### Reihenfolge

Risiko: Ein Block-Entity-Update vor dem zugehörigen Chunk oder Blockzustand
wird vom Client ignoriert.

Gegenmaßnahme: Live-Frames behalten ihre Capture-Reihenfolge; Checkpoints
geben Chunk-/Lichtdaten bereits vor der `BLOCK_ENTITY`-Familie aus.

## 10. Quellen

- Minecraft 26.3 decompiled packet source:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/network/protocol/game/clientboundblockentitydatapacket/>
- Minecraft 26.3 client handler:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>
- Mojang-mapped 1.21.11 class reference:
  <https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket.html>
- Paper API, block-state transmission context:
  <https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/org/bukkit/entity/Player.java>
- Lokal verifizierte Paper-Artefakte:
  `26.2.build.135-stable` und `26.3.build.244-stable`

Die Online-Quellen beschreiben Struktur und Clientverhalten. Maßgeblich für
die Implementierung bleiben die im Projekt angehefteten Paper-Artefakte und
deren native Protokolltests.
