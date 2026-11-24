'use strict';

// One map unit is one block, and at zoom 4 a pixel is a block: the tiles' native zoom. Past it the
// tiles are enlarged, up to 16 pixels a block. Below 0 the server draws as far out as the world
// reaches, and the page lets you out one step past the whole of it.
const NATIVE = 4;
const MOST = NATIVE + 4;
const FARTHEST = -32;
const crs = L.extend({}, L.CRS.Simple, { transformation: new L.Transformation(1 / 16, 0, 1 / 16, 0) });
const map = L.map('map', { crs, minZoom: 0, maxZoom: MOST, zoomSnap: 1, attributionControl: false });
map.setView([0, 0], NATIVE);

const TITLES = { players: 'Players', homes: 'Homes', villages: 'Villages', spawn: 'Spawn',
	portals: 'Portal links', ships: 'Ships', photos: 'Photos', grid: 'Grid' };
const DIMENSION_TITLES = { minecraft_overworld: 'Overworld', minecraft_the_nether: 'Nether', minecraft_the_end: 'End' };
const SHOWN_BY_DEFAULT = { grid: false };
const MARKER_LAYERS = ['homes', 'villages', 'spawn', 'portals', 'ships', 'photos'];
const touch = matchMedia('(hover: none)').matches;
// A pinch anywhere zooms the map, never the page: Safari on iOS ignores touch-action for pinches, so
// its own gesture events are refused as well.
document.addEventListener('gesturestart', e => e.preventDefault(), { passive: false });
const narrow = matchMedia('(max-width: 600px)').matches;

// Latitude is z and longitude is x, so north stays up as it does on an in-game map.
const at = (x, z) => [z + 0.5, x + 0.5];
const dot = kind => L.divIcon({ className: 'label ' + kind, html: '', iconSize: null });
const titleOf = folder => DIMENSION_TITLES[folder] || folder;

// Labels are set as text, never as HTML: player, portal and ship names are whatever players typed.
// What a click does, if anything, is act: also run from the label's line when it is stacked.
function named(marker, text, act = null) {
	marker.label = { text, kind: marker.options.icon.options.className.replace(/^label /, ''), act };
	marker.options.baseZ = marker.options.zIndexOffset;
	if (act) marker.on('click', act);
	marker.on('add', () => {
		labelled.add(marker);
		marker.shownAs = null;
		marker.getElement().textContent = text;
		if (act) marker.getElement().classList.add('acts');
		declutterSoon();
	});
	marker.on('remove', () => { labelled.delete(marker); declutterSoon(); });
	return marker;
}

const kept = {
	get(key, fallback) { try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch (e) { return fallback; } },
	set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* not kept */ } }
};

async function json(name) {
	const response = await fetch(name + '?' + Date.now());
	return response.ok ? response.json() : null;
}

// --- Where you are looking ---

const where = document.getElementById('where');
const showWhere = latlng => { where.textContent = 'x ' + Math.floor(latlng.lng) + ', z ' + Math.floor(latlng.lat); };
if (touch) {
	document.getElementById('crosshair').hidden = false;
	map.on('move', () => showWhere(map.getCenter()));
	showWhere(map.getCenter());
} else {
	map.on('mousemove', e => showWhere(e.latlng));
}

const hint = document.getElementById('hint');
if (!kept.get('web-map hint dismissed', false)) hint.hidden = false;
document.getElementById('hint-close').addEventListener('click', () => { hint.hidden = true; kept.set('web-map hint dismissed', true); });

// --- Dimensions and tiles ---

let dimension = 'minecraft_overworld';
let tilesStamp = 0;
let extents = {};
const tileLayers = {};
const tileUrl = folder => 'tiles/' + folder + '/{z}/{x}_{y}.png?v=' + tilesStamp;
function tilesFor(folder) {
	const layer = L.tileLayer(tileUrl(folder), {
		tileSize: 512, minZoom: FARTHEST, maxZoom: MOST, minNativeZoom: 0, maxNativeZoom: NATIVE, noWrap: true, className: 'pixelated'
	});
	layer.folder = folder;
	tileLayers[folder] = layer;
	return layer;
}

// Tiles are cached like any image, so the page asks again only when the server says it drew something.
async function refreshTiles() {
	const version = await json('version.json').catch(() => null);
	if (!version || !version.tiles || version.tiles === tilesStamp) return;
	tilesStamp = version.tiles;
	extents = version.extents || {};
	for (const folder in tileLayers) {
		// Below its floor a dimension has no tiles of its own, so the floor's are shrunk instead
		const floor = extents[folder] ? extents[folder].floor : 0;
		tileLayers[folder].options.minNativeZoom = floor;
		tileLayers[folder].setUrl(tileUrl(folder));
	}
	fitZoomOut();
}

const drawnBounds = () => {
	const e = extents[dimension];
	return e && L.latLngBounds([e.z0, e.x0], [e.z1, e.x1]);
};

