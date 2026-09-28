# SonderEye

Live public data on a 3D globe, on your phone. Free and open source, no account, no backend.

## Status

Test builds only.

- Native 3D globe drawn with OpenGL ES 3.0: satellite imagery that sharpens as you zoom, atmosphere glow.
- Drag, pinch toward a point, twist to rotate, fling, double-tap to zoom.
- **Earthquakes** (USGS): minimum magnitude, period, optional refresh every 5 minutes.
- **Flights** (adsb.lol): live aircraft within 250 nm of the screen centre, pointing where they fly, every 10 s.
- **Satellites** (CelesTrak): space stations, brightest, weather or science satellites, positions computed
  on the phone every second, orbit line for the selected one, next passes over you.
- **Natural events** (NASA EONET): open wildfires, volcanoes, storms and ice.
- **Where I am**: your position from the phone's GPS and network, only while the app is open.
- Every failure (a feed, the location, imagery tiles, graphics) is shown on screen.

## Roadmap

- Sky view: point the phone up to see what is overhead.
- Pass alerts for the ISS.
- Ships and webcams (these sources need a free key).

## Data sources

| Layer | Source | Key |
|---|---|---|
| Earthquakes | [USGS summary feeds](https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php) | None |
| Flights | [adsb.lol](https://adsb.lol), community-fed ADS-B | None |
| Satellites | [CelesTrak](https://celestrak.org) orbital elements, cached 2 h as they ask | None |
| Natural events | [NASA EONET](https://eonet.gsfc.nasa.gov) | None |
| Imagery | Esri World Imagery (Esri, Maxar, Earthstar Geographics) | None |

Permissions: internet, and location only when "Where I am" is switched on.

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
