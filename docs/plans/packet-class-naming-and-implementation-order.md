# Plan: Namensschema und Implementierungsreihenfolge aller PLAY-Pakete

Status: **Plan zur Freigabe – keine Paketimplementierung enthalten**
Stand: 11. November 2026

Dieser Plan konkretisiert den freigegebenen Architekturplan
`packet-capture-rewrite-refactor.md`. Er legt verbindlich fest,

1. wie jede konkrete Paketklasse benannt und abgelegt wird;
2. in welcher technischen und versionsbezogenen Reihenfolge alle Pakete
   implementiert werden;
3. wann eine Implementierung in den Versionskatalog aufgenommen werden darf;
4. welche Tests und Channel-Garantien vor der Freischaltung erfüllt sein
   müssen.

Die Umsetzung beginnt weiterhin erst nach ausdrücklicher Freigabe dieses Plans.

## 1. Umfang und Ausgangslage

Aktuell sind die Kataloge für Paper 26.2 und 26.3 absichtlich leer. Das
versionsneutrale Gerüst (`PacketDefinition`, `RebuildingPacketDefinition`,
`PacketDefinitionCatalog`, Klassifikation und Rewrite-Ergebnisse), die
Paper-Capture-Grenzen und der PacketEvents-Channel-Pfad bleiben vorhanden.

Der vollständige Zielumfang umfasst ausschließlich clientbound PLAY:

| Version | PLAY-Pakete | Konkrete Klassen |
|---|---:|---:|
| Paper 26.3 | 144 | 144 |
| Paper 26.2 | 141 | 141 |
| Gesamt | – | 285 |

`add_transient_block`, `post_effects` und `swing_animation` existieren in diesem
Inventar nur für 26.3. Die übrigen 141 Pakettypen werden in beiden Modulen
implementiert. STATUS-, LOGIN- und CONFIGURATION-Pakete erhalten keine
Einzelklassen; sie bleiben über die vorhandene Registry-Logik einheitlich
`CONTROL` und werden nie in eine bestehende PLAY-Verbindung injiziert.

Nicht Bestandteil dieses Schritts sind Migrationen alter Recordings, ein
Parallelbetrieb des alten Rewrite-Systems, Änderungen an der öffentlichen API
oder Änderungen am Replay-Dateiformat.

## 2. Verbindliches Namensschema

### 2.1 Paket und Modul

Konkrete Klassen bleiben in den bestehenden versionsgebundenen Playback-Paketen:

- `dev.voldechse.replayframework.adapter.paper.v26_3.playback`
- `dev.voldechse.replayframework.adapter.paper.v26_2.playback`

Dadurch können die Klassen package-private bleiben und direkt vom jeweiligen
Katalog instanziiert werden. Es entsteht keine neue öffentliche API und kein
zusätzlicher Discovery-Mechanismus.

### 2.2 Ableitung des Klassennamens

Der Klassenname wird mechanisch aus dem kanonischen Minecraft-Identifier
abgeleitet:

1. Präfix `play.clientbound.` entfernen.
2. Namespace `minecraft:` entfernen.
3. An `/`, `_` und `-` in Segmente zerlegen.
4. Jedes Segment in UpperCamelCase umwandeln.
5. Suffix `PacketDefinition` anhängen.

Beispiele:

| Identifier | Klassenname |
|---|---|
| `minecraft:add_entity` | `AddEntityPacketDefinition` |
| `minecraft:set_entity_data` | `SetEntityDataPacketDefinition` |
| `minecraft:player_info_update` | `PlayerInfoUpdatePacketDefinition` |
| `minecraft:debug/block_value` | `DebugBlockValuePacketDefinition` |
| `minecraft:swing_animation` | `SwingAnimationPacketDefinition` |

Die gleiche Klasse trägt in 26.2 und 26.3 denselben Namen; die Version wird
ausschließlich durch Modul und Java-Package ausgedrückt.

### 2.3 Verbotene Varianten

Für konkrete Paketklassen werden keine der folgenden Varianten verwendet:

- kein Versionspräfix wie `Paper26_3AddEntity...`;
- kein `Clientbound`-Präfix;
- kein `Handler`-, `Impl`-, `Adapter`- oder `Processor`-Suffix;
- kein Policy-Suffix wie `Blocked` oder `ViewerLocal`;
- keine Sammelklassen für mehrere Identifier;
- keine anonymen Definitionen direkt im Katalog.

