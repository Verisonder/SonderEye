<p align="center">
  <img src="docs/brand/logo.svg" width="128" alt="SonderEye logo: an eye whose iris is a radar scope with an S in it">
</p>

<h1 align="center">SonderEye</h1>

<p align="center">The planet's live traffic on a 3D globe, on your phone.<br>
Free, open source, no account, no backend.</p>

---

SonderEye puts public signals on one native globe: earthquakes, aircraft, satellites,
ships, fires, storms and the weather, drawn on satellite imagery you can zoom from the
whole Earth down to your street. It is an Android app, built from scratch with OpenGL ES,
not a web page in a wrapper.

## What it shows

**On the globe**
- Earthquakes (USGS), sized by magnitude and coloured by depth.
- Aircraft (adsb.lol), gliding between updates, with 30-minute trails and a follow mode.
- Satellites (CelesTrak), positions computed on the phone every second, with the orbit of
  the one you pick and its next passes over you. GPS and geostationary orbits included.
- Ships (AISStream), fire hotspots (NASA FIRMS) and public webcams (Windy), each with your own free key.
- Natural events (NASA EONET): wildfires, volcanoes, storms, ice.
- Rain radar (RainViewer), optionally looping the past hour.
- Surveillance cameras and licence-plate readers mapped in OpenStreetMap.
- Bus lines and stops mapped in OpenStreetMap, and live buses wherever the operator
  publishes GTFS Realtime (found through Transitland, with your own free key).
- Day and night, with city lights on the dark side.

**Around it**
- **Today**: the weather where you are and the day's news from the sources you choose,
  with an optional summary written by Gemini, made once a day.
- **Sky view**: point the phone at the sky to see the satellites, aircraft, Sun and Moon above you, labelled.
- **ISS pass alerts**: a notification before each pass you can actually see.
- **Search** for places, flights by callsign, and satellites by name.
- A list of every item in each layer, sortable, one tap from flying to it.

## The globe

- Native OpenGL ES 3.0, camera-relative rendering: no float jitter from 40,000 km down to 120 m.
- Esri satellite imagery, streets, or yesterday's whole Earth from NASA, with roads and place names on top.
- The whole Earth at country scale ships inside the app (NASA Blue Marble), so the globe appears at once, even offline.
- Tiles load centre first, parents before children, and are kept on the phone (up to 500 MB).
- Markers lie on the curved surface; satellites face you from orbit.
- A radar scope over the screen centre: bearings, range rings at real ground distances, and a sweep.

## Data sources

| Layer | Source | Key |
|---|---|---|
| Earthquakes | [USGS summary feeds](https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php) | None |
| Flights | [adsb.lol](https://adsb.lol), community-fed ADS-B | None |
| Satellites | [CelesTrak](https://celestrak.org) orbital elements, cached 2 h as they ask | None |
| Natural events | [NASA EONET](https://eonet.gsfc.nasa.gov) | None |
| Built-in globe | NASA Blue Marble Next Generation via GIBS, public domain, bundled by CI | None |
| Streets, roads, labels | Esri World Street Map and reference layers | None |
| Today from space | NASA GIBS, VIIRS NOAA-20 true colour | None |
| Rain radar | [RainViewer](https://www.rainviewer.com/api.html), personal use, zoom 7 at most | None |
| Weather | [Open-Meteo](https://open-meteo.com) | None |
| Night lights | NASA GIBS, VIIRS City Lights 2012 | None |
| Place search | [Nominatim](https://nominatim.org), OpenStreetMap | None |
| Ships | [AISStream](https://aisstream.io) live AIS over WebSocket | Free, GitHub sign-in |
| Webcams | [Windy Webcams API](https://api.windy.com/webcams) | Free |
| Fire hotspots | [NASA FIRMS](https://firms.modaps.eosdis.nasa.gov), VIIRS NOAA-20, last 24 h | Free, e-mail |
| News | Publisher RSS/Atom feeds (12 built in, or your own) | None |
| Written brief | [Google Gemini API](https://aistudio.google.com/apikey), optional | Free tier |
| Surveillance cameras | OpenStreetMap via the [Overpass API](https://overpass-api.de) | None |
| Bus lines and stops | OpenStreetMap via the Overpass API | None |
| Live buses | Operators' GTFS Realtime feeds, found and relayed by [Transitland](https://www.transit.land) | Free |
| Imagery | Esri World Imagery (Esri, Maxar, Earthstar Geographics) | None |

Permissions: internet; location only for "Where I am"; camera only for the sky view; notifications only for pass alerts.

## Building

JDK 17 and Gradle 8.9.

```
gradle assembleDebug
gradle testDebugUnitTest
```

CI downloads the bundled Blue Marble tiles (`tools/fetch_bluemarble.py`, about 5,500 tiles,
cached between runs) before building; a local build without them still works, just without
the built-in globe. Pushes to `test/**` build a signed test APK and publish it as a pre-release
named `test-<branch>`. Failed builds attach their output to the pre-release `ci-failure`.

More detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).

Bundled typeface: Barlow Semi Condensed, SIL Open Font License 1.1 ([licenses/Barlow-OFL.txt](licenses/Barlow-OFL.txt)).
The S in the logo is drawn from Audiowide, SIL Open Font License 1.1 ([licenses/Audiowide-OFL.txt](licenses/Audiowide-OFL.txt)).
