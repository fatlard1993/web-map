# Web Map

The world as a map in a web page: top-down, one pixel a block, coloured and shaded the way an in-game map is. Every block that has a map colour shows in its own, including blocks other mods add, so there is no resource pack to keep in step.

It runs on the server and nothing needs to be installed by players.

## How it stays current

- A chunk is drawn when it loads and again whenever something in it changes, a few chunks each tick so no single tick carries the load. A chunk that changes without looking any different, a furnace burning say, is not drawn again.
- A page left open fetches new tiles when the server has drawn something.
- When nothing is loading or changing, nothing is drawn. Player positions and the other layers are still written every few seconds while the server runs.

The world as it was before the map was installed is drawn once (and once more when an update adds to what each chunk holds), from the saved region files, without loading any of it into the game. It runs at low priority alongside everything else, even while an empty server is paused, picks up where it stopped after a restart, and is not repeated once finished. A large world takes a while: roughly an hour for a million chunks. A chunk drawn live meanwhile is newer than its save, and keeps the live drawing.

The Nether is drawn as a slice a little under its roof, so it shows the floor and its caves rather than the roof. The End is drawn from above, the void left empty.

## Layers

The page can show, over the map:

- **Players**, where everyone online was 30 seconds ago, updated every five seconds and gliding between, shown by their face when Offline Skins is installed; spectators, invisible players, anyone sneaking and anyone more than 8 blocks underground are left off
- **Homes**, the bed or respawn anchor of everyone who has played, online or not, by name; with Pandorical installed, a player who turns off "Show me on maps" on its page of their settings is left off both of these
- **Villages**, by name and size, when Village Quests is installed
- **Spawn**
- **Portal links**, when Player Portals is installed: each tied pair in its own colour, a hub's spokes as dotted lines, and where the far end is when it is in another world; clicking a portal shows its other end, switching dimension if need be
- **Ships**, when Big Boats is installed: where each ship is while somebody is near it, and where it was last seen while nobody is; a ship broken up is let go, and one unseen for 30 days is forgotten
- **Photos**, when Redstone Photography is installed: a pin where each photo was taken, which opens the photo, and tapping it fills the window; photos taken from the same block, as a camera block's are, share one pin and step through newest first; photos taken before this was installed have no recorded place and are not pinned
- **Signs**: a sign whose first line is `[map]` is a marker, named by its other lines
- **Beacons**, every beacon with its name and effects, and the box its effects reach while it is lit
- **Deaths**, each player's last three, for 30 days, with the death message and height
- **World border**
- **Day and night**: the overworld darkens at night, with the time of day beside the coordinates
- **Caves**, off until a viewer turns it on: the overworld's caves instead of its surface, the floor of the first opening under each spot, darker the deeper it is
- **Grid**, region and chunk lines with region numbers, off until a viewer turns it on

Each viewer shows or hides layers in the box at the top right (tap it on a phone), and the page remembers their choice in their browser.

Clicking the map says where that spot is, its biome, what it is made of and how high it is, with buttons to copy its coordinates, a `/tp` command or a link to it, and the matching spot in the Nether or overworld. The address bar always holds the dimension and view, so a link sent to someone opens on the same spot. The search box finds players, homes, villages, portals, ships, signs, beacons and deaths by name in any dimension, or goes to typed coordinates; the ruler measures a distance along points clicked in turn, with how long it takes to walk and sprint. Clicking a portal shows its other end.

Labels that would overlap gather into one list at the first of them, the rest keeping their dots; clicking a name in it does what clicking that label would, and clicking elsewhere in it zooms in until they spread apart. The map zooms out as far as it takes to see everything drawn, and the button under the zoom buttons shows all of it at once.

## Settings

`config/web-map-justfatlard.json`, read when the server starts, so a change takes a restart:

- `dimensions`: the dimensions drawn, by id; default the overworld, the Nether and the End. Anything not listed is never written: no tiles, players, homes, ships, photos or portal ends in it. Tiles already drawn for a dimension you take off the list stay in `web-map/tiles/` until you delete its folder.
- `players`, `homes`, `villages`, `spawn`, `portals`, `ships`, `photos`: each layer on or off, all on by default. A layer switched off is never written, so no viewer can see it.
- `caves`: the dimensions whose caves are drawn too, by id; default the overworld.
- `signs`, `beacons`, `deaths`, `border`, `daylight`: these layers on or off, all on by default.
- `hideSneaking`, `hideUnderground`: leave players off the map while they sneak, or while they are more than 8 blocks under the surface (never in the Nether, whose roof would hide everyone); both on by default.
- `playerDelaySeconds`: how late players' positions are shown, so the map cannot be used to chase anyone; 30 by default, 0 for none.
- `hiddenAreas`: areas never drawn and with no markers, kept by the commands below.
- `backfill`: draw the saved world as described above; on by default. Which regions are done is kept in the world folder, in `web-map/drawn-regions.json`; delete it to draw the saved world again.

## Hiding an area

An op can hide an area, a secret base say, with `/webmap hide <name> <radius>` where they stand. Nothing in it is drawn, what was drawn there is blanked, and no marker in it is listed. It is hidden in a shape made to look like map nobody has explored yet, ragged and lumpy and not centred on the spot, rather than as a box or a blur that would say something is there. `/webmap hidden` lists the hidden areas and `/webmap unhide <name>` shows one again, drawn as its chunks load or change.

If the file cannot be read, nothing is drawn and no layer is offered until it is fixed, rather than every layer being switched back on.

## What it writes

Everything the page needs goes in `web-map/` in the server's folder, and nothing else does:

- `index.html`, `map.js`, `map.css` and `leaflet/`, the page and the map library it runs on, put back at every start
- `tiles/`, the map, in 512-pixel squares, from a pixel a block out to as far as it takes for everything drawn to fit in four; the caves are beside each dimension's, as `<dimension>.caves`
- `info/`, what is in each column, for the page to say what a clicked spot is, and `info/palette.json`, the names its numbers stand for
- `heads/`, players' faces
- `meta.json`, what the server offers; `players.json` and `markers.json`, the layers; `world.json`, the time of day and world borders; `version.json`, when tiles last changed and how far each dimension's reach
- `photos/`, copies of the pinned photos

Where ships were last seen and players died, the map signs and beacons, the info palette, and which saved regions are drawn are kept in the world folder, in `web-map/`, not in the served folder.

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
