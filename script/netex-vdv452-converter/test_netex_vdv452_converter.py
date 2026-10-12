"""Tests for netex_vdv452_converter.py. Run with: python3 -m unittest -v (in this directory)."""

import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
NS = "{http://www.netex.org.uk/netex}"


def convert(*options):
  """Convert the example with the given options; return the output root and the report."""
  tmp = tempfile.TemporaryDirectory()
  output = os.path.join(tmp.name, "NX-PI-01_DE_EXA_NETWORK_EXAMPLE_20250601.xml")
  report_file = os.path.join(tmp.name, "report.txt")
  subprocess.run(
    [
      sys.executable,
      "-I",
      os.path.join(HERE, "netex_vdv452_converter.py"),
      os.path.join(HERE, "example", "vdv452-example.xml"),
      output,
      "--codespace",
      "EXA",
      "--day-offset-before",
      "03:00",
      "--report",
      report_file,
      *options,
    ],
    check=True,
  )
  root = ET.parse(output).getroot()
  with open(report_file, encoding="utf-8") as f:
    report = f.read()
  tmp.cleanup()
  return root, report


def passing_times(root, journey_id):
  journey = next(j for j in root.iter(NS + "ServiceJourney") if j.get("id") == journey_id)
  result = []
  for passing in journey.iter(NS + "TimetabledPassingTime"):
    arrival = passing.findtext(NS + "ArrivalTime", "-")[:5]
    departure = passing.findtext(NS + "DepartureTime", "-")[:5]
    offset = passing.findtext(NS + "ArrivalDayOffset") or passing.findtext(NS + "DepartureDayOffset")
    result.append(f"{arrival}/{departure}" + (f"+{offset}" if offset else ""))
  return result


def journey_ids(root):
  return [j.get("id") for j in root.iter(NS + "ServiceJourney")]


class ConverterTest(unittest.TestCase):

  @classmethod
  def setUpClass(cls):
    cls.root, cls.report = convert()

  def passing_times(self, journey_id):
    return passing_times(self.root, journey_id)

  def test_passing_times_use_the_running_time_group_of_the_journey(self):
    # Peak: 7 + 8 minutes, no wait time ("-P0Y0M0DT0H0M0S")
    self.assertEqual(["-/08:00", "08:07/08:07", "08:15/-"], self.passing_times("EXA:ServiceJourney:VJ_1"))

  def test_journey_wait_time_replaces_the_wait_time_of_the_point(self):
    # Normal traffic: 5 minutes, 3 minutes wait (journey specific instead of 1), 6 minutes
    self.assertEqual(["-/20:00", "20:05/20:08", "20:14/-"], self.passing_times("EXA:ServiceJourney:VJ_2"))

  def test_departure_after_midnight_belongs_to_the_previous_operating_day(self):
    self.assertEqual(
      ["-/00:30+1", "00:35/00:36+1", "00:42/-+1"], self.passing_times("EXA:ServiceJourney:VJ_3")
    )

  def test_depot_runs_are_not_converted(self):
    self.assertNotIn("EXA:ServiceJourney:VJ_4", journey_ids(self.root))
    self.assertIn("depot-run-skipped", self.report)

  def test_line_mode_from_operational_context(self):
    line = next(self.root.iter(NS + "Line"))
    self.assertEqual("tram", line.findtext(NS + "TransportMode"))

  def test_swapped_coordinates_are_fixed_and_quays_created(self):
    quay = next(q for q in self.root.iter(NS + "Quay") if q.get("id") == "EXA:Quay:SSP_A")
    location = quay.find(NS + "Centroid/" + NS + "Location")
    self.assertAlmostEqual(7.84, float(location.findtext(NS + "Longitude")))
    self.assertAlmostEqual(47.99, float(location.findtext(NS + "Latitude")))
    self.assertIn("coordinates-swapped", self.report)

  def test_duplicated_running_time_groups_are_reported(self):
    self.assertIn("group 1: TDS_1, TDT_1", self.report)

  def test_journeys_without_run_times_are_dropped_without_estimation(self):
    self.assertNotIn("EXA:ServiceJourney:VJ_5", journey_ids(self.root))
    self.assertIn("run-time-missing", self.report)
    self.assertNotIn("<notices>", ET.tostring(self.root, encoding="unicode"))


class EstimationTest(unittest.TestCase):
  """--estimate-run-times-from-blocks with a layover of 2 minutes."""

  @classmethod
  def setUpClass(cls):
    cls.root, cls.report = convert("--estimate-run-times-from-blocks", "--min-layover", "2")

  def test_run_times_from_the_next_journey_of_the_vehicle(self):
    # 20 minutes to the next departure at C, minus 2 minutes layover, split 1000 m : 2000 m
    self.assertEqual(["-/08:00", "08:06/08:06", "08:18/-"], passing_times(self.root, "EXA:ServiceJourney:VJ_5"))
    self.assertIn("run-times-estimated-block", self.report)

  def test_run_times_from_the_median_speed_without_next_journey(self):
    # 3000 m in 18 minutes = 10 km/h on the other pattern
    self.assertEqual(["-/08:20", "08:32/08:32", "08:38/-"], passing_times(self.root, "EXA:ServiceJourney:VJ_6"))
    self.assertIn("run-times-estimated-speed", self.report)

  def test_given_run_times_are_kept(self):
    self.assertEqual(["-/08:00", "08:07/08:07", "08:15/-"], passing_times(self.root, "EXA:ServiceJourney:VJ_1"))

  def test_estimated_journeys_are_marked_with_a_notice(self):
    marked = [
      j.get("id")
      for j in self.root.iter(NS + "ServiceJourney")
      if j.find(NS + "noticeAssignments") is not None
    ]
    self.assertEqual(["EXA:ServiceJourney:VJ_5", "EXA:ServiceJourney:VJ_6"], marked)
    notice = next(self.root.iter(NS + "Notice"))
    self.assertIn("nicht für die Fahrgastinformation", notice.findtext(NS + "Text"))


if __name__ == "__main__":
  unittest.main()
