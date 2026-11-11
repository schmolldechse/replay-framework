# Implementierungsplan: `animate` (`0x02`)

Status: **Zur Freigabe**
Zielversionen: **Paper 26.2 build 135-stable** und **Paper 26.3 build 244-stable**

## 1. Ziel und Abgrenzung

Das PLAY-Clientbound-Paket `minecraft:animate` mit der dezimalen ID `2`
beziehungsweise `0x02` soll für beide unterstützten Paper-Versionen als
vollständige, explizite Paketdefinition umgesetzt werden. Die Definitionen
sollen das native Paket vollständig lesen, die enthaltene Laufzeit-Entity-ID
in den isolierten Wiedergaberaum übersetzen, sämtliche Action-Werte
verlustfrei erhalten und wieder ein natives Paket erzeugen.

Die Änderung umfasst ausschließlich:

- die beiden versionslokalen `animate`-Definitionen,
- deren Registrierung in der Domäne `effect`,
- die notwendigen Katalog- und Coverage-Anpassungen,
- fokussierte Tests für Protokollformat, Klassifikation, Rewrite und Rebuild.

Nicht Bestandteil dieser Aufgabe sind:

- `swing_animation` aus 26.3 (`0x7B`),
- andere Entity-Effektpakete wie `hurt_animation` oder `entity_event`,
- neue Paper-Events oder PacketEvents-Fallbacks,
- Migration oder Kompatibilität alter Recordings,
- allgemeine Refactorings der Paket- oder Capture-Architektur.

## 2. Analysierter Ist-Zustand

Die bestehende Paketarchitektur besitzt pro Paper-Version einen expliziten
Katalog. Implementierte PLAY-Pakete liefern ihre Klassifikation aus der
Paketdefinition; alle noch nicht implementierten PLAY-Pakete fallen auf
`UNSUPPORTED` zurück. Wiedergabefähige Pakete werden im
`Paper26_2ReplayPacketRewriter` beziehungsweise
`Paper26_3ReplayPacketRewriter` über diesen Katalog decodiert, umgeschrieben,
neu aufgebaut und anschließend über den versionslokalen Codec encodiert.

Bereits vorhanden sind:

- `Id000BundleDelimiterPacketDefinition` in `definition.connection`,
- `Id001AddEntityPacketDefinition` in `definition.entity`,
- die sitzungsstabile `ReplayIdentityMap` und `mapEntityId(int)`,
- `RebuildingPacketDefinition` für den Ablauf Read → Rewrite → Rebuild,
- versionslokale Coverage- und Rewriter-Integrationstests.

Die Layout-Konvention in `docs/packet-definition-layout.md` ordnet
Animationen, Partikel und andere geordnete Szeneneffekte der Domäne `effect`
zu. `animate` gehört deshalb nicht in `entity`, obwohl es eine Entity-ID
enthält: Seine fachliche Wirkung ist ein einmaliger visueller Effekt und kein
dauerhafter Entity-Lebenszykluszustand.

Die Capture-Schicht verarbeitet das Paket bereits über den nativen
clientbound Channel-Pfad. In 26.2 verhindert
`Paper26_2AuthoritativeStateIndex` außerdem ausschließlich für die synthetisch
erzeugten Hand-Swings eine Doppelaufzeichnung im selben Server-Tick. 26.3
verwendet dafür das eigenständige Paket `swing_animation`; `animate` ist dort
nicht Teil dieser Suppression. Diese bestehende Trennung bleibt unverändert.

## 3. Verifizierte Protokollsemantik

### 3.1 Gemeinsames Wire-Format

Die exakten, durch Paperweight bereitgestellten Mojang-mapped Quellen beider
gepinnten Builds registrieren `animate` unmittelbar nach `add_entity` im
PLAY-Clientbound-Protokoll. Da der Bundle-Delimiter die ID `0` belegt, besitzt
`animate` in beiden Versionen die ID `2` (`0x02`).

