#!/usr/bin/env python3
"""
Merges stop places of the German NeTEx export (DELFI) that describe the same stop, using the
German stop id (DHID, keyList "GlobalID") and the central stop register (zHV) as reference.

DELFI delivers one directory per operating unit. Most stops are delivered by one supplier and
referenced by all others with the same stop place id. In some regions several suppliers deliver
the same stop with their own ids; only the DHID connects them. OpenTripPlanner then creates one
station per id. This tool gives such stop places one id, so OTP imports them as one station.

Only top-level stop places (without ParentSiteRef) are merged:
  - parent + parent: the parents get one id; if every parent has exactly one child stop place,
    the children get one id as well (all quays end up in one station);
  - simple + simple (no children): they get one id;
  - other combinations, and stop places of one DHID in the same directory, are only reported.

A stop place is only merged if it lies within --max-distance of the position of its DHID in the
zHV (or, without zHV, of the other stop places of the DHID). Implausible DHIDs are reported.

Optionally (--assign-dhid), top-level stop places without DHID get the DHID of the nearest zHV
stop with the same name within --assign-distance. This is a derivation, not delivered data: the
added key is marked (Key "GlobalIDSource") and every assignment is reported.

Usage: python3 netex_dhid_merge.py INPUT OUTPUT [--zhv zHV.xml|zHV.zip] [options]
  INPUT/OUTPUT: directory with one sub directory per operating unit, or a zip file
"""

import argparse
import collections
import io
import math
import os
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

STOP_PLACE = re.compile(r"<StopPlace\b.*?</StopPlace>", re.S)
STOP_PLACE_ID = re.compile(r'<StopPlace\b[^>]*?\bid="([^"]+)"')
GLOBAL_ID = re.compile(r"<Key>\s*GlobalID\s*</Key>\s*<Value>\s*([^<\s]+)\s*</Value>")
NAME = re.compile(r"<Name>([^<]*)</Name>")
LATITUDE = re.compile(r"<Latitude>([^<]+)</Latitude>")
LONGITUDE = re.compile(r"<Longitude>([^<]+)</Longitude>")
PARENT = re.compile(r'<ParentSiteRef\b[^>]*?\bref="([^"]+)"')
QUOTED_VALUE = re.compile(r'"([^"<>]+)"')
ZHV_NS = "{http://zhv.xml.wvi/}"


# ---------------------------------------------------------------------------------------------
# Input and output


class Source:
  """NeTEx files in a directory tree or a zip file; names are relative paths with '/'."""

  def __init__(self, path):
    self.path = path
    self.zip = zipfile.ZipFile(path) if zipfile.is_zipfile(path) else None

  def names(self):
    if self.zip:
      return sorted(n for n in self.zip.namelist() if not n.endswith("/"))
    result = []
    for root, _, files in os.walk(self.path):
      for f in files:
        result.append(os.path.relpath(os.path.join(root, f), self.path).replace(os.sep, "/"))
    return sorted(result)

  def read(self, name):
    if self.zip:
      return self.zip.read(name)
    with open(os.path.join(self.path, name), "rb") as f:
      return f.read()


class Target:
  def __init__(self, path, zipped):
    self.path = path
    self.zip = zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) if zipped else None

  def write(self, name, data):
    if self.zip:
      self.zip.writestr(name, data)
      return
    file = os.path.join(self.path, name)
    os.makedirs(os.path.dirname(file), exist_ok=True)
    with open(file, "wb") as f:
      f.write(data)

  def close(self):
    if self.zip:
      self.zip.close()


def directory_of(name):
  return name.rsplit("/", 1)[0] if "/" in name else ""


# ---------------------------------------------------------------------------------------------
# Stop places


class StopPlace:
  def __init__(self, id):
    self.id = id
    self.dhid = None
    self.name = ""
    self.position = None
    self.parent = None
    self.has_quays = False
    self.files = set()
    self.children = set()

  def directories(self):
    return {directory_of(f) for f in self.files}


def scan_stop_place(text):
  """Attributes of the stop place element text (own part only, without the quays)."""
  own = text.split("<quays>", 1)[0]
  dhid = GLOBAL_ID.search(own)
  name = NAME.search(own)
  parent = PARENT.search(own)
  lat, lon = LATITUDE.search(own), LONGITUDE.search(own)
  position = None
  if lat and lon:
    position = (float(lat.group(1)), float(lon.group(1)))
  else:
    lats = [float(x) for x in LATITUDE.findall(text)]
    lons = [float(x) for x in LONGITUDE.findall(text)]
    if lats and len(lats) == len(lons):
      position = (sum(lats) / len(lats), sum(lons) / len(lons))
  return {
    "dhid": dhid.group(1) if dhid else None,
    "name": name.group(1).strip() if name else "",
    "parent": parent.group(1) if parent else None,
    "position": position,
    "has_quays": "<quays>" in text,
  }


