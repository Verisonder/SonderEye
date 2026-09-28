# SonderEye

Live public data on a 3D globe, on your phone. Free and open source, no account, no backend.

## Status

Test builds only.

- Native 3D globe (OpenGL ES 3.0) from the whole Earth down to about 120 m, with atmosphere.
- **Map**: satellite, streets, or today's Earth from space (NASA VIIRS), with optional roads and place names.
- Drag, pinch toward a point, twist to rotate, fling, double-tap to zoom, long-press for the weather there.
- **Earthquakes** (USGS), **flights** (adsb.lol), **satellites** (CelesTrak, computed on the phone),
  **natural events** (NASA EONET), **rain radar** (RainViewer), **weather** (Open-Meteo).
- **Where I am**, with the weather where you are and the next satellite passes over you.
- **Day and night**: the night side shaded with a twilight band, city lights after dark.
- **Flights** glide between updates, with 30-minute trails and a follow mode.
- **Search**: places (OpenStreetMap), flights by callsign anywhere, satellites by name.
- **Satellites** in every orbit: GPS and geostationary use a simplified high-orbit model (within about 50 km).
- **Surveillance cameras** and licence-plate readers mapped in OpenStreetMap.
- **Weather forecast**: 12 hours and 3 days; the rain radar loops over the past hour.
- **Map cache** size and a clear button in the menu.
- **Sky view**: point the phone at the sky to see satellites, aircraft, the Sun and the Moon labelled.
- **ISS pass alerts**: a notification about 10 minutes before each pass you can see.
- The whole Earth down to country scale ships inside the app (NASA Blue Marble, zoom 2 to 6),
  so the globe appears at once, even offline. Closer tiles are kept on disk (up to 500 MB).
- Every failure is shown on screen.

## Roadmap

- Ships and webcams (these sources need a free key).

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
| Surveillance cameras | OpenStreetMap via the [Overpass API](https://overpass-api.de) | None |
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
the built-in globe.

Pushes to `test/**` build a signed test APK and publish it as a pre-release named `test-<branch>`.
Failed builds attach their output to the pre-release `ci-failure`.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).
