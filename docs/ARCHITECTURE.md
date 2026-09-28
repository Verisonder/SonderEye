# Architecture

```
app/src/main/
  java/com/verisonder/sondereye/
    core/    Pure Kotlin, no Android. Unit-tested off-device.
      Json.kt       Reader and JavaScript-safe string escaping.
      Quakes.kt     Quake model, USGS feed URLs and parsing, globe payload, formatting.
    data/
      UsgsClient.kt Downloads a feed; every failure becomes a sentence for the screen.
      Settings.kt   Layer settings in SharedPreferences.
    ui/
      Globe.kt        GlobeController: owns the WebView, the JS bridge and crash recovery.
      MainActivity.kt State, refresh and auto-refresh (only while on screen).
      Screen.kt       Compose overlays: status readout, buttons, quake card, layers panel.
      Theme.kt        Palette, shared with the marker colours in the page.
  assets/globe/index.html   The globe: Cesium viewer, markers, touch picking, window.SE API.
```

## The bridge

The page is served from `https://appassets.androidplatform.net/assets/globe/index.html`
through `WebViewAssetLoader`, so it is a normal https origin and can load Cesium from the CDN.

App to page (`evaluateJavascript`):

| Call | Effect |
|---|---|
| `SE.setQuakes([[id, lat, lon, mag, depthKm], …])` | Replaces all quake markers |
| `SE.select(id or null)` | Rings a quake and flies to it, or clears the ring |
| `SE.home()` | Whole-Earth view |

Page to app (`SonderEyeApp`, a `@JavascriptInterface`):

| Call | Meaning |
|---|---|
| `ready()` | Viewer is up. The controller replays the last data and selection. |
| `select(id)` | A tap picked a quake; empty string means nothing picked. |
| `error(message)` | Shown on screen as-is. |

Data sent before `ready()` is kept and delivered on ready, so load order never matters.
After a renderer crash the controller builds a new WebView and replays the same way.

## Choices

- Markers are a `PointPrimitiveCollection`: thousands of points without entities overhead.
- A tap picks the nearest visible quake within 30 px, since a fingertip is wider than a marker.
- `requestRenderMode`: the globe draws only when something changes.
- Colour is depth (0–70, 70–300, 300+ km); size is magnitude.
- The activity handles rotation itself so the globe is never reloaded.

## Adding a layer

1. Model and parser in `core/`, with tests.
2. Client in `data/` returning an outcome with an on-screen message.
3. Payload function in `core/`, `SE.set<Layer>` in the page.
4. Settings, status line and panel section in `ui/`.
