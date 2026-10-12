#!/usr/bin/env python3
"""
Convert a NeTEx export following the VDV 452 -> NeTEx mapping (VehicleJourney + ServicePattern
with a timing model, no passing times) into NeTEx that OpenTripPlanner can import:
ServiceJourneys with TimetabledPassingTimes, ServiceJourneyPatterns, lines with a transport
mode, and stop places with quays.

The tool is not part of the OTP build. It uses the Python standard library only.

Semantics (VDV 452 / "VDV 452 - NeTEx Datenzuordnung"):
- TRAVEL_TIME (JourneyPatternRunTime) is the run time per running time group (TimeDemandType)
  and route section (TimingLink from point -> to point). It does not depend on the pattern, so
  run times are collected from all patterns.
- WAIT_TIME (JourneyPatternWaitTime) is the stopping time per running time group and point,
  also independent of the pattern. JOURNEY_WAIT_TIME (VehicleJourneyWaitTime) and journey
  specific run times (VehicleJourneyRunTime) replace them for one journey.
- ROUTE_TYPE (TypeOfJourneyPattern) 1 is the normal (passenger) profile; 2, 3 and 4 are depot
  runs, which are not converted.
- There is no wait time at the first and the last stop of a journey.

Every correction and every gap in the data is reported, so the data can be fixed at the source.
"""

import argparse
import collections
import datetime
import math
import re
import statistics
import sys
import xml.etree.ElementTree as ET

NETEX = "http://www.netex.org.uk/netex"
NS = "{" + NETEX + "}"

# Bounding box used to detect swapped coordinates (Germany and its neighbours)
LAT_RANGE = (45.0, 56.0)
LON_RANGE = (4.0, 17.0)

TRANSPORT_MODES = {
  "BUS": "bus",
  "TRAM": "tram",
  "STR": "tram",
  "STRASSENBAHN": "tram",
  "U": "metro",
  "UBAHN": "metro",
  "METRO": "metro",
  "S": "rail",
  "SBAHN": "rail",
  "RAIL": "rail",
  "ZUG": "rail",
  "FAEHRE": "water",
  "SCHIFF": "water",
  "SEILBAHN": "cableway",
}

DURATION = re.compile(
  r"^(-)?P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$"
)


class Report:
  """Collects corrections and problems, grouped by type, with a few examples each."""

  def __init__(self):
    self.counts = collections.Counter()
    self.examples = collections.defaultdict(list)
    self.texts = {}

  def add(self, key, text, example=None):
    self.counts[key] += 1
    self.texts[key] = text
    if example is not None and len(self.examples[key]) < 5:
      self.examples[key].append(example)

  def write(self, out):
    for key, count in sorted(self.counts.items()):
      out.write(f"{count:7d}  {key}: {self.texts[key]}\n")
      for example in self.examples[key]:
        out.write(f"           e.g. {example}\n")


def local(element):
  return element.tag.split("}")[-1]


def text(element, name):
  child = element.find(NS + name)
  return None if child is None or child.text is None else child.text.strip()


def ref(element, name):
  child = element.find(NS + name)
  return None if child is None else child.get("ref")


def parse_duration(value, report, context):
  """Seconds of an xsd:duration. "-P0Y0M0DT0H0M0S" (negative zero) is read as zero."""
  m = DURATION.match(value or "")
  if not m:
    report.add("invalid-duration", "Duration can not be parsed; the value is ignored", f"{context}: {value}")
    return None
  negative, years, months, days, hours, minutes, seconds = m.groups()
  if (years and int(years)) or (months and int(months)):
    report.add("invalid-duration", "Duration with years or months; the value is ignored", f"{context}: {value}")
    return None
  total = int(days or 0) * 86400 + int(hours or 0) * 3600 + int(minutes or 0) * 60
  total += round(float(seconds or 0))
  if negative:
    if total != 0:
      report.add("negative-duration", "Negative duration; the value is ignored", f"{context}: {value}")
      return None
    report.add(
      "negative-zero-duration",
      "Duration '-P0Y0M0DT0H0M0S' (negative zero) is read as 0; it should be written as PT0S",
      context,
    )
  return total


def parse_time(value):
  h, m, s = (value.split(":") + ["0", "0"])[:3]
  return int(h) * 3600 + int(m) * 60 + int(float(s))


def format_time(seconds):
  day, rest = divmod(seconds, 86400)
  return f"{rest // 3600:02d}:{rest % 3600 // 60:02d}:{rest % 60:02d}", day


def coordinate_value(value):
  """Decimal degrees, or VDV 452 dddmmssnnn (degrees, minutes, seconds, milliseconds)."""
  number = float(value)
  if abs(number) <= 180:
    return number
  sign = -1 if number < 0 else 1
  digits = f"{abs(int(number)):010d}"
  degrees, minutes, millis = int(digits[:3]), int(digits[3:5]), int(digits[5:])
  return sign * (degrees + minutes / 60 + millis / 3600000)


def haversine(a, b):
  lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
  h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
  return 2 * 6371000 * math.asin(math.sqrt(h))


def inside(lat, lon):
  return LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]


