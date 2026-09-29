# Glossary (owner: business-analyst)

| Term | Meaning |
|---|---|
| **Aimag** | Province of Mongolia (21 aimags plus the capital). |
| **Düüreg (district)** | One of Ulaanbaatar's 9 city districts, e.g. Sükhbaatar, Chingeltei, Bayanzürkh. |
| **Khoroo** | Sub-district of a düüreg; the main unit in Mongolian addresses (district → khoroo → building/apartment or ger plot). |
| **Ger district** | Peri-urban UB area of ger (yurt) and detached-house plots, often with unpaved roads and irregular addressing. |
| **OSM extract** | A regional subset of OpenStreetMap in `.osm.pbf` format. Dev uses the BBBike Ulaanbaatar extract; production uses Geofabrik `mongolia-latest`. |
| **PMTiles** | A single-file vector tile archive, read by clients with HTTP Range requests. Built by Planetiler using the Protomaps basemap schema. |
| **Range request** | HTTP request with a `Range: bytes=a-b` header. The server answers `206 Partial Content`. PMTiles relies on it. |
| **CORS** | Browser rule that lets a web page on one origin call an API on another. The gateway must send `Access-Control-Allow-*` headers. |
| **Costing** | Valhalla's per-request travel profile (`auto`, `pedestrian`, `bicycle`, `taxi`, …) and its options (e.g. `exclude_unpaved`). |
| **OSRM-compatible output** | Valhalla's `format=osrm` response shape (routes → legs → steps → maneuver) that Ferrostar and MapLibre Navigation consume. |
| **Manoeuvre** | A single driving action at a point on the route (turn left, keep right, roundabout exit). |
| **Banner instruction** | The on-screen text for the next manoeuvre (`bannerInstructions[].primary.text`), shown from `distanceAlongGeometry` onwards. |
| **Voice instruction** | The spoken text for a manoeuvre (`voiceInstructions[].announcement`, optional SSML), triggered at `distanceAlongGeometry`. |
| **mn-MN** | Mongolian (Mongolia) locale code, used for Valhalla narrative and the app UI. |
| **Polyline6** | Encoded route geometry with 6-decimal precision; the default for Valhalla OSRM output. |
| **Off-route / reroute** | The navigation SDK detects the user has left the route beyond a distance/time threshold and requests a new route from the current position. |
| **ETA** | Estimated time of arrival; derived from route `duration` (static speeds until traffic data exists). |
| **Autocomplete (search-as-you-type)** | Search results returned for a partial query (prefix), e.g. `Сүхб` → Sükhbaatar Square. |
| **Reverse geocoding** | Coordinates → nearest address/place ("what is here?"). |
| **Transliteration** | Writing Mongolian Cyrillic in Latin letters, with no single standard ("Sukhbaatar", "Suhbaatar", "Sükhbaatar" = "Сүхбаатар"). |
| **Smoke test** | A short automated check that each service answers correctly for known inputs; it doesn't test full behaviour. |
| **Gateway** | The single HTTP entry point (Caddy or Nginx) that routes to tiles, routing and search and applies CORS. |
