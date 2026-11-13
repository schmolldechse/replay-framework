# Implementierungsplan: `minecraft:damage_event` (ID 25 / `0x19`)

## Ziel und Umfang

Das clientbound PLAY-Paket `minecraft:damage_event` wird für die beiden bereits
unterstützten Adapter Paper 26.2 und Paper 26.3 als eigenständige,
versionslokale Paketdefinition implementiert. Die Umsetzung folgt der
bestehenden Read/Rewrite/Rebuild-Architektur und deckt die vollständige native
Paketstruktur einschließlich aller optionalen Entity-Referenzen und der
optionalen Quellposition ab.

Der Umfang bleibt auf diese Paketdefinition, ihre Katalogintegration, die
unmittelbar betroffenen Abdeckungs- und Playback-Policy-Tests sowie die beiden
neutralen Adapterinventare beschränkt. Capture-Handler, PacketEvents-Fallback,
Recording-Format, Checkpoint-Encoder, öffentliche APIs und Abhängigkeiten
werden nicht verändert.

## Analysierter Ausgangszustand

- Arbeitsbranch: `codex/refactor-packet-capture-rewrite`.
- Die bisherigen Paketdefinitionen für IDs 0 bis 24 sind versionslokal unter
  `packet/definition/<domain>` organisiert und werden über einen
  domänenspezifischen Aggregator in den jeweiligen Paketkatalog aufgenommen.
- Identitätsabhängige Pakete verwenden
  `RebuildingPacketDefinition`, ein unveränderliches semantisches Value-Modell
  und `Paper26_2ReplayPacketRewriter` beziehungsweise
  `Paper26_3ReplayPacketRewriter`.
- `damage_event` ist in beiden Adapterinventaren bereits als ID 25, geordneter
  Szeneneffekt, `REWRITE` und nicht checkpoint-relevant aufgeführt, besitzt aber
  noch keine explizite Paketdefinition.
- Die vorhandenen, nicht zum Auftrag gehörenden unversionierten IDE-Dateien
  unter `.idea/` bleiben unangetastet.

## Verbindliche Protokollgrundlage

Als maßgebliche Quelle dienen die von den beiden Projektmodulen tatsächlich
verwendeten Paper-Dev-Bundles:

- Paper `26.2.build.135-stable` (Protokoll 776)
- Paper `26.3.build.244-stable` (Protokoll 777)

In beiden gepinnten Minecraft-Quellständen definiert
`net.minecraft.network.protocol.game.ClientboundDamageEventPacket` dieselbe
Struktur und denselben Codec. Die Felder werden in folgender Reihenfolge
übertragen:

1. `entityId`: VarInt; ID der Entity, welche den Damage-Effekt erhält.
2. `sourceType`: registry-gebundener `Holder<DamageType>`; auf dem Wire die
   VarInt-ID des Eintrags aus der `minecraft:damage_type`-Registry.
3. `sourceCauseId`: VarInt mit Offsetkodierung `entityId + 1`; der Wire-Wert
   `0` wird zu `-1` und bedeutet „keine verursachende Entity“.
4. `sourceDirectId`: dieselbe Offsetkodierung; bezeichnet die unmittelbare
   Quelle, beispielsweise ein Projektil, während `sourceCauseId` dessen
   Schützen bezeichnen kann.
5. `sourcePosition`: Boolean; bei `true` folgen X, Y und Z als drei
   IEEE-754-Double-Werte.

Die native Methode `getSource(Level)` zeigt eine wichtige Priorität: Ist
`sourcePosition` vorhanden, wird der `DamageSource` ausschließlich aus
Damage-Type und Position rekonstruiert. Andernfalls werden Cause und Direct aus
der Entity-Tabelle des Client-Levels aufgelöst. Das Paket transportiert weder
Schadenshöhe noch neue Lebenspunkte; beim Ziel löst es den geordneten
clientseitigen Damage-/Hurt-Effekt einschließlich typabhängigem Sound und
Animationszustand aus.

Zwischen 26.2 und 26.3 besteht für dieses Paket kein relevanter Wire- oder
Semantikunterschied. Die Implementierungen bleiben dennoch versionslokal, da
sie gegen unterschiedliche native Paper-Artefakte kompiliert werden.

## Technische Entscheidung

### Ablage und Benennung

Je Version wird folgende Klasse ergänzt:

- `packet/definition/effect/Id025DamageEventPacketDefinition.java`

`damage_event` gehört in die Domäne `effect`: Das Paket beschreibt keinen
persistenten Entity-Zustand und keine Entity-Erzeugung, sondern einen zeitlich
geordneten, unmittelbar auszulösenden Szeneneffekt. Es wird in den bestehenden
`EffectPacketDefinitions`-Aggregator aufgenommen.

### Klassifizierung

Die Definition erhält:

