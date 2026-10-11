package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.netex.mapping.MappingSupport.ID_FACTORY;
import static org.opentripplanner.netex.mapping.MappingSupport.createWrappedRef;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.transit.model._data.TransitRepositoryForTest;
import org.opentripplanner.transit.model.framework.DefaultEntityById;
import org.opentripplanner.transit.model.site.MultiModalStation;
import org.opentripplanner.transit.model.site.Station;
import org.rutebanken.netex.model.GroupOfStopPlaces;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.StopPlaceRefStructure;
import org.rutebanken.netex.model.StopPlaceRefs_RelStructure;

class GroupOfStationsMapperTest {

  private static final String GROUP_ID = "NSR:GroupOfStopPlaces:1";
  private static final String KNOWN_STATION_ID = "NSR:StopPlace:1";
  private static final String UNKNOWN_STATION_ID = "NSR:StopPlace:2";

  private final TransitRepositoryForTest testModel = TransitRepositoryForTest.of();
  private final DefaultDataImportIssueStore issueStore = new DefaultDataImportIssueStore();
  private final DefaultEntityById<Station> stations = new DefaultEntityById<>();
  private final GroupOfStationsMapper mapper = new GroupOfStationsMapper(
    issueStore,
    ID_FACTORY,
    new DefaultEntityById<MultiModalStation>(),
    stations
  );

  @Test
  void mapGroupWithName() {
    var group = new GroupOfStopPlaces()
      .withId(GROUP_ID)
      .withName(new MultilingualString().withValue("Oslo"));

    assertEquals("Oslo", mapper.map(group).getName().toString());
  }

  @Test
  void useNameOfFirstKnownMemberIfNameIsMissing() {
    stations.add(testModel.station(KNOWN_STATION_ID).build());
    var group = new GroupOfStopPlaces()
      .withId(GROUP_ID)
      .withMembers(members(UNKNOWN_STATION_ID, KNOWN_STATION_ID));

    assertEquals(KNOWN_STATION_ID, mapper.map(group).getName().toString());
    assertThat(listIssueTypes()).contains("GroupOfStopPlacesWithoutName");
  }

  @Test
  void useNotAvailableIfNameAndMembersAreMissing() {
    var group = new GroupOfStopPlaces().withId(GROUP_ID);

    assertEquals("N/A", mapper.map(group).getName().toString());
    assertThat(listIssueTypes()).contains("GroupOfStopPlacesWithoutName");
  }

  private static StopPlaceRefs_RelStructure members(String... stopPlaceIds) {
    var members = new StopPlaceRefs_RelStructure();
    for (String id : stopPlaceIds) {
      members.getStopPlaceRef().add(createWrappedRef(id, StopPlaceRefStructure.class));
    }
    return members;
  }

  private List<String> listIssueTypes() {
    return issueStore.listIssues().stream().map(DataImportIssue::getType).toList();
  }
}