Damit ist aus `<Name>PacketDefinition` immer eindeutig ableitbar, welchem
Protokoll-Identifier die Datei gehört.

### 2.4 Klassenaufbau

Jede konkrete Klasse ist `final` und package-private. Sie besitzt:

- eine Konstante `IDENTIFIER` mit dem Wert `minecraft:<name>`;
- einen parameterlosen package-private Konstruktor;
- genau eine geeignete abstrakte Basisklasse;
- bei echtem Payload-Rewrite ein verschachteltes immutable `record Value`;
- ausschließlich paketbezogene Logik.

Beispielhafte Form:

```java
final class AddEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundAddEntityPacket,
        AddEntityPacketDefinition.Value> {

    static final String IDENTIFIER = "minecraft:add_entity";

    AddEntityPacketDefinition() {
        super(IDENTIFIER, PacketClassification.stateful(REWRITE_IDENTITIES),
                ClientboundAddEntityPacket.class);
    }

    // read, rewrite, rebuild und optional record Value
}
```

Klassifikation und Identifier werden von der Definition selbst geliefert.
Der Katalog enthält nur die explizite, immutable Instanziierungsliste und keine
zweite Policy-Tabelle.

### 2.5 Testnamen

- fokussierter Test: `<PacketDefinition>Test`;
- gemeinsamer Inventartest: `Paper26_3PacketCatalogTest` beziehungsweise
  `Paper26PacketCatalogTest`;
- keine breite `NeutralObserverTest`-Sammelklasse;
- policy-only Definitionen werden zusätzlich über parameterisierte
  Katalog-Contract-Tests abgedeckt.

## 3. Erforderliche Basisklassen vor dem ersten Paket

Vor Arbeitsschritt 001 wird das gemeinsame Gerüst minimal ergänzt:

| Basis | Aufgabe |
|---|---|
| `AbstractBlockedPacketDefinition<C>` | `UNSAFE`/`CONTROL`; Abbruch garantiert vor Decode |
| `AbstractViewerLocalPacketDefinition<C>` | `VIEWER_LOCAL`; Drop garantiert vor Decode |
| `AbstractPassThroughPacketDefinition<C, P>` | native Klasse prüfen und dasselbe Objekt sicher weitergeben |
| `AbstractPayloadSensitivePacketDefinition<C, P>` | typisierte inhaltsabhängige SEND/DROP/FAIL-Entscheidung |
| `RebuildingPacketDefinition<C, P, V>` | bestehende Read/Rewrite/Rebuild-Schablone für Identitäten |

`SimplePacketDefinition` und `TypedPacketDefinition` dürfen nicht als Ersatz für
benannte konkrete Paketklassen im Versionskatalog verwendet werden. Nach
Einführung der abstrakten Basen wird geprüft, ob sie noch einen anderen
Verwendungszweck haben; andernfalls wird ihre Entfernung separat begründet.

Zusätzlich werden vorab folgende Contract-Tests angelegt:

1. Policy-Drop und Block erfolgen ohne Codec-Decode.
2. Pass-through prüft die erwartete native Klasse.
3. Rebuild encodiert wieder exakt Phase, Richtung und numerische Paket-ID des
   gebundenen Descriptors.
4. Der Katalog lehnt doppelte Identifier ab.
5. Der aus dem Identifier berechnete Soll-Klassenname entspricht dem
   tatsächlichen `getSimpleName()`.
6. Ein Katalogeintrag darf nur aktiviert werden, wenn sein nativer
   Registry-Descriptor existiert.

## 4. Arbeitsablauf pro Paket

Für jeden gemeinsamen Eintrag in Kapitel 6 gilt dieselbe Reihenfolge:

1. 26.3-Klasse und fokussierten Test implementieren.
2. Gegen die echte 26.3-NMS-Klasse und den Live-Descriptor prüfen.
3. Dieselbe semantische Implementierung nach 26.2 portieren.
4. Unterschiede der nativen Konstruktoren, Stream-Codecs und Felder explizit
   im 26.2-Test festhalten.
5. Beide Klassen in einem gemeinsamen Review vergleichen.
6. Erst nach erfolgreichem Test beide Katalogeinträge aktivieren.

