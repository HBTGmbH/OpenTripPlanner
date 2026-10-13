"""Tests for netex_dhid_merge.py. Run with: python3 -m unittest test_netex_dhid_merge.py"""

import os
import tempfile
import unittest

import netex_dhid_merge

HEADER = '<PublicationDelivery xmlns="http://www.netex.org.uk/netex"><dataObjects><SiteFrame id="F"><stopPlaces>'
MIDDLE = "</stopPlaces></SiteFrame><ServiceFrame id=\"S\"><stopAssignments>"
FOOTER = "</stopAssignments></ServiceFrame></dataObjects></PublicationDelivery>"

ZHV = """<?xml version="1.0" encoding="utf-8"?>
<GetResponseType xmlns="http://zhv.xml.wvi/"><Items>
  <StopPlace><Name><Name>Trünzig, Waldhäuser</Name></Name>
    <Location><Latitude>50.757228</Latitude><Longitude>12.246041</Longitude></Location>
    <DHID>de:14524:10599</DHID></StopPlace>
  <StopPlace><Name><Name>Lautlingen Kirche</Name></Name>
    <Location><Latitude>48.2400</Latitude><Longitude>8.9600</Longitude></Location>
    <DHID>de:08417:30750</DHID></StopPlace>
</Items></GetResponseType>
"""


def stop_place(id, dhid=None, name="Trünzig, Waldhäuser", lat=50.757228, lon=12.246041, parent=None, quay=None):
  key = f"<keyList><KeyValue><Key>GlobalID</Key><Value>{dhid}</Value></KeyValue></keyList>" if dhid else ""
  parent_ref = f'<ParentSiteRef ref="{parent}"/>' if parent else ""
  centroid = "" if quay else f"<Centroid><Location><Longitude>{lon}</Longitude><Latitude>{lat}</Latitude></Location></Centroid>"
  quays = (
    f'<quays><Quay id="{quay}"><Centroid><Location><Longitude>{lon}</Longitude>'
    f"<Latitude>{lat}</Latitude></Location></Centroid></Quay></quays>"
    if quay
    else ""
  )
  return f'<StopPlace id="{id}" version="1">{key}<Name>{name}</Name>{centroid}{parent_ref}{quays}</StopPlace>'


def assignment(ssp, stop_place):
  return (
    f'<PassengerStopAssignment id="PSA_{ssp}" order="1"><ScheduledStopPointRef ref="{ssp}"/>'
    f'<StopPlaceRef ref="{stop_place}"/></PassengerStopAssignment>'
  )


def hierarchy(parent, child, quay, dhid="de:14524:10599", **position):
  return stop_place(parent, dhid, **position) + stop_place(child, dhid, parent=parent, quay=quay, **position)


