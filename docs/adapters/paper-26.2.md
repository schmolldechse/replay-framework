# Paper 26.2 adapter packet reference

This reference describes the exact clientbound protocol surface reviewed for Paper 26.2 build 135. The adapter uses Java 26, PacketEvents 2.14.0, and the `26.2.build.135-stable` Paper development bundle. The inspected registry contains 169 clientbound packet types, including 141 PLAY packets.

Registry fingerprint: `d9d610afb1ff45fa2a97302337053d3f9643e6d09a685cf32652bc6678ffd744`.
Adapter format revision: `4`. Revision-3 recordings are intentionally not
migrated or accepted by this implementation.

## Adapter structure

The adapter entry point is `Paper26ReplayAdapter`, provided through `Paper26AdapterProvider`. Both follow the shared adapter design: NMS types remain inside `dev.voldechse.replayframework.adapter.paper.v26_2`, while `AbstractReplayAdapter` owns the common contracts.

`replay-adapter:paper-common` supplies the NMS-free packet-definition template.
The 26.2 catalog is the single source for registry policy and playback behavior;
all 141 PLAY entries bind to a named version-local definition class and unknown
PLAY packets fail adapter opening. Definitions apply local/unsafe policy
before decode and validate decode, rewrite/rebuild, and encode against the same
descriptor. `add_entity` is the concrete read/value/rewrite/rebuild reference
implementation for identity-bearing packets.

- `capture` attaches packet capture, resolves recording scope, and emits synthetic state changes.
- `checkpoint` snapshots loaded Paper world state and encodes ordered native 26.2 packets. After optional scope preloading, it allocates an actual Paper tick/time/sequence barrier through the capture bridge; the core drains all earlier 26.2 deltas before persisting that checkpoint.
- `playback` decodes 26.2 payloads, maps identities per viewer, filters connection-local state, and removes replay-owned objects during reset.

## Playback policy for 26.2

Chunks, light, block entities, entity lifecycle, movement, metadata, equipment, passengers, leashes, active effects, and player profiles form the reconstructible scene. Animations, sounds, particles, chat, and damage effects are ordered deltas.

Player visual state follows the [capture source policy](../capture-source-policy.md). PacketEvents observes accepted PLAY serverbound packets and copies only the user identity, protocol entity ID, packet type, and animation hand on the Netty thread. A coalesced server-thread sample then compares the player's position, orientation, ground state, and all equipment slots with the previous sample. The adapter emits the 26.2 absolute `entity_position_sync` form, `rotate_head` when required, changed `set_equipment` slots including cleared slots, and `animate` with `SWING_MAIN_HAND` or `SWING_OFF_HAND`. Player movement, equipment, and swings do not use Paper gameplay events.

Non-player entities have no owner connection or serverbound stream. `EntityMoveEvent` and `EntityEquipmentChangedEvent` are retained only as non-player fallbacks and are translated into the same version-native packets. Once either path owns a state family, recipient-derived movement and equipment copies are suppressed; the `SWAP_HANDS` entity event is also suppressed for equipment-authoritative entities. Swing suppression is limited to the accepted animation's server tick so unrelated and later animation packets remain available. Scope filtering uses the recorded entity's world and block position rather than a packet recipient.

`SEND` is scene-safe. `REWRITE` rebuilds native packets with session-local identities. `DROP_LOCAL` covers state belonging to the recorded connection: health, experience, inventory, viewer movement, abilities, camera, menus, titles, scoreboard, teams, waypoints, commands, recipes, dialogs, and related UI. Equivalent live packets continue through the outbound gate. `BLOCKED` covers connection control and diagnostic traffic.

Recorded player, system, disguised, and action-bar chat packets remain capture-policy data and are never presented to a neutral playback observer. Explosions keep scene particles and sound but lose viewer knockback. Replay player profiles use mapped UUIDs while preserving their recorded profile names and properties. They do not enter the live tab list and never reuse a recorded chat session.

During seek reconstruction, the 26.2 bridge accepts only registry descriptors whose `Checkpoint` column is `yes`. Durable chunk, block, entity, profile, movement, metadata, equipment, effect, passenger, border, time, and registry-tag changes therefore converge on the target state. Ordered effects with `Checkpoint` set to `no`, including animations, block-breaking presentation, damage effects, explosions, particles, and sounds, remain available during normal timeline playback but are skipped during reconstruction. Frames are enqueued as batches while the channel drain retains its limit of 128 packet operations and one flush per event-loop turn.