Für die drei 26.3-only Einträge entfällt Schritt 3 bis 5.

Eine Klasse gilt nicht als fertig, wenn sie nur kompiliert. Sie muss Identifier,
Klassifikation, Playback-Scope, Checkpoint-Relevanz, native Klasse, Decode,
Rewrite/Drop und Re-Encode gemäß ihrer Kategorie nachweisen.

## 5. Aktivierungsstrategie

Die Implementierungsreihenfolge und die Freischaltung werden getrennt:

- Policy-only Pakete aus Phase 1 und 2 dürfen nach bestandenem Phasen-Gate
  sofort registriert werden, weil sie nie decodiert oder wiedergegeben werden.
- Szenenpakete werden familienweise aktiviert. Einzelne Bewegungs- oder
  Metadatenpakete werden nicht replaybar, solange Spawn, Remove, Reset und
  Identitätsabbildung derselben Familie fehlen.
- Bis zur Aktivierung greift weiterhin der aktuelle `UNSUPPORTED`-Fallback:
  Capture ist erlaubt, Playback und Checkpoint-Aufnahme bleiben gesperrt.
- Nach jeder Phase verifiziert der Live-Registry-Test, dass ausschließlich die
  freigegebenen Identifier explizit klassifiziert sind.
- Der finale Gate entfernt nicht zwingend den Fallback, verlangt aber für jede
  bekannte PLAY-Definition einen Katalogeintrag und lässt neue unbekannte
  Protokollpakete weiterhin sicher auf `UNSUPPORTED` fallen.

## 6. Exakte Implementierungsreihenfolge

Die Nummern 001 bis 144 sind verbindlich. Bei einem gemeinsamen Paket bedeutet
ein Schritt immer: **26.3 zuerst, anschließend 26.2**. Die Reihenfolge innerhalb
einer Phase darf nur geändert werden, wenn eine neu entdeckte NMS-Abhängigkeit
im Plan dokumentiert und vor der Implementierung freigegeben wird.

### 6.1 Fail-closed Steuerungs- und Diagnosepakete

| # | Paket | Konkrete Klasse | Versionsreihenfolge | Basis / Schwerpunkt | Checkpoint |
|---:|---|---|---|---|:---:|
| 001 | `minecraft:bundle_delimiter` | `BundleDelimiterPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 002 | `minecraft:block_changed_ack` | `BlockChangedAckPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 003 | `minecraft:chunk_batch_finished` | `ChunkBatchFinishedPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 004 | `minecraft:chunk_batch_start` | `ChunkBatchStartPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 005 | `minecraft:cookie_request` | `CookieRequestPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 006 | `minecraft:debug/block_value` | `DebugBlockValuePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 007 | `minecraft:debug/chunk_value` | `DebugChunkValuePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 008 | `minecraft:debug/entity_value` | `DebugEntityValuePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 009 | `minecraft:debug/event` | `DebugEventPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 010 | `minecraft:debug_sample` | `DebugSamplePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 011 | `minecraft:disconnect` | `DisconnectPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 012 | `minecraft:game_test_highlight_pos` | `GameTestHighlightPosPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 013 | `minecraft:keep_alive` | `KeepAlivePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 014 | `minecraft:login` | `LoginPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 015 | `minecraft:low_disk_space_warning` | `LowDiskSpaceWarningPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 016 | `minecraft:ping` | `PingPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 017 | `minecraft:pong_response` | `PongResponsePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 018 | `minecraft:resource_pack_pop` | `ResourcePackPopPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 019 | `minecraft:resource_pack_push` | `ResourcePackPushPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 020 | `minecraft:start_configuration` | `StartConfigurationPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 021 | `minecraft:store_cookie` | `StoreCookiePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 022 | `minecraft:tag_query` | `TagQueryPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 023 | `minecraft:test_instance_block_status` | `TestInstanceBlockStatusPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 024 | `minecraft:ticking_state` | `TickingStatePacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 025 | `minecraft:ticking_step` | `TickingStepPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 026 | `minecraft:transfer` | `TransferPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |
| 027 | `minecraft:custom_report_details` | `CustomReportDetailsPacketDefinition` | 26.3 → 26.2 | `AbstractBlockedPacketDefinition` – vor Decode blockieren | no |

### 6.2 Viewer-lokale Pakete