Das Payload-Format ist in beiden Versionen identisch:

| Reihenfolge | Feld | Wire-Typ | Bedeutung |
|---:|---|---|---|
| 1 | Entity ID | VarInt | Laufzeit-ID der Entity, auf die die Animation angewendet wird |
| 2 | Action | Unsigned Byte | Versionsabhängige Animationskennung im Bereich `0..255` |

`ClientboundAnimatePacket` liest die Entity-ID mit `readVarInt()` und die
Action mit `readUnsignedByte()`. Beim Schreiben werden `writeVarInt()` und
`writeByte()` verwendet. Die native Klasse stellt nur einen öffentlichen
Konstruktor mit einem vollständigen `Entity`-Objekt bereit, nicht mit einer
beliebigen bereits umgeschriebenen Entity-ID.

### 3.2 Action-Semantik in Paper 26.2

| Action | Mojang-Konstante | Clientwirkung |
|---:|---|---|
| `0` | `SWING_MAIN_HAND` | Hauptarm schwingen |
| `2` | `WAKE_UP` | Spieler verlässt das Bett / beendet Schlafzustand |
| `3` | `SWING_OFF_HAND` | Nebenarm schwingen |
| `4` | `CRITICAL_HIT` | Kritische Trefferpartikel an der Entity |
| `5` | `MAGIC_CRITICAL_HIT` | Magische kritische Trefferpartikel an der Entity |

Action `1` sowie `6..255` haben in diesem Client keine definierte Wirkung.

### 3.3 Action-Semantik in Paper 26.3

| Action | Mojang-Konstante | Clientwirkung |
|---:|---|---|
| `0` | `WAKE_UP` | Spieler verlässt das Bett / beendet Schlafzustand |
| `1` | `CRITICAL_HIT` | Kritische Trefferpartikel an der Entity |
| `2` | `MAGIC_CRITICAL_HIT` | Magische kritische Trefferpartikel an der Entity |

Actions `3..255` haben in diesem Client keine definierte Wirkung. Die beiden
Hand-Swings gehören ab 26.3 **nicht mehr** zu `animate`; sie werden über das
separate `minecraft:swing_animation` übertragen. Eine gemeinsame
versionsübergreifende Action-Enum wäre deshalb fachlich falsch: Dieselbe Zahl
hat zwischen 26.2 und 26.3 eine andere Bedeutung.

### 3.4 Verhalten bei unbekannten Actions und ungültigen Zielen

Das Wire-Feld erlaubt jeden Wert von `0` bis `255`. Der Vanilla-Client führt
nur für die oben dokumentierten Werte eine Aktion aus und ignoriert andere
Werte. Die Definition wird unbekannte Werte daher nicht umdeuten, normalisieren
oder verwerfen, sondern bytegenau erhalten. So deckt die Implementierung das
vollständige Wire-Format ab und verändert auch von Plugins gesendete Werte
nicht unbemerkt.

Wenn die umgeschriebene Entity-ID clientseitig nicht existiert, bleibt das
Paket ohne sichtbare Wirkung. `WAKE_UP` ist semantisch für eine Spieler-Entity
bestimmt; das Paket enthält jedoch weder Entity-Typ noch UUID. Diese
Zieltyp-Bedingung kann die Definition nicht eigenständig validieren, ohne
zusätzlichen Weltzustand einzuführen. Sie erhält deshalb die Serversemantik
und schreibt ausschließlich die ID um.

### 3.5 Quellen

- Minecraft Wiki, PLAY clientbound `animate`, aktuelles 26.3-Format und
  Action-Tabelle: <https://minecraft.wiki/w/Java_Edition_protocol/Packets#Entity_Animation>
- VoidMC, 26.2-kompatibles PLAY-Clientbound-Protokoll mit den Action-Werten
  `0`, `2`, `3`, `4`, `5`:
  <https://voidminecraft.github.io/VoidMC/reference/protocol-specs/v26.1.2/play-clientbound.html>