// However big the drawn world, zooming out stops one step past seeing all of it.
function fitZoomOut() {
	const bounds = drawnBounds();
	// The zoom that fits is held to the current least, so lift that first
	map.options.minZoom = FARTHEST;
	map.setMinZoom(bounds ? Math.min(0, map.getBoundsZoom(bounds) - 1) : 0);
}

const WholeMap = L.Control.extend({
	options: { position: 'topleft' },
	onAdd() {
		const bar = L.DomUtil.create('div', 'leaflet-bar');
		const button = L.DomUtil.create('a', 'whole-map', bar);
		button.href = '#';
		button.title = 'Show the whole map';
		button.setAttribute('role', 'button');
		button.setAttribute('aria-label', 'Show the whole map');
		L.DomEvent.disableClickPropagation(bar);
		L.DomEvent.on(button, 'click', e => {
			L.DomEvent.preventDefault(e);
			const bounds = drawnBounds();
			if (bounds) map.fitBounds(bounds);
		});
		return bar;
	}
});
new WholeMap().addTo(map);

// --- Layers, shown or hidden per viewer; the server decides which exist at all ---

const shown = kept.get('web-map layers', {});
const showing = key => key in shown ? shown[key] : (SHOWN_BY_DEFAULT[key] ?? true);
const layers = {};
const control = L.control.layers(null, null, { collapsed: touch || narrow, position: 'topright' }).addTo(map);
map.on('overlayadd', e => { shown[e.layer.key] = true; kept.set('web-map layers', shown); });
map.on('overlayremove', e => { shown[e.layer.key] = false; kept.set('web-map layers', shown); });
map.on('baselayerchange', e => {
	dimension = e.layer.folder;
	kept.set('web-map dimension', dimension);
	fitZoomOut();
	refreshMarkers();
	refreshPlayers();
});

function offer(key, layer = L.layerGroup()) {
	layer.key = key;
	layers[key] = layer;
	control.addOverlay(layer, TITLES[key]);
	if (showing(key)) layer.addTo(map);
}

// Chunk lines once a chunk is at least eight pixels across, region numbers once a region is at least
// 96 pixels across, and region lines always, though far enough out only every second, fourth, and so
// on, keeping them at least 24 pixels apart.
const CHUNK_LINES_FROM = 8;
const REGION_NUMBERS_FROM = 96;
const LINES_APART = 24;
const Grid = L.GridLayer.extend({
	createTile(coords) {
		const tile = document.createElement('canvas');
		const size = this.getTileSize();
		tile.width = size.x;
		tile.height = size.y;
		const context = tile.getContext('2d');
		const blocks = size.x * 16 / Math.pow(2, coords.z);
		const scale = size.x / blocks;
		const x0 = coords.x * blocks, z0 = coords.y * blocks;
		const lines = (step, style) => {
			context.strokeStyle = style;
			context.beginPath();
			for (let b = Math.ceil(x0 / step) * step; b < x0 + blocks; b += step) {
				const px = Math.round((b - x0) * scale) + 0.5;
				context.moveTo(px, 0); context.lineTo(px, size.y);
			}
			for (let b = Math.ceil(z0 / step) * step; b < z0 + blocks; b += step) {
				const pz = Math.round((b - z0) * scale) + 0.5;
				context.moveTo(0, pz); context.lineTo(size.x, pz);
			}
			context.stroke();
		};
		if (16 * scale >= CHUNK_LINES_FROM) lines(16, 'rgba(255, 255, 255, 0.18)');
		let regionLines = 512;
		while (regionLines * scale < LINES_APART) regionLines *= 2;
		lines(regionLines, 'rgba(255, 255, 255, 0.55)');
		if (512 * scale >= REGION_NUMBERS_FROM) {
			context.font = '11px ui-monospace, monospace';
			context.fillStyle = 'rgba(255, 255, 255, 0.8)';
			context.shadowColor = '#000';
			context.shadowBlur = 2;
			for (let bx = Math.ceil(x0 / 512) * 512; bx < x0 + blocks; bx += 512) {
				for (let bz = Math.ceil(z0 / 512) * 512; bz < z0 + blocks; bz += 512) {
					context.fillText('r ' + bx / 512 + ', ' + bz / 512, (bx - x0) * scale + 4, (bz - z0) * scale + 13);
				}
			}
		}
		return tile;
	}
});

// --- Labels that would overlap share one stack ---

// Labels whose boxes touch are gathered under the first of them by rank, which lists them all; the
// rest keep only their dots, so where each one is stays visible. A stack shows a few names and how
// many more, unless the map is in as far as it goes. Its lines act as their labels would, and any
// other line zooms in on the stack, which spreads it apart.
const labelled = new Set();
const RANK = ['spawn', 'player', 'village', 'portal', 'ship', 'home', 'photo'];
const LINE = 17;
const STACK_SHOWS = 5;
const measure = document.createElement('canvas').getContext('2d');
const widths = new Map();
const rankOf = item => { const r = RANK.indexOf(item.marker.label.kind.split(' ')[0]); return r < 0 ? RANK.length : r; };

