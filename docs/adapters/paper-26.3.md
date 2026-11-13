# Paper 26.3 adapter

This document describes the architectural boundaries and compatibility
contract of the Paper 26.3 adapter. It intentionally does not track packet
implementation or test status.

## Runtime baseline

The adapter targets Paper `26.3.build.244-stable`, Java 26, and PacketEvents
2.14.0. Version-bound NMS access remains inside
`dev.voldechse.replayframework.adapter.paper.v26_3`.

The registry fingerprint is derived at runtime from the active protocol
registry and adapter policy. Adapter format revision `4` is required;
recordings from earlier revisions are not migrated or accepted.

## Architecture

`Paper26_3ReplayAdapter` is the adapter entry point and
`Paper26_3AdapterProvider` exposes it to adapter discovery. Shared,
NMS-independent contracts live in `replay-adapter:paper-common` and
`replay-adapter:api`.

The version module is divided by responsibility:

- `packet` owns protocol discovery, native codec access, classifications, and
  version-local packet definitions.
- `capture` owns outbound observation, semantic capture context, source
  selection, ordering, and frame emission.
- `checkpoint` owns scoped Paper snapshots, deterministic assembly, and the
  capture barrier used to separate snapshots from later deltas.
- `playback` owns decoding, session isolation, queueing, channel injection,
  and cleanup of replay-owned client state.

Packet definition naming and placement are specified centrally in
[`packet-definition-layout.md`](../packet-definition-layout.md).

## Capture boundary

Clientbound traffic is observed after Paper and other handlers have produced
its final protocol representation. Capture copies immutable frame data before
leaving the network thread and attaches only semantic context that can be
derived safely from typed adapter state.

Packet observation remains the authoritative source for recipient-specific
wire behavior. Server-side events are used only where the protocol stream
cannot provide the required authoritative state and where an equivalent,
well-defined fallback exists.

## Checkpoints

Checkpoint creation preloads the requested scope when necessary, captures a
Paper-owned snapshot, and allocates a server tick/time/sequence barrier only
after that snapshot is ready. The core drains earlier deltas before persisting
the checkpoint, preserving an exclusive boundary between snapshot state and
subsequent traffic.

Snapshot data is converted to ordered native protocol frames without exposing
mutable Paper objects to asynchronous persistence work.

## Playback boundary

Playback validates the recorded adapter identity, format revision, and
registry fingerprint before emitting frames. Version-native decoding and
encoding stay behind the adapter boundary.

Replay-owned identities are deterministic per playback session and isolated
from live server identities. Viewer-local state is kept separate from scene
state, while unsupported or unsafe traffic is rejected by registry policy.
Live outbound traffic that would conflict with an active replay view is
filtered at the Paper channel boundary.

Seek reconstruction emits only state admitted by the verified checkpoint
contract. Normal timeline playback may additionally emit ordered transient
state. Reset removes tracked replay-owned client state in deterministic order
and reports partial failures without silently discarding its tracking data.

## Compatibility and limits

The adapter requires the pinned Paper protocol and compatible registries.
Custom server software, modified registries, datapacks, or intermediary
protocol translators can invalidate assumptions even when the nominal game
version matches.

Only semantics explicitly modeled by the adapter are transformed. Opaque or
unknown values are never guessed from field shape, packet names, or payload
bytes. The registry fingerprint is a compatibility guard, not a substitute
for validating custom runtime content.

## Quality assurance

The version module is covered by protocol-registry, native-codec, capture,
checkpoint, playback-isolation, reset, and outbound-channel tests. Validation
uses the pinned Paper development bundle so runtime IDs, codecs, and native
types are checked against the same version used for compilation.

## Protocol inventory

The following tables document every clientbound packet in the pinned
protocol registry, including its protocol role, replay relevance, and
intended playback/checkpoint policy. They do not state implementation or
test status.

### STATUS

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:status_response` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 1 | `minecraft:pong_response` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |

### LOGIN

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:login_disconnect` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 1 | `minecraft:hello` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 2 | `minecraft:login_finished` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 3 | `minecraft:login_compression` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 4 | `minecraft:custom_query` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 5 | `minecraft:cookie_request` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |

