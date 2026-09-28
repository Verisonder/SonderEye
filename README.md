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
- **Sky view**: point the phone at the sky to see satellites, aircraft, the Sun and the Moon labelled.
- **ISS pass alerts**: a notification about 10 minutes before each pass you can see.
- Tiles are kept on disk (up to 500 MB), so places seen once load instantly.
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
| Streets, roads, labels | Esri World Street Map and reference layers | None |
| Today from space | NASA GIBS, VIIRS NOAA-20 true colour | None |
| Rain radar | [RainViewer](https://www.rainviewer.com/api.html), personal use, zoom 7 at most | None |
| Weather | [Open-Meteo](https://open-meteo.com) | None |
| Imagery | Esri World Imagery (Esri, Maxar, Earthstar Geographics) | None |

Permissions: internet; location only for "Where I am"; camera only for the sky view; notifications only for pass alerts.

## Building

JDK 17 and Gradle 8.9.

```
gradle assembleDebug
gradle testDebugUnitTest
```

Pushes to `test/**` build a signed test APK and publish it as a pre-release named `test-<branch>`.
Failed builds attach their output to the pre-release `ci-failure`.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).
