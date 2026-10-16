package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.opentripplanner.netex.mapping.MappingSupport.ID_FACTORY;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.deduplicator.DeduplicatorService;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.transfer.constrained.model.TripTransferPoint;
import org.opentripplanner.transit.model._data.TransitRepositoryForTest;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimesFactory;
import org.rutebanken.netex.model.ScheduledStopPointRefStructure;
import org.rutebanken.netex.model.ServiceJourneyInterchange;
import org.rutebanken.netex.model.VehicleJourneyRefStructure;

/**
 * An interchange of one file group references a journey of another file group, as in the German
 * data where every operating unit is a file group: a bus continues as a regional bus of another
 * operating unit at stop B.
 */
class GroupNetexMapperTest {

  private static final TransitRepositoryForTest TEST_MODEL = TransitRepositoryForTest.of();
  private static final NetexProfileSupport EPIP = NetexProfileSupport.of(true, false);

  private final RegularStop stopA = TEST_MODEL.stop("A").build();
  private final RegularStop stopB = TEST_MODEL.stop("B").build();
  private final RegularStop stopC = TEST_MODEL.stop("C").build();
  private final Trip bus = TransitRepositoryForTest.trip("SJ:1").build();
  private final Trip regionalBus = TransitRepositoryForTest.trip("SJ:2").build();

  private final DefaultDataImportIssueStore issueStore = new DefaultDataImportIssueStore();
  private final TransitDataImportBuilder transitBuilder = new TransitDataImportBuilder(
    TEST_MODEL.siteRepositoryBuilder()
      .withRegularStops(List.of(stopA, stopB, stopC))
      .build(),
    issueStore
  );

  @Test
  void mapInterchangeToJourneyOfOtherFileGroupAfterAllGroups() {
    setupTripsAndStopPoints();
    var shared = new GroupNetexMapper(ID_FACTORY, issueStore, transitBuilder, EPIP);
    var group = groupMapper(shared, EPIP);

    group.mapGroupEntries();
    assertThat(transitBuilder.getTransfers()).isEmpty();

    shared.mapGroupEntries();
    assertThat(transitBuilder.getTransfers()).hasSize(1);
    var transfer = transitBuilder.getTransfers().getFirst();
    var from = (TripTransferPoint) transfer.getFrom();
    var to = (TripTransferPoint) transfer.getTo();
    assertThat(from.getTrip()).isEqualTo(bus);
    assertThat(from.getStopPositionInPattern()).isEqualTo(1);
    assertThat(to.getTrip()).isEqualTo(regionalBus);
    assertThat(to.getStopPositionInPattern()).isEqualTo(0);
    assertThat(transfer.getTransferConstraint().isStaySeated()).isTrue();
    assertThat(issueStore.listIssues()).isEmpty();
  }

  @Test
  void interchangeToJourneyOfOtherFileGroupIsInvalidWithoutExtendedNetex() {
    setupTripsAndStopPoints();
    var shared = new GroupNetexMapper(
      ID_FACTORY,
      issueStore,
      transitBuilder,
      NetexProfileSupport.NORDIC
    );

    groupMapper(shared, NetexProfileSupport.NORDIC).mapGroupEntries();
    shared.mapGroupEntries();

    assertThat(transitBuilder.getTransfers()).isEmpty();
    assertThat(issueStore.listIssues().stream().map(DataImportIssue::getType).toList()).contains(
      "InvalidInterchange"
    );
  }

  private GroupNetexMapper groupMapper(GroupNetexMapper shared, NetexProfileSupport profile) {
    var group = new GroupNetexMapper(ID_FACTORY, issueStore, transitBuilder, profile, shared);
    group.scheduledStopPointsIndex.put("SJ:1", List.of("SSP:A", "SSP:B"));
    group.addInterchange(
      List.of(
        new ServiceJourneyInterchange()
          .withId("SJI:1")
          .withFromJourneyRef(new VehicleJourneyRefStructure().withRef("SJ:1"))
          .withFromPointRef(new ScheduledStopPointRefStructure().withRef("SSP:B"))
          .withToJourneyRef(new VehicleJourneyRefStructure().withRef("SJ:2"))
          .withToPointRef(new ScheduledStopPointRefStructure().withRef("SSP:B2"))
          .withStaySeated(true)
      )
    );
    return group;
  }

  /** Both journeys are mapped; the second one belongs to another file group. */
  private void setupTripsAndStopPoints() {
    addTripPattern(bus, stopA, stopB);
    addTripPattern(regionalBus, stopB, stopC);
    transitBuilder.addStopByScheduledStopPoint(ID_FACTORY.createId("SSP:B"), stopB);
    transitBuilder.addStopByScheduledStopPoint(ID_FACTORY.createId("SSP:B2"), stopB);
  }

  private void addTripPattern(Trip trip, RegularStop... stops) {
    var stopTimes = new ArrayList<StopTime>();
    for (int i = 0; i < stops.length; i++) {
      var stopTime = new StopTime();
      stopTime.setTrip(trip);
      stopTime.setStop(stops[i]);
      stopTime.setStopSequence(i);
      stopTime.setArrivalTime(28800 + 300 * i);
      stopTime.setDepartureTime(28800 + 300 * i);
      stopTimes.add(stopTime);
    }
    var stopPattern = new StopPattern(stopTimes);
    var tripTimes = TripTimesFactory.tripTimes(trip, stopTimes, DeduplicatorService.NOOP);
    var pattern = TransitRepositoryForTest.tripPattern("P" + trip.getId().getId(), trip.getRoute())
      .withStopPattern(stopPattern)
      .withScheduledTimeTableBuilder(builder -> builder.addTripTimes(tripTimes))
      .build();
    transitBuilder.getTripsById().add(trip);
    transitBuilder.getTripPatterns().put(stopPattern, pattern);
  }
}