class NetexDhidMergeTest(unittest.TestCase):
  def setUp(self):
    self.tmp = tempfile.TemporaryDirectory()
    self.input = os.path.join(self.tmp.name, "in")
    self.output = os.path.join(self.tmp.name, "out")
    self.report = os.path.join(self.tmp.name, "report.txt")
    self.zhv = os.path.join(self.tmp.name, "zhv.xml")
    with open(self.zhv, "w", encoding="utf-8") as f:
      f.write(ZHV)

  def tearDown(self):
    self.tmp.cleanup()

  def write(self, name, stop_places, assignments=""):
    path = os.path.join(self.input, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
      f.write(HEADER + stop_places + MIDDLE + assignments + FOOTER)

  def run_tool(self, *options):
    netex_dhid_merge.main([self.input, self.output, "--zhv", self.zhv, "--report", self.report, *options])
    with open(self.report, encoding="utf-8") as f:
      return f.read()

  def read(self, name):
    with open(os.path.join(self.output, name), encoding="utf-8") as f:
      return f.read()

  def test_merge_parents_and_their_single_children(self):
    self.write("vms_d_RV1/line.xml", hierarchy("P_A", "C_A", "Q_A"), assignment("SSP_A", "C_A"))
    self.write("vms_d_RV2/line.xml", hierarchy("P_A", "C_A", "Q_A"))
    self.write("PRG1/line.xml", hierarchy("P_B", "C_B", "Q_B"), assignment("SSP_B", "C_B"))

    report = self.run_tool()

    text = self.read("PRG1/line.xml")
    self.assertIn('<StopPlace id="P_A"', text)
    self.assertIn('<StopPlace id="C_A"', text)
    self.assertIn('<ParentSiteRef ref="P_A"/>', text)
    self.assertIn('<StopPlaceRef ref="C_A"/>', text)
    self.assertIn('<Quay id="Q_B">', text)
    self.assertNotIn('"P_B"', text)
    self.assertIn("merged-parent-and-child: 1", report)

  def test_merge_only_parents_with_several_children(self):
    parent_a = stop_place("P_A", "de:14524:10599")
    children_a = stop_place("C_A1", "de:14524:10599", parent="P_A", quay="Q_A1") + stop_place(
      "C_A2", "de:14524:10599", parent="P_A", quay="Q_A2"
    )
    self.write("a/line.xml", parent_a + children_a)
    self.write("b/line.xml", hierarchy("P_B", "C_B", "Q_B"))

    report = self.run_tool()

    text = self.read("b/line.xml")
    self.assertIn('<StopPlace id="C_B"', text)
    self.assertIn('<ParentSiteRef ref="P_A"/>', text)
    self.assertIn("merged-parent: 1", report)

  def test_do_not_merge_a_stop_place_far_from_the_zhv(self):
    self.write("tub_d/line.xml", stop_place("S_A", "de:08417:30750", "Lautlingen Kirche", 48.2400, 8.9600))
    self.write("rab_d/line.xml", stop_place("S_B", "de:08417:30750", "Bad Waldsee Freibad", 47.92, 9.75))

    report = self.run_tool()

    self.assertIn('<StopPlace id="S_B"', self.read("rab_d/line.xml"))
    self.assertIn("dhid-implausible: 1", report)
    self.assertIn("S_B „Bad Waldsee Freibad“", report)

  def test_merge_simple_stop_places(self):
    self.write("a/line.xml", stop_place("S_A", "de:14524:10599"))
    self.write("b/line.xml", stop_place("S_B", "de:14524:10599"), assignment("SSP_B", "S_B"))

    self.run_tool()

    self.assertIn('<StopPlaceRef ref="S_A"/>', self.read("b/line.xml"))

  def test_report_parent_and_simple_stop_place(self):
    self.write("a/line.xml", hierarchy("P_A", "C_A", "Q_A"))
    self.write("b/line.xml", stop_place("S_B", "de:14524:10599"))

    report = self.run_tool()

    self.assertIn('<StopPlace id="S_B"', self.read("b/line.xml"))
    self.assertIn("not-merged-parent-and-simple-stop-place: 1", report)

  def test_report_two_stop_places_of_one_dhid_in_the_same_directory(self):
    self.write("a/line1.xml", stop_place("S_A", "de:14524:10599"))
    self.write("a/line2.xml", stop_place("S_B", "de:14524:10599"))

    report = self.run_tool()

    self.assertIn('<StopPlace id="S_B"', self.read("a/line2.xml"))
    self.assertIn("not-merged-same-directory: 1", report)

  def test_assign_dhid_only_when_asked(self):
    self.write("a/line.xml", stop_place("S_A", "de:14524:10599"))
    self.write("b/line.xml", stop_place("S_B", name="Trünzig Waldhäuser", lat=50.7573), assignment("SSP_B", "S_B"))

    self.run_tool()
    self.assertIn('<StopPlaceRef ref="S_B"/>', self.read("b/line.xml"))

    report = self.run_tool("--assign-dhid")
    text = self.read("b/line.xml")
    self.assertIn("<Value>de:14524:10599</Value>", text)
    self.assertIn("<Key>GlobalIDSource</Key>", text)
    self.assertIn('<StopPlaceRef ref="S_A"/>', text)
    self.assertIn("dhid-assigned-from-zhv: 1", report)

  def test_keep_unchanged_files_identical(self):
    self.write("a/line.xml", stop_place("S_A", "de:14524:10599"))
    self.write("b/other.txt", "")

    self.run_tool()

    with open(os.path.join(self.input, "a/line.xml"), "rb") as f:
      original = f.read()
    with open(os.path.join(self.output, "a/line.xml"), "rb") as f:
      self.assertEqual(original, f.read())
    self.assertTrue(os.path.exists(os.path.join(self.output, "b/other.txt")))


if __name__ == "__main__":
  unittest.main()
