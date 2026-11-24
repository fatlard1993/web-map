# Web Map - Development Guide

For what the mod is and how it works, see [README.md](README.md).

## Installation

Install on the server alongside Fabric API (see `fabric.mod.json`); clients need nothing. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and `fabric.mod.json` (Java).

## Building

The village, portal, ship and photo layers, players' faces and the "Show me on maps" setting compile against their mods' jars, so `village-quests`, `player-portals`, `big-boats`, `redstone-photography`, `offline-skins` and `pandorical` must be checked out and built beside this one (`mc-build <mod>`). The build says which is missing if one is.

```
./gradlew build
```

## Testing

`./gradlew build` compiles the gametests; `./gradlew verify` runs them, on a headless test server: the top block of a column is the one drawn, a change reaches its tile and its info picture, the tiles reach down far enough to show everything drawn, map signs are found live and saved, the cave view shows a cave's floor but not a house's, a hidden area is never drawn, positions are shown the delay late, and deaths are kept. They are server gametests, where the rest of the suite has client ones, because a server-only mod never loads in a client.

## Trying it

`./gradlew runServer` starts a server in `run/`. With nobody connected almost no chunks load, so load some from the console, for example `forceload add -100 -100 100 100`; a `fill` high above them shows a change reaching the map within seconds. To try the integrated layers, put the built jars of Pandorical and the four mods above in `run/mods/`.

Open the map through a local web server, since the page fetches its tiles, which a browser refuses from a `file:` address:

```
cd run/web-map && python3 -m http.server 8000
```