| # | Paket | Konkrete Klasse | Versionsreihenfolge | Basis / Schwerpunkt | Checkpoint |
|---:|---|---|---|---|:---:|
| 028 | `minecraft:award_stats` | `AwardStatsPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 029 | `minecraft:change_difficulty` | `ChangeDifficultyPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 030 | `minecraft:clear_titles` | `ClearTitlesPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 031 | `minecraft:command_suggestions` | `CommandSuggestionsPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 032 | `minecraft:commands` | `CommandsPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 033 | `minecraft:container_close` | `ContainerClosePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 034 | `minecraft:container_set_content` | `ContainerSetContentPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 035 | `minecraft:container_set_data` | `ContainerSetDataPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 036 | `minecraft:container_set_slot` | `ContainerSetSlotPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 037 | `minecraft:cooldown` | `CooldownPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 038 | `minecraft:custom_chat_completions` | `CustomChatCompletionsPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 039 | `minecraft:custom_payload` | `CustomPayloadPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen; Plugin-Payload nie an neutralen Observer | no |
| 040 | `minecraft:delete_chat` | `DeleteChatPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 041 | `minecraft:game_rule_values` | `GameRuleValuesPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 042 | `minecraft:mount_screen_open` | `MountScreenOpenPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 043 | `minecraft:merchant_offers` | `MerchantOffersPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 044 | `minecraft:move_vehicle` | `MoveVehiclePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 045 | `minecraft:open_book` | `OpenBookPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 046 | `minecraft:open_screen` | `OpenScreenPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 047 | `minecraft:open_sign_editor` | `OpenSignEditorPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 048 | `minecraft:place_ghost_recipe` | `PlaceGhostRecipePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 049 | `minecraft:player_abilities` | `PlayerAbilitiesPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 050 | `minecraft:player_combat_end` | `PlayerCombatEndPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 051 | `minecraft:player_combat_enter` | `PlayerCombatEnterPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 052 | `minecraft:player_combat_kill` | `PlayerCombatKillPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 053 | `minecraft:player_look_at` | `PlayerLookAtPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 054 | `minecraft:player_position` | `PlayerPositionPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 055 | `minecraft:player_rotation` | `PlayerRotationPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 056 | `minecraft:recipe_book_add` | `RecipeBookAddPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 057 | `minecraft:recipe_book_remove` | `RecipeBookRemovePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 058 | `minecraft:recipe_book_settings` | `RecipeBookSettingsPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 059 | `minecraft:reset_score` | `ResetScorePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 060 | `minecraft:post_effects` | `PostEffectsPacketDefinition` | 26.3 | `AbstractViewerLocalPacketDefinition` – 26.3-spezifischen Post-Processing-Zustand lokal halten | no |
| 061 | `minecraft:respawn` | `RespawnPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 062 | `minecraft:select_advancements_tab` | `SelectAdvancementsTabPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 063 | `minecraft:server_data` | `ServerDataPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 064 | `minecraft:set_action_bar_text` | `SetActionBarTextPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 065 | `minecraft:set_camera` | `SetCameraPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 066 | `minecraft:set_cursor_item` | `SetCursorItemPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 067 | `minecraft:set_display_objective` | `SetDisplayObjectivePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 068 | `minecraft:set_experience` | `SetExperiencePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 069 | `minecraft:set_health` | `SetHealthPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 070 | `minecraft:set_held_slot` | `SetHeldSlotPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 071 | `minecraft:set_objective` | `SetObjectivePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 072 | `minecraft:set_player_inventory` | `SetPlayerInventoryPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 073 | `minecraft:set_player_team` | `SetPlayerTeamPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 074 | `minecraft:set_score` | `SetScorePacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 075 | `minecraft:set_subtitle_text` | `SetSubtitleTextPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 076 | `minecraft:set_title_text` | `SetTitleTextPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 077 | `minecraft:set_titles_animation` | `SetTitlesAnimationPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 078 | `minecraft:tab_list` | `TabListPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 079 | `minecraft:update_advancements` | `UpdateAdvancementsPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 080 | `minecraft:update_recipes` | `UpdateRecipesPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 081 | `minecraft:server_links` | `ServerLinksPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 082 | `minecraft:waypoint` | `WaypointPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 083 | `minecraft:clear_dialog` | `ClearDialogPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 084 | `minecraft:show_dialog` | `ShowDialogPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – vor Decode droppen | no |
| 085 | `minecraft:disguised_chat` | `DisguisedChatPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – Capture-Policy beibehalten; neutralen Playback-Observer schützen | no |
| 086 | `minecraft:player_chat` | `PlayerChatPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – Capture-Policy beibehalten; Chat-Session nie übernehmen | no |
| 087 | `minecraft:system_chat` | `SystemChatPacketDefinition` | 26.3 → 26.2 | `AbstractViewerLocalPacketDefinition` – Chat/Action-Bar des Recordings nicht auf Observer anwenden | no |

