"""Temporary probe: how well each provider's imagery shows Silesia Ring under the recorded lap.

For every provider a z18 mosaic over the lap is built; the lap's points are sampled on it. Asphalt is
grey (low colour saturation); a provider whose photo predates the track shows grass there. A second
sample 40 m to the side is the control. Sharpness = variance of the Laplacian over the mosaic.
"""
import io, math, sys, time, urllib.request
from PIL import Image, ImageFilter, ImageStat

Z, X0, Y0, NX, NY = 18, 144244, 88300, 9, 7
pts = [l.split() for l in open(sys.argv[1]).read().splitlines()[1:]][::20]
pts = [(float(p[1]), float(p[2])) for p in pts]

def px(lat, lon):
    n = 2 ** Z
    x = (lon + 180) / 360 * n
    y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
    return (x - X0) * 256, (y - Y0) * 256

def merc_bounds(x, y):
    span = 2 * math.pi * 6378137 / 2 ** Z
    minx = -math.pi * 6378137 + x * span
    maxy = math.pi * 6378137 - y * span
    return minx, maxy - span, minx + span, maxy

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "BetterDriver-probe/1 (+https://github.com/FlappedEar/BetterDriver)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()

URLS = {
    "esri": lambda x, y: f"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{Z}/{y}/{x}",
    "google": lambda x, y: f"https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={Z}",
    "gugik-wmts": lambda x, y: ("https://mapy.geoportal.gov.pl/wss/service/PZGIK/ORTO/WMTS/StandardResolution?SERVICE=WMTS&REQUEST=GetTile"
        f"&VERSION=1.0.0&LAYER=ORTOFOTOMAPA&STYLE=default&FORMAT=image/jpeg&TILEMATRIXSET=EPSG:3857&TILEMATRIX=EPSG:3857:{Z}&TILEROW={y}&TILECOL={x}"),
    #"gugik-wms": lambda x, y: ("https://mapy.geoportal.gov.pl/wss/service/PZGIK/ORTO/WMS/StandardResolution?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0"
        #"&LAYERS=Raster&STYLES=&CRS=EPSG:3857&BBOX=%f,%f,%f,%f&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg" % merc_bounds(x, y)),
}

def mosaic(name):
    img = Image.new("RGB", (NX * 256, NY * 256))
    times, fails, err = [], 0, ""
    for j in range(NY):
        for i in range(NX):
            t = time.time()
            try:
                data = get(URLS[name](X0 + i, Y0 + j))
                tile = Image.open(io.BytesIO(data)).convert("RGB").resize((256, 256))
                img.paste(tile, (i * 256, j * 256))
            except Exception as e:
                fails += 1
                err = str(e)[:120]
            times.append(time.time() - t)
    times.sort()
    print(f"{name}: tiles {len(times) - fails}/{len(times)} ok, median {times[len(times)//2]:.2f}s, p90 {times[int(len(times)*.9)]:.2f}s {err}")
    return img if fails < len(times) / 2 else None

def grey(img, x, y):
    if not (0 <= x < img.width and 0 <= y < img.height):
        return None
    r, g, b = img.getpixel((int(x), int(y)))
    return max(r, g, b) - min(r, g, b) < 22 and g <= max(r, b) + 6

def score(name, img):
    on = off = n = 0
    mpp = 2 * math.pi * 6378137 * math.cos(math.radians(50.527)) / (256 * 2 ** Z)
    side = 40 / mpp
    for k in range(1, len(pts)):
        x, y = px(*pts[k])
        px0, py0 = px(*pts[k - 1])
        dx, dy = x - px0, y - py0
        d = math.hypot(dx, dy) or 1
        a, b = grey(img, x, y), grey(img, x - dy / d * side, y + dx / d * side)
        if a is None or b is None:
            continue
        n += 1; on += a; off += b
    sharp = ImageStat.Stat(img.convert("L").filter(ImageFilter.FIND_EDGES)).var[0]
    print(f"RESULT {name}: lap on grey {100*on/max(n,1):.0f}%  control 40 m aside {100*off/max(n,1):.0f}%  edge variance {sharp:.0f}  (n={n})")

def shift(a, b):
    """Best (dx, dy) in pixels moving b onto a, from edge maps, within +-48 px."""
    import numpy as np
    ea = np.asarray(a.convert("L").filter(ImageFilter.FIND_EDGES), dtype=float)
    eb = np.asarray(b.convert("L").filter(ImageFilter.FIND_EDGES), dtype=float)
    ea -= ea.mean(); eb -= eb.mean()
    best = (-1e18, 0, 0)
    m = 48
    for dy in range(-m, m + 1, 2):
        for dx in range(-m, m + 1, 2):
            pa = ea[m:-m, m:-m]
            pb = eb[m + dy:eb.shape[0] - m + dy, m + dx:eb.shape[1] - m + dx]
            c = float((pa * pb).sum())
            if c > best[0]: best = (c, dx, dy)
    return best[1], best[2]

if len(sys.argv) < 3:
    imgs = {}
    for name in URLS:
        img = mosaic(name)
        if img:
            score(name, img); imgs[name] = img
    for name in imgs:
        if name != "google": print(f"RESULT shift {name} vs google: {shift(imgs['google'], imgs[name])} px")
    sys.exit(0)
try:
    score("apple", Image.open("apple.png").convert("RGB").resize((NX * 256, NY * 256)))
except Exception as e:
    print("apple", e)