class Converter:

  def __init__(self, root, args, report):
    self.root = root
    self.args = args
    self.report = report
    self.codespace = args.codespace
    self.by_id = collections.defaultdict(dict)
    for element in root.iter():
      if element.get("id") is not None:
        self.by_id[local(element)][element.get("id")] = element

  def nid(self, kind, original_id):
    return f"{self.codespace}:{kind}:{original_id}"

  # ----------------------------------------------------------------------------------------
  # Input model
  # ----------------------------------------------------------------------------------------

  def collect_time_demand_types(self):
    """
    Running time groups are identified by their number (VDV 452 TIMING_GROUP_NO, mapped to the
    PrivateCode of the TimeDemandType). Several TimeDemandTypes with the same number are one group.
    """
    self.timing_group = {}
    ids_by_number = collections.defaultdict(list)
    for type_id, element in self.by_id["TimeDemandType"].items():
      number = text(element, "PrivateCode") or type_id
      self.timing_group[type_id] = number
      ids_by_number[number].append(type_id)
    for number, ids in sorted(ids_by_number.items()):
      if len(ids) > 1:
        self.report.add(
          "time-demand-type-duplicated",
          "Several TimeDemandTypes have the same running time group number (PrivateCode); they are "
          "treated as one group. Each group should be exported once",
          f"group {number}: {', '.join(sorted(ids))}",
        )

  def time_demand_type(self, ref_id, context):
    """The running time group number of a TimeDemandTypeRef."""
    if ref_id is None:
      return None
    if ref_id not in self.timing_group:
      self.report.add(
        "time-demand-type-ref-unknown", "TimeDemandTypeRef references no TimeDemandType", f"{context}: {ref_id}"
      )
      return ref_id
    return self.timing_group[ref_id]

  def collect_timing(self):
    links = {}
    for link_id, link in self.by_id["TimingLink"].items():
      links[link_id] = (ref(link, "FromPointRef"), ref(link, "ToPointRef"))

    self.run_times = {}
    self.journey_run_times = {}
    for element in self.root.iter(NS + "JourneyPatternRunTime"):
      self._add_run_time(element, links, self.run_times, None)
    for element in self.root.iter(NS + "VehicleJourneyRunTime"):
      journey = ref(element, "VehicleJourneyRef")
      if journey is not None:
        self._add_run_time(element, links, self.journey_run_times, journey)

    self.wait_times = {}
    for element in self.root.iter(NS + "JourneyPatternWaitTime"):
      tdt = self.time_demand_type(ref(element, "TimeDemandTypeRef"), element.get("id"))
      point = ref(element, "TimingPointRef") or ref(element, "ScheduledStopPointRef")
      seconds = parse_duration(text(element, "WaitTime"), self.report, f"JourneyPatternWaitTime {element.get('id')}")
      if point is None or seconds is None:
        continue
      previous = self.wait_times.setdefault((point, tdt), seconds)
      if previous != seconds:
        self.report.add(
          "wait-time-conflict",
          "Different wait times for the same point and time demand type; the first one is used",
          f"{point}/{tdt}: {previous}s and {seconds}s",
        )

    self.journey_wait_times = {}
    for journey in self.by_id["VehicleJourney"].values():
      for element in journey.iter(NS + "VehicleJourneyWaitTime"):
        point = ref(element, "ScheduledStopPointRef") or ref(element, "TimingPointRef")
        seconds = parse_duration(text(element, "WaitTime"), self.report, f"VehicleJourneyWaitTime {element.get('id')}")
        if point is not None and seconds is not None:
          self.journey_wait_times[(journey.get("id"), point)] = seconds

  def _add_run_time(self, element, links, target, journey):
    link = links.get(ref(element, "TimingLinkRef"))
    context = f"{local(element)} {element.get('id') or ''} TimingLink {ref(element, 'TimingLinkRef')}"
    if link is None:
      self.report.add("timing-link-unknown", "Run time references an unknown TimingLink", context)
      return
    tdt = self.time_demand_type(ref(element, "TimeDemandTypeRef"), context)
    seconds = parse_duration(text(element, "RunTime"), self.report, context)
    if seconds is None:
      return
    key = (journey, link[0], link[1], tdt) if journey else (link[0], link[1], tdt)
    previous = target.setdefault(key, seconds)
    if previous != seconds:
      self.report.add(
        "run-time-conflict",
        "Different run times for the same route section and time demand type; the first one is used",
        f"{link[0]} -> {link[1]} / {tdt}: {previous}s and {seconds}s",
      )

  def collect_coordinates(self):
    """Coordinates of the scheduled stop points, with swapped axes fixed."""
    self.coordinates = {}
    for ssp_id, ssp in self.by_id["ScheduledStopPoint"].items():
      location = ssp.find(NS + "Location")
      if location is None or text(location, "Latitude") is None or text(location, "Longitude") is None:
        self.report.add("stop-point-without-location", "ScheduledStopPoint has no Location", ssp_id)
        continue
      lat = coordinate_value(text(location, "Latitude"))
      lon = coordinate_value(text(location, "Longitude"))
      swap = self.args.swap_coordinates == "yes" or (
        self.args.swap_coordinates == "auto" and not inside(lat, lon) and inside(lon, lat)
      )
      if swap:
        lat, lon = lon, lat
        self.report.add(
          "coordinates-swapped",
          "Latitude and Longitude are swapped (Longitude contains the latitude); they are exchanged",
          ssp_id,
        )
      elif not inside(lat, lon):
        self.report.add("coordinates-outside-area", "Location outside the expected area", f"{ssp_id}: {lat}, {lon}")
      self.coordinates[ssp_id] = (lat, lon)

  def transport_mode(self, line):
    mode = text(line, "TransportMode")
    if mode:
      return mode
    context = self.by_id["OperationalContext"].get(ref(line, "OperationalContextRef"))
    keys = []
    if context is not None:
      keys = [text(context, "ShortName"), text(context, "Name")]
    for key in keys:
      normalized = re.sub(r"[^A-Z]", "", (key or "").upper().replace("Ä", "AE").replace("Ö", "OE").replace("Ü", "UE"))
      if normalized in self.args.mode_map:
        mode = self.args.mode_map[normalized]
      elif normalized in TRANSPORT_MODES:
        mode = TRANSPORT_MODES[normalized]
      if mode:
        self.report.add(
          "line-mode-from-operational-context",
          "Line has no TransportMode; it is derived from the OperationalContext (VDV 452 OP_DEP)",
          f"{line.get('id')}: {key} -> {mode}",
        )
        return mode
    self.report.add(
      "line-without-mode",
      "Line has no TransportMode and its OperationalContext is not known; the line is dropped. Use --mode",
      f"{line.get('id')}: {keys}",
    )
    return None

  def is_passenger_pattern(self, pattern):
    type_ref = ref(pattern, "TypeOfJourneyPatternRef")
    if type_ref is None:
      return True
    pattern_type = self.by_id["TypeOfJourneyPattern"].get(type_ref)
    return pattern_type is None or text(pattern_type, "PrivateCode") in (None, "1")

  def pattern_line(self, pattern):
    route = self.by_id["Route"].get(ref(pattern, "RouteRef"))
    if route is None:
      view = pattern.find(NS + "RouteView")
      return (ref(view, "LineRef") if view is not None else None), None
    direction = self.by_id["Direction"].get(ref(route, "DirectionRef"))
    direction_type = text(route, "DirectionType") or (text(direction, "DirectionType") if direction is not None else None)
    return ref(route, "LineRef"), direction_type

  # ----------------------------------------------------------------------------------------
  # Journeys
  # ----------------------------------------------------------------------------------------

  def convert_journeys(self):
    self.journeys = []
    self.estimated_journeys = set()
    self.stop_place_by_ssp = {
      ref(psa, "ScheduledStopPointRef"): ref(psa, "StopPlaceRef")
      for psa in self.by_id["PassengerStopAssignment"].values()
    }
    to_estimate = []
    for journey_id, journey in self.by_id["VehicleJourney"].items():
      candidate = self.journey_candidate(journey_id, journey)
      if candidate is None:
        continue
      runs = self.known_run_times(candidate)
      if all(run is not None for run in runs):
        self.journeys.append((journey, candidate["pattern"], self.passing_times(candidate, runs)))
      elif self.args.estimate_run_times_from_blocks:
        to_estimate.append((candidate, runs))
      else:
        i = runs.index(None)
        self.report.add(
          "run-time-missing",
          "No run time for a route section and the time demand type of the journey; journey dropped. "
          "VDV 452 TRAVEL_TIME (SEL_FZT_FELD) is missing",
          f"{candidate['ssps'][i]} -> {candidate['ssps'][i + 1]} / {candidate['tdt']} (journey {journey_id})",
        )
    if to_estimate:
      self.estimate(to_estimate)

  def journey_candidate(self, journey_id, journey):
    """The pattern, stop points, start time and running time group of a passenger journey."""
    pattern = self.by_id["ServicePattern"].get(ref(journey, "ServicePatternRef"))
    if pattern is None:
      pattern = self.by_id["ServiceJourneyPattern"].get(ref(journey, "ServiceJourneyPatternRef"))
    if pattern is None:
      self.report.add("journey-without-pattern", "VehicleJourney references no pattern; dropped", journey_id)
      return None
    if not self.is_passenger_pattern(pattern):
      self.report.add(
        "depot-run-skipped",
        "Journey on a depot run pattern (ROUTE_TYPE 2-4) is not a passenger journey; skipped",
        journey_id,
      )
      return None
    departure = text(journey, "DepartureTime")
    if departure is None:
      self.report.add("journey-without-departure", "VehicleJourney has no DepartureTime; dropped", journey_id)
      return None
    start = parse_time(departure)
    if self.args.day_offset_before is not None and start < self.args.day_offset_before:
      start += 86400
      self.report.add(
        "departure-after-midnight",
        "Departure before --day-offset-before belongs to the previous operating day; day offset 1 is set",
        f"{journey_id}: {departure}",
      )
    tdt_ref = ref(journey, "TimeDemandTypeRef")
    types = journey.find(NS + "timeDemandTypes")
    if tdt_ref is None and types is not None and types.find(NS + "TimeDemandTypeRef") is not None:
      tdt_ref = types.find(NS + "TimeDemandTypeRef").get("ref")
    points = [p for p in pattern.find(NS + "pointsInSequence") if local(p) == "StopPointInJourneyPattern"]
    if any(p.get("order") for p in points):
      points.sort(key=lambda p: int(p.get("order") or 0))
    return {
      "id": journey_id,
      "journey": journey,
      "pattern": pattern,
      "points": points,
      "ssps": [ref(p, "ScheduledStopPointRef") for p in points],
      "start": start,
      "tdt": self.time_demand_type(tdt_ref, journey_id),
    }

  def known_run_times(self, candidate):
    """The run time of each route section, None if unknown."""
    ssps, tdt, journey_id = candidate["ssps"], candidate["tdt"], candidate["id"]
    runs = []
    for a, b in zip(ssps, ssps[1:]):
      run = self.journey_run_times.get((journey_id, a, b, tdt))
      if run is None:
        run = self.run_times.get((a, b, tdt))
      if run == 0:
        if self.args.estimate_run_times_from_blocks:
          self.report.add(
            "run-time-zero-ignored",
            "Run time 0 for a route section between two stop points is treated as missing and estimated",
            f"{a} -> {b} / {tdt}",
          )
          run = None
        else:
          self.report.add(
            "run-time-zero",
            "Run time 0 for a route section; the arrival equals the previous departure",
            f"{a} -> {b} / {tdt}",
          )
      runs.append(run)
    return runs

  def wait_time(self, candidate, ssp):
    wait = self.journey_wait_times.get((candidate["id"], ssp))
    return self.wait_times.get((ssp, candidate["tdt"]), 0) if wait is None else wait

  def passing_times(self, candidate, runs):
    """Arrival and departure times from the start time, the run times and the wait times."""
    ssps = candidate["ssps"]
    times = []
    time = candidate["start"]
    for i, ssp in enumerate(ssps):
      arrival = None
      if i > 0:
        time += runs[i - 1]
        arrival = round(time)
      departure = None
      if i < len(ssps) - 1:
        if i > 0:
          time += self.wait_time(candidate, ssp)
        departure = round(time)
      times.append((candidate["points"][i], arrival, departure))
    return times

  # ----------------------------------------------------------------------------------------
  # Estimation from vehicle blocks (opt-in)
  # ----------------------------------------------------------------------------------------

  def estimate(self, to_estimate):
    """
    Estimate missing run times from the vehicle blocks. If the vehicle runs another journey from
    the end stop of a journey, the difference of their departure times is the run time plus the
    wait times plus the layover at the end stop. That total is distributed over the route sections
    without run time in proportion to their length. For a pattern without such a pair of journeys,
    the median speed of the other patterns is used.
    """
    self.next_in_block = {}
    for block in self.by_id["Block"].values():
      journeys = block.find(NS + "journeys")
      ids = [] if journeys is None else [r.get("ref") for r in journeys]
      for a, b in zip(ids, ids[1:]):
        self.next_in_block[a] = b
    if not self.next_in_block:
      self.report.add("no-blocks", "The data has no vehicle blocks; run times can not be estimated", None)

    candidates = {c["id"]: c for c, _ in to_estimate}
    all_candidates = dict(candidates)
    for journey_id, journey in self.by_id["VehicleJourney"].items():
      if journey_id not in all_candidates and self.next_in_block.get(journey_id):
        candidate = self.journey_candidate_quietly(journey_id, journey)
        if candidate is not None:
          all_candidates[journey_id] = candidate

    run_totals = {}
    for journey_id, candidate in all_candidates.items():
      total = self.run_total_from_block(candidate)
      if total is not None:
        run_totals[journey_id] = total
    shortest = {}
    for journey_id, total in run_totals.items():
      pattern_id = all_candidates[journey_id]["pattern"].get("id")
      shortest[pattern_id] = min(total, shortest.get(pattern_id, total))

    speeds = []
    for pattern_id, total in sorted(shortest.items()):
      some = next(c for c in all_candidates.values() if c["pattern"].get("id") == pattern_id)
      length = self.length(some["ssps"])
      speed = length / total * 3.6
      self.report.add(
        "pattern-speed-from-blocks",
        "Shortest run time of a pattern from the vehicle blocks (length, time, mean speed)",
        f"{pattern_id}: {length:.0f} m in {total} s = {speed:.1f} km/h",
      )
      if 5 <= speed <= 80:
        speeds.append(speed)
      else:
        self.report.add(
          "pattern-speed-implausible",
          "Mean speed from the vehicle blocks is implausible; the pattern uses the median speed",
          f"{pattern_id}: {speed:.1f} km/h",
        )
        del shortest[pattern_id]
    median_speed = statistics.median(speeds) if speeds else None

    for candidate, runs in to_estimate:
      self.estimate_journey(candidate, runs, run_totals, shortest, median_speed)

  def journey_candidate_quietly(self, journey_id, journey):
    """A journey candidate without reporting, used for the following journeys in a block."""
    report = self.report
    self.report = Report()
    try:
      return self.journey_candidate(journey_id, journey)
    finally:
      self.report = report

  def run_total_from_block(self, candidate):
    """Run time of all route sections from the departure of the next journey of the vehicle."""
    next_id = self.next_in_block.get(candidate["id"])
    next_journey = self.by_id["VehicleJourney"].get(next_id)
    if next_journey is None or text(next_journey, "DepartureTime") is None:
      return None
    next_pattern = self.by_id["ServicePattern"].get(ref(next_journey, "ServicePatternRef"))
    if next_pattern is None:
      return None
    first = [p for p in next_pattern.find(NS + "pointsInSequence") if local(p) == "StopPointInJourneyPattern"]
    end, next_start = candidate["ssps"][-1], ref(first[0], "ScheduledStopPointRef") if first else None
    same_place = self.stop_place_by_ssp.get(end) is not None and self.stop_place_by_ssp.get(
      end
    ) == self.stop_place_by_ssp.get(next_start)
    if end != next_start and not same_place:
      return None
    gap = (parse_time(text(next_journey, "DepartureTime")) - candidate["start"]) % 86400
    waits = sum(self.wait_time(candidate, s) for s in candidate["ssps"][1:-1])
    total = gap - waits - self.args.min_layover
    return total if total > 0 else None

  def length(self, ssps):
    return sum(self.section_length(a, b) for a, b in zip(ssps, ssps[1:]))

  def section_length(self, a, b):
    if not hasattr(self, "service_link_lengths"):
      self.service_link_lengths = {}
      for link in self.by_id["ServiceLink"].values():
        distance = text(link, "Distance")
        if distance:
          self.service_link_lengths[(ref(link, "FromPointRef"), ref(link, "ToPointRef"))] = float(distance)
    length = self.service_link_lengths.get((a, b))
    if length:
      return length
    if a in self.coordinates and b in self.coordinates:
      self.report.add(
        "section-length-from-coordinates",
        "No ServiceLink Distance for a route section; 1.3 times the straight line distance is used",
        f"{a} -> {b}",
      )
      return 1.3 * haversine(self.coordinates[a], self.coordinates[b])
    self.report.add("section-length-unknown", "No length for a route section; 500 m are assumed", f"{a} -> {b}")
    return 500.0

  def estimate_journey(self, candidate, runs, run_totals, shortest, median_speed):
    journey_id, pattern_id, ssps = candidate["id"], candidate["pattern"].get("id"), candidate["ssps"]
    own, pattern_total = run_totals.get(journey_id), shortest.get(pattern_id)
    if own is not None and pattern_total is not None and own <= 1.3 * pattern_total:
      total, method = own, "block"
    elif pattern_total is not None:
      total, method = pattern_total, "pattern"
    elif median_speed is not None:
      total, method = self.length(ssps) / (median_speed / 3.6), "speed"
    else:
      self.report.add(
        "run-time-not-estimated", "Run times can not be estimated for the journey; journey dropped", journey_id
      )
      return
    known = sum(run for run in runs if run is not None)
    unknown_sections = [i for i, run in enumerate(runs) if run is None]
    unknown_length = sum(self.section_length(ssps[i], ssps[i + 1]) for i in unknown_sections)
    remaining = total - known
    if remaining <= 0 or unknown_length <= 0:
      self.report.add(
        "run-time-not-estimated",
        "The given run times exceed the estimated total; journey dropped",
        f"{journey_id}: {known} s given, {total:.0f} s estimated",
      )
      return
    estimated = list(runs)
    for i in unknown_sections:
      estimated[i] = remaining * self.section_length(ssps[i], ssps[i + 1]) / unknown_length
    self.report.add(
      f"run-times-estimated-{method}",
      "Run times ESTIMATED (not for passenger information); the journey is marked with a notice. "
      + ESTIMATION_METHODS[method],
      f"{journey_id}: {len(unknown_sections)} of {len(runs)} sections, {total:.0f} s"
      + (f" at {median_speed:.1f} km/h" if method == "speed" else ""),
    )
    self.estimated_journeys.add(journey_id)
    self.journeys.append((candidate["journey"], candidate["pattern"], self.passing_times(candidate, estimated)))

  # ----------------------------------------------------------------------------------------
  # Output
  # ----------------------------------------------------------------------------------------

  def build(self):
    self.collect_time_demand_types()
    self.collect_timing()
    self.collect_coordinates()
    self.convert_journeys()

    used_patterns = {p.get("id"): p for _, p, _ in self.journeys}
    lines = {}
    for pattern in used_patterns.values():
      line_id, _ = self.pattern_line(pattern)
      line = self.by_id["Line"].get(line_id)
      if line is None:
        self.report.add("pattern-without-line", "Pattern references no Line", pattern.get("id"))
        continue
      if line_id not in lines:
        mode = self.transport_mode(line)
        if mode:
          lines[line_id] = (line, mode)
    self.journeys = [j for j in self.journeys if self.pattern_line(j[1])[0] in lines]
    used_patterns = {p.get("id"): p for _, p, _ in self.journeys}

    used_ssps = set()
    for pattern in used_patterns.values():
      for point in pattern.find(NS + "pointsInSequence"):
        used_ssps.add(ref(point, "ScheduledStopPointRef"))
    stop_place_by_ssp = {}
    for psa in self.by_id["PassengerStopAssignment"].values():
      stop_place_by_ssp[ref(psa, "ScheduledStopPointRef")] = ref(psa, "StopPlaceRef")

    root = ET.Element(NS + "PublicationDelivery", {"version": "1.0"})
    ET.SubElement(root, NS + "PublicationTimestamp").text = datetime.datetime.now().replace(microsecond=0).isoformat()
    ET.SubElement(root, NS + "ParticipantRef").text = self.codespace
    composite = ET.SubElement(
      ET.SubElement(root, NS + "dataObjects"),
      NS + "CompositeFrame",
      {"id": self.nid("CompositeFrame", "1"), "version": "1"},
    )
    defaults = ET.SubElement(composite, NS + "FrameDefaults")
    ET.SubElement(ET.SubElement(defaults, NS + "DefaultLocale"), NS + "TimeZone").text = self.args.time_zone
    ET.SubElement(defaults, NS + "DefaultLocationSystem").text = "urn:ogc:def:crs:EPSG::4326"
    frames = ET.SubElement(composite, NS + "frames")

    self.resource_frame(frames)
    self.site_frame(frames, used_ssps, stop_place_by_ssp)
    self.calendar_frame(frames)
    service_frame = self.service_frame(frames, lines, used_patterns, used_ssps, stop_place_by_ssp)
    if self.estimated_journeys:
      self.estimated_notice(service_frame)
    self.timetable_frame(frames, lines)
    return root

  def element(self, parent, name, kind, original_id):
    return ET.SubElement(parent, NS + name, {"id": self.nid(kind, original_id), "version": "1"})

  def reference(self, parent, name, kind, original_id):
    return ET.SubElement(parent, NS + name, {"ref": self.nid(kind, original_id), "version": "1"})

  def resource_frame(self, frames):
    frame = self.element(frames, "ResourceFrame", "ResourceFrame", "1")
    organisations = ET.SubElement(frame, NS + "organisations")
    for operator_id, operator in self.by_id["Operator"].items():
      out = self.element(organisations, "Operator", "Operator", operator_id)
      ET.SubElement(out, NS + "Name").text = text(operator, "Name") or text(operator, "ShortName") or operator_id
      if text(operator, "ShortName"):
        ET.SubElement(out, NS + "ShortName").text = text(operator, "ShortName")
      ET.SubElement(out, NS + "OrganisationType").text = "operator"

  def site_frame(self, frames, used_ssps, stop_place_by_ssp):
    frame = self.element(frames, "SiteFrame", "SiteFrame", "1")
    stop_places = ET.SubElement(frame, NS + "stopPlaces")
    ssps_by_stop_place = collections.defaultdict(list)
    for ssp in sorted(used_ssps):
      stop_place_id = stop_place_by_ssp.get(ssp)
      if stop_place_id is None:
        self.report.add(
          "stop-point-without-stop",
          "ScheduledStopPoint is not assigned to a StopPlace (VDV 452 REC_ORT); a stop place is created for it",
          ssp,
        )
        stop_place_id = ssp
      ssps_by_stop_place[stop_place_id].append(ssp)
    self.quay_by_ssp = {}
    self.stop_place_of_ssp = {}
    for stop_place_id, ssps in sorted(ssps_by_stop_place.items()):
      source = self.by_id["StopPlace"].get(stop_place_id)
      located = [s for s in ssps if s in self.coordinates]
      if not located:
        self.report.add("stop-place-without-location", "No stop point of the stop place has a location; stop place dropped", stop_place_id)
        continue
      stop_place = self.element(stop_places, "StopPlace", "StopPlace", stop_place_id)
      name = (text(source, "Name") if source is not None else None) or text(self.by_id["ScheduledStopPoint"][located[0]], "Name")
      ET.SubElement(stop_place, NS + "Name").text = name
      if source is not None and source.find(NS + "Centroid") is None:
        self.report.add(
          "stop-place-centroid-derived",
          "StopPlace has no Centroid; the mean position of its stop points is used",
          stop_place_id,
        )
      self.centroid(
        stop_place,
        statistics.fmean(self.coordinates[s][0] for s in located),
        statistics.fmean(self.coordinates[s][1] for s in located),
      )
      quays = ET.SubElement(stop_place, NS + "quays")
      for ssp in located:
        source_ssp = self.by_id["ScheduledStopPoint"][ssp]
        quay = self.element(quays, "Quay", "Quay", ssp)
        ET.SubElement(quay, NS + "Name").text = text(source_ssp, "Name") or name
        self.centroid(quay, *self.coordinates[ssp])
        self.quay_by_ssp[ssp] = ssp
        self.stop_place_of_ssp[ssp] = stop_place_id

  def centroid(self, parent, lat, lon):
    location = ET.SubElement(ET.SubElement(parent, NS + "Centroid"), NS + "Location")
    ET.SubElement(location, NS + "Longitude").text = f"{lon:.7f}"
    ET.SubElement(location, NS + "Latitude").text = f"{lat:.7f}"

  def calendar_frame(self, frames):
    frame = self.element(frames, "ServiceCalendarFrame", "ServiceCalendarFrame", "1")
    day_types = ET.SubElement(frame, NS + "dayTypes")
    for day_type_id, day_type in self.by_id["DayType"].items():
      out = self.element(day_types, "DayType", "DayType", day_type_id)
      if text(day_type, "Name"):
        ET.SubElement(out, NS + "Name").text = text(day_type, "Name")
    assignments = ET.SubElement(frame, NS + "dayTypeAssignments")
    order = 0
    for assignment_id, assignment in self.by_id["DayTypeAssignment"].items():
      date = text(assignment, "Date")
      if date is None:
        operating_day = self.by_id["OperatingDay"].get(ref(assignment, "OperatingDayRef"))
        date = text(operating_day, "CalendarDate") if operating_day is not None else None
      if date is None:
        self.report.add("day-type-assignment-without-date", "DayTypeAssignment has no date; ignored", assignment_id)
        continue
      order += 1
      out = ET.SubElement(
        assignments,
        NS + "DayTypeAssignment",
        {"id": self.nid("DayTypeAssignment", assignment_id), "version": "1", "order": str(order)},
      )
      ET.SubElement(out, NS + "Date").text = date[:10]
      self.reference(out, "DayTypeRef", "DayType", ref(assignment, "DayTypeRef"))
      available = text(assignment, "isAvailable")
      if available is not None:
        ET.SubElement(out, NS + "isAvailable").text = available

  def service_frame(self, frames, lines, used_patterns, used_ssps, stop_place_by_ssp):
    frame = self.element(frames, "ServiceFrame", "ServiceFrame", "1")
    out_lines = ET.SubElement(frame, NS + "lines")
    operators = list(self.by_id["Operator"])
    for line_id, (line, mode) in sorted(lines.items()):
      out = self.element(out_lines, "Line", "Line", line_id)
      ET.SubElement(out, NS + "Name").text = text(line, "Name") or text(line, "ShortName") or line_id
      if text(line, "ShortName"):
        ET.SubElement(out, NS + "ShortName").text = text(line, "ShortName")
      ET.SubElement(out, NS + "TransportMode").text = mode
      ET.SubElement(out, NS + "PublicCode").text = text(line, "PublicCode") or text(line, "ShortName") or text(line, "Name")
      if len(operators) == 1:
        self.reference(out, "OperatorRef", "Operator", operators[0])
      elif operators:
        self.report.add("line-without-operator", "Several operators and no OperatorRef on the line", line_id)

    used_displays = set()
    for pattern in used_patterns.values():
      for point in pattern.find(NS + "pointsInSequence"):
        if ref(point, "DestinationDisplayRef"):
          used_displays.add(ref(point, "DestinationDisplayRef"))
    displays = ET.SubElement(frame, NS + "destinationDisplays")
    for display_id in sorted(used_displays):
      display = self.by_id["DestinationDisplay"].get(display_id)
      if display is None:
        continue
      out = self.element(displays, "DestinationDisplay", "DestinationDisplay", display_id)
      for name in ("SideText", "FrontText"):
        if text(display, name):
          ET.SubElement(out, NS + name).text = text(display, name)

    stop_points = ET.SubElement(frame, NS + "scheduledStopPoints")
    for ssp in sorted(s for s in used_ssps if s in self.quay_by_ssp):
      source = self.by_id["ScheduledStopPoint"][ssp]
      out = self.element(stop_points, "ScheduledStopPoint", "ScheduledStopPoint", ssp)
      ET.SubElement(out, NS + "Name").text = text(source, "Name") or ssp
      location = ET.SubElement(out, NS + "Location")
      ET.SubElement(location, NS + "Longitude").text = f"{self.coordinates[ssp][1]:.7f}"
      ET.SubElement(location, NS + "Latitude").text = f"{self.coordinates[ssp][0]:.7f}"
      if text(source, "PublicCode"):
        ET.SubElement(out, NS + "PublicCode").text = text(source, "PublicCode")

    assignments = ET.SubElement(frame, NS + "stopAssignments")
    for order, ssp in enumerate(sorted(self.quay_by_ssp), start=1):
      out = ET.SubElement(
        assignments,
        NS + "PassengerStopAssignment",
        {"id": self.nid("PassengerStopAssignment", ssp), "version": "1", "order": str(order)},
      )
      self.reference(out, "ScheduledStopPointRef", "ScheduledStopPoint", ssp)
      self.reference(out, "StopPlaceRef", "StopPlace", self.stop_place_of_ssp[ssp])
      self.reference(out, "QuayRef", "Quay", ssp)

    patterns = ET.SubElement(frame, NS + "journeyPatterns")
    self.point_ids = {}
    for pattern_id, pattern in sorted(used_patterns.items()):
      line_id, direction_type = self.pattern_line(pattern)
      out = self.element(patterns, "ServiceJourneyPattern", "ServiceJourneyPattern", pattern_id)
      view = ET.SubElement(out, NS + "RouteView")
      self.reference(view, "LineRef", "Line", line_id)
      if direction_type:
        ET.SubElement(out, NS + "DirectionType").text = direction_type
      points = ET.SubElement(out, NS + "pointsInSequence")
      source_points = [p for p in pattern.find(NS + "pointsInSequence") if local(p) == "StopPointInJourneyPattern"]
      for order, point in enumerate(source_points, start=1):
        point_id = point.get("id") or f"{pattern_id}-{order}"
        self.point_ids[(pattern_id, id(point))] = point_id
        sp = ET.SubElement(
          points,
          NS + "StopPointInJourneyPattern",
          {"id": self.nid("StopPointInJourneyPattern", point_id), "version": "1", "order": str(order)},
        )
        self.reference(sp, "ScheduledStopPointRef", "ScheduledStopPoint", ref(point, "ScheduledStopPointRef"))
        if ref(point, "DestinationDisplayRef") in self.by_id["DestinationDisplay"]:
          self.reference(sp, "DestinationDisplayRef", "DestinationDisplay", ref(point, "DestinationDisplayRef"))
    return frame

  ESTIMATED_NOTICE_TEXT = (
    "Fahrzeiten geschätzt aus Fahrzeugumläufen und Streckenlängen – nicht für die Fahrgastinformation"
  )

  def estimated_notice(self, service_frame):
    """The notice marking journeys with estimated run times."""
    notices = ET.SubElement(service_frame, NS + "notices")
    notice = self.element(notices, "Notice", "Notice", "EstimatedRunTimes")
    ET.SubElement(notice, NS + "Text", {"lang": "de"}).text = self.ESTIMATED_NOTICE_TEXT
    ET.SubElement(notice, NS + "PublicCode").text = "ESTIMATED"

  def timetable_frame(self, frames, lines):
    frame = self.element(frames, "TimetableFrame", "TimetableFrame", "1")
    journeys = ET.SubElement(frame, NS + "vehicleJourneys")
    for journey, pattern, times in self.journeys:
      journey_id = journey.get("id")
      line_id, _ = self.pattern_line(pattern)
      out = self.element(journeys, "ServiceJourney", "ServiceJourney", journey_id)
      ET.SubElement(out, NS + "TransportMode").text = lines[line_id][1]
      if journey_id in self.estimated_journeys:
        assignment = ET.SubElement(
          ET.SubElement(out, NS + "noticeAssignments"),
          NS + "NoticeAssignment",
          {"id": self.nid("NoticeAssignment", journey_id), "version": "1", "order": "1"},
        )
        self.reference(assignment, "NoticeRef", "Notice", "EstimatedRunTimes")
      day_types = ET.SubElement(out, NS + "dayTypes")
      for day_type in journey.iter(NS + "DayTypeRef"):
        self.reference(day_types, "DayTypeRef", "DayType", day_type.get("ref"))
      self.reference(out, "ServiceJourneyPatternRef", "ServiceJourneyPattern", pattern.get("id"))
      self.reference(out, "LineRef", "Line", line_id)
      passing_times = ET.SubElement(out, NS + "passingTimes")
      for order, (point, arrival, departure) in enumerate(times, start=1):
        passing = ET.SubElement(
          passing_times,
          NS + "TimetabledPassingTime",
          {"id": self.nid("TimetabledPassingTime", f"{journey_id}-{order}"), "version": "1"},
        )
        self.reference(
          passing,
          "StopPointInJourneyPatternRef",
          "StopPointInJourneyPattern",
          self.point_ids[(pattern.get("id"), id(point))],
        )
        for name, value in (("Arrival", arrival), ("Departure", departure)):
          if value is None:
            continue
          clock, day = format_time(value)
          ET.SubElement(passing, NS + f"{name}Time").text = clock
          if day:
            ET.SubElement(passing, NS + f"{name}DayOffset").text = str(day)


