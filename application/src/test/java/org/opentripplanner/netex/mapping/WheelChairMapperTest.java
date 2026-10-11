package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.rutebanken.netex.model.LimitationStatusEnumeration.FALSE;
import static org.rutebanken.netex.model.LimitationStatusEnumeration.PARTIAL;
import static org.rutebanken.netex.model.LimitationStatusEnumeration.TRUE;
import static org.rutebanken.netex.model.LimitationStatusEnumeration.UNKNOWN;

import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.accessibility.Accessibility;
import org.rutebanken.netex.model.AccessibilityAssessment;
import org.rutebanken.netex.model.AccessibilityLimitation;
import org.rutebanken.netex.model.AccessibilityLimitations_RelStructure;
import org.rutebanken.netex.model.LimitationStatusEnumeration;

class WheelChairMapperTest {

  @Test
  void mapWheelchairLimitation() {
    var assessment = assessment(FALSE, TRUE);

    assertThat(map(assessment, false)).isEqualTo(Accessibility.POSSIBLE);
    assertThat(map(assessment, true)).isEqualTo(Accessibility.POSSIBLE);
  }

  @Test
  void useDefaultValueWithoutAssessment() {
    assertThat(map(null, true)).isEqualTo(Accessibility.NOT_POSSIBLE);
  }

  @Test
  void ignoreMobilityImpairedAccessIfNotEnabled() {
    assertThat(map(assessment(TRUE, null), false)).isEqualTo(Accessibility.NOT_POSSIBLE);
  }

  @Test
  void useMobilityImpairedAccessWithoutWheelchairLimitation() {
    assertThat(map(assessment(TRUE, null), true)).isEqualTo(Accessibility.POSSIBLE);
    assertThat(
      WheelChairMapper.wheelchairAccessibility(
        assessment(FALSE, null),
        Accessibility.POSSIBLE,
        true
      )
    ).isEqualTo(Accessibility.NOT_POSSIBLE);
  }

  @Test
  void useDefaultValueIfMobilityImpairedAccessIsInconclusive() {
    assertThat(map(assessment(PARTIAL, null), true)).isEqualTo(Accessibility.NOT_POSSIBLE);
    assertThat(map(assessment(UNKNOWN, null), true)).isEqualTo(Accessibility.NOT_POSSIBLE);
  }

  /** Map with a default value that differs from all values derived from the assessment. */
  private static Accessibility map(
    AccessibilityAssessment assessment,
    boolean useMobilityImpairedAccess
  ) {
    return WheelChairMapper.wheelchairAccessibility(
      assessment,
      Accessibility.NOT_POSSIBLE,
      useMobilityImpairedAccess
    );
  }

  private static AccessibilityAssessment assessment(
    LimitationStatusEnumeration mobilityImpairedAccess,
    LimitationStatusEnumeration wheelchairAccess
  ) {
    var assessment = new AccessibilityAssessment().withMobilityImpairedAccess(
      mobilityImpairedAccess
    );
    if (wheelchairAccess != null) {
      assessment.withLimitations(
        new AccessibilityLimitations_RelStructure().withAccessibilityLimitation(
          new AccessibilityLimitation().withWheelchairAccess(wheelchairAccess)
        )
      );
    }
    return assessment;
  }
}