- Decompiled Minecraft 26.3 `ClientboundAnimatePacket` mit Mojang-Mappings:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/network/protocol/game/clientboundanimatepacket/>
- Decompiled Minecraft 26.3 `ClientPacketListener#handleAnimate`; bestätigt
  Entity-Auflösung, Wake-up, beide Partikeleffekte und das Ignorieren anderer
  Actions:
  <https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/multiplayer/clientpacketlistener/>
- Lokale, exakt gepinnte Paperweight/Mache-Quellen der Builds
  `26.2.build.135-stable` und `26.3.build.244-stable`, insbesondere
  `GameProtocols`, `GamePacketTypes`, `ClientboundAnimatePacket` und in 26.3
  zusätzlich `ClientboundSwingAnimationPacket`.

## 4. Technische Umsetzung

### 4.1 Versionslokale Definitionen

In beiden Modulen wird folgende Klasse angelegt:

```text
packet/definition/effect/Id002AnimatePacketDefinition.java
```

Vollqualifizierte Pakete:

```text
dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect
dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect
```

Beide Klassen sind `final`, package-private und erweitern:

```java
RebuildingPacketDefinition<
        Paper26_XReplayPacketRewriter,
        ClientboundAnimatePacket,
        Id002AnimatePacketDefinition.Value>
```

Konstanten:

```java
static final int PACKET_ID = 0x02;
static final String IDENTIFIER = "minecraft:animate";
```

Die interne immutable `Value`-Repräsentation enthält exakt:

```java
record Value(int entityId, int action)
```

Der Record prüft `action` auf den durch das Wire-Format zulässigen Bereich
`0..255`. Es wird bewusst keine gemeinsame Action-Enum eingeführt, weil die
Mojang-Werte versionsabhängig sind und unbekannte Byte-Werte erhalten bleiben
müssen.

### 4.2 Klassifikation

Die Definition verwendet:

```java
PacketClassification.ephemeral(
        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES)
```

Daraus folgen:

- `PacketDisposition.EPHEMERAL`,
- Capture standardmäßig aktiv,
- Wiedergabe aktiv,
- nicht checkpoint-relevant,
- Entity-ID-Rewrite zwingend erforderlich.

Die Klassifikation als `EPHEMERAL` gilt auch für `WAKE_UP`: Das Paket ist eine
geordnete einmalige Clienttransition und kein vollständiger, eigenständig
rekonstruierbarer Entity-Zustand. Ein Checkpoint rekonstruiert den jeweils
gültigen Entity-/Metadatenzustand, nicht eine historische Animation.

### 4.3 Read, Rewrite und Rebuild

`read(...)` übernimmt `packet.getId()` und `packet.getAction()` vollständig.

`rewrite(...)` erzeugt immer ein neues `Value`:

- `entityId` wird mit `context.mapEntityId(...)` in den sitzungslokalen
  Replay-ID-Raum übersetzt,
- `action` bleibt unverändert,
- das Ergebnis lautet `ValueRewriteResult.send(...)`.

Das Rewrite gilt für jede bekannte und unbekannte Action gleichermaßen. Eine
vor dem zugehörigen `add_entity` beobachtete Animation erhält bereits dieselbe
deterministische Replay-ID, die ein späteres Spawn-Paket verwendet; existiert
die Entity beim Client noch nicht, ignoriert der Client die Animation.

`rebuild(...)` kann keinen normalen Konstruktor mit der neuen ID nutzen. Es
schreibt daher die beiden bereits validierten Felder in einen temporären
`FriendlyByteBuf` und decodiert daraus über
`ClientboundAnimatePacket.STREAM_CODEC` eine neue native Instanz. Der Buffer
wird in `finally` freigegeben. Dadurch wird exakt der native, versionsgebundene
Codec verwendet, ohne Reflection, künstliche NMS-Entity oder neue
Abhängigkeit.

### 4.4 Domain-Aggregatoren und Kataloge

