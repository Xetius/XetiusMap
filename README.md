# XetiusMap

A shared map for Minecraft **26.2** — a minimap overlay and a full-screen world map, in the style of
Xaero's, but with one difference that shapes the whole design: **the map is collective**. A chunk
any player explores becomes visible to everyone, waypoints are server-wide, and the world map shows
live player and mob positions across every dimension.

It ships as two artifacts:

| | |
|---|---|
| **`xetiusmap-fabric`** | Fabric client mod — renders the map, scans chunks, draws the UI |
| **`xetiusmap-paper`** | Paper server plugin — stores the shared map, waypoints, radar and teleports |

The mod works on its own too. Without the plugin (or in single player) it keeps a private local map
in the same on-disk format, so nothing breaks — you simply do not share.

## Features

- **Minimap overlay** with nine anchor positions, adjustable size and pixel offset, square or
  circular shape, north-up or rotate-with-player, optional frame, coordinates, biome name and
  compass letters.
- **World map** (`M`) with drag-to-pan, scroll-to-zoom anchored on the cursor, dimension switching,
  a waypoint side panel, and a live coordinate readout.
- **Zoom** on both views, from 1/8 up to 8 pixels per block.
- **Vanilla-looking terrain** — block map colours with vanilla's north-neighbour relief shading,
  and biome tint applied to grass, foliage and water so swamps and badlands read correctly. A
  "vanilla map" colour mode drops the tinting for the exact in-game-map look.
- **Shared discovery** — explored chunks are uploaded, stored server-side and pushed to everyone
  else watching that area.
- **Global waypoints** — create, edit, recolour, delete; everyone with the mod sees them. Manage
  them from the world map panel or with `/xmap waypoint`.
- **In-world waypoint markers** — waypoints appear at their real position as you look around, with
  name and distance, fading out as you arrive so they stop covering the view.
- **Edge arrows on the minimap** — waypoints and players that have scrolled off the minimap are
  pinned to its rim with an arrow pointing the way, so you can still navigate to them.
- **Teleport to a waypoint**, behind a permission that is granted to nobody by default.
- **Live radar** — all online players across all dimensions, plus mobs within a configurable radius.
- **All dimensions**, including maps of dimensions you are not currently in.

## Requirements

- **Minecraft 26.2**, **Java 25**
- Client: **Fabric Loader 0.19.3+** and **Fabric API 0.157.0+26.2**
- Server: **Paper 26.2** (built against `26.2.build.111-stable`)

## Building

Requires a JDK 25 on `PATH` (or `JAVA_HOME`). The Gradle wrapper handles the rest.

```bash
./gradlew build
```

Artifacts:

- `fabric/build/libs/xetiusmap-fabric-1.0.0.jar` → the client's `mods/` folder
- `paper/build/libs/xetiusmap-paper-1.0.0.jar` → the server's `plugins/` folder

Both jars bundle the shared `:common` module, so there is nothing else to install.

Run the development client with:

```bash
./gradlew :fabric:runClient
# or join a server straight away:
./gradlew :fabric:runClient -PquickPlay=localhost:25565
```

## Default controls

| Key | Action |
|---|---|
| `M` | Open the world map |
| `B` | New waypoint at your position |
| `Page Up` / `Page Down` | Zoom the minimap |
| *(unbound)* | Toggle the minimap |
| *(unbound)* | Open map settings |

In the world map: drag to pan, scroll to zoom, `Tab` cycles dimension, `C` recentres on you, arrow
keys pan, right-click creates a waypoint where you clicked, left-click selects one.

All keys are rebindable through Minecraft's own Controls screen.

## Server commands and permissions

```
/xmap waypoint add <name> [icon] [#rrggbb]
/xmap waypoint remove <name>
/xmap waypoint edit <name> <name|icon|color> <value>
/xmap list [page]
/xmap tp <name>
/xmap hide [on|off]
/xmap stats
/xmap purge <dimension> confirm
/xmap reload
```

| Permission | Default | Meaning |
|---|---|---|
| `xetiusmap.use` | everyone | Connect to the shared map |
| `xetiusmap.upload` | everyone | Contribute explored chunks |
| `xetiusmap.waypoint.create` | everyone | Create waypoints |
| `xetiusmap.waypoint.edit.own` / `.delete.own` | everyone | Manage your own waypoints |
| `xetiusmap.waypoint.edit.other` / `.delete.other` | op | Manage anyone's waypoints |
| **`xetiusmap.teleport`** | **nobody** | Teleport to waypoints — grant this deliberately |
| `xetiusmap.hidden` | nobody | Never appear on other players' maps |
| `xetiusmap.admin` | op | `reload`, `purge`, and bypass the teleport cooldown |

Everything else is in `plugins/XetiusMap/config.yml`: storage location, upload and request rate
limits, radar interval and mob radius, teleport cooldown/warmup/cancel rules, and waypoint limits.

## How it works

The client renders each chunk it sees into a 16×16 grid of colours, heights and water depths,
palettises and deflates it to a few hundred bytes, and offers it to the server. The server validates
the blob, checks it against what it already holds, assigns a revision and pushes it to everyone
watching that region.

Because the client does the colouring, the **plugin never needs to know anything about blocks** — it
stores opaque blobs. That is what keeps it on plain `paper-api` with no NMS and no pinning to an
exact Paper build.

Both sides use the same storage code from `:common`: one file per 512×512 region, with a slot table
of `(revision, offset, length, hash)` and blobs appended on write. The server's copy lives in
`plugins/XetiusMap/mapdata/<dimension>/`, the client's cache in
`.minecraft/xetiusmap/<server>/map/<dimension>/`.

Everything travels over one plugin-messaging channel, `xetiusmap:main`. Fabric's custom payloads and
Bukkit's `Messenger` are the same vanilla packet underneath, so no bridge is needed; `:common`
fragments anything larger than a single plugin message and reassembles it on the other side.

### Trade-off worth knowing

Client-rendered tiles mean a modified client could upload misleading imagery. That is the price of
keeping the plugin free of NMS. It is bounded by per-player rate limits, a size cap, structural
validation of every blob, a check that the uploader is actually in the dimension they claim, and
`/xmap purge` to wipe a dimension and start again.

## Project layout

```
common/   protocol, tile codec, region storage, waypoint model — no Minecraft or Bukkit
fabric/   client mod: scanner, colouriser, raster cache, minimap, world map, screens
paper/    server plugin: tile service, waypoints, radar, teleports, commands
```

## Licence

MIT.
