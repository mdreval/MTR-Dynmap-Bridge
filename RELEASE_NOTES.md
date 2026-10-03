# MTR-Dynmap-Bridge 1.0.0.1

## What's New in 1.0.0.1

### Fixed stale Dynmap markers

- Fixed an issue where station and depot markers remained on Dynmap after the corresponding MTR station or depot was deleted.
- The bridge now automatically removes outdated MTR station/depot markers during synchronization.
- Existing station and depot markers continue to update their position and icon correctly.
- Cleanup is limited to markers managed by MTR-Dynmap Bridge and does not affect unrelated Dynmap markers.

## Features

- Universal MTR 4.x station/depot bridge for Dynmap.
- Automatic world detection.
- Optional world mappings.
- Automatic hourly synchronization.
- Station markers on Dynmap.
- Depot markers on Dynmap.
- Automatic cleanup of stale markers.
- Support for multiple worlds.
- Java 21 support.

## Requirements

- Minecraft 1.21 / 1.21.4
- Java 21+
- MTR 4.1.0-beta.2
- Dynmap 3.7-beta8
- Youer or a compatible hybrid server supporting NeoForge + Bukkit/Spigot/Paper

## Installation

1. Install MTR and Dynmap on your server.
2. Place `MTR-Dynmap-Bridge-1.0.0.1.jar` into the server's `plugins` folder.
3. Start or restart the server.
4. The plugin will automatically detect MTR dimensions and available Bukkit worlds.
5. World mappings can be adjusted in `plugins/MTR-Dynmap-Bridge/config.yml` if needed.

## Synchronization

The plugin checks for MTR initialization after startup and then synchronizes MTR stations and depots with Dynmap automatically.

Synchronization is performed hourly after the initial synchronization.

During synchronization, existing managed markers are updated and stale station/depot markers are removed.

## Dynmap Markers

- Stations use the `pin` marker icon.
- Depots use the `building` marker icon.
- A fallback marker icon is used if the requested icon is unavailable.
- Stale markers are removed automatically.
- Cleanup only affects markers managed by MTR-Dynmap Bridge.

## World Detection

World mappings are detected automatically from the MTR dimensions on the server.

If multiple worlds exist, mappings can be overridden in `config.yml`.

## License

Proprietary.

Copyright © 2026 Igrobar.

Free to use on Minecraft servers. Redistribution or re-upload of the plugin is prohibited.