- Disposition: `EPHEMERAL`
- Capture: standardmäßig aktiviert
- Replay: aktiviert
- Checkpoint: deaktiviert
- Playback-Scope: `REWRITE_IDENTITIES`

Die Einstufung als `EPHEMERAL` bildet ab, dass Animation, Sound und temporärer
Hurt-Zustand nur an der aufgezeichneten Zeitposition sinnvoll sind und nicht in
einen späteren Checkpoint übernommen werden dürfen. `REWRITE_IDENTITIES` ist
erforderlich, weil Ziel, Cause und Direct Laufzeit-Entity-IDs enthalten können.

### Semantisches Value-Modell

Die Definition liest alle fünf nativen Felder in ein unveränderliches
versionslokales `Value`:

- `int entityId`
- `Holder<DamageType> sourceType`
- `int sourceCauseId`
- `int sourceDirectId`
- `Optional<Vec3> sourcePosition`

`sourceType`, das Optional und ein enthaltener Vektor dürfen nicht `null` sein.
Vorhandene Positionskoordinaten werden auf endliche Double-Werte begrenzt.
Damit werden alle regulären Minecraft-Koordinaten einschließlich negativer
Werte, Subnormalwerte, `Double.MIN_VALUE`, `Double.MAX_VALUE` und vorzeichenbehafteter
Null exakt erhalten, während `NaN` und positive oder negative Unendlichkeit als
inkompatible Paketdaten abgewiesen werden. Diese Validierung entspricht der
bereits bei positionsführenden Paketdefinitionen angewandten Schutzgrenze.

Andere negative Entity-IDs werden nicht pauschal verworfen: Die native
VarInt-/Offsetkodierung kann den gesamten `int`-Bereich darstellen. Nur `-1`
hat bei Cause und Direct die definierte Abwesenheitssemantik.

### Rewrite-Regeln

- `entityId` wird immer über den sessionlokalen Entity-ID-Mapper übersetzt.
- `sourceCauseId` und `sourceDirectId` werden jeweils unabhängig übersetzt,
  sofern ihr Wert nicht `-1` ist.
- Der Sentinel `-1` bleibt exakt erhalten und darf niemals als echte Identität
  in die Replay-Map gelangen.
- Gleiche aufgezeichnete IDs ergeben durch den bestehenden Mapper in allen drei
  Rollen dieselbe Replay-ID; verschiedene IDs bleiben voneinander getrennt.
- Auch bei vorhandener `sourcePosition` werden nichtleere Entity-Referenzen
  isoliert. Der aktuelle Client ignoriert sie in diesem Zweig, aber ein
  aufgezeichnetes Fremd- oder Observer-Identity-Leak soll weder im neu
  aufgebauten Paket verbleiben noch von künftiger Auswertungslogik sichtbar
  werden.
- `sourceType` und `sourcePosition` werden semantisch unverändert übernommen.
  Insbesondere wird der registry-gebundene Holder nicht in einen Namen oder
  eine globale numerische ID umgewandelt; der native Connection-Codec löst ihn
  beim Decodieren und Encodieren gegen den Registry-Kontext der Verbindung auf.

Anschließend wird ein neuer `ClientboundDamageEventPacket` aus dem
umgeschriebenen Value aufgebaut. Das Originalobjekt wird nicht mutiert.

## Geplante Änderungen

### Paper 26.2

1. `Id025DamageEventPacketDefinition` unter `packet/definition/effect`
   ergänzen.
2. Die Definition in `EffectPacketDefinitions` registrieren.
3. Einen dedizierten
   `Id025DamageEventPacketDefinitionTest` unter demselben Test-Package
   ergänzen.
4. `PacketCoverageTest` um ID 25 und ihre vollständige Policy erweitern.
5. Die Playback-Gate-Abdeckung so ergänzen, dass live eintreffende
   Replay-Szeneneffekte weiterhin von der isolierten Wiedergabe unterdrückt
   werden.
6. Ausschließlich die Zeile zu ID 25 in `docs/adapters/paper-26.2.md`
   fachlich präzisieren; das Dokument bleibt ein vollständiges, neutrales
   Paketinventar ohne Implementierungs- oder Teststatus.

### Paper 26.3

Dieselben sechs Schritte werden symmetrisch im 26.3-Modul und in
`docs/adapters/paper-26.3.md` ausgeführt.

## Testabdeckung je Version

Jede Version erhält genau für dieses Paket eine eigene Testklasse. Sie deckt
mindestens folgende Bereiche ab:

### Definition und Integration

- ID `25` / `0x19`, Identifier und nativer Pakettyp.
- Disposition, Capture-, Replay-, Checkpoint- und Playback-Scope-Policy.
- Einmalige Registrierung im korrekten Domain-Aggregator und Gesamtkatalog.
- Policy-Auswertung vor Decode/Encode sowie Ablehnung eines falschen decodierten
  Pakettyps.

