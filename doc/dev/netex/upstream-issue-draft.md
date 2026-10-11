# Draft: upstream issue "NeTEx import of EPIP and VDV 462 data"

_Draft for an issue in opentripplanner/OpenTripPlanner. Not yet submitted._

## Goal

Import NeTEx data following the European Passenger Information Profile (EPIP, CEN/TS 16614-4) and
the German VDV 462 profile, for example the German national data set published by DELFI e.V. on the
national access point. Today the NeTEx import assumes the Nordic profile: EPIP files are not loaded
with the default file patterns, and constructs that are optional in EPIP (quays, line modes, time
zones, passing time ids, …) abort the import or drop most of the data.

## Proposal

- Two sandbox features, off by default: `NetexEpip` and `NetexVdv462`. With both off, the import is
  unchanged (guarded by a regression test that compares entity and issue counts of the Nordic and
  the EPIP test data).
- Profile-neutral gaps are handled in the core NeTEx package behind one switch,
  `NetexProfileSupport.extendedNetex()`, following the `FlexRouting` precedent. Only
  profile-specific parts live in `ext/netexprofile` (EPIP file grouping so far).
- Fixes for input that crashes the import today go into core without a flag (abort → data import
  issue), one change per commit.
- No changes to the transit model. Every fallback adds a data import issue of its own type, so the
  build report shows how much of the data relies on it.
- Builds on #8054 (stop place assignments).

## Scope (EPIP, implemented on the branch)

File grouping for national access points (one group per operating unit), stop places without quays
(stops derived from scheduled stop points), inline notice assignments, agency and operator
resolution without authority, additional transport modes, lines without or with unknown mode, site
connections as minimum transfer times, parent stop places as multimodal stations, trip direction and
destination display on the journey pattern, missing time zone, wheelchair access from the overall
assessment, interchange visit numbers, day types across file levels.

Out of scope: fares (NeTEx part 3), flexible transport, SIRI real-time, merging stops across
operating units by their national id (DHID).

## Evidence

- Hand-built EPIP data set with a routing integration test (one transfer), schema-validated.
- German DELFI data set of October 2026 (28 289 files, 79 GB): imports without failure in 12 minutes
  (4.75 million journeys; 2.7 % dropped, mostly call-collective taxis with mode `unknown`); the
  Hamburg part (1 359 files) builds a graph with OSM and plans trips including bus–U-Bahn transfers.
- Details: `doc/dev/netex/epip-analysis.md`.

## Open questions for the maintainers

1. Is a sandbox feature the right vehicle, or should EPIP become a supported NeTEx profile with a
   per-feed `profile` parameter in the build configuration?
2. Headsign when no destination display exists: empty, or the name of the last stop?
3. Lines with mode `unknown`, `other` or `lift`: drop (current) or map to a configurable default?