function widthOf(label) {
	const key = label.kind + '|' + label.text;
	if (!widths.has(key)) {
		measure.font = (label.kind === 'village' ? '600 ' : '') + '12px system-ui, sans-serif';
		widths.set(key, Math.ceil(measure.measureText(label.text).width) + 12);
		if (widths.size > 5000) widths.clear();
	}
	return widths.get(key);
}

const stackShows = members => map.getZoom() >= MOST || members.length <= STACK_SHOWS ? members.length : STACK_SHOWS - 1;
const moreText = hidden => '+' + hidden + ' more';

// The dot sits on its block, with the text, or the stack's lines, running right and down from it.
function boxOf(group) {
	const shows = stackShows(group.members);
	let width = 0;
	for (let i = 0; i < shows; i++) width = Math.max(width, group.members[i].width);
	if (shows < group.members.length) width = Math.max(width, widthOf({ kind: '', text: moreText(group.members.length - shows) }));
	const lines = shows + (shows < group.members.length ? 1 : 0);
	const p = group.members[0].point;
	return { left: p.x - 6, top: p.y - LINE / 2, right: p.x - 4 + width + 2, bottom: p.y - LINE / 2 + lines * LINE };
}

const overlap = (a, b) => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;

let declutterPending = false;
function declutterSoon() {
	if (declutterPending) return;
	declutterPending = true;
	requestAnimationFrame(() => { declutterPending = false; declutter(); });
}

function declutter() {
	const zoom = map.getZoom();
	const items = [];
	for (const marker of labelled) {
		if (map.hasLayer(marker)) items.push({ marker, point: map.project(marker.getLatLng(), zoom), width: widthOf(marker.label) });
	}
	items.sort((a, b) => rankOf(a) - rankOf(b) || a.marker.label.text.localeCompare(b.marker.label.text));
	let groups = items.map(item => ({ members: [item] }));
	// A stack grows down as it gathers, and may come to cover labels it missed, so go round again until none touch
	for (let merged = true; merged;) {
		merged = false;
		const placed = [];
		for (const group of groups) {
			const box = boxOf(group);
			const into = placed.find(other => overlap(boxOf(other), box));
			if (into) { into.members.push(...group.members); merged = true; } else placed.push(group);
		}
		groups = placed;
	}
	for (const group of groups) {
		const [first, ...rest] = group.members.map(item => item.marker);
		showStack(first, group.members.length > 1 ? group.members.map(item => item.marker) : null);
		for (const marker of rest) showMuted(marker);
	}
}

function showMuted(marker) {
	if (marker.shownAs === 'muted') return;
	marker.shownAs = 'muted';
	const element = marker.getElement();
	element.classList.remove('stack');
	element.classList.add('muted');
	element.textContent = '';
	element.title = marker.label.text;
	marker.setZIndexOffset(marker.options.baseZ);
}

function showStack(marker, members) {
	const element = marker.getElement();
	element.title = marker.options.title;
	if (!members) {
		if (marker.shownAs === 'alone') return;
		marker.shownAs = 'alone';
		element.classList.remove('stack', 'muted');
		element.textContent = marker.label.text;
		marker.setZIndexOffset(marker.options.baseZ);
		return;
	}
	const shows = stackShows(members);
	const shownAs = members.slice(0, shows).map(m => L.stamp(m)).join(',') + '/' + members.length;
	if (marker.shownAs === shownAs) return;
	marker.shownAs = shownAs;
	element.classList.remove('muted');
	element.classList.add('stack');
	element.textContent = '';
	const spread = () => {
		const bounds = L.latLngBounds(members.map(m => m.getLatLng()));
		const target = Math.min(MOST, Math.max(map.getZoom() + 1, map.getBoundsZoom(bounds, false, L.point(80, 80))));
		map.setView(bounds.getCenter(), target);
	};
	const line = (kind, text, onClick, style) => {
		const div = document.createElement('div');
		div.className = 'line ' + kind;
		div.textContent = text;
		if (kind.includes('portal') && kind.includes('acts')) div.title = 'Go to the other end';
		if (style) div.style.setProperty('--portal', style);
		L.DomEvent.on(div, 'click', e => { L.DomEvent.stop(e); onClick(); });
		// Only the double click stops here: a touch starting on a line may be one finger of a pinch, which the map must see
		L.DomEvent.on(div, 'dblclick', L.DomEvent.stopPropagation);
		element.append(div);
	};
	for (const m of members.slice(0, shows)) {
		line(m.label.kind + (m.label.act ? ' acts' : ''), m.label.text, m.label.act || spread, m.label.portal);
	}
	if (shows < members.length) line('more', moreText(members.length - shows), spread);
	marker.setZIndexOffset(3000);
}

map.on('zoomend', declutterSoon);

