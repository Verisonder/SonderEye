# Architecture

```
app/src/main/java/com/verisonder/sondereye/
  core/    Pure Kotlin, no Android. Unit-tested off-device.
    Geo.kt      Globe maths: camera, projection, picking, tile selection, tile meshes, fly-to.
    Json.kt     JSON reader.
    Quakes.kt   Quake model, USGS feed URLs and parsing, formatting.
  data/
    UsgsClient.kt  Downloads a feed; every failure becomes a sentence for the screen.
    Settings.kt    Layer settings in SharedPreferences.
  globe/   The native globe.
    GlobeView.kt      GLSurfaceView. Owns the camera and every gesture.
    GlobeRenderer.kt  OpenGL ES 3.0: imagery tiles, polar caps, atmosphere, markers.
    TileLoader.kt     Imagery downloads, newest first, stale requests dropped.
  ui/
    MainActivity.kt  State, refresh, auto-refresh (only while on screen).
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
- **Imagery** is Esri World Imagery, Web Mercator, 256 px tiles, zoom 2 to 18.
  Tiles split until they show at no more than 384 px, capped at 180 on screen.
  A missing tile shows its nearest loaded ancestor, stretched, until it arrives.
  Skirts under each tile hide cracks between detail levels. Polar caps close the
  globe above 85.05°, where Web Mercator ends.
- **Rendering on demand.** Frames are drawn only when something changes: a gesture,
  an animation, or a tile arriving. An idle globe costs nothing.
- **Gestures** keep the ground under the finger under the finger: drag, pinch around
  the focus point, two-finger twist, fling with decay, double-tap zoom.
- **Picking** chooses the nearest visible marker within 30 dp of a tap.

## Adding a layer

1. Model and parser in `core/`, with tests.
2. Client in `data/` returning an outcome with an on-screen message.
3. Markers (or a new draw pass in `GlobeRenderer`).
4. Settings, status line and panel section in `ui/`.
