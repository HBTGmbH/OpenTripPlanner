# NeTEx VDV 462 Import

## Contact Info

- HBT Hamburger Berater Team GmbH

## Documentation

VDV Recommendation 462 is the German application handbook for exchanging network and timetable
data with NeTEx. Its passenger information profiles (levels L1 to L3) differ from both the Nordic
profile and the European Passenger Information Profile (EPIP): for example, journeys carry no
passing times (they are derived from run and wait times per time demand type), and stop points are
assigned to stop places only, with the position given on the scheduled stop point. Note that the
German national access point (DELFI) publishes data in the EPIP profile; use the
[NeTEx EPIP Import](NetexEpip.md) feature for that data.

The OTP transit model is not changed; only the way from NeTEx into the model is extended. Missing
optional data does not abort the import; OTP uses a sensible default instead and reports a data
import issue.

The analysis behind this feature is documented in
[doc/dev/netex/epip-analysis.md](https://github.com/opentripplanner/OpenTripPlanner/blob/dev-2.x/doc/dev/netex/epip-analysis.md).

### Activation

Enable the feature in `otp-config.json`:

```json
// otp-config.json
{
  "otpFeatures": {
    "NetexVdv462": true
  }
}
```

With the feature turned off, the NeTEx import behaves exactly as before.

### Supported VDV 462 scope

The feature is under development. The following table lists what is supported so far.

| Area | Status |
| ---- | ------ |
| Robustness: invalid or incomplete data is reported instead of aborting the build | In core OTP, independent of this feature |
| Notices: notice assignments embedded in the noticed element (stop point in journey pattern, journey pattern, line, service journey), notices referenced by `NoticeRef` from an earlier embedded assignment | Supported |
| Operator of a line given only as additional operator (`additionalOperators`) | Supported |
| Stop points assigned to a stop place only (no `QuayRef`): the quay with the lowest id is used; for a stop place without quays a stop is derived from the scheduled stop point (its location, otherwise the stop place centroid), with the id of the scheduled stop point | Supported |
| Stop place without centroid and quays: located at the mean position of its scheduled stop points | Supported |
| Operating days inside the `ServiceCalendar` | Supported |
| Transport modes `ferry`, `trolleyBus`, `urbanRail` and `intercityRail` on lines and service journeys | Supported (`lift` and `other` are not supported) |

### Known limitations

- Fares, demand responsive transport, train formation and AVLC specific data are out of scope.

## Changelog

### OTP 2.11

- Initial version (feature flag and documentation).