### Semantisches Rewrite

- Ziel-ID wird immer umgeschrieben.
- Cause und Direct werden unabhängig umgeschrieben.
- Alle vier Kombinationen aus vorhandener/fehlender Cause- und Direct-ID.
- Gleiche IDs in mehreren Rollen verwenden dieselbe stabile Zuordnung;
  unterschiedliche IDs verwenden unterschiedliche Zuordnungen.
- `-1` bleibt in beiden optionalen Rollen unangetastet.
- Nicht-sentinelartige negative Werte, `0`, positive Werte sowie relevante
  `int`-Grenzen werden als darstellbare IDs behandelt.
- Damage-Type-Holder bleibt erhalten.
- Fehlende und vorhandene Quellposition bleiben erhalten; vorhandene Position
  wird zusammen mit eventuell vorhandenen Entity-Referenzen korrekt behandelt.
- Das Originalpaket bleibt unverändert und das Ergebnis ist ein neu aufgebautes
  natives Paket.

### Wire-Codec

- Native Paket-ID `25` und exakte Feldreihenfolge.
- VarInt-Grenzen für Ziel-ID sowie Offsetgrenzen von Cause und Direct,
  einschließlich des `-1`/Wire-`0`-Sentinels und der `int`-Überlaufgrenzen der
  reversiblen `id + 1`-Kodierung.
- Registry-IDs für alle im Test-Registry-Kontext verfügbaren Damage-Types,
  damit keine einzelne Damage-Art hartcodiert wird.
- Beide Optional-Zustände der Quellposition.
- Endliche Double-Grenzwerte, negative Werte und vorzeichenbehaftete Null mit
  bitgenauer Prüfung.
- Native Decode-/Encode-Roundtrips und vollständiger Verbrauch des Paketbodys.
- Ablehnung beziehungsweise dokumentiertes natives Verhalten bei leeren,
  abgeschnittenen und überlangen VarInts, unbekannten Damage-Type-Registry-IDs,
  fehlendem Optional-Flag sowie unvollständigen X-/Y-/Z-Werten.
- Definitionsebene weist nicht-endliche Positionswerte kontrolliert als
  inkompatibel ab, selbst wenn der niedrige native Double-Codec sie technisch
  lesen kann.

### Playback-Vertrag

- Vollständiger `handlePlayback`-Pfad mit Decode, Rewrite und Rebuild.
- Validierter Encode-Pfad mit korrekter Paket-ID.
- Der Replay-Viewer-Gate behandelt das Paket als isolierten Szeneneffekt und
  nicht als viewer-lokalen Live-Zustand.

## Qualitätssicherung nach Freigabe

Vor der Implementierung wird der lokale Hauptbranch read-only mit dem
Remote-Hauptbranch verglichen, ohne den vorgeschriebenen Arbeitsbranch zu
verlassen oder lokale Änderungen zu überschreiben. Danach werden ausgeführt:

1. Fokussierte Tests für `Id025DamageEventPacketDefinitionTest` in 26.2.
2. Fokussierte Tests für `Id025DamageEventPacketDefinitionTest` in 26.3.
3. Vollständige Tests von `paper-common`, `paper-26_2` und `paper-26_3`.
4. `git diff --check` und Kontrolle, dass ausschließlich aufgabenrelevante
   Dateien geändert beziehungsweise gestaged sind.
5. Symmetrievergleich der beiden Paketdefinitionen und ihrer Policies.

## Versionsverwaltung

Die Arbeit bleibt ausschließlich auf
`codex/refactor-packet-capture-rewrite`. Nach Freigabe werden die
Implementierungsänderungen in einem logisch abgegrenzten englischen
semantischen Commit festgehalten, beispielsweise:

`feat: implement damage event packet definitions`

Der temporäre Plan wird nach erfolgreicher Implementierung wie bei den
vorherigen Paketen in einem separaten Dokumentations-Commit wieder entfernt.
Es erfolgt kein Push ohne gesonderten Auftrag.

## Auswirkungen und bewusst ausgeschlossene Änderungen

- Keine Migration alter Recordings und keine Kompatibilitätsschicht für das
  entfernte Altsystem.
- Keine Änderung der autoritativen Paper-ChannelHandler-Erfassung.
- Keine semantische Erfassung über Bukkit-/Paper-Events; Events bleiben nur die
  bereits vorgesehene letzte Fallback-Ebene und werden für dieses Paket nicht
  benötigt.
- Keine Änderung an PacketEvents außer der unveränderten bestehenden Rolle am
  finalen Write-/Fallback-Pfad.
- Kein Checkpoint-Eintrag für temporäre Damage-Effekte.
- Keine Änderung an anderen Paketdefinitionen oder deren Dokumentationszeilen.
