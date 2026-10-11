package org.opentripplanner.netex.config;

import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.utils.tostring.ToStringBuilder;

/**
 * Which optional, profile related parts of the NeTEx import are enabled. The NeTEx importer was
 * developed against the Nordic profile; the parts enabled here extend it for data following other
 * profiles. With all parts disabled, the import behaves exactly as for the Nordic profile.
 * <p>
 * The parts are enabled by the sandbox features {@link OTPFeature#NetexEpip} and
 * {@link OTPFeature#NetexVdv462}.
 */
public final class NetexProfileSupport {

  /** Nordic profile only - all optional parts are disabled. */
  public static final NetexProfileSupport NORDIC = new NetexProfileSupport(false, false);

  private final boolean epip;
  private final boolean vdv462;

  private NetexProfileSupport(boolean epip, boolean vdv462) {
    this.epip = epip;
    this.vdv462 = vdv462;
  }

  public static NetexProfileSupport of(boolean epip, boolean vdv462) {
    return epip || vdv462 ? new NetexProfileSupport(epip, vdv462) : NORDIC;
  }

  /** Create an instance from the {@link OTPFeature} configuration. */
  public static NetexProfileSupport ofFeatures() {
    return of(OTPFeature.NetexEpip.isOn(), OTPFeature.NetexVdv462.isOn());
  }

  /** Support for the European Passenger Information Profile (EPIP, CEN/TS 16614-4). */
  public boolean epip() {
    return epip;
  }

  /** Support for the German VDV 462 profile. */
  public boolean vdv462() {
    return vdv462;
  }

  /**
   * Support for standard NeTEx constructs that the Nordic profile does not use, but that both EPIP
   * and VDV 462 rely on - for example notice assignments embedded in the noticed element, or stop
   * points assigned to a stop place without a quay.
   */
  public boolean extendedNetex() {
    return epip || vdv462;
  }

  @Override
  public String toString() {
    return ToStringBuilder.of(NetexProfileSupport.class)
      .addBoolIfTrue("epip", epip)
      .addBoolIfTrue("vdv462", vdv462)
      .toString();
  }
}