### CONFIGURATION

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:cookie_request` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 1 | `minecraft:custom_payload` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 2 | `minecraft:disconnect` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 3 | `minecraft:finish_configuration` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 4 | `minecraft:keep_alive` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 5 | `minecraft:ping` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 6 | `minecraft:reset_chat` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 7 | `minecraft:registry_data` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 8 | `minecraft:resource_pack_pop` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 9 | `minecraft:resource_pack_push` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 10 | `minecraft:post_effects` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 11 | `minecraft:store_cookie` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 12 | `minecraft:transfer` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 13 | `minecraft:update_enabled_features` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 14 | `minecraft:update_tags` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 15 | `minecraft:select_known_packs` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 16 | `minecraft:custom_report_details` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 17 | `minecraft:server_links` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 18 | `minecraft:clear_dialog` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 19 | `minecraft:show_dialog` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |
| 20 | `minecraft:code_of_conduct` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no |

### PLAY

| ID | Identifier | Description / relevance | Playback | Checkpoint |
|---:|---|---|---|---|
| 0 | `minecraft:bundle_delimiter` | Payload-free, identity-sensitive bundle boundary; retain for stream analysis but never replay without channel-scoped bundle ownership | BLOCKED | no |
| 1 | `minecraft:add_entity` | Essential scene state: spawn an entity with type, position, UUID, and type-specific data | REWRITE | yes |
| 2 | `minecraft:animate` | Ordered scene effect: entity animation such as wake-up or critical hit; rewrite entity ID | REWRITE | no |
| 3 | `minecraft:award_stats` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 4 | `minecraft:block_changed_ack` | Cumulative recorded-client prediction acknowledgement; no block state or result | BLOCKED | no |
| 5 | `minecraft:block_destruction` | Spatial, ordered block-breaking overlay; isolate its source/overlay ID, preserve all unsigned progress values, and clear active overlays during reset | REWRITE | no |
| 6 | `minecraft:block_entity_data` | Essential scene state: durable world or scene state | SEND | yes |
| 7 | `minecraft:block_event` | Spatial, ordered block-local action; preserve the registered block and both unsigned action fields because their meaning depends on the block state at the position | SEND | no |
| 8 | `minecraft:block_update` | Spatial, durable replacement of one global block state, preserving every state property such as orientation, shape, level, age and flags | SEND | yes |
| 9 | `minecraft:boss_event` | Durable boss-bar lifecycle with six operation variants; preserve its component, progress, style and properties while isolating the bar UUID per playback session | REWRITE | yes |
| 10 | `minecraft:change_difficulty` | Synchronizes the four-level world difficulty and its lock flag with the receiving client; keep the recorded connection state from changing the neutral observer | DROP_LOCAL | no |
| 11 | `minecraft:chunk_batch_finished` | Ends the client-side chunk-throughput measurement with the transmitted batch size and triggers a serverbound rate acknowledgement; never replay into an established connection | BLOCKED | no |
| 12 | `minecraft:chunk_batch_start` | Payload-free start of a connection-local chunk-batch timing measurement; never replay into an established connection | BLOCKED | no |
| 13 | `minecraft:chunks_biomes` | Replaces the serialized biome palettes of zero or more chunks while preserving entry order, duplicate coordinates, and the complete per-chunk payload | SEND | yes |
| 14 | `minecraft:clear_titles` | Clears the recorded connection's title and subtitle and can also reset its animation timings; never apply either operation to the neutral observer | DROP_LOCAL | no |
| 15 | `minecraft:command_suggestions` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 16 | `minecraft:commands` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 17 | `minecraft:container_close` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 18 | `minecraft:container_set_content` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 19 | `minecraft:container_set_data` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 20 | `minecraft:container_set_slot` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 21 | `minecraft:cookie_request` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 22 | `minecraft:cooldown` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 23 | `minecraft:custom_chat_completions` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 24 | `minecraft:custom_payload` | Must remain local: plugin-specific state of the recorded client; blocked for playback | DROP_LOCAL | no |
| 25 | `minecraft:damage_event` | Ordered scene effect: damage on replay entities with isolated source IDs | REWRITE | no |
| 26 | `minecraft:debug/block_value` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 27 | `minecraft:debug/chunk_value` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 28 | `minecraft:debug/entity_value` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 29 | `minecraft:debug/event` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 30 | `minecraft:debug_sample` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 31 | `minecraft:delete_chat` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 32 | `minecraft:disconnect` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 33 | `minecraft:disguised_chat` | Recorded connection chat retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no |
| 34 | `minecraft:entity_event` | Ordered scene effect: entity status or animation; rewrite entity ID | REWRITE | no |
| 35 | `minecraft:entity_position_sync` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 36 | `minecraft:explode` | Ordered scene effect: explosion presentation; remove observer knockback | SEND | no |
| 37 | `minecraft:add_transient_block` | Ordered scene effect: 26.3 transient block presentation; clear its client lifetime by unloading replay chunks on reset | SEND | no |
| 38 | `minecraft:forget_level_chunk` | Essential scene state: durable world or scene state | SEND | yes |
| 39 | `minecraft:game_event` | Allow weather presentation; block game-mode, respawn, demo, and other observer-affecting events | SEND | no |
| 40 | `minecraft:game_rule_values` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 41 | `minecraft:game_test_highlight_pos` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 42 | `minecraft:mount_screen_open` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 43 | `minecraft:hurt_animation` | Ordered scene effect: hurt animation for a replay entity | REWRITE | no |
| 44 | `minecraft:initialize_border` | Essential scene state: durable world or scene state | SEND | yes |
| 45 | `minecraft:keep_alive` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 46 | `minecraft:level_chunk_with_light` | Essential scene state: durable world or scene state | SEND | yes |
| 47 | `minecraft:level_event` | Ordered presentation or environment effect | SEND | no |
| 48 | `minecraft:level_particles` | Ordered presentation or environment effect | SEND | no |
| 49 | `minecraft:light_update` | Essential scene state: durable world or scene state | SEND | yes |
| 50 | `minecraft:login` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 51 | `minecraft:low_disk_space_warning` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 52 | `minecraft:map_item_data` | Map presentation; numeric map IDs are not currently isolated | SEND | yes |
| 53 | `minecraft:merchant_offers` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 54 | `minecraft:move_entity_pos` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 55 | `minecraft:move_entity_pos_rot` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 56 | `minecraft:move_minecart_along_track` | Essential scene state: minecart interpolation and movement steps | REWRITE | yes |
| 57 | `minecraft:move_entity_rot` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 58 | `minecraft:move_vehicle` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 59 | `minecraft:open_book` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 60 | `minecraft:open_screen` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 61 | `minecraft:open_sign_editor` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 62 | `minecraft:ping` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 63 | `minecraft:pong_response` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 64 | `minecraft:place_ghost_recipe` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 65 | `minecraft:player_abilities` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 66 | `minecraft:player_chat` | Recorded connection chat retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no |
| 67 | `minecraft:player_combat_end` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 68 | `minecraft:player_combat_enter` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 69 | `minecraft:player_combat_kill` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 70 | `minecraft:player_info_remove` | Essential scene state: remove only replay-owned player profiles | REWRITE | yes |
| 71 | `minecraft:player_info_update` | Essential scene state: isolate the player UUID, preserve the recorded profile name and properties, hide the replay profile from the tab list, and discard the recorded chat session | REWRITE | yes |
| 72 | `minecraft:player_look_at` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 73 | `minecraft:player_position` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 74 | `minecraft:player_rotation` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 75 | `minecraft:recipe_book_add` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 76 | `minecraft:recipe_book_remove` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 77 | `minecraft:recipe_book_settings` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 78 | `minecraft:remove_entities` | Essential scene state: remove replay entities | REWRITE | yes |
| 79 | `minecraft:remove_mob_effect` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 80 | `minecraft:reset_score` | Recorded-viewer scoreboard score removal; preserve the observer scoreboard | DROP_LOCAL | no |
| 81 | `minecraft:resource_pack_pop` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 82 | `minecraft:resource_pack_push` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 83 | `minecraft:post_effects` | Must remain local: 26.3 post-processing for the recorded player; never apply to the observer | DROP_LOCAL | no |
| 84 | `minecraft:respawn` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 85 | `minecraft:rotate_head` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 86 | `minecraft:section_blocks_update` | Essential scene state: durable world or scene state | SEND | yes |
| 87 | `minecraft:select_advancements_tab` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 88 | `minecraft:server_data` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 89 | `minecraft:set_action_bar_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 90 | `minecraft:set_border_center` | Supplementary durable world or scene state | SEND | yes |
| 91 | `minecraft:set_border_lerp_size` | Supplementary durable world or scene state | SEND | yes |
| 92 | `minecraft:set_border_size` | Supplementary durable world or scene state | SEND | yes |
| 93 | `minecraft:set_border_warning_delay` | Supplementary durable world or scene state | SEND | yes |
| 94 | `minecraft:set_border_warning_distance` | Supplementary durable world or scene state | SEND | yes |
| 95 | `minecraft:set_camera` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 96 | `minecraft:set_chunk_cache_center` | Supplementary durable world or scene state | SEND | yes |
| 97 | `minecraft:set_chunk_cache_radius` | Supplementary durable world or scene state | SEND | yes |
| 98 | `minecraft:set_cursor_item` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 99 | `minecraft:set_default_spawn_position` | Essential scene state: durable world or scene state | SEND | yes |
| 100 | `minecraft:set_display_objective` | Recorded-viewer scoreboard display slot; preserve the observer sidebar | DROP_LOCAL | no |
| 101 | `minecraft:set_entity_data` | Essential scene state: synchronized entity metadata | REWRITE | yes |
| 102 | `minecraft:set_entity_link` | Essential scene state: leash link; rewrite both entity IDs and preserve unlink sentinel 0 | REWRITE | yes |
| 103 | `minecraft:set_entity_motion` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 104 | `minecraft:set_equipment` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 105 | `minecraft:set_experience` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 106 | `minecraft:set_health` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 107 | `minecraft:set_held_slot` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 108 | `minecraft:set_objective` | Recorded-viewer scoreboard objective; keep local to preserve observer state | DROP_LOCAL | no |
| 109 | `minecraft:set_passengers` | Essential scene state: vehicle and passengers; rewrite every entity ID | REWRITE | yes |
| 110 | `minecraft:set_player_inventory` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 111 | `minecraft:set_player_team` | Recorded-viewer team and name presentation; keep local to preserve observer state | DROP_LOCAL | no |
| 112 | `minecraft:set_score` | Recorded-viewer scoreboard value; preserve the observer scoreboard | DROP_LOCAL | no |
| 113 | `minecraft:set_simulation_distance` | Supplementary durable world or scene state | SEND | yes |
| 114 | `minecraft:set_subtitle_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 115 | `minecraft:set_time` | Essential scene state: durable world or scene state | SEND | yes |
| 116 | `minecraft:set_title_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 117 | `minecraft:set_titles_animation` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 118 | `minecraft:sound_entity` | Ordered scene effect: sound attached to a rewritten entity ID | REWRITE | no |
| 119 | `minecraft:sound` | Ordered presentation or environment effect | SEND | no |
| 120 | `minecraft:start_configuration` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 121 | `minecraft:stop_sound` | Ordered presentation or environment effect | SEND | no |
| 122 | `minecraft:store_cookie` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 123 | `minecraft:swing_animation` | Ordered scene effect: 26.3 swing animation; rewrite entity ID and retain hand/animation data | REWRITE | no |
| 124 | `minecraft:system_chat` | Recorded connection chat and action-bar presentation retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no |
| 125 | `minecraft:tab_list` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 126 | `minecraft:tag_query` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 127 | `minecraft:take_item_entity` | Scene presentation with playback-session identity rewriting | REWRITE | no |
| 128 | `minecraft:teleport_entity` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 129 | `minecraft:test_instance_block_status` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no |
| 130 | `minecraft:ticking_state` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 131 | `minecraft:ticking_step` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 132 | `minecraft:transfer` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 133 | `minecraft:update_advancements` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 134 | `minecraft:update_attributes` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 135 | `minecraft:update_mob_effect` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes |
| 136 | `minecraft:update_recipes` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 137 | `minecraft:update_tags` | Registry tags; requires compatible server and datapack registries | SEND | yes |
| 138 | `minecraft:projectile_power` | Scene presentation with playback-session identity rewriting | REWRITE | yes |
| 139 | `minecraft:custom_report_details` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no |
| 140 | `minecraft:server_links` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 141 | `minecraft:waypoint` | Recorded-viewer waypoint presentation; keep local to preserve observer state | DROP_LOCAL | no |
| 142 | `minecraft:clear_dialog` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |
| 143 | `minecraft:show_dialog` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no |

