# SonderEye

Live public data on a 3D globe, on your phone. Free and open source, no account, no backend.

## Status

Phase 1, test builds only.

- Native 3D globe drawn with OpenGL ES 3.0: satellite imagery that sharpens as you zoom, atmosphere glow.
- Drag, pinch toward a point, twist to rotate, fling, double-tap to zoom.
- Earthquakes from USGS: minimum magnitude, period, optional refresh every 5 minutes.
- Tap a quake for magnitude, place, time, depth and the USGS event page.
- Every failure (feed download, imagery tiles, graphics) is shown on screen.

## Roadmap

1. Globe and earthquakes (this build).
2. Flights, tap to inspect, follow mode.
3. Satellites computed on the phone, ISS pass alerts.
4. Sky view: point the phone up to see what is overhead.
5. Ships, wildfires, webcams.

## Data sources

| Layer | Source | Key |
|---|---|---|
| Earthquakes | [USGS summary feeds](https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php) | None |
| Imagery | Esri World Imagery (Esri, Maxar, Earthstar Geographics) | None |

The app asks for one permission: internet.

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