// --- Portals lead somewhere: clicking one end shows the other, in its own dimension if need be ---

// Answers whether the dimension is drawn, and so whether the map is now showing it.
function showDimension(folder) {
	if (folder === dimension) return true;
	if (!tileLayers[folder]) return false;
	map.removeLayer(tileLayers[dimension]);
	// The layers control hears this and switches everything else over
	tileLayers[folder].addTo(map);
	return true;
}

function travel(end) {
	if (showDimension(end.dimension)) map.setView(at(end.x, end.z), Math.max(map.getZoom(), NATIVE - 1));
}

// --- Markers ---

const here = list => (list || []).filter(item => item.dimension === dimension);
const PHOTO_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const photoCaption = photo => [photo.by ? 'By ' + photo.by : '', photo.taken ? new Date(photo.taken).toLocaleString() : '']
	.filter(Boolean).join(', ');

// The photo filling the window, over the map. A spot with several photos steps through them with the
// arrows or the arrow keys; a tap elsewhere, the close button or Escape puts it away.
const viewer = document.getElementById('viewer');
const viewerImage = document.getElementById('viewer-image');
const viewerPrevious = document.getElementById('viewer-previous');
const viewerNext = document.getElementById('viewer-next');
let viewing = null;
let viewerOpener = null;

function counted(photos, index) {
	const caption = photoCaption(photos[index]);
	return photos.length > 1 ? (index + 1) + ' of ' + photos.length + (caption ? ': ' + caption : '') : caption;
}

function showInViewer() {
	const { photos, index } = viewing;
	viewerImage.src = 'photos/' + photos[index].id + '.png';
	document.getElementById('viewer-caption').textContent = counted(photos, index);
	viewerPrevious.hidden = viewerNext.hidden = photos.length < 2;
}

function enlarge(photos, index) {
	viewing = { photos, index };
	showInViewer();
	viewerOpener = document.activeElement;
	viewer.hidden = false;
	document.getElementById('viewer-close').focus();
}

function stepViewer(by) {
	if (!viewing || viewing.photos.length < 2) return;
	viewing.index = (viewing.index + by + viewing.photos.length) % viewing.photos.length;
	showInViewer();
}

function closeViewer() {
	if (viewer.hidden) return;
	viewer.hidden = true;
	viewing = null;
	viewerImage.removeAttribute('src');
	if (viewerOpener && viewerOpener.focus) viewerOpener.focus();
}

viewer.addEventListener('click', closeViewer);
viewerPrevious.addEventListener('click', e => { e.stopPropagation(); stepViewer(-1); });
viewerNext.addEventListener('click', e => { e.stopPropagation(); stepViewer(1); });
viewerImage.addEventListener('click', e => { if (viewing && viewing.photos.length > 1) { e.stopPropagation(); stepViewer(1); } });
document.addEventListener('keydown', e => {
	if (viewer.hidden) return;
	if (e.key === 'Escape') closeViewer();
	else if (e.key === 'ArrowLeft') stepViewer(-1);
	else if (e.key === 'ArrowRight') stepViewer(1);
});

// Photos taken from the same block, as a camera block's always are, share one pin, newest first.
function photoView(photos) {
	let index = 0;
	const view = document.createElement('div');
	view.className = 'photo-view';
	const image = document.createElement('img');
	image.alt = 'Photo';
	image.addEventListener('click', () => enlarge(photos, index));
	const caption = document.createElement('p');
	const show = () => {
		image.src = 'photos/' + photos[index].id + '.png';
		caption.textContent = counted(photos, index);
	};
	const button = (text, label, onClick) => {
		const b = document.createElement('button');
		b.type = 'button';
		b.textContent = text;
		if (label) b.setAttribute('aria-label', label);
		b.addEventListener('click', onClick);
		return b;
	};
	const steps = document.createElement('div');
	steps.className = 'photo-steps';
	if (photos.length > 1) {
		steps.append(
			button('\u2039', 'Previous photo', () => { index = (index - 1 + photos.length) % photos.length; show(); }),
			button('\u203a', 'Next photo', () => { index = (index + 1) % photos.length; show(); }));
	}
	steps.append(button('View larger', null, () => enlarge(photos, index)));
	show();
	view.append(image, caption, steps);
	return view;
}

