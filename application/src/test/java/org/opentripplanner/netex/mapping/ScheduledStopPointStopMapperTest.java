package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.netex.mapping.MappingSupport.ID_FACTORY;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.netex.NetexTestDataSupport;
import org.opentripplanner.netex.index.hierarchy.HierarchicalMap;
import org.opentripplanner.netex.index.hierarchy.HierarchicalMapById;
import org.opentripplanner.netex.index.hierarchy.HierarchicalVersionMapById;
import org.opentripplanner.street.geometry.WgsCoordinate;
import org.opentripplanner.transit.model._data.TransitRepositoryForTest;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.service.SiteRepository;
import org.opentripplanner.transit.service.SiteRepositoryBuilder;
import org.rutebanken.netex.model.LocationStructure;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.PrivateCodeStructure;
import org.rutebanken.netex.model.ScheduledStopPoint;
import org.rutebanken.netex.model.StopPlace;

class ScheduledStopPointStopMapperTest {

  private static final String STOP_PLACE_ID = "StopPlace:1";
  private static final String SSP_ID_1 = "SSP_1";
  private static final String SSP_ID_2 = "SSP_2";

  private final DefaultDataImportIssueStore issueStore = new DefaultDataImportIssueStore();
  private final SiteRepositoryBuilder siteRepositoryBuilder = SiteRepository.of();
  private final HierarchicalMap<String, String> stopPlaceIdByStopPointRef = new HierarchicalMap<>();
  private final HierarchicalMapById<ScheduledStopPoint> scheduledStopPoints =
    new HierarchicalMapById<>();
  private final HierarchicalVersionMapById<StopPlace> stopPlaces =
    new HierarchicalVersionMapById<>();
  private final Station station = TransitRepositoryForTest.of().station(STOP_PLACE_ID).build();

  private final ScheduledStopPointStopMapper subject = new ScheduledStopPointStopMapper(
    ID_FACTORY,
    issueStore,
    siteRepositoryBuilder
  );

  @Test
  void deriveStopAtTheLocationOfTheScheduledStopPoint() {
    addStopPlace(stopPlaceWithoutQuays());
    assign(scheduledStopPoint(SSP_ID_1, "Platform A", 53.5, 10.0).withPublicCode(code("A")));

    var stops = mapStops();

    var stop = stops.get(SSP_ID_1);
    assertEquals(ID_FACTORY.createId(SSP_ID_1), stop.getId());
    assertEquals("Platform A", stop.getName().toString());
    assertEquals("A", stop.getPlatformCode());
    assertEquals(new WgsCoordinate(53.5, 10.0), stop.getCoordinate());
    assertEquals(station, stop.getParentStation());
    assertThat(listIssueTypes()).containsExactly("StopDerivedFromScheduledStopPoint");
  }

  @Test
  void deriveStopAtTheStopPlaceIfTheScheduledStopPointHasNoLocation() {
    addStopPlace(stopPlaceWithoutQuays());
    assign(new ScheduledStopPoint().withId(SSP_ID_1));

    var stop = mapStops().get(SSP_ID_1);

    assertEquals(station.getCoordinate(), stop.getCoordinate());
    assertEquals(station.getName(), stop.getName());
  }

  @Test
  void doNotDeriveStopsForStopPlacesWithQuays() {
    addStopPlace(
      NetexTestDataSupport.createStopPlace(NetexTestDataSupport.createQuay()).withId(STOP_PLACE_ID)
    );
    assign(scheduledStopPoint(SSP_ID_1, "Platform A", 53.5, 10.0));

    assertThat(mapStops()).isEmpty();
  }

  @Test
  void meanPositionOfTheAssignedScheduledStopPoints() {
    assign(scheduledStopPoint(SSP_ID_1, "A", 53.0, 10.0));
    assign(scheduledStopPoint(SSP_ID_2, "B", 54.0, 11.0));

    var result = ScheduledStopPointStopMapper.meanPositionByStopPlaceId(
      stopPlaceIdByStopPointRef,
      scheduledStopPoints
    );

    assertEquals(new WgsCoordinate(53.5, 10.5), result.get(STOP_PLACE_ID));
  }

  private Map<String, RegularStop> mapStops() {
    return subject.mapStops(stopPlaceIdByStopPointRef, scheduledStopPoints, stopPlaces);
  }

  private void addStopPlace(StopPlace stopPlace) {
    stopPlaces.add(stopPlace);
    siteRepositoryBuilder.withStation(station);
  }

  private void assign(ScheduledStopPoint scheduledStopPoint) {
    scheduledStopPoints.add(scheduledStopPoint);
    stopPlaceIdByStopPointRef.add(scheduledStopPoint.getId(), STOP_PLACE_ID);
  }

  private static StopPlace stopPlaceWithoutQuays() {
    return new StopPlace().withId(STOP_PLACE_ID).withVersion("1");
  }

  private static ScheduledStopPoint scheduledStopPoint(
    String id,
    String name,
    double lat,
    double lon
  ) {
    return new ScheduledStopPoint()
      .withId(id)
      .withName(new MultilingualString().withValue(name))
      .withLocation(
        new LocationStructure()
          .withLatitude(BigDecimal.valueOf(lat))
          .withLongitude(BigDecimal.valueOf(lon))
      );
  }

  private static PrivateCodeStructure code(String value) {
    return new PrivateCodeStructure().withValue(value);
  }

  private List<String> listIssueTypes() {
    return issueStore.listIssues().stream().map(DataImportIssue::getType).toList();
  }
}
