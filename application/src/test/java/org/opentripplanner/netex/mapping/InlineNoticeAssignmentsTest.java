package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.opentripplanner.netex.mapping.MappingSupport.createJaxbElement;
import static org.opentripplanner.netex.mapping.MappingSupport.createWrappedRef;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rutebanken.netex.model.Call_VersionedChildStructure;
import org.rutebanken.netex.model.JourneyPatternRefStructure;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.Notice;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.NoticeAssignments_RelStructure;
import org.rutebanken.netex.model.NoticeRefStructure;
import org.rutebanken.netex.model.PointsInJourneyPattern_RelStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.ServiceJourneyPattern;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

class InlineNoticeAssignmentsTest {

  private static final String NOTICE_ID = "DE::Notice:1::";
  private static final String LINE_ID = "DE::Line:1::";
  private static final String PATTERN_ID = "DE::ServiceJourneyPattern:1::";
  private static final String STOP_POINT_ID = "DE::StopPointInJourneyPattern:1::";
  private static final String JOURNEY_ID_1 = "DE::ServiceJourney:1::";
  private static final String JOURNEY_ID_2 = "DE::ServiceJourney:2::";
  private static final String OTHER_JOURNEY_ID = "DE::ServiceJourney:3::";

  @Test
  void collectAssignmentsEmbeddedInTheNoticedElement() {
    var line = new Line().withId(LINE_ID).withNoticeAssignments(assignments(withNotice()));
    var stopPoint = new StopPointInJourneyPattern()
      .withId(STOP_POINT_ID)
      .withNoticeAssignments(
        new Call_VersionedChildStructure.NoticeAssignments().withNoticeAssignment_OrNoticeAssignmentView(
          createJaxbElement(withNoticeRef())
        )
      );
    var nonPassengerStopPoint = new StopPointInJourneyPattern()
      .withId("DE::StopPointInJourneyPattern:2::")
      .withForBoarding(false)
      .withForAlighting(false)
      .withNoticeAssignments(
        new Call_VersionedChildStructure.NoticeAssignments().withNoticeAssignment_OrNoticeAssignmentView(
          createJaxbElement(withNoticeRef())
        )
      );
    var pattern = new ServiceJourneyPattern()
      .withId(PATTERN_ID)
      .withNoticeAssignments(assignments(withNoticeRef()))
      .withPointsInSequence(
        new PointsInJourneyPattern_RelStructure().withPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern(
          stopPoint,
          nonPassengerStopPoint,
          new StopPointInJourneyPattern().withId("DE::StopPointInJourneyPattern:3::")
        )
      );
    var journey1 = journey(JOURNEY_ID_1, PATTERN_ID).withNoticeAssignments(
      assignments(withNoticeRef())
    );
    var journey2 = journey(JOURNEY_ID_2, PATTERN_ID);
    var otherJourney = journey(OTHER_JOURNEY_ID, "DE::ServiceJourneyPattern:2::");

    var result = InlineNoticeAssignments.collect(
      List.of(pattern),
      List.of(line),
      List.of(journey1, journey2, otherJourney)
    );

    assertThat(
      result
        .assignments()
        .stream()
        .map(InlineNoticeAssignments.Assignment::noticedObjectId)
        .toList()
    ).containsExactly(
      LINE_ID,
      JOURNEY_ID_1,
      // the assignment on the pattern applies to all journeys of the pattern
      JOURNEY_ID_1,
      JOURNEY_ID_2,
      STOP_POINT_ID
    );
    assertThat(result.noticesById().keySet()).containsExactly(NOTICE_ID);
  }

  @Test
  void assignNoticesOnEveryStopPointToTheJourneys() {
    var pattern = new ServiceJourneyPattern()
      .withId(PATTERN_ID)
      .withPointsInSequence(
        new PointsInJourneyPattern_RelStructure().withPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern(
          stopPointWithNotice(STOP_POINT_ID, withNotice()),
          stopPointWithNotice("DE::StopPointInJourneyPattern:2::", withNoticeRef())
        )
      );

    var result = InlineNoticeAssignments.collect(
      List.of(pattern),
      List.of(),
      List.of(journey(JOURNEY_ID_1, PATTERN_ID), journey(JOURNEY_ID_2, PATTERN_ID))
    );

    assertThat(
      result
        .assignments()
        .stream()
        .map(InlineNoticeAssignments.Assignment::noticedObjectId)
        .toList()
    ).containsExactly(JOURNEY_ID_1, JOURNEY_ID_2);
    assertThat(result.noticesById().keySet()).containsExactly(NOTICE_ID);
  }

  private static StopPointInJourneyPattern stopPointWithNotice(
    String id,
    NoticeAssignment assignment
  ) {
    return new StopPointInJourneyPattern()
      .withId(id)
      .withNoticeAssignments(
        new Call_VersionedChildStructure.NoticeAssignments().withNoticeAssignment_OrNoticeAssignmentView(
          createJaxbElement(assignment)
        )
      );
  }

  private static ServiceJourney journey(String id, String patternId) {
    return new ServiceJourney()
      .withId(id)
      .withJourneyPatternRef(createWrappedRef(patternId, JourneyPatternRefStructure.class));
  }

  private static NoticeAssignments_RelStructure assignments(NoticeAssignment assignment) {
    return new NoticeAssignments_RelStructure().withNoticeAssignment_OrNoticeAssignmentView(
      createJaxbElement(assignment)
    );
  }

  private static NoticeAssignment withNotice() {
    return new NoticeAssignment().withNotice(new Notice().withId(NOTICE_ID));
  }

  private static NoticeAssignment withNoticeRef() {
    return new NoticeAssignment().withNoticeRef(new NoticeRefStructure().withRef(NOTICE_ID));
  }
}
