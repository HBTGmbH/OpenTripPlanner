# NeTEx DHID merge

Merges stop places of the German NeTEx export (DELFI) that describe the same stop, so that
OpenTripPlanner imports them as one station. The German stop id (DHID, `keyList` key `GlobalID`)
connects them, and the central stop register (zHV, "zentrales Haltestellenverzeichnis") serves as
reference. The tool is **not part of the OTP build**; it needs Python 3.9 or newer and no other
packages.

## Why

DELFI delivers one directory per operating unit. Most stops are delivered by one supplier and
referenced by all others with the same stop place id; OTP then creates one station. In some
regions several suppliers deliver the same stop with their own ids, mostly as their own hierarchy
of a parent stop place and a child stop place with the quays:

```
de:09661:60903  DE::StopPlace:60903_vu_d::          Aschaffenburg, Sandkirche (parent, directory vu_d_1)
                └ DE::StopPlace:1609030000_vu_d::   (child with quays)
de:09661:60903  DE::StopPlace:60903_abs_d::         Aschaffenburg, Sandkirche (parent, directory abs_d_BUS)
                └ DE::StopPlace:1609030000_abs_d::  (child with quays)
```

OTP creates a multimodal station per parent, so the stop appears twice, for example in stop
searches and departure boards. In the export of 5 October 2026 this concerns about 12,500 stops,
mainly in Bavaria (17 % of the stops), Baden-Württemberg (9 %) and North Rhine-Westphalia (4 %).

## What it does

Only top-level stop places (without `ParentSiteRef`) of one DHID are merged; the one used in most
directories keeps its id, the others are renamed in all files (stop place ids and all references):

| Stop places of one DHID                                | Result                                                     |
| ------------------------------------------------------ | ---------------------------------------------------------- |
| Parents, each with exactly one child                   | Parents and children get one id: one station with all quays |
| Parents, at least one with several children            | Parents get one id: one multimodal station with all child stations |
| Stop places without children                           | One id: one station                                        |
| Parent and stop place without children                 | Not merged, reported                                       |
| Two of them in the same directory                      | Not merged, reported                                       |

A stop place is only merged if it lies within `--max-distance` of the position of its DHID in the
zHV (without zHV: of the other stop places of the DHID). A stop place farther away carries a wrong
DHID; it is reported (`dhid-implausible`) and keeps its id. Files without changes are copied
unchanged.

## Usage

```bash
python3 netex_dhid_merge.py 20261005_fahrplaene_gesamtdeutschland.zip merged.zip \
  --zhv 20261012_zHV_gesamt_xml.zip --report report.txt
```

| Option              | Meaning                                                                                          |
| ------------------- | ------------------------------------------------------------------------------------------------ |
| `INPUT`, `OUTPUT`   | Zip file or directory with one sub directory per operating unit. The output is a zip if its name ends with `.zip`. |
| `--zhv`             | zHV XML export, or a zip with it. Recommended: used to detect wrong DHIDs.                         |
| `--max-distance`    | Meters a stop place may lie from the zHV position of its DHID to be merged, default 300.          |
| `--assign-dhid`     | **Derive** the DHID of top-level stop places without one from the zHV: the nearest zHV stop with the same name within `--assign-distance` (default 150 m). Off by default. |
| `--report`          | Write the report to a file instead of stderr.                                                     |

`--assign-dhid` is a derivation, not delivered data. Measured on the stop places that have a DHID,
the rule finds the right stop for 92.6 %, a wrong one for 0.1 % and none for 7.3 %. The added key
is marked (`GlobalIDSource` = `zHV name and position`), and every assignment is reported.

## Report

Counts and examples per kind, so the data can be fixed at the source:

- `merged-parent-and-child`, `merged-parent`, `merged-stop-place` – merged stops with the ids.
- `dhid-implausible` – stop place far from the zHV position of its DHID (all cases are listed).
- `not-merged-parent-and-simple-stop-place`, `not-merged-same-directory`.
- `dhid-assigned-from-zhv`, `stop-place-without-dhid` – only with `--assign-dhid`.

## Tests

```bash
python3 -m unittest test_netex_dhid_merge.py
```
