package org.opentripplanner.netex.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.netex.index.api.HMapValidationRule.Status.DISCARD;
import static org.opentripplanner.netex.index.api.HMapValidationRule.Status.OK;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.rutebanken.netex.model.ServiceJourneyBuilder;

class ServiceJourneyWithoutPassingTimesTest {

  private static final String JOURNEY_ID = "journey";

  private final ServiceJourneyWithoutPassingTimes rule = new ServiceJourneyWithoutPassingTimes();

  @Test
  void journeyWithPassingTimes() {
    rule.setup(new NetexEntityIndex().readOnlyView());

    var journey = new ServiceJourneyBuilder(JOURNEY_ID).withPassingTimes(List.of("P-1")).build();

    assertEquals(OK, rule.validate(journey));
  }

  @Test
  void journeyWithoutPassingTimes() {
    rule.setup(new NetexEntityIndex().readOnlyView());

    var journey = new ServiceJourneyBuilder(JOURNEY_ID).build();

    assertEquals(DISCARD, rule.validate(journey));
    assertEquals(
      "ServiceJourneyWithoutPassingTimes",
      rule.logMessage(JOURNEY_ID, journey).getType()
    );
  }
}
