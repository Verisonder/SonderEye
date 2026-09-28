#!/usr/bin/env python3
"""
Downloads NASA Blue Marble Next Generation tiles (public domain, via NASA GIBS) for
zoom 2 to 6 into the app's assets, so the whole-Earth view ships inside the APK.

Run by CI before the build; the result is cached between runs. Exits non-zero if more
than 1% of the tiles could not be fetched, so a broken download never ships quietly.
"""
import concurrent.futures
import os
import sys
import time
import urllib.error
import urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/bluemarble"
ZOOMS = range(2, 7)
URL = ("https://gibs-{s}.earthdata.nasa.gov/wmts/epsg3857/best/BlueMarble_NextGeneration/default/"
       "GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpeg")


def fetch(job):
    z, x, y = job
    path = os.path.join(OUT, str(z), str(x), f"{y}.jpg")
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return None
    url = URL.format(s="abc"[(x + y) % 3], z=z, x=x, y=y)
    last = "unknown"
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "SonderEye build (github.com/Verisonder/SonderEye)"})
            with urllib.request.urlopen(req, timeout=30) as r:
                data = r.read()
            if not data.startswith(b"\xff\xd8"):
                raise ValueError(f"not a JPEG ({len(data)} bytes)")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
            return None
        except (urllib.error.URLError, OSError, ValueError) as e:
            last = str(e)
            time.sleep(1 + attempt * 2)
    return f"{z}/{x}/{y}: {last}"


def main():
    jobs = [(z, x, y) for z in ZOOMS for x in range(1 << z) for y in range(1 << z)]
    failed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        for i, err in enumerate(pool.map(fetch, jobs), 1):
            if err:
                failed.append(err)
            if i % 500 == 0:
                print(f"{i}/{len(jobs)} tiles", flush=True)
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(OUT) for f in fs)
    print(f"{len(jobs) - len(failed)}/{len(jobs)} tiles, {size / 1e6:.1f} MB in {OUT}")
    for f in failed[:20]:
        print("failed:", f)
    if len(failed) > len(jobs) // 100:
        print(f"Too many failures ({len(failed)}); not building with a patchy globe.")
        sys.exit(1)


if __name__ == "__main__":
    main()