### 6.3 Dauerhafter Weltzustand ohne Rewrite

| # | Paket | Konkrete Klasse | Versionsreihenfolge | Basis / Schwerpunkt | Checkpoint |
|---:|---|---|---|---|:---:|
| 088 | `minecraft:chunks_biomes` | `ChunksBiomesPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 089 | `minecraft:level_chunk_with_light` | `LevelChunkWithLightPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – Chunk und eingebettetes Licht als atomare Szene | yes |
| 090 | `minecraft:light_update` | `LightUpdatePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – Chunk-Abhängigkeit und Seek-Reihenfolge | yes |
| 091 | `minecraft:block_update` | `BlockUpdatePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 092 | `minecraft:block_entity_data` | `BlockEntityDataPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 093 | `minecraft:section_blocks_update` | `SectionBlocksUpdatePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 094 | `minecraft:forget_level_chunk` | `ForgetLevelChunkPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – Reset- und Seek-Lebenszyklus | yes |
| 095 | `minecraft:initialize_border` | `InitializeBorderPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 096 | `minecraft:set_border_center` | `SetBorderCenterPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 097 | `minecraft:set_border_lerp_size` | `SetBorderLerpSizePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 098 | `minecraft:set_border_size` | `SetBorderSizePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 099 | `minecraft:set_border_warning_delay` | `SetBorderWarningDelayPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 100 | `minecraft:set_border_warning_distance` | `SetBorderWarningDistancePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 101 | `minecraft:set_default_spawn_position` | `SetDefaultSpawnPositionPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 102 | `minecraft:set_time` | `SetTimePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 103 | `minecraft:set_chunk_cache_center` | `SetChunkCacheCenterPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 104 | `minecraft:set_chunk_cache_radius` | `SetChunkCacheRadiusPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 105 | `minecraft:set_simulation_distance` | `SetSimulationDistancePacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – typisiert validieren und unverändert senden | yes |
| 106 | `minecraft:update_tags` | `UpdateTagsPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – Registry-/Datapack-Kompatibilität validieren | yes |
| 107 | `minecraft:map_item_data` | `MapItemDataPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – bekannte Einschränkung: Map-ID vorerst nicht remappen | yes |

### 6.4 Geordnete Effekte und payloadabhängige SAFE-Pakete

| # | Paket | Konkrete Klasse | Versionsreihenfolge | Basis / Schwerpunkt | Checkpoint |
|---:|---|---|---|---|:---:|
| 108 | `minecraft:block_event` | `BlockEventPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – geordnet senden; nicht checkpoint-relevant | no |
| 109 | `minecraft:level_event` | `LevelEventPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – geordnet senden; nicht checkpoint-relevant | no |
| 110 | `minecraft:level_particles` | `LevelParticlesPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – geordnet senden; nicht checkpoint-relevant | no |
| 111 | `minecraft:sound` | `SoundPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – geordnet senden; nicht checkpoint-relevant | no |
| 112 | `minecraft:stop_sound` | `StopSoundPacketDefinition` | 26.3 → 26.2 | `AbstractPassThroughPacketDefinition` – geordnet senden; nicht checkpoint-relevant | no |
| 113 | `minecraft:add_transient_block` | `AddTransientBlockPacketDefinition` | 26.3 | `AbstractPassThroughPacketDefinition` – 26.3-only; Reset durch Chunk-Unload absichern | no |
| 114 | `minecraft:explode` | `ExplodePacketDefinition` | 26.3 → 26.2 | `AbstractPayloadSensitivePacketDefinition` – Observer-Knockback entfernen, Szeneffekt behalten | no |
| 115 | `minecraft:game_event` | `GameEventPacketDefinition` | 26.3 → 26.2 | `AbstractPayloadSensitivePacketDefinition` – nur szenensichere Events zulassen | no |

