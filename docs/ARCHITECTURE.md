# Architecture

```
app/src/main/java/com/verisonder/sondereye/
  core/    Pure Kotlin, no Android. Unit-tested off-device.
    Geo.kt      Globe maths: camera, projection, picking, tile selection, tile meshes, fly-to.
    Json.kt     JSON reader.
    Quakes.kt   Quake model, USGS feed URLs and parsing, formatting.
    Live.kt     Flights (adsb.lol) and natural events (NASA EONET): models and parsers.
    Sgp4.kt     TLE parsing, SGP4 near-Earth propagation, sidereal time, look angles, passes.
    Tiles.kt    Map tile sources (base and overlays), radar index, Open-Meteo weather.
    Astro.kt    Sun and Moon positions, Earth's shadow, sky-view projection.
    Transit.kt  Bus lines and stops (Overpass), live buses: Transitland lookups, GTFS Realtime
                as JSON and as protocol buffer (a small reader for the fields a map needs).
  alerts/
    PassAlerts.kt  One alarm at a time for the next visible ISS pass; receiver re-arms after reboot.
  data/
    Net.kt       One GET; every failure becomes a sentence for the screen.
    Feeds.kt     Every public feed, and the 2-hour TLE disk cache.
    Settings.kt  Layer settings in SharedPreferences.
    Where.kt     Position from the platform LocationManager (no Play services).
  globe/   The native globe.
    GlobeView.kt      GLSurfaceView. Owns the camera and every gesture.
    GlobeRenderer.kt  OpenGL ES 3.0: imagery tiles, polar caps, atmosphere, markers.
    TileLoader.kt     Imagery downloads, newest first, stale requests dropped.
  ui/
    MainActivity.kt  State, refresh, auto-refresh (only while on screen).
    SkyActivity.kt   Sky view: CameraX preview, rotation-vector sensor, labels projected on top.
    Screen.kt        Compose overlays: status readout, buttons, quake card, layers panel.
    Theme.kt         Palette; the marker colours come from here.
```

## Globe

- Spherical Earth, metres, x toward 0°/0°, z north.
- The camera looks straight down at a lat/lon from an altitude, with a heading.
  Camera state is immutable and handed from the UI thread to the GL thread.
- **No float jitter.** Every object is positioned relative to the eye in double
  precision before becoming floats: tiles by their centre (vertices stored relative to
  it), markers per frame. The GPU never sees a coordinate near 6,400 km.
- **Imagery**: one base source plus transparent overlays drawn over it on the same
  meshes. Sources with less detail are stretched from their deepest zoom; a 404
  (blankTile=false) means "no more detail here", not an error. Tiles download 8 at a
  time across two hostnames, parents before children, and are kept on disk (500 MB).
- **Imagery** zoom 2 to 20.
  Tiles split until they show at no more than 384 px, capped at 180 on screen.
  A missing tile shows its nearest loaded ancestor, stretched, until it arrives.
  Skirts under each tile hide cracks between detail levels. Polar caps close the
  globe above 85.05°, where Web Mercator ends.
- **Rendering on demand.** Frames are drawn only when something changes: a gesture,
  an animation, or a tile arriving. An idle globe costs nothing.
- **Gestures** keep the ground under the finger under the finger: drag, pinch around
  the focus point, two-finger twist, fling with decay, double-tap zoom.
- **Picking** chooses the nearest visible marker within 30 dp of a tap; later layers win ties.
- **Markers** are GPU point sprites with shapes: dot (quakes, events), arrow turned to
  the aircraft's track (flights), diamond at orbital altitude (satellites), blue dot (you).
- **Layers** draw in a fixed order: quakes, events, flights, satellites, you.

## Satellites

SGP4 (near-Earth) is a port of Vallado's reference code with WGS-72 constants. Checked
against the python-sgp4 library: 25 cases, agreement within a millimetre. Orbits of 225
minutes or more need SDP4 and are counted but not drawn. Positions are converted from
TEME to Earth-fixed with IAU-82 sidereal time. Passes are found on a 30 s scan over 48 h
and refined to a second.

## Adding a layer

1. Model and parser in `core/`, with tests.
2. Client in `data/` returning an outcome with an on-screen message.
3. Markers (or a new draw pass in `GlobeRenderer`).
4. Settings, status line and panel section in `ui/`.
