# Web Map

The world as a map in a web page: top-down, one pixel a block, coloured and shaded the way an in-game map is. Every block that has a map colour shows in its own, including blocks other mods add, so there is no resource pack to keep in step.

It runs on the server and nothing needs to be installed by players.

## How it stays current

- A chunk is drawn when it loads and again whenever something in it changes, a few chunks each tick so no single tick carries the load. A chunk that changes without looking any different, a furnace burning say, is not drawn again.
- A page left open fetches new tiles when the server has drawn something.
- When nothing is loading or changing, nothing is drawn. Player positions and the other layers are still written every few seconds while the server runs.

The world as it was before the map was installed is drawn once, from the saved region files, without loading any of it into the game. It runs at low priority alongside everything else, even while an empty server is paused, picks up where it stopped after a restart, and is not repeated once finished. A large world takes a while: roughly an hour for a million chunks. A chunk drawn live meanwhile is newer than its save, and keeps the live drawing.

The Nether is drawn as a slice a little under its roof, so it shows the floor and its caves rather than the roof. The End is drawn from above, the void left empty.

## Layers

The page can show, over the map:

- **Players**, where everyone online is, every five seconds; spectators and invisible players are left off
- **Homes**, the bed or respawn anchor of everyone who has played, online or not, by name
- **Villages**, by name and size, when Village Quests is installed
- **Spawn**
- **Portal links**, when Player Portals is installed: each tied pair in its own colour, a hub's spokes as dotted lines, and where the far end is when it is in another world
- **Ships**, when Big Boats is installed: where each ship is while somebody is near it, and where it was last seen while nobody is; a ship broken up is let go, and one unseen for 30 days is forgotten
- **Photos**, when Redstone Photography is installed: a pin where each photo was taken, which opens the photo, and tapping it fills the window; photos taken from the same block, as a camera block's are, share one pin and step through newest first; photos taken before this was installed have no recorded place and are not pinned
- **Grid**, region and chunk lines with region numbers, off until a viewer turns it on

Each viewer shows or hides layers in the box at the top right (tap it on a phone), and the page remembers their choice in their browser.

## Settings

`config/web-map-justfatlard.json`, read when the server starts, so a change takes a restart:

- `dimensions`: the dimensions drawn, by id; default the overworld, the Nether and the End. Anything not listed is never written: no tiles, players, homes, ships, photos or portal ends in it. Tiles already drawn for a dimension you take off the list stay in `web-map/tiles/` until you delete its folder.
- `players`, `homes`, `villages`, `spawn`, `portals`, `ships`, `photos`: each layer on or off, all on by default. A layer switched off is never written, so no viewer can see it.
- `backfill`: draw the saved world as described above; on by default. Which regions are done is kept in the world folder, in `web-map/drawn-regions.json`; delete it to draw the saved world again.

If the file cannot be read, nothing is drawn and no layer is offered until it is fixed, rather than every layer being switched back on.

## What it writes

Everything the page needs goes in `web-map/` in the server's folder, and nothing else does:

- `index.html`, `map.js`, `map.css` and `leaflet/`, the page and the map library it runs on, put back at every start
- `tiles/`, the map, in 512-pixel squares at five zooms
- `meta.json`, what the server offers; `players.json` and `markers.json`, the layers; `version.json`, when tiles last changed
- `photos/`, copies of the pinned photos

Where ships were last seen, and which saved regions are drawn, are kept in the world folder, in `web-map/`, not in the served folder.

## Serving it

The folder is plain files; serve it with any web server. The map shows where everyone builds and sleeps, so if it can be reached from outside:

- Put it behind a password, over HTTPS only: a password sent over plain HTTP is readable on the way.
- Serve exactly the `web-map/` folder. The server's folder above it holds `server.properties`, with the RCON password, and `ops.json`.
- Leave directory listing off.

With Caddy, for example:

```
map.example.com {
	root * /path/to/server/web-map
	basic_auth {
		family <hash from `caddy hash-password`>
	}
	header /tiles/* Cache-Control "no-cache"
	file_server
}
```

## Development

Building and trying it are in [DEVELOPMENT.md](DEVELOPMENT.md).
