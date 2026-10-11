package org.opentripplanner.ext.netexprofile.vdv462;

import static com.google.common.truth.Truth.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.rutebanken.netex.model.JourneyPatternWaitTime;
import org.rutebanken.netex.model.JourneyPatternWaitTimes_RelStructure;
import org.rutebanken.netex.model.JourneyRunTime;
import org.rutebanken.netex.model.JourneyRunTimes_RelStructure;
import org.rutebanken.netex.model.LinksInJourneyPattern_RelStructure;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.PointsInJourneyPattern_RelStructure;
import org.rutebanken.netex.model.ScheduledStopPointRefStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.ServiceJourneyPattern;
import org.rutebanken.netex.model.ServiceJourneyPatternRefStructure;
import org.rutebanken.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.rutebanken.netex.model.TimeDemandTypeRefStructure;
import org.rutebanken.netex.model.TimetabledPassingTimes_RelStructure;
import org.rutebanken.netex.model.TimingLinkRefStructure;
import org.rutebanken.netex.model.VehicleJourneyRunTime;
import org.rutebanken.netex.model.VehicleJourneyRunTimes_RelStructure;
import org.rutebanken.netex.model.VehicleJourneyWaitTime;
import org.rutebanken.netex.model.VehicleJourneyWaitTimes_RelStructure;

/**
 * A journey pattern A → B → C with a peak and an off-peak time demand type: 5 and 3 minutes from
 * A to B, 6 and 4 minutes from B to C, and a wait time of 1 minute at B in the peak.
 */
class TimingModelPassingTimesTest {

  private static final ObjectFactory OBJECT_FACTORY = new ObjectFactory();
  private static final String PATTERN_ID = "DE::ServiceJourneyPattern:1::";
  private static final String PEAK = "DE::TimeDemandType:HVZ::";
  private static final String OFF_PEAK = "DE::TimeDemandType:NVZ::";

  private final NetexEntityIndex index = new NetexEntityIndex();
  private final DefaultDataImportIssueStore issueStore = new DefaultDataImportIssueStore();

  @Test
  void derivePassingTimesFromRunTimesAndWaitTimes() {
    var journey = journey("SJ-1", LocalTime.of(8, 0), PEAK);

    derive(journey);

    assertThat(times(journey)).containsExactly("-/08:00", "08:05/08:06", "08:12/-").inOrder();
    assertThat(issueTypes()).containsExactly("PassingTimesDerivedFromTimingModel");
  }

  @Test
  void deriveDayOffsetAfterMidnight() {
    var journey = journey("SJ-1", LocalTime.of(23, 58), OFF_PEAK);

    derive(journey);

    assertThat(times(journey)).containsExactly("-/23:58", "00:01+1/00:01+1", "00:05+1/-").inOrder();
  }

  @Test
  void journeySpecificRunTimeAndWaitTimeReplaceThePatternTimes() {
    var journey = journey("SJ-1", LocalTime.of(8, 0), PEAK)
      .withRunTimes(
        new VehicleJourneyRunTimes_RelStructure().withVehicleJourneyRunTime(
          (VehicleJourneyRunTime) new VehicleJourneyRunTime()
            .withTimingLinkRef(new TimingLinkRefStructure().withRef("TL-BC"))
            .withRunTime(Duration.ofMinutes(10))
        )
      )
      .withWaitTimes(
        new VehicleJourneyWaitTimes_RelStructure().withVehicleJourneyWaitTime(
          (VehicleJourneyWaitTime) new VehicleJourneyWaitTime()
            .withTimingPointRef(
              OBJECT_FACTORY.createScheduledStopPointRef(
                new ScheduledStopPointRefStructure().withRef("SSP-B")
              )
            )
            .withWaitTime(Duration.ofMinutes(2))
        )
      );

    derive(journey);

    assertThat(times(journey)).containsExactly("-/08:00", "08:05/08:07", "08:17/-").inOrder();
  }

  @Test
  void doNotDerivePassingTimesIfARunTimeIsMissing() {
    var journey = journey("SJ-1", LocalTime.of(8, 0), "DE::TimeDemandType:Night::");

    derive(journey);

    assertThat(journey.getPassingTimes()).isNull();
    assertThat(issueTypes()).containsExactly("PassingTimesNotDerived");
  }

