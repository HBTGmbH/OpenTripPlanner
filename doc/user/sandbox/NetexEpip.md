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
| EPIP file names (CEN/TS 16614-4, 8.14): if the feed uses the default file patterns, `NX-PI-…_STOP…` files are loaded as shared files and `NX-PI-…_LINE…`/`…_NETWORK…` files as group files, grouped by country and provider | Supported |
| Files matching none of the file patterns are loaded as a group of their own instead of being ignored (`NetexFileNotMatchingPatterns`) | Supported |
| Notices: notice assignments embedded in the noticed element (stop point in journey pattern, journey pattern, line, service journey), notices referenced by `NoticeRef` from an earlier embedded assignment | Supported |
| Operator of a line given only as additional operator (`additionalOperators`) | Supported |
| Agency of a line without authority: `AuthorityRef`, an authority among the additional operators, otherwise the operator of the line is used as agency (`AgencyDerivedFromOperator`) | Supported |
| Stop points assigned to a stop place only (no `QuayRef`): the quay with the lowest id is used; for a stop place without quays a stop is derived from the scheduled stop point (its location, otherwise the stop place centroid), with the id of the scheduled stop point | Supported |
| Stop place without centroid and quays: located at the mean position of its scheduled stop points | Supported |
| Stop place hierarchy (CEN/TS 16614-4, 6.1): a stop place without quays which is referenced as `ParentSiteRef` or typed `epip:general` becomes a multimodal station grouping its child stop places | Supported |
| Operating days inside the `ServiceCalendar` | Supported |
| Site connections (`SiteConnection` with walk transfer duration) between or within stop places and quays: mapped to constrained transfers with a minimum transfer time | Supported |
| Transport modes `ferry`, `trolleyBus`, `urbanRail` and `intercityRail` on lines and service journeys | Supported (`lift` and `other` are not supported) |
| Trip direction from the service journey, the journey pattern or a referenced `Direction` (`DirectionRef`), not only from the route | Supported |
| Destination display on the journey pattern, used as headsign until a stop point states another one | Supported |
| Wheelchair accessibility from the overall `MobilityImpairedAccess` assessment (`true`/`false`) when no `WheelchairAccess` limitation is given (stop places, quays, service journeys) | Supported |
| Data without time zone (`FrameDefaults/DefaultLocale/TimeZone`): the `transitModelTimeZone` of the build configuration is used instead of GMT (`NetexTimeZoneFallback`) | Supported |

### Known limitations

- Fares (NeTEx part 3), flexible services and SIRI real-time data are out of scope.

## Changelog

### OTP 2.11

- Initial version (feature flag and documentation).
