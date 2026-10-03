# MTR-Dynmap Bridge
<img width="100" height="100" alt="MTR-Dynmap-Bridge-1 0 0 0" src="https://github.com/user-attachments/assets/1bf25f7e-1603-4609-805b-0b7992b207e7" />

MTR-Dynmap Bridge is a lightweight bridge between Minecraft Transit Railway (MTR) and Dynmap.

The plugin automatically synchronizes MTR stations and depots with Dynmap and reliably detects the server worlds without hardcoded world names.

<img width="1911" height="837" alt="Screenshot_5" src="https://github.com/user-attachments/assets/2f74f293-d40a-44be-b1c6-6393e85d37b9" />

## Features

- Automatically displays MTR stations and depots on Dynmap
- Automatically detects worlds and dimensions
- Supports multiple MTR worlds and dimensions
- Automatically restores the Y coordinate when determining the surface location
- Station icon: `pin`
- Depot icon: `building`
- First synchronization occurs 5 seconds after server startup
- Further synchronization runs every 60 minutes
- Automatically removes markers when MTR stations or depots are deleted
- No server-specific world names are hardcoded in the plugin

## Requirements

- Minecraft 1.21 / 1.21.4
- **Youer** or another compatible hybrid server that supports **NeoForge mods together with the Bukkit, Spigot, and Paper APIs**
- MTR 4.1.0-beta.2
- Dynmap 3.7-beta8
- Java 21+

The plugin was tested on Minecraft 1.21.1 / 1.21.4.

## Hybrid Servers

To synchronize MTR stations and depots, the server must provide access to the MTR mod alongside a Bukkit-compatible API. This can be achieved using a compatible hybrid server such as **Youer**, which supports NeoForge mods together with the Bukkit, Spigot, and Paper APIs.

The plugin is intended for Minecraft 1.21 and 1.21.4 servers running MTR through Youer or another compatible NeoForge/Bukkit hybrid server.

A standard Bukkit/Paper/Spigot server without the MTR mod may load the plugin, but MTR stations and depots cannot be synchronized without access to the MTR API.

## Installation

1. Install MTR and Dynmap on a compatible hybrid server.
2. Place `MTR-Dynmap-Bridge-1.0.0.1.jar` into the `plugins` folder.
3. Start or restart the server.
4. The plugin will automatically create its configuration.
5. MTR stations and depots will appear on Dynmap after synchronization.

No manual configuration is normally required.

## Configuration

The plugin automatically creates:

`plugins/MTR-Dynmap-Bridge/config.yml`

Example:

```yaml
# MTR-Dynmap Bridge 1.0.0.1

# World mappings are detected automatically from the MTR dimensions on the server.
# You normally do NOT need to edit this file.
# On first synchronization, missing mappings are written here automatically.
# If you have multiple worlds and want to choose a specific one, edit the mapping.
# Example:
# worlds:
#   minecraft/overworld: Survival
#   minecraft/the_nether: Survival_nether
#   minecraft/the_end: Survival_the_end
# Existing entries are preserved unless the configured world no longer exists.
worlds:
  minecraft/overworld: Virus
  minecraft/the_nether: DIM-1
  minecraft/the_end: DIM1
  minecraft/old: old
  minecraft/partygames: PartyGames
```

**Note:** `Virus`, `old`, and `PartyGames` are example world names from the author's server. The plugin automatically detects the worlds available on your server and writes their names to the configuration. No manual changes are normally required.

If multiple possible worlds exist for the same MTR dimension, the mapping can be configured manually in `config.yml`.

## Synchronization

The first synchronization starts approximately 5 seconds after server startup.

After that, MTR synchronization automatically runs every 60 minutes.

The plugin waits for MTR to become available before performing the initial synchronization. If MTR is initialized after Bukkit, the plugin detects it and starts synchronization automatically.

## Dynmap Markers

The plugin creates an MTR marker set in Dynmap.

- **Stations:** `pin` icon
- **Depots:** `building` icon

Markers use the actual Minecraft world coordinates, including a restored Y coordinate when necessary.

## Marker Cleanup

MTR markers managed by MTR-Dynmap Bridge are automatically maintained in Dynmap.

If a station or depot is removed from MTR, its corresponding Dynmap marker will be removed during the next synchronization.

Only markers managed by MTR-Dynmap Bridge are affected. Other Dynmap markers are not removed.

## World Detection

World mappings are detected automatically from the MTR dimensions available on the server.

The plugin does not contain server-specific world names in its Java code.

If multiple worlds can correspond to the same MTR dimension, a specific world can be selected through `config.yml`.

Existing mappings are preserved unless the configured world no longer exists.

## Versioning

The plugin version is controlled from `build.gradle`:

```gradle
version = '1.0.0.1'
```

For example, changing it to:

```gradle
version = '1.0.0.2'
```

will produce:

```text
build/libs/MTR-Dynmap-Bridge-1.0.0.2.jar
```

The same Gradle version is automatically inserted into `plugin.yml`, and the plugin reads its version from the generated plugin description.

This means the version does not need to be manually changed in multiple project files.

When updating the plugin on an existing server, do not replace the existing:

```text
plugins/MTR-Dynmap-Bridge/config.yml
```

The plugin preserves existing world mappings.

## Building

This project uses Gradle and Java 21.

To build the plugin locally:

```bash
gradle clean build
```

The resulting JAR will be created in:

```text
build/libs/
```

The GitHub Actions workflow builds the plugin automatically on pushes and pull requests to `main`, and the resulting JAR is uploaded as a workflow artifact.

## What's New in Version 1.0.0.1

- Fixed Dynmap markers remaining after MTR stations or depots were removed
- Added automatic cleanup of station and depot markers
- Existing station and depot synchronization remains unchanged
- Existing icons continue to use the `pin` and `building` icons
- Added automatic world and dimension detection
- Added automatic Y coordinate restoration when determining the surface location

## License

MTR-Dynmap Bridge is proprietary software.

Copyright © 2026 Igrobar.

Free to use on Minecraft servers.

Redistribution or re-uploading of the plugin without permission is prohibited.