## Tests

- `PacketCoverageTest` loads the real 26.2 NMS registry and fails for an unreviewed PLAY packet or missing essential family.
- Registry, native codec, checkpoint, snapshot, observer isolation, and outbound-gate tests cover their respective version-bound behavior.
- Snapshot tests verify that the 26.2 capture barrier is allocated only after asynchronous scope preloading completes; generic core tests verify the exclusive delta boundary stored with it.
- `Paper26EntityStateCaptureTest` covers packet-driven movement/equipment authority, hand-swap and tick-scoped swing suppression, equipment deltas, cleared slots, and the 26.2 `animate` hand encoding.
- `NeutralObserverTest` includes entity effects, minecart motion, sound, leashes, filtered chat, explosion knockback, block-breaking overlays, serializer-qualified entity-reference metadata, and idempotent reset.
- `Paper26PlaybackBridgeTest` verifies that reconstruction retains 26.2 checkpoint state while suppressing transient frames.

`R` means registry/classification coverage. `R+N` additionally identifies a focused native behavior test. Registry coverage does not claim every possible payload or custom datapack renders correctly.

`DROP_LOCAL*` is a neutral-observer guard applied before payload decoding.
The registry fingerprint remains the protocol-policy guard; format revision 4
deliberately does not retain revision-3 artifact compatibility.

## Known limits

Map IDs and unclassified integer fields are not generically rewritten. `OPTIONAL_UNSIGNED_INT` entity metadata is rewritten as a verified entity reference; in 26.2 this covers firework attachment targets, frog tongue targets, and player shoulder-parrot references while ordinary integer metadata remains unchanged. Diagnostic and game-test packets are captured but blocked. Short-lived deltas before a selected checkpoint are absent after a seek. The fingerprint is a compatibility guard, not a substitute for validating custom registries or datapacks.

## STATUS

| ID | Packet (`minecraft:`) | Meaning / relevance | Playback | Checkpoint | Test |
|---:|---|---|---|---|---|
| 0 | `status_response` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 1 | `pong_response` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |

## LOGIN

| ID | Packet (`minecraft:`) | Meaning / relevance | Playback | Checkpoint | Test |
|---:|---|---|---|---|---|
| 0 | `login_disconnect` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 1 | `hello` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 2 | `login_finished` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 3 | `login_compression` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 4 | `custom_query` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 5 | `cookie_request` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |

## CONFIGURATION

| ID | Packet (`minecraft:`) | Meaning / relevance | Playback | Checkpoint | Test |
|---:|---|---|---|---|---|
| 0 | `cookie_request` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 1 | `custom_payload` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 2 | `disconnect` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 3 | `finish_configuration` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 4 | `keep_alive` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 5 | `ping` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 6 | `reset_chat` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 7 | `registry_data` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 8 | `resource_pack_pop` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 9 | `resource_pack_push` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 10 | `store_cookie` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 11 | `transfer` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 12 | `update_enabled_features` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 13 | `update_tags` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 14 | `select_known_packs` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 15 | `custom_report_details` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 16 | `server_links` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 17 | `clear_dialog` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 18 | `show_dialog` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |
| 19 | `code_of_conduct` | Connection setup or management; never inject into an established PLAY connection | BLOCKED | no | R |

## PLAY