Pro Version wird angelegt:

```text
packet/definition/effect/EffectPacketDefinitions.java
```

Der öffentliche Aggregator hält eine immutable Liste mit
`new Id002AnimatePacketDefinition()` und enthält selbst kein Paketverhalten.

`Paper26_2PacketCatalog` und `Paper26_3PacketCatalog` werden um die Effect-Liste
ergänzt. Die Definitionsreihenfolge bleibt nach Packet-ID sortiert:

1. `bundle_delimiter` (`0`),
2. `add_entity` (`1`),
3. `animate` (`2`).

Die Zusammenführung wird nur so weit angepasst, wie für den dritten
Domain-Aggregator notwendig; keine allgemeine Katalogabstraktion wird
eingeführt.

### 4.5 Capture- und Fallback-Auswirkungen

Mit der Katalogregistrierung klassifiziert der live entdeckte Registry-Eintrag
`animate` explizit und erlaubt Capture sowie Replay. Der bestehende native
Paper-ChannelHandler bleibt die Quelle für ausgehende clientbound Pakete. Für
Player-Swings bleibt PacketEvents ausschließlich die bereits vorhandene
serverbound Beobachtungsquelle, aus der der Server-Thread in 26.2 `animate`
und in 26.3 `swing_animation` synthetisiert.

Es wird kein Bukkit/Paper-Event-Fallback ergänzt. Events könnten insbesondere
kritische Trefferpartikel, magische kritische Treffer und alle pluginseitig
direkt gesendeten Pakete nicht vollständig oder in korrekter Reihenfolge
rekonstruieren. Der vorhandene Channel-Pfad deckt diese Fälle ohne
semantischen Informationsverlust ab.

## 5. Tests

### 5.1 Definitionstests je Version

Angelegt werden:

```text
src/test/java/.../packet/definition/effect/Id002AnimatePacketDefinitionTest.java
```

Die Tests prüfen je Version:

1. ID `2`/`0x02`, Typname und vollständige `EPHEMERAL`-/
   `REWRITE_IDENTITIES`-Klassifikation.
2. Rewrite der Entity-ID in die sitzungslokale ID und unveränderte Action.
3. Alle semantisch definierten Action-Werte der jeweiligen Version.
4. Die versionsabhängige Neubelegung ausdrücklich:
   - 26.2: Swing `0`/`3`, Wake-up `2`, Critical `4`, Magic-Critical `5`.
   - 26.3: Wake-up `0`, Critical `1`, Magic-Critical `2`; `3` ist kein
     Offhand-Swing mehr.
5. Verlustfreien Read/Rewrite/Rebuild für den vollständigen Unsigned-Byte-
   Bereich `0..255`, einschließlich unbekannter Actions und `255`.
6. Ablehnung intern konstruierter Actions außerhalb `0..255`, damit
   `writeByte` keinen Wert stillschweigend abschneidet.
7. Den nativen PLAY-Codec: Paket-ID `2`, VarInt-Entity-ID, unsigned Action und
   vollständigen Verbrauch des Payloads.
8. Validiertes Re-Encode mit PLAY/clientbound/ID `2`.

Die Testpakete werden ebenfalls über `STREAM_CODEC` aus ihren beiden
Wire-Feldern erzeugt. Damit sind keine laufende Welt und keine künstliche
Entity nötig.

### 5.2 Katalog-, Registry- und Rewriter-Tests

Die Coverage-Tests beider Versionen werden erweitert:

- erwartete explizite Definitionen enthalten `animate` als dritten Eintrag,
- `animate` wird als `EPHEMERAL` klassifiziert,
- Capture und Replay sind aktiv,
- Checkpoint-Relevanz ist aus,
- Playback-Scope ist `REWRITE_IDENTITIES`,
- alle übrigen noch nicht implementierten PLAY-Pakete bleiben
  `UNSUPPORTED`.