### 6.5 Dauerhafter Zustand mit Identitäts-Rewrite

| # | Paket | Konkrete Klasse | Versionsreihenfolge | Basis / Schwerpunkt | Checkpoint |
|---:|---|---|---|---|:---:|
| 116 | `minecraft:add_entity` | `AddEntityPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-ID, UUID und typabhängige Daten (u. a. Fishing-Hook-Owner) | yes |
| 117 | `minecraft:player_info_update` | `PlayerInfoUpdatePacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – UUID remappen, Name/Properties behalten, Chat-Session verwerfen | yes |
| 118 | `minecraft:player_info_remove` | `PlayerInfoRemovePacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – nur replay-eigene Profile entfernen | yes |
| 119 | `minecraft:remove_entities` | `RemoveEntitiesPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – alle Entity-IDs remappen und Ownership bereinigen | yes |
| 120 | `minecraft:entity_position_sync` | `EntityPositionSyncPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 121 | `minecraft:move_entity_pos` | `MoveEntityPosPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 122 | `minecraft:move_entity_pos_rot` | `MoveEntityPosRotPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 123 | `minecraft:move_entity_rot` | `MoveEntityRotPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 124 | `minecraft:move_minecart_along_track` | `MoveMinecartAlongTrackPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-ID remappen; Interpolationsschritte erhalten | yes |
| 125 | `minecraft:rotate_head` | `RotateHeadPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 126 | `minecraft:teleport_entity` | `TeleportEntityPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 127 | `minecraft:set_entity_motion` | `SetEntityMotionPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 128 | `minecraft:set_entity_data` | `SetEntityDataPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – nur serializerverifizierte Entity-Referenzen remappen | yes |
| 129 | `minecraft:set_equipment` | `SetEquipmentPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-ID remappen; Slot-Deltas inklusive Leerungen erhalten | yes |
| 130 | `minecraft:update_attributes` | `UpdateAttributesPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 131 | `minecraft:update_mob_effect` | `UpdateMobEffectPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 132 | `minecraft:remove_mob_effect` | `RemoveMobEffectPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-/UUID-Referenzen vollständig remappen | yes |
| 133 | `minecraft:set_passengers` | `SetPassengersPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Vehicle und sämtliche Passenger-IDs remappen | yes |
| 134 | `minecraft:set_entity_link` | `SetEntityLinkPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – beide IDs remappen; Unlink-Sentinel 0 erhalten | yes |
| 135 | `minecraft:projectile_power` | `ProjectilePowerPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Projectile-Entity-ID remappen | yes |
| 136 | `minecraft:boss_event` | `BossEventPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – UUID remappen und sessionlokalen Bossbar-Zustand führen | yes |

### 6.6 Geordnete Effekte mit Identitäts-Rewrite

| # | Paket | Konkrete Klasse | Versionsreihenfolge | Basis / Schwerpunkt | Checkpoint |
|---:|---|---|---|---|:---:|
| 137 | `minecraft:animate` | `AnimatePacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-ID remappen; 26.2-Hand-Swing-Semantik testen | no |
| 138 | `minecraft:entity_event` | `EntityEventPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Entity-ID remappen; Event-Payload unverändert | no |
| 139 | `minecraft:hurt_animation` | `HurtAnimationPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Referenzen remappen; geordneter Effekt | no |
| 140 | `minecraft:damage_event` | `DamageEventPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Ziel-, direkte und verursachende Entity-Referenzen behandeln | no |
| 141 | `minecraft:block_destruction` | `BlockDestructionPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Overlay-ID isolieren und beim Reset entfernen | no |
| 142 | `minecraft:take_item_entity` | `TakeItemEntityPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – Item- und Collector-Entity-ID remappen | no |
| 143 | `minecraft:sound_entity` | `SoundEntityPacketDefinition` | 26.3 → 26.2 | `RebuildingPacketDefinition` – gebundene Entity-ID remappen | no |
| 144 | `minecraft:swing_animation` | `SwingAnimationPacketDefinition` | 26.3 | `RebuildingPacketDefinition` – 26.3-only; Entity-ID remappen, Hand/Animation erhalten | no |

## 7. Phasen-Gates

### Gate A – nach 027

- Alle PLAY-Steuerungs- und Diagnosepakete besitzen benannte Klassen.
- Kein Paket dieser Gruppe ruft den Decoder auf.
- Capture bleibt möglich; Playback und Checkpoint bleiben gesperrt.
- Unbekannte künftige Pakete fallen weiterhin auf `UNSUPPORTED`.

### Gate B – nach 087

- Sämtliche viewer-lokalen Pakete werden vor Decode gedroppt.
- Inventar, Menüs, Kamera, Bewegung des Observers, Scoreboard, Titel, Rezepte,
  Dialoge und Chat verändern den neutralen Observer nicht.
- `custom_payload` wird nie blind wiedergegeben.
- Die Capture-Policy für Chat bleibt unabhängig vom neutralen Playback-Drop.

### Gate C – nach 107

- Chunk-, Licht-, Block-, Border-, Zeit- und Registry-Zustand kann vollständig
  als Checkpoint rekonstruiert werden.
- Chunk-Abhängigkeiten und Reihenfolge sind in Seek-Tests abgesichert.
- `map_item_data` dokumentiert weiterhin die fehlende Map-ID-Isolation.
- Ein Reset entfernt alle durch diese Phase aufgebauten replay-eigenen
  Weltzustände.

### Gate D – nach 115

- Geordnete SAFE-Effekte funktionieren ohne Checkpoint-Aufnahme.
- `explode` überträgt keinen Observer-Knockback.
- `game_event` filtert alle verbindungs- oder spielermodusbezogenen Varianten.
- `add_transient_block` ist nur in 26.3 aktiv und wird beim Reset beseitigt.

### Gate E – nach 136

- Entity- und UUID-Mapping ist pro Playback-Session isoliert.
- Spawn, Profile, Bewegung, Metadaten, Equipment, Effekte, Beziehungen,
  Projektile und Bossbars sind gemeinsam seek- und resetfähig.
- Keine aufgezeichnete Entity-ID oder UUID kollidiert mit Observer oder
  Live-Welt.
- Serializerfremde Integer in Entity-Metadaten bleiben unverändert.

### Gate F – nach 144

- Alle 144/141 PLAY-Identifier besitzen genau eine konkrete Definition.
- Geordnete Identitätseffekte sind replaybar, aber nicht checkpoint-relevant.
- 26.2-`animate` und 26.3-`swing_animation` besitzen getrennte native Tests.
- Catalog-Coverage beträgt exakt 144 für 26.3 und 141 für 26.2.

## 8. Capture-Quellen und Event-Fallbacks

Die Definition eines Playback-Pakets darf die Capture-Quelle nicht
versehentlich umkehren. Es gilt weiterhin:

1. PacketEvents beziehungsweise der Paper-Channel ist die primäre Quelle für
   tatsächlich gesendete Pakete.
2. Player-Bewegung, Equipment und Swing werden aus akzeptierten
   serverbound/ausgehenden Paketinformationen und anschließendem
   Server-Thread-Sampling abgeleitet.
3. Paper-Events sind nur Fallback für Zustände ohne verlässlichen
   verbindungsgebundenen Paketpfad, insbesondere Non-Player-Entitäten.
4. Sobald Paket- oder Sampler-Pfad eine Zustandsfamilie besitzt, muss der
   Event-Fallback für denselben Tick und dieselbe Entität unterdrückt werden.
5. Cleanup-Events dürfen Besitz und Reset steuern, gelten aber nicht als
   autoritative Bewegungs- oder Equipment-Quelle.

Besonders zu prüfen sind `entity_position_sync`, die drei
`move_entity_*`-Pakete, `rotate_head`, `set_equipment`, `animate` und
`swing_animation`. Für diese Familien werden Tests benötigt, die Paketquelle,
Event-Fallback und Duplicate Suppression gemeinsam abdecken.

## 9. Paper- und PacketEvents-Channel-Garantien

Jede Phase muss die bestehenden Channel-Grenzen beibehalten:

- Capture kopiert den finalen, von PacketEvents akzeptierten unkomprimierten
  Paketbody erst nach erfolgreichem Re-Encode.
- Ein abgebrochenes PacketEvents-Paket erzeugt kein Recording-Frame.
- Playback encodiert über den verbindungsgebundenen Paper-Codec.
- Das fertige Replay-Paket wird am PacketEvents-Encoder-Kontext eingespeist,
  sodass Paper-Encoder, Replay-Gate und Capture-Listener nicht erneut laufen.
- Transport-Framing, Kompression und Verschlüsselung bleiben nachgelagert aktiv.
- Gleichzeitige Aufnahme desselben Spielers erhält kein Replay-Echo.
- Fehlende oder verschobene Channel-Handler führen zu kontrolliertem Fail,
  nicht zu doppeltem Live-Traffic.

Diese Eigenschaften werden nach jeder Phase mit `EmbeddedChannel`-Tests und
den versionsspezifischen `PacketEventsReplayWriterTest`-Tests abgesichert.

## 10. Testmatrix pro Kategorie

| Kategorie | Mindestnachweis |
|---|---|
| Blocked | kein Decode; FAIL/BLOCKED; capture-only |
| Viewer-local | kein Decode; DROP_LOCAL; Observer unverändert |
| Pass-through checkpoint | native Klasse; Roundtrip; Checkpoint und Seek |
| Pass-through effect | Roundtrip; Reihenfolge; nicht im Checkpoint |
| Payload-sensitive | erlaubte und verbotene Payloadvarianten |
| Identity rewrite | alle Referenzfelder; keine Mutation des Decode-Objekts |
| Stateful identity | zusätzlich Seek, Reset und Session-Isolation |
| Version-only | Abwesenheit im 26.2-Katalog und positive 26.3-Abdeckung |

Für jede Rebuilding-Definition gilt außerdem:

- `read` extrahiert alle semantisch relevanten Felder;
- `rewrite` verändert ausschließlich dokumentierte Identitäten oder
  observerlokale Felder;
- `rebuild` erzeugt eine neue native Instanz;
- Encode liefert denselben Descriptor;
- der Test enthält mindestens einen negativen Typ- oder Descriptorfall.

## 11. Commit- und Review-Struktur

Die Umsetzung wird in logisch getrennten englischsprachigen Commits gehalten:

1. `refactor: add packet definition policy bases`
2. `feat: classify blocked play packets`
3. `feat: isolate viewer-local play packets`
4. `feat: replay durable world state packets`
5. `feat: replay ordered scene effect packets`
6. `feat: rewrite durable replay identities`
7. `feat: rewrite identity-bearing scene effects`
8. `test: enforce complete packet catalog coverage`
9. `docs: finalize packet implementation matrix`

Ein Phasen-Commit darf nur die zugehörigen Definitionen, Katalogbindungen,
Tests und unmittelbar notwendige Dokumentation enthalten. Unabhängige
Refactorings bleiben außerhalb dieser Serie.

## 12. Definition of Done

Die Gesamtaufgabe ist erst abgeschlossen, wenn:

- 285 konkrete Klassen nach dem Namensschema existieren;
- die Live-Inventare exakt 144 beziehungsweise 141 Definitionen finden;
- kein bekannter PLAY-Identifier auf den `UNSUPPORTED`-Fallback fällt;
- ein neu auftauchender unbekannter Identifier weiterhin fail-closed bleibt;
- alle Packet-/Codec-/Checkpoint-/Seek-/Reset-/Channel-Tests beider Versionen
  erfolgreich sind;
- Event-Fallbacks nachweislich sekundär und duplikatfrei bleiben;
- die Adapterreferenzen den tatsächlich aktiven Katalog widerspiegeln;
- ausschließlich aufgabenbezogene Dateien geändert wurden.

## 13. Erwartete Auswirkungen und Risiken

- Die Dateizahl steigt bewusst um 285 konkrete Klassen plus fokussierte Tests.
- Der größte technische Risikobereich sind Identitätsfelder, deren Bedeutung
  vom Pakettyp oder Metadata-Serializer abhängt.
- `custom_payload`, Chat, UI und Verbindungssteuerung bleiben absichtlich
  fail-closed beziehungsweise viewer-lokal.
- NMS-Unterschiede werden nicht durch Reflection vereinheitlicht, sondern
  explizit je Versionsklasse dokumentiert.
- Die schrittweise Katalogfreischaltung verhindert, dass eine halb fertige
  Entity-Familie produktiv replaybar wird.
