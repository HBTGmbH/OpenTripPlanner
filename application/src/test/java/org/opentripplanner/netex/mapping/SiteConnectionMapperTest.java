package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.netex.mapping.MappingSupport.ID_FACTORY;
import static org.opentripplanner.netex.mapping.MappingSupport.createWrappedRef;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.transfer.constrained.model.StationTransferPoint;
import org.opentripplanner.transfer.constrained.model.StopTransferPoint;
import org.opentripplanner.transit.model._data.TransitRepositoryForTest;
import org.opentripplanner.transit.model.framework.DefaultEntityById;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;
import org.rutebanken.netex.model.QuayRefStructure;
import org.rutebanken.netex.model.SiteConnection;
import org.rutebanken.netex.model.SiteConnectionEndStructure;
import org.rutebanken.netex.model.StopPlaceRefStructure;
import org.rutebanken.netex.model.TransferDurationStructure;

class SiteConnectionMapperTest {

  private static final String STOP_PLACE_A = "DE::StopPlace:A::";
  private static final String STOP_PLACE_B = "DE::StopPlace:B::";
  private static final String QUAY_A1 = "DE::Quay:A1::";
  private static final String QUAY_A2 = "DE::Quay:A2::";

  private final TransitRepositoryForTest testModel = TransitRepositoryForTest.of();
  private final DefaultDataImportIssueStore issueStore = new DefaultDataImportIssueStore();
  private final DefaultEntityById<Station> stations = new DefaultEntityById<>();
  private final DefaultEntityById<RegularStop> stops = new DefaultEntityById<>();
  private final Set<String> mappedConnections = new HashSet<>();
  private final SiteConnectionMapper subject = new SiteConnectionMapper(
    ID_FACTORY,
    issueStore,
    stations,
    stops,
    mappedConnections
  );

  @Test
  void connectionBetweenTwoStopPlacesInBothDirections() {
    var stationA = addStation(STOP_PLACE_A);
    var stationB = addStation(STOP_PLACE_B);

    var transfers = subject.map(connection(stopPlace(STOP_PLACE_A), stopPlace(STOP_PLACE_B), true));

    assertEquals(2, transfers.size());
    var forward = transfers.get(0);
    assertEquals(stationA, ((StationTransferPoint) forward.getFrom()).getStation());
    assertEquals(stationB, ((StationTransferPoint) forward.getTo()).getStation());
    assertEquals(120, forward.getTransferConstraint().getMinTransferTime());
    var reverse = transfers.get(1);
    assertEquals(stationB, ((StationTransferPoint) reverse.getFrom()).getStation());
  }

  @Test
  void connectionBetweenTwoQuaysOfOneStopPlace() {
    var station = addStation(STOP_PLACE_A);
    var quayA1 = addQuay(QUAY_A1, station);
    var quayA2 = addQuay(QUAY_A2, station);

    var transfers = subject.map(connection(quay(QUAY_A1), quay(QUAY_A2), true));

    assertEquals(2, transfers.size());
    assertEquals(quayA1, ((StopTransferPoint) transfers.get(0).getFrom()).getStop());
    assertEquals(quayA2, ((StopTransferPoint) transfers.get(0).getTo()).getStop());
    assertThat(issueStore.listIssues()).isEmpty();
  }

  @Test
  void connectionWithinOneStopPlaceIsMappedOnce() {
    var station = addStation(STOP_PLACE_A);

    var transfers = subject.map(connection(stopPlace(STOP_PLACE_A), stopPlace(STOP_PLACE_A), true));

    assertEquals(1, transfers.size());
    assertEquals(station, ((StationTransferPoint) transfers.getFirst().getFrom()).getStation());
    assertEquals(station, ((StationTransferPoint) transfers.getFirst().getTo()).getStation());
    assertEquals(120, transfers.getFirst().getTransferConstraint().getMinTransferTime());
  }

  @Test
  void connectionFromAQuayToItsStopPlace() {
    var station = addStation(STOP_PLACE_A);
    var quay = addQuay(QUAY_A1, station);

    var transfers = subject.map(connection(quay(QUAY_A1), stopPlace(STOP_PLACE_A), false));

    assertEquals(1, transfers.size());
    assertEquals(quay, ((StopTransferPoint) transfers.getFirst().getFrom()).getStop());
  }

  /** The German data repeats a site connection in the files of every operating unit. */
  @Test
  void sameConnectionOfAnotherFileGroupIsMappedOnce() {
    addStation(STOP_PLACE_A);
    var mapperOfOtherGroup = new SiteConnectionMapper(
      ID_FACTORY,
      issueStore,
      stations,
      stops,
      mappedConnections
    );
    var connection = connection(stopPlace(STOP_PLACE_A), stopPlace(STOP_PLACE_A), true);

    assertEquals(1, subject.map(connection).size());
    assertThat(mapperOfOtherGroup.map(connection.withId("DE::SiteConnection:2::"))).isEmpty();
    var longer = connection
      .withId("DE::SiteConnection:3::")
      .withWalkTransferDuration(
        new TransferDurationStructure().withDefaultDuration(Duration.ofMinutes(5))
      );
    assertEquals(1, mapperOfOtherGroup.map(longer).size());
    assertThat(issueStore.listIssues()).isEmpty();
  }

  @Test
  void connectionFromAQuayToAnotherStopPlace() {
    var quay = addQuay(QUAY_A1, addStation(STOP_PLACE_A));
    var stationB = addStation(STOP_PLACE_B);

    var transfers = subject.map(connection(quay(QUAY_A1), stopPlace(STOP_PLACE_B), false));

    assertEquals(1, transfers.size());
    assertEquals(quay, ((StopTransferPoint) transfers.getFirst().getFrom()).getStop());
    assertEquals(stationB, ((StationTransferPoint) transfers.getFirst().getTo()).getStation());
  }

  @Test
  void ignoreConnectionWithoutDuration() {
    addStation(STOP_PLACE_A);
    var connection = connection(
      stopPlace(STOP_PLACE_A),
      stopPlace(STOP_PLACE_A),
      false
    ).withWalkTransferDuration(null);

    assertThat(subject.map(connection)).isEmpty();
    assertThat(listIssueTypes()).containsExactly("InvalidSiteConnection");
  }

  private Station addStation(String id) {
    var station = testModel.station(id).build();
    stations.add(station);
    return station;
  }

  private RegularStop addQuay(String id, Station station) {
    var quay = testModel.stop(id).withParentStation(station).build();
    stops.add(quay);
    return quay;
  }

  private static SiteConnection connection(
    SiteConnectionEndStructure from,
    SiteConnectionEndStructure to,
    boolean bothWays
  ) {
    return new SiteConnection()
      .withId("DE::SiteConnection:1::")
      .withWalkTransferDuration(
        new TransferDurationStructure().withDefaultDuration(Duration.ofMinutes(2))
      )
      .withBothWays(bothWays)
      .withFrom(from)
      .withTo(to);
  }

  private static SiteConnectionEndStructure stopPlace(String id) {
    return new SiteConnectionEndStructure().withStopPlaceRef(
      createWrappedRef(id, StopPlaceRefStructure.class)
    );
  }

  private static SiteConnectionEndStructure quay(String id) {
    return new SiteConnectionEndStructure().withQuayRef(
      createWrappedRef(id, QuayRefStructure.class)
    );
  }

  private List<String> listIssueTypes() {
    return issueStore.listIssues().stream().map(DataImportIssue::getType).toList();
  }
}