  @Test
  void keepGivenPassingTimes() {
    var passingTimes = new TimetabledPassingTimes_RelStructure();
    var journey = journey("SJ-1", LocalTime.of(8, 0), PEAK).withPassingTimes(passingTimes);

    derive(journey);

    assertThat(journey.getPassingTimes()).isSameInstanceAs(passingTimes);
    assertThat(issueTypes()).isEmpty();
  }

  private void derive(ServiceJourney journey) {
    index.journeyPatternsById.add(pattern());
    index.serviceJourneyById.add(journey);
    TimingModelPassingTimes.derive(index.readOnlyView(), issueStore);
  }

  private static ServiceJourneyPattern pattern() {
    return new ServiceJourneyPattern()
      .withId(PATTERN_ID)
      .withPointsInSequence(
        new PointsInJourneyPattern_RelStructure().withPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern(
          stopPoint("A", 1, null),
          stopPoint(
            "B",
            2,
            new JourneyPatternWaitTimes_RelStructure().withJourneyPatternWaitTimeRefOrJourneyPatternWaitTime(
              new JourneyPatternWaitTime()
                .withTimeDemandTypeRef(new TimeDemandTypeRefStructure().withRef(PEAK))
                .withWaitTime(Duration.ofMinutes(1))
            )
          ),
          stopPoint("C", 3, null)
        )
      )
      .withLinksInSequence(
        new LinksInJourneyPattern_RelStructure().withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
          link("TL-AB", 1, 5, 3),
          link("TL-BC", 2, 6, 4)
        )
      );
  }

  private static StopPointInJourneyPattern stopPoint(
    String name,
    int order,
    @Nullable JourneyPatternWaitTimes_RelStructure waitTimes
  ) {
    return new StopPointInJourneyPattern()
      .withId("SPIJP-" + name)
      .withOrder(BigInteger.valueOf(order))
      .withScheduledStopPointRef(
        OBJECT_FACTORY.createScheduledStopPointRef(
          new ScheduledStopPointRefStructure().withRef("SSP-" + name)
        )
      )
      .withWaitTimes(waitTimes);
  }

  private static ServiceLinkInJourneyPattern_VersionedChildStructure link(
    String timingLinkId,
    int order,
    int peakMinutes,
    int offPeakMinutes
  ) {
    return (ServiceLinkInJourneyPattern_VersionedChildStructure) new ServiceLinkInJourneyPattern_VersionedChildStructure()
      .withTimingLinkRef(new TimingLinkRefStructure().withRef(timingLinkId))
      .withRunTimes(
        new JourneyRunTimes_RelStructure().withJourneyRunTime(
          runTime(PEAK, peakMinutes),
          runTime(OFF_PEAK, offPeakMinutes)
        )
      )
      .withOrder(BigInteger.valueOf(order));
  }

  private static JourneyRunTime runTime(String timeDemandType, int minutes) {
    return (JourneyRunTime) new JourneyRunTime()
      .withTimeDemandTypeRef(new TimeDemandTypeRefStructure().withRef(timeDemandType))
      .withRunTime(Duration.ofMinutes(minutes));
  }

  private static ServiceJourney journey(String id, LocalTime departure, String timeDemandType) {
    return new ServiceJourney()
      .withId(id)
      .withDepartureTime(departure)
      .withTimeDemandTypeRef(new TimeDemandTypeRefStructure().withRef(timeDemandType))
      .withJourneyPatternRef(
        OBJECT_FACTORY.createServiceJourneyPatternRef(
          new ServiceJourneyPatternRefStructure().withRef(PATTERN_ID)
        )
      );
  }

  /** The passing times as "arrival/departure", with "+n" for a day offset. */
  private static List<String> times(ServiceJourney journey) {
    return journey
      .getPassingTimes()
      .getTimetabledPassingTime()
      .stream()
      .map(
        it ->
          time(it.getArrivalTime(), it.getArrivalDayOffset()) +
          "/" +
          time(it.getDepartureTime(), it.getDepartureDayOffset())
      )
      .toList();
  }

  private static String time(@Nullable LocalTime time, @Nullable BigInteger dayOffset) {
    if (time == null) {
      return "-";
    }
    return time + (dayOffset == null ? "" : "+" + dayOffset);
  }

  private List<String> issueTypes() {
    return issueStore.listIssues().stream().map(DataImportIssue::getType).toList();
  }
}