def scan(source, names):
  stop_places = {}
  for name in names:
    text = source.read(name).decode("utf-8", "replace")
    for match in STOP_PLACE.finditer(text):
      id_match = STOP_PLACE_ID.match(match.group(0))
      if not id_match:
        continue
      sp = stop_places.setdefault(id_match.group(1), StopPlace(id_match.group(1)))
      attributes = scan_stop_place(match.group(0))
      sp.files.add(name)
      sp.dhid = sp.dhid or attributes["dhid"]
      sp.name = sp.name or attributes["name"]
      sp.parent = sp.parent or attributes["parent"]
      sp.position = sp.position or attributes["position"]
      sp.has_quays = sp.has_quays or attributes["has_quays"]
  for sp in stop_places.values():
    if sp.parent in stop_places:
      stop_places[sp.parent].children.add(sp.id)
  return stop_places


# ---------------------------------------------------------------------------------------------
# zHV


def read_zhv(path):
  """DHID -> (name, (lat, lon)) of the stops (not the quays) in the zHV XML or a zip with it."""
  if zipfile.is_zipfile(path):
    archive = zipfile.ZipFile(path)
    stream = archive.open(next(n for n in archive.namelist() if n.endswith(".xml")))
  else:
    stream = open(path, "rb")
  result = {}
  for _, el in ET.iterparse(stream, events=("end",)):
    if el.tag not in (ZHV_NS + "StopPlace", "StopPlace"):
      continue
    dhid = zhv_text(el, "DHID")
    lat, lon = zhv_text(el, "Location", "Latitude"), zhv_text(el, "Location", "Longitude")
    if dhid and lat and lon:
      result[dhid] = (zhv_text(el, "Name", "Name"), (float(lat.replace(",", ".")), float(lon.replace(",", "."))))
    el.clear()
  return result


def zhv_text(el, *path):
  for name in path:
    found = el.find(ZHV_NS + name)
    if found is None:
      found = el.find(name)
    if found is None:
      return ""
    el = found
  return (el.text or "").strip()


# ---------------------------------------------------------------------------------------------
# Rules


def distance(a, b):
  lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
  h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
  return 2 * 6371000 * math.asin(math.sqrt(h))


def normalize_name(name):
  s = unicodedata.normalize("NFKD", name.lower())
  s = "".join(c for c in s if not unicodedata.combining(c)).replace("ß", "ss")
  s = s.replace("strasse", "str").replace("str.", "str")
  s = re.sub(r"\b(bf|bahnhof)\b", "bf", s)
  return re.sub(r"[^a-z0-9]+", " ", s).strip()


def stop_level(dhid):
  return ":".join(dhid.split(":")[:3])


class Report:
  def __init__(self):
    self.counts = collections.Counter()
    self.examples = collections.defaultdict(list)

  def add(self, kind, text, limit=20):
    self.counts[kind] += 1
    if len(self.examples[kind]) < limit:
      self.examples[kind].append(text)

  def write(self, out):
    for kind in sorted(self.counts):
      out.write(f"{kind}: {self.counts[kind]}\n")
      for example in self.examples[kind]:
        out.write(f"  {example}\n")


def assign_dhids(stop_places, zhv, max_distance, report):
  """Derive the DHID of top-level stop places without one from the zHV by name and position."""
  cell = 0.01
  grid = collections.defaultdict(list)
  for dhid, (name, position) in zhv.items():
    grid[(int(position[0] / cell), int(position[1] / cell))].append((dhid, normalize_name(name), position))
  assigned = {}
  for sp in stop_places.values():
    if sp.dhid or sp.parent or not sp.position:
      continue
    name = normalize_name(sp.name)
    gx, gy = int(sp.position[0] / cell), int(sp.position[1] / cell)
    candidates = sorted(
      (distance(sp.position, position), dhid)
      for dx in (-1, 0, 1)
      for dy in (-1, 0, 1)
      for dhid, zhv_name, position in grid.get((gx + dx, gy + dy), [])
      if zhv_name == name
    )
    candidates = [c for c in candidates if c[0] <= max_distance]
    if candidates:
      sp.dhid = candidates[0][1]
      assigned[sp.id] = sp.dhid
      report.add("dhid-assigned-from-zhv", f"{sp.id} „{sp.name}“ → {sp.dhid} ({candidates[0][0]:.0f} m)")
    else:
      report.add("stop-place-without-dhid", f"{sp.id} „{sp.name}“")
  return assigned