async function refreshMarkers() {
	if (!MARKER_LAYERS.some(key => layers[key])) return;
	const markers = await json('markers.json').catch(() => null);
	if (!markers) return;
	lastMarkers = markers;
	for (const key of MARKER_LAYERS) if (layers[key]) layers[key].clearLayers();

	if (layers.homes) {
		for (const h of here(markers.homes)) named(L.marker(at(h.x, h.z), { icon: dot('home') }), h.name + "'s home").addTo(layers.homes);
	}
	if (layers.villages) {
		for (const v of here(markers.villages)) {
			if (v.radius > 0) {
				L.circle(at(v.x, v.z), { radius: v.radius, color: '#7bd389', weight: 1, fillOpacity: 0.08, interactive: false }).addTo(layers.villages);
			}
			named(L.marker(at(v.x, v.z), { icon: dot('village') }), v.name || 'Village').addTo(layers.villages);
		}
	}
	if (layers.spawn && markers.spawn && markers.spawn.dimension === dimension) {
		named(L.marker(at(markers.spawn.x, markers.spawn.z), { icon: dot('spawn') }), 'Spawn').addTo(layers.spawn);
	}
	if (layers.portals) {
		for (const link of markers.portals || []) {
			const name = link.name || 'Portal';
			const color = link.color || '#b06cff';
			const ends = [link.from, link.to].filter(end => end.dimension === dimension);
			if (ends.length === 2) {
				L.polyline(ends.map(end => at(end.x, end.z)),
					{ color, weight: 2, opacity: 0.8, dashArray: link.bothWays ? '6 6' : '2 6', interactive: false }).addTo(layers.portals);
			}
			for (const end of ends) {
				const other = end === link.from ? link.to : link.from;
				const away = other.elsewhere ? 'elsewhere' : titleOf(other.dimension) + ' ' + other.x + ', ' + other.z;
				const leadsAway = ends.length === 2 ? name : name + ' → ' + away;
				const goes = !other.elsewhere && tileLayers[other.dimension] ? () => travel(other) : null;
				const marker = named(L.marker(at(end.x, end.z), { icon: dot('portal'), title: goes ? 'Go to the other end' : '' }), leadsAway, goes);
				marker.label.portal = color;
				marker.on('add', () => marker.getElement().style.setProperty('--portal', color));
				marker.addTo(layers.portals);
			}
		}
	}
	if (layers.ships) {
		for (const ship of here(markers.ships)) {
			const text = (ship.name || 'A ship') + (ship.live ? '' : ' (last seen)');
			named(L.marker(at(ship.x, ship.z), { icon: dot(ship.live ? 'ship' : 'ship away') }), text).addTo(layers.ships);
		}
	}
	if (layers.photos) {
		const spots = new Map();
		for (const photo of here(markers.photos)) {
			if (!PHOTO_ID.test(photo.id)) continue;
			const key = photo.x + ',' + photo.z;
			if (!spots.has(key)) spots.set(key, []);
			spots.get(key).push(photo);
		}
		for (const photos of spots.values()) {
			photos.sort((a, b) => (b.taken || 0) - (a.taken || 0));
			const first = photos[0];
			const text = photos.length > 1 ? photos.length + ' photos' : (first.by ? 'Photo by ' + first.by : 'Photo');
			const marker = named(L.marker(at(first.x, first.z), { icon: dot('photo') }), text);
			marker.bindPopup(() => photoView(photos), { maxWidth: 300 });
			marker.label.act = () => marker.openPopup();
			marker.addTo(layers.photos);
		}
	}
}

let lastMarkers = {};
let lastPlayers = [];
const playerMarkers = new Map();
// A player glides from one place to the next over the five seconds between them, unless they went
// farther than anyone walks in that time, a teleport, or the map is mid-zoom, when gliding would drift.
const GLIDES_UP_TO = 96;
let zooming = false;
map.on('zoomstart', () => {
	zooming = true;
	for (const marker of playerMarkers.values()) marker.getElement()?.classList.remove('glide');
});
map.on('zoomend', () => { zooming = false; });
const elsewhere = document.getElementById('elsewhere');
async function refreshPlayers() {
	if (!layers.players) return;
	const players = await json('players.json').catch(() => null);
	if (!players) return;
	lastPlayers = players;
	const seen = new Set();
	for (const p of here(players)) {
		seen.add(p.name);
		let marker = playerMarkers.get(p.name);
		if (!marker) {
			marker = named(L.marker(at(p.x, p.z), { icon: dot('player'), zIndexOffset: 1000 }), p.name);
			playerMarkers.set(p.name, marker);
			marker.addTo(layers.players);
		}
		const from = marker.getLatLng(), to = at(p.x, p.z);
		marker.getElement()?.classList.toggle('glide', !zooming && Math.hypot(from.lat - to[0], from.lng - to[1]) <= GLIDES_UP_TO);
		marker.setLatLng(to);
	}
	declutterSoon();
	for (const [name, marker] of playerMarkers) {
		if (!seen.has(name)) { layers.players.removeLayer(marker); playerMarkers.delete(name); }
	}
	const away = players.filter(p => p.dimension !== dimension);
	elsewhere.hidden = away.length === 0 || !map.hasLayer(layers.players);
	elsewhere.textContent = 'Elsewhere: ' + away.map(p => p.name + ' (' + titleOf(p.dimension) + ')').join(', ');
}

// --- Links: the address holds the dimension and view, so a spot can be sent to someone ---

