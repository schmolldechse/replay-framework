# Korrekturplan: typspezifische `add_entity.data`-Semantik

## Status und Ziel

Dieser Plan wartet auf Freigabe. Vor der ausdrücklichen Genehmigung werden keine
Produktiv- oder Testklassen geändert.

Ziel ist, die Behandlung des `data`-Felds von `minecraft:add_entity` für die
gebundenen Paper-Versionen 26.2 und 26.3 vollständig, nachvollziehbar und durch
Tests abgesichert zu dokumentieren. Das Feld ist kein einheitlicher
Entity-Verweis, sondern eine durch `type` diskriminierte Ganzzahl. Nur die
Varianten `Fishing Hook` und `Projectile` enthalten eine Entity-ID, die in den
isolierten Identity Space einer Wiedergabesitzung übersetzt werden darf.

## Analysierter Projektstand

- Beide Versionsdefinitionen lesen und bauen `data` bereits verlustfrei als
  `int` neu auf.
- Die aktuelle Implementierung schreibt einen von null verschiedenen Wert nur
  für eine explizite Menge von Projektiltypen als Entity-ID um. Dieses Verhalten
  ist grundsätzlich korrekt.
- Der Kommentar nennt bislang nur Projectile, Falling Block und Item Frame. Die
  ebenfalls relevanten Varianten Painting, Fishing Hook und Warden sowie der
  Fishing-Hook-Sonderfall sind nicht vollständig erklärt.
- Die Tests decken alle explizit gebundenen Projektiltypen sowie einzelne
  Beispiele für Falling Block und Item Frame ab. Painting, Glow Item Frame,
  Warden und die Selbstreferenz eines Fishing Hooks sind nicht ausdrücklich
  abgesichert.

## Protokoll- und Implementierungsquellen

### Minecraft-Protokolldokumentation

