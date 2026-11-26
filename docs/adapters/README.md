# Version adapters

A version adapter connects the framework to one Minecraft version of Paper.
The runtime contains every adapter and, at startup, opens the one whose
Minecraft version equals the server's (`PaperAdapterSelector`, through the
`PaperAdapterProvider` service). A server version without an adapter is not
supported.

## Supported versions

| Minecraft | Module | Pinned Paper build | Document |
|---|---|---|---|
| 26.2 | `replay-adapter:paper-26_2` | `26.2.build.135-stable` | [Paper 26.2 adapter](paper-26.2.md) |

Start a development server of one version with
`runServer -PminecraftVersion=<version>`.

A replay plays only on the Minecraft version it was recorded on: its manifest
names the adapter, the protocol version and the registry fingerprint, and
playback refuses a replay whose adapter does not match.

## What an adapter implements

Each adapter implements the [version contract](../virtual-observer.md#version-contract)
completely and on its own:

- one clientbound definition class for every PLAY clientbound packet and one
  serverbound definition class for every PLAY serverbound packet of its
  version, named by the packet IDs of its version (see
  [packet definition layout](../packet-definition-layout.md));
- the native codec, the reading of the live registries into a
  `ProtocolSnapshot`, the eye and the platform queries of Argus, world
  snapshots, actor effects and the capture, playback and scene hooks;
- its own tests.

Adapters share no code with each other. What is the same for every version
and needs no Minecraft server classes lives in `paper-common` (see
[architecture](../architecture.md#shared-paper-base-and-version-modules)):
the definition base classes and catalogs, `CaptureContexts`, the protocol
snapshot and `PaperPacketRegistry`, Argus, the channel handlers and the
capture, playback and checkpoint bridges.

## Adding a version

1. Create `replay-adapter:paper-<version>` with the version's Paper dev
   bundle and add it to the runtime's dependencies.
2. Implement every packet of the version with its own definition class and
   test, and the hooks of the version contract.
3. Register a `PaperAdapterProvider` whose `minecraftVersion()` returns the
   version, in `META-INF/services`.
4. Write `docs/adapters/paper-<version>.md` with the runtime baseline, the
   differences to the other adapters and the protocol inventory, and add the
   version to the table above.