ESTIMATION_METHODS = {
  "block": "Total from the departure of the next journey of the vehicle at the end stop",
  "pattern": "Total from the shortest run time of the pattern in the vehicle blocks (the own block "
  "gap is missing or more than 30 % longer)",
  "speed": "No block gap for the pattern; total from the median speed of the other patterns",
}


def mode_map(values):
  result = {}
  for value in values or []:
    key, _, mode = value.partition("=")
    result[re.sub(r"[^A-Z]", "", key.upper())] = mode
  return result


def main():
  parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument("input", help="NeTEx file following the VDV 452 -> NeTEx mapping")
  parser.add_argument("output", help="NeTEx file to write, e.g. NX-PI-01_DE_VAG_NETWORK_FREIBURG_20120724.xml")
  parser.add_argument("--codespace", default="VDV", help="prefix for all ids (default: VDV)")
  parser.add_argument("--time-zone", default="Europe/Berlin", help="time zone of the data (default: Europe/Berlin)")
  parser.add_argument(
    "--swap-coordinates",
    choices=("auto", "yes", "no"),
    default="auto",
    help="exchange Latitude and Longitude: auto if a position is outside Central Europe, but inside if swapped",
  )
  parser.add_argument(
    "--mode",
    action="append",
    metavar="CONTEXT=MODE",
    help="NeTEx transport mode for an operational context short name, e.g. --mode STR=tram",
  )
  parser.add_argument(
    "--day-offset-before",
    metavar="HH:MM",
    help="journeys departing before this time belong to the previous operating day (e.g. 03:00)",
  )
  parser.add_argument(
    "--estimate-run-times-from-blocks",
    action="store_true",
    help="ESTIMATE missing run times from the vehicle blocks (not for passenger information, see README)",
  )
  parser.add_argument(
    "--min-layover",
    type=float,
    default=0,
    metavar="MINUTES",
    help="layover at the end stop subtracted when estimating run times from the blocks (default: 0)",
  )
  parser.add_argument("--report", help="write the report to this file instead of stderr")
  args = parser.parse_args()
  args.mode_map = mode_map(args.mode)
  args.day_offset_before = parse_time(args.day_offset_before) if args.day_offset_before else None
  args.min_layover = round(args.min_layover * 60)

  report = Report()
  ET.register_namespace("", NETEX)
  source = ET.parse(args.input).getroot()
  converter = Converter(source, args, report)
  output = converter.build()
  ET.indent(output, space="  ")
  ET.ElementTree(output).write(args.output, encoding="UTF-8", xml_declaration=True)

  journeys = len(converter.by_id["VehicleJourney"])
  out = open(args.report, "w", encoding="utf-8") if args.report else sys.stderr
  out.write(f"Converted {len(converter.journeys)} of {journeys} vehicle journeys into {args.output}\n\n")
  report.write(out)
  if args.day_offset_before is None:
    early = sum(1 for j, _, _ in converter.journeys if parse_time(text(j, "DepartureTime")) < 4 * 3600)
    if early:
      out.write(
        f"\nNote: {early} journeys depart before 04:00. If they belong to the previous operating day "
        "(VDV 452 departure times can exceed 24:00), use --day-offset-before.\n"
      )
  if args.report:
    out.close()


if __name__ == "__main__":
  main()
