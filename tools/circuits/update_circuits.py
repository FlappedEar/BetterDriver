#!/usr/bin/env python3
"""Rebuilds timing/src/main/resources/circuits/circuits.json, the circuit list the
app ships and fetches from GitHub, from Wikidata (CC0 1.0):

    python3 tools/circuits/update_circuits.py

A port of FlappedEar Telemetry's tool/circuits/update_circuits.dart, so both apps
build the same list. Every item that is a motorsport racing track, a kart circuit
or a rallycross circuit, has a position and is not closed; street circuits and
dragstrips are left out (temporary, or not lapped). Items sharing one position
(within 100 m) keep the oldest (lowest Q number): a copied position is wrong for
all but one of them, and one venue needs one name. The revision is the hour of
the run (UTC).

--sparql-json FILE reads saved query results instead of asking Wikidata (tests).
"""
import argparse
import json
import math
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

QUERY = """
SELECT ?item ?itemLabel ?coord ?iso WHERE {
  VALUES ?type { wd:Q2338524 wd:Q1232319 wd:Q94196270 }
  ?item wdt:P31 ?type ;
        wdt:P625 ?coord .
  OPTIONAL { ?item wdt:P17 ?country . ?country wdt:P297 ?iso . }
  FILTER NOT EXISTS { ?item wdt:P31 wd:Q926439 }
  FILTER NOT EXISTS { ?item wdt:P31 wd:Q357121 }
  FILTER NOT EXISTS { ?item wdt:P576 [] }
  FILTER NOT EXISTS { ?item wdt:P3999 [] }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul,pl,de,fr,it,es,nl,cs,pt". }
}"""

SOURCE = "Wikidata (https://www.wikidata.org), CC0 1.0; built by tools/circuits/update_circuits.py"
POINT = re.compile(r"^Point\((-?[0-9.]+(?:[eE]-?\d+)?) (-?[0-9.]+(?:[eE]-?\d+)?)\)$")
QID = re.compile(r"/entity/(Q(\d+))$")
EARTH_RADIUS_M = 6_371_008.8


def metres(a, b):
    d_lat = math.radians(b["lat"] - a["lat"])
    d_lon = math.radians(b["lon"] - a["lon"])
    h = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(a["lat"])) * math.cos(math.radians(b["lat"])) * math.sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def fetch():
    url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": QUERY, "format": "json"})
    request = urllib.request.Request(url, headers={
        "Accept": "application/sparql-results+json",
        "User-Agent": "BetterDriver-circuits/1.0 (https://github.com/FlappedEar/BetterDriver)",
    })
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def build(results):
    by_id, numbers = {}, {}
    for row in results["results"]["bindings"]:
        item = QID.search(row["item"]["value"])
        point = POINT.match(row["coord"]["value"])
        name = row.get("itemLabel", {}).get("value", "").strip()
        if not item or not point:
            continue
        # An item without a label in any of the languages is shown by its id.
        if not name or re.fullmatch(r"Q\d+", name):
            continue
        lon, lat = float(point[1]), float(point[2])
        if abs(lat) > 90 or abs(lon) > 180:
            continue
        circuit_id = "wd:" + item[1]
        # Several countries or positions: the first is kept.
        if circuit_id in by_id:
            continue
        numbers[circuit_id] = int(item[2])
        entry = {"id": circuit_id, "name": name[:128]}
        iso = row.get("iso", {}).get("value")
        if isinstance(iso, str) and len(iso) == 2:
            entry["country"] = iso
        entry["lat"] = round(lat, 5)
        entry["lon"] = round(lon, 5)
        by_id[circuit_id] = entry
    circuits = []
    for entry in sorted(by_id.values(), key=lambda e: numbers[e["id"]]):
        if not any(metres(kept, entry) < 100 for kept in circuits):
            circuits.append(entry)
    return circuits, len(by_id) - len(circuits)


def write(path, circuits, revision):
    lines = [
        "{",
        ' "format": "flappedear-circuits",',
        ' "version": 1,',
        f' "revision": {revision},',
        f' "source": {json.dumps(SOURCE, ensure_ascii=False)},',
        ' "circuits": [',
        # One circuit a line, so a revision's diff shows what changed.
        ",\n".join("  " + json.dumps(c, ensure_ascii=False, separators=(",", ":")) for c in circuits),
        " ]",
        "}",
    ]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", default="timing/src/main/resources/circuits/circuits.json")
    parser.add_argument("--sparql-json", help="saved query results to use instead of Wikidata")
    args = parser.parse_args()
    if args.sparql_json:
        with open(args.sparql_json, encoding="utf-8") as f:
            results = json.load(f)
    else:
        results = fetch()
    circuits, left_out = build(results)
    revision = int(datetime.now(timezone.utc).strftime("%Y%m%d%H"))
    write(args.output, circuits, revision)
    print(f"Wrote {len(circuits)} circuits ({left_out} within 100 m of another left out), "
          f"revision {revision}, to {args.output}.", file=sys.stderr)


if __name__ == "__main__":
    main()
