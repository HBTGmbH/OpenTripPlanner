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

### Known limitations

- Fares (NeTEx part 3), flexible services and SIRI real-time data are out of scope.

## Changelog

### OTP 2.11

- Initial version (feature flag and documentation).
