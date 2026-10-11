package org.opentripplanner.netex.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.netex.index.api.HMapValidationRule.Status.DISCARD;
import static org.opentripplanner.netex.index.api.HMapValidationRule.Status.OK;

import org.junit.jupiter.api.Test;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.ServiceJourneyBuilder;
import org.rutebanken.netex.model.ServiceJourneyPatternBuilder;

class JourneyPatternNotFoundInSJTest {

  private static final String PATTERN_ID = "pattern";
  private static final String JOURNEY_ID = "journey";

  private final NetexEntityIndex index = new NetexEntityIndex();
  private final JourneyPatternNotFoundInSJ rule = new JourneyPatternNotFoundInSJ();

  @Test
  void patternExists() {
    index.journeyPatternsById.add(new ServiceJourneyPatternBuilder(PATTERN_ID).build());
    rule.setup(index.readOnlyView());

    var journey = new ServiceJourneyBuilder(JOURNEY_ID).withPatternId(PATTERN_ID).build();

    assertEquals(OK, rule.validate(journey));
  }

  @Test
  void patternDoesNotExist() {
    rule.setup(index.readOnlyView());

    var journey = new ServiceJourneyBuilder(JOURNEY_ID).withPatternId(PATTERN_ID).build();

    assertEquals(DISCARD, rule.validate(journey));
  }

  @Test
  void journeyWithoutPatternRef() {
    rule.setup(index.readOnlyView());

    var journey = new ServiceJourney().withId(JOURNEY_ID);

    assertEquals(DISCARD, rule.validate(journey));
    assertEquals(
      "Object not found. ServiceJourney JourneyPattern is not found in import. Id = journey, JourneyPattern = null",
      rule.logMessage(JOURNEY_ID, journey).getMessage()
    );
  }
}