const linkTo = (folder, x, z, zoom) => location.pathname + location.search + '#' + [folder, x, z, zoom].join('/');
const LINK = /^#([a-z0-9_.-]+)\/(-?\d+)\/(-?\d+)\/(-?\d+)$/;

function followLink() {
	const link = LINK.exec(location.hash);
	if (!link) return false;
	showDimension(link[1]);
	// The least zoom is fitted only once the tiles' reach is known, after this
	map.setMinZoom(Math.min(map.getMinZoom(), +link[4]));
	map.setView(at(+link[2], +link[3]), Math.max(map.getMinZoom(), Math.min(MOST, +link[4])), { animate: false });
	return true;
}

function keepLink() {
	const c = map.getCenter();
	history.replaceState(null, '', linkTo(dimension, Math.floor(c.lng), Math.floor(c.lat), map.getZoom()));
}

async function copy(text, button) {
	try {
		await navigator.clipboard.writeText(text);
		const was = button.textContent;
		button.textContent = 'Copied';
		setTimeout(() => { button.textContent = was; }, 1200);
	} catch (e) {
		prompt('Copy this:', text);
	}
}

// --- A spot: clicked, it says where it is, and what is there as far as the map knows ---

// What is at a spot comes from the info pictures beside the tiles: two pixels a column, the first the
// top block's palette number shifted up 12 bits and the biome's, the second the ground's under it and
// the height plus 2048. Number 0 is nothing.
const AROUND = 8;
const infoPictures = new Map();
let paletteFetch = null, paletteStamp = null;

const prettyName = id => id.replace(/^[^:]*:/, '').split(/[_/]/).filter(Boolean).map(w => w[0].toUpperCase() + w.slice(1)).join(' ');

function palette() {
	if (!paletteFetch || paletteStamp !== tilesStamp) {
		paletteStamp = tilesStamp;
		paletteFetch = json('info/palette.json').catch(() => null);
	}
	return paletteFetch;
}

