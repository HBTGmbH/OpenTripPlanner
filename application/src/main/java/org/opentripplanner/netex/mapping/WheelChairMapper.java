package org.opentripplanner.netex.mapping;

import java.util.Optional;
import org.opentripplanner.core.model.accessibility.Accessibility;
import org.rutebanken.netex.model.AccessibilityAssessment;
import org.rutebanken.netex.model.AccessibilityLimitation;
import org.rutebanken.netex.model.AccessibilityLimitations_RelStructure;
import org.rutebanken.netex.model.LimitationStatusEnumeration;

public class WheelChairMapper {

  /**
   * If input and containing objects are not null, get the LimitationStatusEnumeration and map to
   * internal {@link Accessibility} enumeration.
   *
   * @param accessibilityAssessment NeTEx object wrapping information regarding WheelChairBoarding
   * @param defaultValue            If no {@link AccessibilityAssessment} is defined, default to
   *                                this value
   * @return Mapped enumerator, {@link Accessibility#NO_INFORMATION} if no value is found
   */
  public static Accessibility wheelchairAccessibility(
    AccessibilityAssessment accessibilityAssessment,
    Accessibility defaultValue
  ) {
    return wheelchairAccessibility(accessibilityAssessment, defaultValue, false);
  }

  /**
   * Same as {@link #wheelchairAccessibility(AccessibilityAssessment, Accessibility)}, but if
   * {@code useMobilityImpairedAccess} is set and the assessment has no wheelchair limitation, the
   * overall {@code MobilityImpairedAccess} assessment is used. The overall assessment is mandatory
   * in the European Passenger Information Profile (EPIP), while the limitations are optional. In
   * NeTEx the overall assessment is {@code true} only if all limitations are {@code true} and
   * {@code false} only if all are {@code false}, so only these two values are used; {@code partial}
   * and {@code unknown} fall back to the default value.
   */
  public static Accessibility wheelchairAccessibility(
    AccessibilityAssessment accessibilityAssessment,
    Accessibility defaultValue,
    boolean useMobilityImpairedAccess
  ) {
    if (defaultValue == null) {
      defaultValue = Accessibility.NO_INFORMATION;
    }

    var wheelchairAccess = Optional.ofNullable(accessibilityAssessment)
      .map(AccessibilityAssessment::getLimitations)
      .map(AccessibilityLimitations_RelStructure::getAccessibilityLimitation)
      .map(AccessibilityLimitation::getWheelchairAccess);

    if (wheelchairAccess.isPresent() || !useMobilityImpairedAccess) {
      return wheelchairAccess
        .map(WheelChairMapper::fromLimitationStatusEnumeration)
        .orElse(defaultValue);
    }

    return Optional.ofNullable(accessibilityAssessment)
      .map(AccessibilityAssessment::getMobilityImpairedAccess)
      .map(WheelChairMapper::fromLimitationStatusEnumeration)
      .filter(it -> it != Accessibility.NO_INFORMATION)
      .orElse(defaultValue);
  }

  public static Accessibility fromLimitationStatusEnumeration(
    LimitationStatusEnumeration wheelChairLimitation
  ) {
    if (wheelChairLimitation == null) {
      return Accessibility.NO_INFORMATION;
    }

    switch (wheelChairLimitation.value()) {
      case "true":
        return Accessibility.POSSIBLE;
      case "false":
        return Accessibility.NOT_POSSIBLE;
      default:
        return Accessibility.NO_INFORMATION;
    }
  }
}
