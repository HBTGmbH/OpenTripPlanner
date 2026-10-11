package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.opentripplanner.netex.NetexTestDataSupport.createQuay;
import static org.opentripplanner.netex.NetexTestDataSupport.createStopPlace;

import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.netex.NetexTestDataSupport;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.service.SiteRepository;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.StopPlace;

class StationMapperTest {

  private StationMapper stationMapper;

  @BeforeEach
  void setUp() {
    stationMapper = new StationMapper(
      DataImportIssueStore.NOOP,
      new FeedScopedIdFactory("FEED_ID"),
      ZoneId.of("UTC"),
      false,
      Set.of(),
      SiteRepository.of()
    );
  }

  @Test
  void testMappingWithStopPlaceCentroid() {
    StopPlace stopPlace = createStopPlace();
    Station station = stationMapper.map(stopPlace);
    assertEquals(NetexTestDataSupport.STOP_PLACE_LAT, station.getLat());
    assertEquals(NetexTestDataSupport.STOP_PLACE_LON, station.getLon());
  }

  @Test
  void testMappingWithoutAnyCoordinate() {
    var issueStore = new DefaultDataImportIssueStore();
    var mapper = new StationMapper(
      issueStore,
      new FeedScopedIdFactory("FEED_ID"),
      ZoneId.of("UTC"),
      false,
      Set.of(),
      SiteRepository.of()
    );
    StopPlace stopPlace = createStopPlace();
    stopPlace.withCentroid(null);

    assertNull(mapper.map(stopPlace));
    assertThat(issueStore.listIssues().stream().map(DataImportIssue::getType).toList()).contains(
      "StopPlaceWithoutCoordinates"
    );
  }

  @Test
  void testMappingWithQuayCentroid() {
    Quay quay = createQuay();
    StopPlace stopPlace = createStopPlace(quay);
    stopPlace.withCentroid(null);
    Station station = stationMapper.map(stopPlace);
    assertEquals(NetexTestDataSupport.QUAY_LAT, station.getLat());
    assertEquals(NetexTestDataSupport.QUAY_LON, station.getLon());
  }
}