async function readPicture(url) {
	const response = await fetch(url);
	if (!response.ok) return null;
	const bitmap = await createImageBitmap(await response.blob(), { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
	const canvas = document.createElement('canvas');
	canvas.width = bitmap.width;
	canvas.height = bitmap.height;
	const context = canvas.getContext('2d', { willReadFrequently: true });
	context.drawImage(bitmap, 0, 0);
	return { width: bitmap.width, data: context.getImageData(0, 0, bitmap.width, bitmap.height).data };
}

function infoPicture(folder, rx, rz) {
	const url = 'info/' + folder + '/' + rx + '_' + rz + '.png?v=' + tilesStamp;
	if (!infoPictures.has(url)) {
		if (infoPictures.size >= 6) infoPictures.delete(infoPictures.keys().next().value);
		infoPictures.set(url, readPicture(url).catch(() => null));
	}
	return infoPictures.get(url);
}

async function columnAt(folder, x, z) {
	const picture = await infoPicture(folder, Math.floor(x / 512), Math.floor(z / 512));
	if (!picture) return null;
	const i = ((((z % 512) + 512) % 512) * picture.width + (((x % 512) + 512) % 512) * 2) * 4;
	const d = picture.data;
	const first = d[i] << 16 | d[i + 1] << 8 | d[i + 2], second = d[i + 4] << 16 | d[i + 5] << 8 | d[i + 6];
	if (!(first >> 12)) return null;
	return { top: first >> 12, biome: first & 0xFFF, ground: second >> 12, y: (second & 0xFFF) - 2048 };
}

const shares = counts => {
	const total = [...counts.values()].reduce((a, b) => a + b, 0);
	return [...counts].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([name, n]) => Math.round(n * 100 / total) + '% ' + name).join(', ');
};

async function describeSpot(view, folder, x, z) {
	const [names, here] = await Promise.all([palette(), columnAt(folder, x, z)]);
	const line = (text, muted) => {
		const p = document.createElement('p');
		p.textContent = text;
		if (muted) p.className = 'muted-text';
		view.about.append(p);
	};
	if (!names || !here) return line('Nothing drawn here yet', true);
	const block = n => n && names.blocks[n - 1] ? prettyName(names.blocks[n - 1]) : null;
	const biome = n => n && names.biomes[n - 1] ? prettyName(names.biomes[n - 1]) : null;
	view.spot.y = here.y + 1;
	if (biome(here.biome)) line(biome(here.biome));
	line((block(here.top) || 'Something') + (block(here.ground) ? ' over ' + block(here.ground) : '') + ', at y ' + here.y);
	const materials = new Map(), biomes = new Map();
	const columns = [];
	for (let dz = -AROUND; dz < AROUND; dz++) for (let dx = -AROUND; dx < AROUND; dx++) columns.push(columnAt(folder, x + dx, z + dz));
	for (const column of await Promise.all(columns)) {
		if (!column) continue;
		const name = block(column.ground) || block(column.top);
		if (name) materials.set(name, (materials.get(name) || 0) + 1);
		const b = biome(column.biome);
		if (b) biomes.set(b, (biomes.get(b) || 0) + 1);
	}
	if (materials.size > 1) line('Around it: ' + shares(materials), true);
	if (biomes.size > 1) line('Biomes near: ' + shares(biomes), true);
}

const OTHER_SIDE = { minecraft_overworld: ['minecraft_the_nether', 1 / 8], minecraft_the_nether: ['minecraft_overworld', 8] };

function spotView(x, z) {
	const view = document.createElement('div');
	view.className = 'spot';
	const heading = document.createElement('p');
	heading.className = 'spot-where';
	heading.textContent = 'x ' + x + ', z ' + z;
	const about = document.createElement('div');
	about.className = 'spot-about';
	const buttons = document.createElement('div');
	buttons.className = 'spot-buttons';
	const button = (text, onClick) => {
		const b = document.createElement('button');
		b.type = 'button';
		b.textContent = text;
		b.addEventListener('click', () => onClick(b));
		buttons.append(b);
		return b;
	};
	const spot = { y: null };
	button('Copy x z', b => copy(x + ' ' + (spot.y ?? '~') + ' ' + z, b));
	button('Copy /tp', b => copy('/tp @s ' + x + ' ' + (spot.y ?? '~') + ' ' + z, b));
	button('Copy link', b => copy(location.origin + linkTo(dimension, x, z, Math.max(map.getZoom(), NATIVE)), b));
	const other = OTHER_SIDE[dimension];
	if (other && tileLayers[other[0]]) {
		const ox = Math.floor(x * other[1]), oz = Math.floor(z * other[1]);
		const line = document.createElement('p');
		line.className = 'spot-other';
		line.textContent = 'In the ' + titleOf(other[0]) + ': x ' + ox + ', z ' + oz + ' ';
		const go = document.createElement('button');
		go.type = 'button';
		go.textContent = 'Show';
		go.addEventListener('click', () => { map.closePopup(); travel({ dimension: other[0], x: ox, z: oz }); });
		line.append(go);
		view.append(heading, about, line, buttons);
	} else {
		view.append(heading, about, buttons);
	}
	view.spot = spot;
	view.about = about;
	return view;
}

map.on('click', e => {
	if (measuring) return;
	const x = Math.floor(e.latlng.lng), z = Math.floor(e.latlng.lat);
	const view = spotView(x, z);
	const popup = L.popup({ maxWidth: 320, className: 'spot-popup' }).setLatLng(at(x, z)).setContent(view).openOn(map);
	describeSpot(view, dimension, x, z).then(() => popup.update());
});

// --- Search: by name among everything the map shows, in any dimension, or by coordinates ---

const COORDINATES = /^\s*(-?\d+)(?:[\s,]+-?\d+|[\s,]+~)?[\s,]+(-?\d+)\s*$/;

function searchables() {
	const found = [];
	const add = (kind, text, spot) => { if (spot && !spot.elsewhere && tileLayers[spot.dimension]) found.push({ kind, text, spot }); };
	for (const p of lastPlayers) add('player', p.name, p);
	for (const h of lastMarkers.homes || []) add('home', h.name + "'s home", h);
	for (const v of lastMarkers.villages || []) add('village', v.name || 'Village', v);
	if (lastMarkers.spawn) add('spawn', 'Spawn', lastMarkers.spawn);
	for (const link of lastMarkers.portals || []) for (const end of [link.from, link.to]) add('portal', link.name || 'Portal', end);
	for (const ship of lastMarkers.ships || []) add('ship', ship.name || 'A ship', ship);
	for (const sign of lastMarkers.signs || []) add('sign', sign.text, sign);
	for (const death of lastMarkers.deaths || []) add('death', death.name + ' died', death);
	return found;
}

const Search = L.Control.extend({
	options: { position: 'topleft' },
	onAdd() {
		const box = L.DomUtil.create('div', 'search');
		const input = L.DomUtil.create('input', '', box);
		input.type = 'search';
		input.placeholder = 'Find a name, or x z';
		input.setAttribute('aria-label', 'Find a name, or go to coordinates');
		const results = L.DomUtil.create('ul', 'search-results', box);
		L.DomEvent.disableClickPropagation(box);
		L.DomEvent.disableScrollPropagation(box);
		let shown = [];
		const go = item => {
			results.textContent = '';
			input.blur();
			travel(item.spot);
		};
		const list = () => {
			results.textContent = '';
			const query = input.value.trim();
			if (!query) { shown = []; return; }
			const coordinates = COORDINATES.exec(query);
			shown = coordinates
				? [{ kind: 'spot', text: 'Go to x ' + coordinates[1] + ', z ' + coordinates[2], spot: { dimension, x: +coordinates[1], z: +coordinates[2] } }]
				: searchables().filter(item => item.text.toLowerCase().includes(query.toLowerCase()))
					.sort((a, b) => (a.spot.dimension !== dimension) - (b.spot.dimension !== dimension) || a.text.localeCompare(b.text))
					.slice(0, 8);
			if (!shown.length) {
				const none = L.DomUtil.create('li', 'search-none', results);
				none.textContent = 'Nothing by that name';
			}
			for (const item of shown) {
				const li = L.DomUtil.create('li', 'line ' + item.kind, results);
				li.textContent = item.text + (item.spot.dimension !== dimension ? ' (' + titleOf(item.spot.dimension) + ')' : '');
				L.DomEvent.on(li, 'click', () => go(item));
			}
		};
		L.DomEvent.on(input, 'input', list);
		L.DomEvent.on(input, 'keydown', e => {
			if (e.key === 'Enter' && shown.length) go(shown[0]);
			if (e.key === 'Escape') { input.value = ''; list(); input.blur(); }
		});
		return box;
	}
});

// --- Measuring: points clicked in turn, and how far it is along them ---

let measuring = null;
const WALKING = 4.317, SPRINTING = 5.612;
const duration = seconds => seconds < 90 ? Math.round(seconds) + ' s' : Math.round(seconds / 60) + ' min';

function measureLabel(points) {
	let blocks = 0;
	for (let i = 1; i < points.length; i++) blocks += Math.hypot(points[i].lng - points[i - 1].lng, points[i].lat - points[i - 1].lat);
	const text = Math.round(blocks) + ' blocks, ' + duration(blocks / WALKING) + ' walking, ' + duration(blocks / SPRINTING) + ' sprinting';
	return dimension === 'minecraft_overworld' && tileLayers.minecraft_the_nether && blocks >= 64
		? text + '; ' + Math.round(blocks / 8) + ' through the Nether' : text;
}

function stopMeasuring() {
	if (!measuring) return;
	measuring.layer.remove();
	measuring.button.classList.remove('on');
	map.getContainer().classList.remove('measuring');
	measuring = null;
}

const Measure = L.Control.extend({
	options: { position: 'topleft' },
	onAdd() {
		const bar = L.DomUtil.create('div', 'leaflet-bar');
		const button = L.DomUtil.create('a', 'measure', bar);
		button.href = '#';
		button.title = button.ariaLabel = 'Measure a distance';
		button.setAttribute('role', 'button');
		L.DomEvent.disableClickPropagation(bar);
		L.DomEvent.on(button, 'click', e => {
			L.DomEvent.preventDefault(e);
			if (measuring) return stopMeasuring();
			map.closePopup();
			const layer = L.layerGroup().addTo(map);
			const line = L.polyline([], { color: '#ffd23f', weight: 2, dashArray: '4 4', interactive: false }).addTo(layer);
			const total = L.tooltip({ permanent: true, direction: 'right', offset: [8, 0], className: 'measure-total' });
			measuring = { layer, line, total, button };
			button.classList.add('on');
			map.getContainer().classList.add('measuring');
		});
		return bar;
	}
});

map.on('click', e => {
	if (!measuring) return;
	const point = L.latLng(Math.floor(e.latlng.lat) + 0.5, Math.floor(e.latlng.lng) + 0.5);
	measuring.line.addLatLng(point);
	L.circleMarker(point, { radius: 3, color: '#ffd23f', weight: 2, fillOpacity: 1, interactive: false }).addTo(measuring.layer);
	const points = measuring.line.getLatLngs();
	if (points.length > 1) measuring.total.setLatLng(point).setContent(measureLabel(points)).addTo(measuring.layer);
});
document.addEventListener('keydown', e => { if (e.key === 'Escape' && viewer.hidden) stopMeasuring(); });

// --- Start ---

function start(meta) {
	if (meta && meta.spawn) map.setView(at(meta.spawn.x, meta.spawn.z), NATIVE);
	const folders = meta && meta.dimensions && meta.dimensions.length ? meta.dimensions : ['minecraft_overworld'];
	const chosen = kept.get('web-map dimension', null);
	dimension = folders.includes(chosen) ? chosen : folders[0];
	for (const folder of folders) {
		const layer = tilesFor(folder);
		if (folders.length > 1) control.addBaseLayer(layer, titleOf(folder));
	}
	tileLayers[dimension].addTo(map);
	new Search().addTo(map);
	new Measure().addTo(map);
	followLink();
	map.on('moveend', keepLink);
	addEventListener('hashchange', followLink);
	refreshTiles();
	setInterval(refreshTiles, 30000);

	for (const key of (meta && meta.layers) || []) if (TITLES[key]) offer(key);
	offer('grid', new Grid({ tileSize: 512, minZoom: FARTHEST, maxZoom: MOST, pane: 'overlayPane' }));
	map.on('overlayadd overlayremove', refreshPlayers);
	refreshMarkers();
	setInterval(refreshMarkers, 30000);
	if (layers.players) {
		refreshPlayers();
		setInterval(refreshPlayers, 5000);
	}
}

json('meta.json').then(start).catch(() => start(null));