- Die
  [Paketdokumentation](https://minecraft.wiki/w/Java_Edition_protocol/Packets#Add_Entity)
  beschreibt `Data` als `VarInt`, dessen Bedeutung vom Feld `Type` abhängt.
- Die
  [Object-data-Dokumentation](https://minecraft.wiki/w/Java_Edition_protocol/Object_data)
  unterscheidet Item Frame, Painting, Falling Block, Fishing Hook, Projectile
  und Warden.
- Eine zugängliche
  [Spiegelung der Object-data-Tabelle](https://wiki.ronlab.site/content/minecraftwiki_en_all_maxi_2025-11/Java_Edition_protocol/Object_data)
  dokumentiert zusätzlich die konkreten Richtungs- und Posewerte.

### Gebundene Mojang-/Paper-Quellen

Die durch Paperweight bereitgestellten, Mojang-gemappten Quellen der exakt
gebundenen Dev Bundles wurden für beide Module geprüft:

- Paper `26.2.build.135-stable`
- Paper `26.3.build.244-stable`

In beiden Quellständen existieren dieselben Erzeugungsregeln:

| Erzeugende Klasse | Wert von `data` |
| --- | --- |
| `Entity` | Standardwert `0` |
| `LeashFenceKnotEntity` | `0`; nur die Blockposition wird speziell gesetzt |
| `ItemFrame` | `Direction#get3DDataValue()` |
| `Painting` | `Direction#get3DDataValue()` |
| `FallingBlockEntity` | `Block.getId(blockState)` |
| `Projectile` | Owner-Entity-ID oder `0`, wenn kein Owner existiert |
| `FishingHook` | Owner-Entity-ID; ohne Owner die eigene Entity-ID |
| `Warden` | `1` für `Pose.EMERGING`, sonst `0` |

Die öffentlich einsehbaren 26.3-Quellen bestätigen dies zusätzlich für
[Painting](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/world/entity/decoration/painting/painting/),
[Falling Block](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/world/entity/item/fallingblockentity/)
und
[Warden](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/world/entity/monster/warden/warden/).
Die
[Geyser-Übersetzung](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/translator/protocol/java/entity/JavaAddEntityTranslator.java)
bestätigt unabhängig die typabhängige Interpretation von Direction,
FallingBlockData, ProjectileData und WardenData.

## Verbindliche Semantik für das Replay

| Entity-Kategorie | Bedeutung | Replay-Behandlung |
| --- | --- | --- |
| Item Frame und Glow Item Frame | 3D-Richtung `0..5` | unverändert erhalten |
| Painting | horizontale 3D-Richtung `2..5` | unverändert erhalten |
| Falling Block | Block-State-ID | unverändert erhalten |
| Fishing Hook | Owner-ID, ersatzweise eigene Entity-ID | als Entity-ID umschreiben |
| Projectile | Owner-ID oder `0` für „kein Owner“ | nur Werte ungleich `0` umschreiben |
| Warden | `1` für Emerging-Pose, sonst ohne Wirkung | unverändert erhalten |
| alle übrigen Typen | regulär `0`, gegebenenfalls typspezifisch | bytegetreu unverändert erhalten |

Die Unterscheidung muss ausschließlich über den Entity-Typ erfolgen. Ein
numerischer Wert allein ist nicht aussagekräftig: `1` kann beispielsweise eine
Entity-ID, eine Richtung, eine Block-State-ID oder die Emerging-Pose eines
Wardens bezeichnen.

## Geplante Änderungen

### 1. Semantik in beiden Definitionen explizit machen

Betroffene Dateien:

- `paper-26_2/.../entity/Id001AddEntityPacketDefinition.java`
- `paper-26_3/.../entity/Id001AddEntityPacketDefinition.java`

Die vorhandene Menge wird von dem allgemeinen Namen
`ENTITY_REFERENCE_DATA_TYPES` auf einen präziseren Namen wie
`OWNER_ENTITY_ID_DATA_TYPES` umbenannt. Die Umschreibung wird in eine kleine
Methode `rewriteData(...)` gekapselt, damit die Entscheidung getrennt vom
Neuaufbau der restlichen Paketfelder lesbar bleibt.

Der zugehörige Kommentar dokumentiert unmittelbar am Code:

1. dass `data` eine über `type` diskriminierte Union ist;
2. die nicht umzuschreibenden Bedeutungen Direction, Block-State-ID und
   Warden-Pose;
3. die umzuschreibenden Owner-IDs von Projectile und Fishing Hook;
4. den Fishing-Hook-Fallback auf die eigene Entity-ID;
5. weshalb `0` nicht über die Identity Map laufen darf.

Die Rewrite-Regel bleibt:

```text
if type carries an owner entity ID and data != 0:
    data = mapEntityId(data)
else:
    data = data
```

Damit bleibt die bereits korrekte Laufzeitsemantik erhalten; die Änderung macht
den Vertrag explizit und reduziert das Risiko einer späteren pauschalen
Umschreibung.

### 2. Vollständige Kategorie- und Invariantentests ergänzen

Betroffene Dateien:

- `paper-26_2/.../Id001AddEntityPacketDefinitionTest.java`
- `paper-26_3/.../Id001AddEntityPacketDefinitionTest.java`

Zusätzlich zu den vorhandenen Projektiltests werden folgende Fälle geprüft:

- Item Frame und Glow Item Frame behalten Richtungswerte unverändert;
- Painting behält Richtungswerte unverändert;
- Falling Block behält seine Block-State-ID unverändert;
- Warden behält `0`, `1` und einen vom Client ignorierten anderen Wert
  unverändert;
- ein generischer Entity-Typ behält einen unbekannten Wert unverändert;
- ein Projectile mit `data == 0` bleibt ohne Owner;
- ein Fishing Hook mit `data == entityId` behält nach dem Rewrite die
  Selbstreferenz: `rewritten.data == rewritten.entityId`;
- ein Fishing Hook mit fremder Owner-ID verweist auf deren stabil gemappte ID.

Die bestehende Prüfung aller explizit gebundenen Projektiltypen bleibt erhalten.

### 3. Dauerhafte Dokumentation korrigieren

Betroffene Datei:

- `docs/plans/add-entity-packet.md`

Die bisherige verkürzte Tabelle wird um Painting, Warden und den
Fishing-Hook-Sonderfall ergänzt. Die Quellen und die Begründung, weshalb nur
Owner-Referenzen umgeschrieben werden, werden dort dauerhaft festgehalten.

## Nicht vorgesehene Änderungen

- keine Änderung am Wire-Format oder an Paket-ID `0x01`;
- keine neue Paketabstraktion oder zusätzliche Abhängigkeit;
- keine Änderung an Capture, Paper-ChannelHandler oder PacketEvents;
- keine Validierung oder Normalisierung von Richtungs-, Block-State- oder
  Warden-Werten, da das Replay den aufgezeichneten Wire-Wert erhalten soll;
- keine Änderungen an anderen Paketdefinitionen.

## Qualitätssicherung

Nach Freigabe und Umsetzung werden ausgeführt:

```text
:replay-adapter:paper-common:test
:replay-adapter:paper-26_2:test
:replay-adapter:paper-26_3:test
```

Zusätzlich werden `git diff --check`, die betroffenen Dateilisten und der
Arbeitsbaum geprüft. Das benutzerseitige, unversionierte `.idea/` bleibt
unberührt.