def plausible(sp, members, zhv, max_distance, report):
  if not sp.position:
    report.add("dhid-not-merged-without-position", f"{sp.id} ({sp.dhid})")
    return False
  reference = zhv.get(sp.dhid) or zhv.get(stop_level(sp.dhid)) if zhv else None
  if reference:
    d = distance(sp.position, reference[1])
    if d > max_distance:
      report.add(
        "dhid-implausible",
        f"{sp.id} „{sp.name}“ is {d:.0f} m from {sp.dhid} „{reference[0]}“ in the zHV",
        limit=1000,
      )
      return False
    return True
  others = [o.position for o in members if o is not sp and o.position]
  if not others:
    return True
  lat = sorted(p[0] for p in others)[len(others) // 2]
  lon = sorted(p[1] for p in others)[len(others) // 2]
  d = distance(sp.position, (lat, lon))
  if d > max_distance:
    report.add("dhid-implausible", f"{sp.id} „{sp.name}“ is {d:.0f} m from the other stop places of {sp.dhid}", limit=1000)
    return False
  return True


def merge_plan(stop_places, zhv, max_distance, report):
  """Old stop place id -> new stop place id."""
  by_dhid = collections.defaultdict(list)
  for sp in stop_places.values():
    if sp.dhid and not sp.parent:
      by_dhid[sp.dhid].append(sp)
  renames = {}
  for dhid, members in sorted(by_dhid.items()):
    if len(members) < 2:
      continue
    members = [sp for sp in members if plausible(sp, members, zhv, max_distance, report)]
    if len(members) < 2:
      continue
    directories = [d for sp in members for d in sp.directories()]
    if len(directories) != len(set(directories)):
      report.add("not-merged-same-directory", f"{dhid}: " + ", ".join(sp.id for sp in members))
      continue
    parents = [sp for sp in members if sp.children]
    if 0 < len(parents) < len(members):
      report.add("not-merged-parent-and-simple-stop-place", f"{dhid}: " + ", ".join(sp.id for sp in members))
      continue
    # The stop place used by most directories keeps its id
    members.sort(key=lambda sp: (-len(sp.directories()), sp.id))
    target = members[0]
    for sp in members[1:]:
      renames[sp.id] = target.id
    if parents and all(len(sp.children) == 1 for sp in members):
      target_child = next(iter(target.children))
      for sp in members[1:]:
        renames[next(iter(sp.children))] = target_child
      report.add("merged-parent-and-child", f"{dhid}: " + ", ".join(sp.id for sp in members) + f" → {target.id}")
    elif parents:
      report.add("merged-parent", f"{dhid}: " + ", ".join(sp.id for sp in members) + f" → {target.id}")
    else:
      report.add("merged-stop-place", f"{dhid}: " + ", ".join(sp.id for sp in members) + f" → {target.id}")
  return renames


# ---------------------------------------------------------------------------------------------
# Writing


def add_global_id(element, dhid):
  key = (
    f"<KeyValue><Key>GlobalID</Key><Value>{dhid}</Value></KeyValue>"
    f"<KeyValue><Key>GlobalIDSource</Key><Value>zHV name and position</Value></KeyValue>"
  )
  own = element.split("<quays>", 1)[0]
  if "<keyList>" in own:
    return element.replace("<keyList>", "<keyList>" + key, 1)
  start = element.index(">") + 1
  return element[:start] + "<keyList>" + key + "</keyList>" + element[start:]


def rewrite(text, renames, assigned):
  if assigned:
    def assign(match):
      id_match = STOP_PLACE_ID.match(match.group(0))
      if id_match and id_match.group(1) in assigned:
        return add_global_id(match.group(0), assigned[id_match.group(1)])
      return match.group(0)

    text = STOP_PLACE.sub(assign, text)
  if renames:
    text = QUOTED_VALUE.sub(lambda m: f'"{renames.get(m.group(1), m.group(1))}"', text)
  return text


def main(argv=None):
  parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument("input")
  parser.add_argument("output")
  parser.add_argument("--zhv", help="zHV XML export (or zip with it) used as reference")
  parser.add_argument("--max-distance", type=float, default=300, help="meters, default 300")
  parser.add_argument("--assign-dhid", action="store_true", help="derive missing DHIDs from the zHV by name and position")
  parser.add_argument("--assign-distance", type=float, default=150, help="meters, default 150")
  parser.add_argument("--report", help="write the report to this file instead of stderr")
  args = parser.parse_args(argv)
  if args.assign_dhid and not args.zhv:
    parser.error("--assign-dhid needs --zhv")

  source = Source(args.input)
  names = source.names()
  xml_names = [n for n in names if n.endswith(".xml")]
  report = Report()
  stop_places = scan(source, xml_names)
  zhv = read_zhv(args.zhv) if args.zhv else {}
  assigned = assign_dhids(stop_places, zhv, args.assign_distance, report) if args.assign_dhid else {}
  renames = merge_plan(stop_places, zhv, args.max_distance, report)
  report.counts["stop-places"] = len(stop_places)
  report.counts["stop-place-ids-renamed"] = len(renames)

  target = Target(args.output, args.output.endswith(".zip"))
  changed_files = 0
  for name in names:
    data = source.read(name)
    if name.endswith(".xml") and (renames or assigned):
      text = data.decode("utf-8")
      new_text = rewrite(text, renames, assigned)
      if new_text != text:
        changed_files += 1
        data = new_text.encode("utf-8")
    target.write(name, data)
  target.close()
  report.counts["files-changed"] = changed_files

  out = open(args.report, "w", encoding="utf-8") if args.report else sys.stderr
  report.write(out)
  if args.report:
    out.close()


if __name__ == "__main__":
  main()