Die versionslokalen Rewriter-Tests erhalten je einen fokussierten
`animate`-Durchlauf durch Katalog, Decode, Rewrite, Rebuild und Encode. Dabei
wird geprüft, dass derselbe aufgezeichnete Entity-Identifier wie bei
`add_entity` auf dieselbe Replay-ID abgebildet wird.

Die bestehenden Capture-Tests bleiben unverändert und dienen beim vollständigen
Modultest als Regressionstest für die unterschiedliche 26.2-/26.3-Swing-
Behandlung.

## 6. Betroffene Dateien

Voraussichtlich neu:

- `replay-adapter/paper-26_2/src/main/java/.../packet/definition/effect/EffectPacketDefinitions.java`
- `replay-adapter/paper-26_2/src/main/java/.../packet/definition/effect/Id002AnimatePacketDefinition.java`
- `replay-adapter/paper-26_2/src/test/java/.../packet/definition/effect/Id002AnimatePacketDefinitionTest.java`
- `replay-adapter/paper-26_3/src/main/java/.../packet/definition/effect/EffectPacketDefinitions.java`
- `replay-adapter/paper-26_3/src/main/java/.../packet/definition/effect/Id002AnimatePacketDefinition.java`
- `replay-adapter/paper-26_3/src/test/java/.../packet/definition/effect/Id002AnimatePacketDefinitionTest.java`

Voraussichtlich geändert:

- `Paper26_2PacketCatalog.java`
- `Paper26_3PacketCatalog.java`
- beide `PacketCoverageTest.java`
- beide `Paper26_XReplayPacketRewriterTest.java`

Weitere Produktivkomponenten, öffentliche APIs, Datenbankformate und
Konfigurationen werden nicht geändert.

## 7. Versionsverwaltung und Ausführungsreihenfolge

Nach Freigabe:

1. Remote-Stand abrufen und lokalen `master` mit `origin/master` vergleichen,
   ohne lokale Änderungen zu überschreiben.
2. Den unversionierten, benutzereigenen Ordner `.idea/` weiterhin unangetastet
   lassen.
3. Einen separaten Branch `codex/implement-animate-packet` erstellen. Da die
   benötigte Paketarchitektur sowie ID `0` und `1` derzeit noch auf dem
   vorausgehenden Feature-Branch liegen, wird der neue Branch auf dessen
   freigegebenem HEAD gestapelt; es wird nichts ungefragt nach `master`
   gemergt.
4. Definitionen und Aggregatoren beider Versionen implementieren.
5. Kataloge, Coverage-Tests und Rewriter-Integration aktualisieren.
6. Fokussierte Definitionstests und danach vollständige Tests der drei
   betroffenen Adaptermodule ausführen.
7. `git diff --check` sowie den finalen Dateiumfang prüfen.
8. Ausschließlich aufgabenbezogene Dateien committen, voraussichtlich als
   `feat: implement animate packet definitions`.

## 8. Qualitätsprüfung und Abnahmekriterien

Geplante Prüfkommandos:

```powershell
.\gradlew.bat :replay-adapter:paper-common:test :replay-adapter:paper-26_2:test :replay-adapter:paper-26_3:test --console=plain
git diff --check
git status --short
```

Die Aufgabe gilt als erfüllt, wenn:

- beide live entdeckten `animate`-Einträge exakt ID `2` besitzen und explizit
  klassifiziert werden,
- jede Entity-ID deterministisch umgeschrieben wird,
- jede Action von `0` bis `255` unverändert erhalten bleibt,
- die abweichenden bekannten Action-Semantiken beider Versionen durch Tests
  dokumentiert sind,
- Rebuild und Re-Encode ein gültiges natives Paket mit vollständigem Payload
  erzeugen,
- `animate` aufgezeichnet und wiedergegeben, aber nie in Checkpoints
  aufgenommen wird,
- keine Event-Fallbacks oder fachfremden Änderungen hinzukommen,
- alle verfügbaren Adaptertests erfolgreich sind und `.idea/` unverändert
  bleibt.
