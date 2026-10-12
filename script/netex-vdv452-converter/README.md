# NeTEx VDV 452 converter

Converts a NeTEx export that follows the "VDV 452 → NeTEx" mapping (for example the VAG Freiburg
export of 2012) into NeTEx that OpenTripPlanner can import. The tool is **not part of the OTP
build**; it needs Python 3.9 or newer and no other packages.

## Why

Exports from German scheduling systems that map VDV 452 directly to NeTEx describe the timetable
with a timing model instead of passing times:

| VDV 452 export                                           | Converted output (EPIP-like)                         |
| -------------------------------------------------------- | ---------------------------------------------------- |
| `VehicleJourney` with `DepartureTime` and a running time group (`TimeDemandTypeRef`) | `ServiceJourney` with `TimetabledPassingTime`s |
| `ServicePattern` (`RouteRef`, `TypeOfJourneyPatternRef`) | `ServiceJourneyPattern` (`RouteView`/`LineRef`)      |
| Run times per route section (`JourneyPatternRunTime`, VDV 452 `SEL_FZT_FELD`), wait times per point (`JourneyPatternWaitTime`, `ORT_HZTF`), journey wait times (`VehicleJourneyWaitTime`) | Arrival and departure times computed from them |
| Line with `OperationalContextRef` (VDV 452 `OP_DEP`) only | Line with `TransportMode`                            |
| Stop points with a location, stop places without         | Stop places with centroid and one quay per stop point |
| `DayTypeAssignment` with `OperatingDayRef`               | `DayTypeAssignment` with `Date`                      |

Depot runs (`TypeOfJourneyPattern` with VDV 452 `ROUTE_TYPE` 2–4), dead runs, blocks and
vehicle schedules are not converted. Interchange rules (`InterchangeRule`) and notices are not
converted yet.

The semantics follow the mapping table `bhn01-326956-v22-vdv452-netex-datenzuordnung.xlsx` and
VDV 462 chapter 13: run times belong to a running time group and a route section, not to a
pattern, and there is no wait time at the first and the last stop.

## Usage

```bash
python3 netex_vdv452_converter.py "NeTEx-Export Example.xml" \
  NX-PI-01_DE_VAG_NETWORK_FREIBURG_20120724.xml --codespace VAG --report report.txt
```

| Option                  | Meaning                                                                                              |
| ----------------------- | ---------------------------------------------------------------------------------------------------- |
| `--codespace`           | Prefix for all ids, e.g. `VAG` → `VAG:ServiceJourney:VJ_1`.                                            |
| `--time-zone`           | Time zone of the data, default `Europe/Berlin`.                                                        |
| `--swap-coordinates`    | `auto` (default): exchange latitude and longitude of a stop point if it lies outside Central Europe, but inside if swapped. `yes`/`no` to force. |
| `--mode CONTEXT=MODE`   | NeTEx transport mode for an operational context short name, e.g. `--mode STR=tram`. `BUS`, `TRAM`, `STR`, `U`, `S` and some others are known. |
| `--day-offset-before`   | Journeys departing before this time belong to the previous operating day, e.g. `03:00`. VDV 452 departure times can exceed 24:00, but the NeTEx export writes them as time of day. |
| `--estimate-run-times-from-blocks` | **Estimate** missing run times from the vehicle blocks, see below. Off by default.         |
| `--min-layover`         | Layover in minutes at the end stop subtracted when estimating, default 0. 2 is a reasonable value.     |
| `--report`              | Write the report to a file instead of stderr.                                                          |

Name the output file following the EPIP convention (`NX-PI-01_<country>_<provider>_NETWORK_…xml`)
and import it with the `NetexEpip` sandbox feature, which loads EPIP file names and uses the
operator as agency. Without the feature, configure `groupFilePattern` in `build-config.json` to
match the file name.

## Report

Every correction and every gap is reported with its count and examples, so the data can be fixed
in the source system. Examples:

- `time-demand-type-duplicated` – a running time group is exported as several `TimeDemandType`s
  with the same number (`PrivateCode`, VDV 452 `TIMING_GROUP_NO`); they are treated as one group.
- `negative-zero-duration` – `-P0Y0M0DT0H0M0S` is read as zero; it should be written as `PT0S`.
- `coordinates-swapped` – latitude and longitude are exchanged.
- `run-time-missing` – no run time for a route section and the running time group of a journey;
  the journey is dropped. Run times are never estimated.

## Estimated run times (opt-in)

**Estimated times are not suitable for passenger information.** Without
`--estimate-run-times-from-blocks`, a journey with a missing run time is dropped and reported. With
it, missing run times are estimated:

1. If the vehicle block (`Block`, VDV 452 `REC_UMLAUF`) continues with a journey from the end stop
   (the same stop point or stop place), the difference of the two departure times is the run time
   plus the wait times plus the layover. Wait times and `--min-layover` are subtracted
   (`run-times-estimated-block`). If this is more than 30 % longer than the shortest value of the
   pattern, for example after a long layover, the shortest value of the pattern is used
   (`run-times-estimated-pattern`).
2. Patterns without such a pair of journeys use the median speed of the other patterns
   (`run-times-estimated-speed`). Speeds below 5 or above 80 km/h are reported and not used.
3. The total is distributed over the route sections without run time in proportion to their
   length (`ServiceLink/Distance`, else 1.3 × the straight line distance). Given run times greater
   than zero are kept; run times of zero are treated as missing.

Every estimated journey gets the notice "Fahrzeiten geschätzt aus Fahrzeugumläufen und
Streckenlängen – nicht für die Fahrgastinformation" (`PublicCode` `ESTIMATED`), which OTP shows
with the journey. Known errors: the arrival at the end stop is too late by the unknown part of the
layover, and the intermediate times do not reflect slow or fast sections.

## The VAG Freiburg example (2012)

The example contains run times for only 16 of the 221 route sections used by passenger journeys
(2 of them greater than zero, with implausible 2.8 and 5.6 km/h), so none of its 3 246 passenger
journeys can be converted with the given data. The report lists the missing sections. All other
corrections (swapped coordinates, duplicated running time groups, negative zero durations,
operational context as mode) work on this file.

With `--estimate-run-times-from-blocks --min-layover 2 --day-offset-before 03:00`, 3 209 journeys
are converted (2 251 from their own block, 899 from the shortest time of the pattern, 59 from the
median speed of 16 km/h); 37 are dropped because the two implausible given run times exceed the
estimated total. OTP imports all 3 209 journeys with the notice. The current timetable of the VAG
Freiburg is part of the German DELFI data set (`frb_d_VAGB`, `frb_d_VAGS`), so this is only needed
for data in the VDV 452 mapping format.

## Tests

```bash
python3 script/netex-vdv452-converter/test_netex_vdv452_converter.py -v
```

`example/vdv452-example.xml` is a small input in the structure of the Freiburg export, including
its quirks, with complete run times for two running time groups and a block without run times for
the estimation. The converted output validates against the NeTEx XSD
(`NeTEx_publication.xsd` 1.16.1) and imports into OTP with `NetexEpip` (3 trips).