| ID | Packet (`minecraft:`) | Meaning / relevance | Playback | Checkpoint | Test |
|---:|---|---|---|---|---|
| 0 | `bundle_delimiter` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 1 | `add_entity` | Essential scene state: spawn an entity with type, position, UUID, and type-specific data | REWRITE | yes | R |
| 2 | `animate` | Ordered scene effect: entity animation such as wake-up or critical hit; rewrite entity ID | REWRITE | no | R+N |
| 3 | `award_stats` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 4 | `block_changed_ack` | Recorded-client acknowledgement; not scene state | BLOCKED | no | R |
| 5 | `block_destruction` | Ordered scene effect: block-breaking progress; isolate its ID and clear the overlay during reset | REWRITE | no | R+N |
| 6 | `block_entity_data` | Essential scene state: durable world or scene state | SEND | yes | R |
| 7 | `block_event` | Ordered presentation or environment effect | SEND | no | R |
| 8 | `block_update` | Essential scene state: durable world or scene state | SEND | yes | R |
| 9 | `boss_event` | Scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 10 | `change_difficulty` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 11 | `chunk_batch_finished` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 12 | `chunk_batch_start` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 13 | `chunks_biomes` | Essential scene state: durable world or scene state | SEND | yes | R |
| 14 | `clear_titles` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 15 | `command_suggestions` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 16 | `commands` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 17 | `container_close` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 18 | `container_set_content` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 19 | `container_set_data` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 20 | `container_set_slot` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 21 | `cookie_request` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 22 | `cooldown` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 23 | `custom_chat_completions` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 24 | `custom_payload` | Must remain local: plugin-specific state of the recorded client; blocked for playback | DROP_LOCAL | no | R+N |
| 25 | `damage_event` | Ordered scene effect: damage on replay entities with isolated source IDs | REWRITE | no | R |
| 26 | `debug/block_value` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 27 | `debug/chunk_value` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 28 | `debug/entity_value` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 29 | `debug/event` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 30 | `debug_sample` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 31 | `delete_chat` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 32 | `disconnect` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 33 | `disguised_chat` | Recorded connection chat retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no | R+N |
| 34 | `entity_event` | Ordered scene effect: entity status or animation; rewrite entity ID | REWRITE | no | R+N |
| 35 | `entity_position_sync` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 36 | `explode` | Ordered scene effect: explosion presentation; remove observer knockback | SEND | no | R+N |
| 37 | `forget_level_chunk` | Essential scene state: durable world or scene state | SEND | yes | R |
| 38 | `game_event` | Allow weather presentation; block game-mode, respawn, demo, and other observer-affecting events | SEND | no | R+N |
| 39 | `game_rule_values` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 40 | `game_test_highlight_pos` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 41 | `mount_screen_open` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 42 | `hurt_animation` | Ordered scene effect: hurt animation for a replay entity | REWRITE | no | R+N |
| 43 | `initialize_border` | Essential scene state: durable world or scene state | SEND | yes | R |
| 44 | `keep_alive` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 45 | `level_chunk_with_light` | Essential scene state: durable world or scene state | SEND | yes | R |
| 46 | `level_event` | Ordered presentation or environment effect | SEND | no | R |
| 47 | `level_particles` | Ordered presentation or environment effect | SEND | no | R |
| 48 | `light_update` | Essential scene state: durable world or scene state | SEND | yes | R |
| 49 | `login` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 50 | `low_disk_space_warning` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 51 | `map_item_data` | Map presentation; numeric map IDs are not currently isolated | SEND | yes | R |
| 52 | `merchant_offers` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 53 | `move_entity_pos` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 54 | `move_entity_pos_rot` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 55 | `move_minecart_along_track` | Essential scene state: minecart interpolation and movement steps | REWRITE | yes | R |
| 56 | `move_entity_rot` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 57 | `move_vehicle` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 58 | `open_book` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 59 | `open_screen` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 60 | `open_sign_editor` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 61 | `ping` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 62 | `pong_response` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 63 | `place_ghost_recipe` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 64 | `player_abilities` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 65 | `player_chat` | Recorded connection chat retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no | R+N |
| 66 | `player_combat_end` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 67 | `player_combat_enter` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 68 | `player_combat_kill` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 69 | `player_info_remove` | Essential scene state: remove only replay-owned player profiles | REWRITE | yes | R |
| 70 | `player_info_update` | Essential scene state: isolate the player UUID, preserve the recorded profile name and properties, hide the replay profile from the tab list, and discard the recorded chat session | REWRITE | yes | R |
| 71 | `player_look_at` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 72 | `player_position` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 73 | `player_rotation` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 74 | `recipe_book_add` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 75 | `recipe_book_remove` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 76 | `recipe_book_settings` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 77 | `remove_entities` | Essential scene state: remove replay entities | REWRITE | yes | R+N |
| 78 | `remove_mob_effect` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 79 | `reset_score` | Recorded-viewer scoreboard score removal; preserve the observer scoreboard | DROP_LOCAL | no | R |
| 80 | `resource_pack_pop` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 81 | `resource_pack_push` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 82 | `respawn` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 83 | `rotate_head` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 84 | `section_blocks_update` | Essential scene state: durable world or scene state | SEND | yes | R |
| 85 | `select_advancements_tab` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 86 | `server_data` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 87 | `set_action_bar_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 88 | `set_border_center` | Supplementary durable world or scene state | SEND | yes | R+N |
| 89 | `set_border_lerp_size` | Supplementary durable world or scene state | SEND | yes | R |
| 90 | `set_border_size` | Supplementary durable world or scene state | SEND | yes | R |
| 91 | `set_border_warning_delay` | Supplementary durable world or scene state | SEND | yes | R |
| 92 | `set_border_warning_distance` | Supplementary durable world or scene state | SEND | yes | R |
| 93 | `set_camera` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 94 | `set_chunk_cache_center` | Supplementary durable world or scene state | SEND | yes | R |
| 95 | `set_chunk_cache_radius` | Supplementary durable world or scene state | SEND | yes | R |
| 96 | `set_cursor_item` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 97 | `set_default_spawn_position` | Essential scene state: durable world or scene state | SEND | yes | R |
| 98 | `set_display_objective` | Recorded-viewer scoreboard display slot; preserve the observer sidebar | DROP_LOCAL | no | R |
| 99 | `set_entity_data` | Essential scene state: synchronized entity metadata | REWRITE | yes | R |
| 100 | `set_entity_link` | Essential scene state: leash link; rewrite both entity IDs and preserve unlink sentinel 0 | REWRITE | yes | R+N |
| 101 | `set_entity_motion` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 102 | `set_equipment` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 103 | `set_experience` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 104 | `set_health` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 105 | `set_held_slot` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 106 | `set_objective` | Recorded-viewer scoreboard objective; keep local to preserve observer state | DROP_LOCAL | no | R |
| 107 | `set_passengers` | Essential scene state: vehicle and passengers; rewrite every entity ID | REWRITE | yes | R |
| 108 | `set_player_inventory` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 109 | `set_player_team` | Recorded-viewer team and name presentation; keep local to preserve observer state | DROP_LOCAL | no | R |
| 110 | `set_score` | Recorded-viewer scoreboard value; preserve the observer scoreboard | DROP_LOCAL | no | R |
| 111 | `set_simulation_distance` | Supplementary durable world or scene state | SEND | yes | R |
| 112 | `set_subtitle_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 113 | `set_time` | Essential scene state: durable world or scene state | SEND | yes | R |
| 114 | `set_title_text` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 115 | `set_titles_animation` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 116 | `sound_entity` | Ordered scene effect: sound attached to a rewritten entity ID | REWRITE | no | R |
| 117 | `sound` | Ordered presentation or environment effect | SEND | no | R |
| 118 | `start_configuration` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 119 | `stop_sound` | Ordered presentation or environment effect | SEND | no | R |
| 120 | `store_cookie` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 121 | `system_chat` | Recorded connection chat and action-bar presentation retained according to capture policy but suppressed for neutral playback | DROP_LOCAL* | no | R+N |
| 122 | `tab_list` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 123 | `tag_query` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 124 | `take_item_entity` | Scene presentation with playback-session identity rewriting | REWRITE | no | R |
| 125 | `teleport_entity` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 126 | `test_instance_block_status` | Diagnostic or special-purpose packet without a safe playback contract | BLOCKED | no | R |
| 127 | `ticking_state` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 128 | `ticking_step` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 129 | `transfer` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 130 | `update_advancements` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 131 | `update_attributes` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 132 | `update_mob_effect` | Essential scene state: scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 133 | `update_recipes` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 134 | `update_tags` | Registry tags; requires compatible server and datapack registries | SEND | yes | R |
| 135 | `projectile_power` | Scene presentation with playback-session identity rewriting | REWRITE | yes | R |
| 136 | `custom_report_details` | Transport, connection, or server control; never replay into an established connection | BLOCKED | no | R |
| 137 | `server_links` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 138 | `waypoint` | Recorded-viewer waypoint presentation; keep local to preserve observer state | DROP_LOCAL | no | R |
| 139 | `clear_dialog` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |
| 140 | `show_dialog` | Must remain local: recorded player or connection state; leave the neutral observer unchanged | DROP_LOCAL | no | R+N |




