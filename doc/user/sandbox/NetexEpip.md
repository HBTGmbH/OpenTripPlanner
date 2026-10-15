# NeTEx EPIP Import

## Contact Info

- HBT Hamburger Berater Team GmbH

## Documentation

The OTP NeTEx importer was developed against the
[Nordic NeTEx profile](https://enturas.atlassian.net/wiki/spaces/PUBLIC/pages/728891481/Nordic+NeTEx+Profile).
Data following the European Passenger Information Profile (EPIP, CEN/TS 16614-4), for example the
data published by many European national access points, uses the same NeTEx schema but is
structured differently in many places. This sandbox feature extends the import so that EPIP data is
imported completely: network, stops, lines, routes, journeys, calendars and the passenger relevant
attributes.

The OTP transit model is not changed; only the way from NeTEx into the model is extended. Missing
optional data does not abort the import; OTP uses a sensible default instead and reports a data
import issue, so the build report shows how much of the data relies on such defaults.

The analysis behind this feature, including all deviations from the Nordic profile and the design,
is documented in
[doc/dev/netex/epip-analysis.md](https://github.com/opentripplanner/OpenTripPlanner/blob/dev-2.x/doc/dev/netex/epip-analysis.md).

### Activation

Enable the feature in `otp-config.json`:

```json
// otp-config.json
{
  "otpFeatures": {
    "NetexEpip": true
  }
}
```

With the feature turned off, the NeTEx import behaves exactly as before.

### Supported EPIP scope

The feature is under development. The following table lists what is supported so far.

| Area | Status |
| ---- | ------ |
| Robustness: invalid or incomplete data is reported instead of aborting the build | In core OTP, independent of this feature |
| EPIP file names (CEN/TS 16614-4, 8.14): if the feed uses the default file patterns, `NX-PI-…_STOP…` files are loaded as shared files and `NX-PI-…_LINE…`/`…_NETWORK…` files as group files, grouped by directory (operating unit, as in the German DELFI zip file), country and provider; zip directory entries are ignored | Supported |
| XML files matching none of the file patterns are loaded as a group of their own instead of being ignored (`NetexFileNotMatchingPatterns`); other files are ignored | Supported |
| Notices: notice assignments embedded in the noticed element (stop point in journey pattern, journey pattern, line, service journey), notices referenced by `NoticeRef` from an earlier embedded assignment | Supported |
| Notices assigned to every stop point of a journey pattern are attached to the service journeys instead of every stop time (the DELFI data repeats journey wide notices on each stop point) | Supported |
| Operator of a line given only as additional operator (`additionalOperators`) | Supported |
| Agency of a line without authority: `AuthorityRef`, an authority among the additional operators, otherwise the operator of the line is used as agency (`AgencyDerivedFromOperator`) | Supported |
| Stop points assigned to a stop place only (no `QuayRef`): the quay with the lowest id is used; for a stop place without quays a stop is derived from the scheduled stop point (its location, otherwise the stop place centroid), with the id of the scheduled stop point | Supported |
| Stop place without centroid and quays: located at the mean position of its scheduled stop points | Supported |
| Stop place hierarchy (CEN/TS 16614-4, 6.1): a stop place without quays which is referenced as `ParentSiteRef` or typed `epip:general` becomes a multimodal station grouping its child stop places | Supported |
| Operating days inside the `ServiceCalendar` | Supported |
| Site connections (`SiteConnection` with walk transfer duration) between stop places or quays: mapped to constrained transfers with a minimum transfer time | Supported |
| Site connections within one stop place or quay, or from a quay to its own stop place | Not supported, reported once per stop place (`SiteConnectionWithinSite`): OTP has no minimum transfer time within a stop place |
| Transport modes `ferry`, `trolleyBus`, `urbanRail` and `intercityRail` on lines and service journeys | Supported (`lift` and `other` are not supported) |
| Trip direction from the service journey, the journey pattern or a referenced `Direction` (`DirectionRef`), not only from the route | Supported |
| Destination display on the journey pattern, used as headsign until a stop point states another one | Supported |
| Journey patterns without any destination display: the name of the station of the last stop is used as headsign, reported once per line (`HeadsignFromLastStop`) | Supported |
| Journey patterns whose first stop points have no destination display: the first destination display of a later stop point is used as headsign for them, reported once per line (`HeadsignFromLaterStopPoint`) | Supported |
| Wheelchair accessibility from the overall `MobilityImpairedAccess` assessment (`true`/`false`) when no `WheelchairAccess` limitation is given (stop places, quays, service journeys) | Supported |
| Interchanges at a stop point served several times: `FromVisitNumber`/`ToVisitNumber` select the visit | Supported |
| Day types defined in a shared file and assigned (`DayTypeAssignment`) in a group file | Supported (a date can not be excluded on another file level than it is assigned) |
| Lines without `TransportMode` or with mode `unknown`: the mode is derived from the service journeys of the line, else from the stop places they serve (`LineTransportModeDerived`); service journeys with mode `unknown` use the mode of their line | Supported for journeys in the same file level as the line |
| Lines whose mode can not be derived are dropped with a warning per line (`LineWithoutTransportMode`) that tells why, and how the mode and the booking conditions must be given in the data. For the German DELFI data set the warning names the `GTFS Route Type` and booking notices found in the data. | Supported |
| Scheduled stop points used for boarding or alighting without any passenger stop assignment: a stop without parent station is created at the scheduled stop point (`StopDerivedFromUnassignedScheduledStopPoint`) | Supported |
| Quays without coordinates are placed at their stop place instead of being skipped (`QuayLocatedAtStopPlace`) | Supported |
| Stop places without centroid (optional in EPIP) take the mean position of their quays, reported as information (`StationLocatedAtQuays`); `StationWithoutCoordinates` only if the quays have no position either | Supported |
| Data without time zone (`FrameDefaults/DefaultLocale/TimeZone`): the `transitModelTimeZone` of the build configuration is used instead of GMT (`NetexTimeZoneFallback`) | Supported |

### Known limitations

- Fares (NeTEx part 3), flexible services and SIRI real-time data are out of scope.

## Changelog

### OTP 2.11

- Initial version (feature flag and documentation).
