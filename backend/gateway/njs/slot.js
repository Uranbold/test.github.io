// NAV-006 slot pointer (ADR-0014 §3). Loaded by conf.d/default.conf (js_import slot), used through js_set.
//
// Slot mode (backend/compose.slots.yaml): the directory /etc/nginx/slot is mounted (read-only) and holds
// active.json, written by backend/pipeline/nav_pipeline.py with temp file + rename(2):
//   {"slot":"20261004T193412Z","lane":"green","valhalla":"valhalla-green:8002","photon":"photon-green:2322",
//    "tiles":"/srv/slots/20261004T193412Z/tiles/basemap.pmtiles","switched_at":"..."}
// The file is read on EVERY request (a few hundred bytes from the page cache, about 0.03 ms), so a rename
// switches all new requests at once: no reload, no closed keep-alive connection, no container change.
// Each nginx variable is evaluated once per request, and each location uses one of them, so every request
// is served wholly by one slot.
//
// Only lane names and the slot tiles path pattern are accepted. A missing, unreadable or invalid pointer in
// slot mode gives the contract's errors: an address where nothing listens (connect refused -> 502
// UpstreamUnavailable for route/search/reverse) and a tiles path that does not exist (-> 404 NotFound).
// Nothing is logged per request.
//
// Dev mode (backend/compose.yaml, no /etc/nginx/slot directory): the NAV-001 defaults are returned
// unchanged: $nav_dev_valhalla / $nav_dev_photon (from VALHALLA_UPSTREAM / PHOTON_UPSTREAM) and
// /srv/data/tiles/basemap.pmtiles.
import fs from 'fs';

const DIR = '/etc/nginx/slot';
const FILE = DIR + '/active.json';
const VALHALLA_RE = /^valhalla-(blue|green):8002$/;
const PHOTON_RE = /^photon-(blue|green):2322$/;
const TILES_RE = /^\/srv\/slots\/[0-9]{8}T[0-9]{6}Z\/tiles\/basemap\.pmtiles$/;
const DEV_TILES = '/srv/data/tiles/basemap.pmtiles';
const DEAD_UPSTREAM = '127.0.0.1:9';
const DEAD_TILES = '/nonexistent/nav-slot/basemap.pmtiles';

// Returns null in dev mode (no pointer directory), {} for an unusable pointer, else the parsed object.
function pointer() {
    let raw;
    try {
        raw = fs.readFileSync(FILE, 'utf8');
    } catch (e) {
        if (e.code === 'ENOENT') {
            try {
                fs.accessSync(DIR);
            } catch (e2) {
                return null;
            }
        }
        return {};
    }
    try {
        const p = JSON.parse(raw);
        return (p && typeof p === 'object') ? p : {};
    } catch (e) {
        return {};
    }
}

function pick(value, re, fallback) {
    return (typeof value === 'string' && re.test(value)) ? value : fallback;
}

function valhalla(r) {
    const p = pointer();
    if (p === null) {
        return r.variables.nav_dev_valhalla;
    }
    return pick(p.valhalla, VALHALLA_RE, DEAD_UPSTREAM);
}

function photon(r) {
    const p = pointer();
    if (p === null) {
        return r.variables.nav_dev_photon;
    }
    return pick(p.photon, PHOTON_RE, DEAD_UPSTREAM);
}

function tiles(r) {
    const p = pointer();
    if (p === null) {
        return DEV_TILES;
    }
    return pick(p.tiles, TILES_RE, DEAD_TILES);
}

export default { valhalla, photon, tiles };
