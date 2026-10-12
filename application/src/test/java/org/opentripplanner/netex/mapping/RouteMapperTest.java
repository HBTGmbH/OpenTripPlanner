package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.netex.mapping.MappingSupport.createJaxbElement;

import com.google.common.collect.ArrayListMultimap;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.opentripplanner.transit.model._data.TransitRepositoryForTest;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.DefaultEntityById;
import org.opentripplanner.transit.model.network.BikeAccess;
import org.opentripplanner.transit.model.network.GroupOfRoutes;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.organization.Branding;
import org.opentripplanner.transit.model.organization.Operator;
import org.opentripplanner.transit.service.SiteRepository;
import org.rutebanken.netex.model.AllVehicleModesOfTransportEnumeration;
import org.rutebanken.netex.model.Authority;
import org.rutebanken.netex.model.AuthorityRef;
import org.rutebanken.netex.model.BrandingRefStructure;
import org.rutebanken.netex.model.GroupOfLinesRefStructure;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.JourneyPatternRefStructure;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.LineRefStructure;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.Network;
import org.rutebanken.netex.model.Notice;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.NoticeAssignments_RelStructure;
import org.rutebanken.netex.model.OperatorRefStructure;
import org.rutebanken.netex.model.PointsInJourneyPattern_RelStructure;
import org.rutebanken.netex.model.PresentationStructure;
import org.rutebanken.netex.model.RouteView;
import org.rutebanken.netex.model.ScheduledStopPointRefStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopPlace;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.rutebanken.netex.model.TransportOrganisationRefStructure;
import org.rutebanken.netex.model.TransportOrganisationRefs_RelStructure;
import org.rutebanken.netex.model.TransportSubmodeStructure;
import org.rutebanken.netex.model.WaterSubmodeEnumeration;

class RouteMapperTest {

  private static final String NETWORK_ID = "RUT:Network:1";
  private static final String GOL_ID_1 = "RUT:GroupOfLines:1";
  private static final String GOL_ID_2 = "RUT:GroupOfLines:2";
  private static final String GOL_NAME_1 = "G1";
  private static final String GOL_NAME_2 = "G2";
  private static final String AUTHORITY_ID = "RUT:Authority:1";
  private static final String OPERATOR_ID = "RUT:Operator:1";
  private static final String BRANDING_ID = "RUT:Branding:1";
  private static final String LINE_ID = "RUT:Line:1";
  private static final String FERRY_WITHOUT_BICYCLES_ID = "RUT:Line:2:NoBicycles";

  private static final Set<String> EMPTY_FERRY_WITHOUT_BICYCLE_IDS = Collections.emptySet();

  @Test
  void mapRouteWithDefaultAgency() {
    NetexEntityIndex netexEntityIndex = new NetexEntityIndex();
    Line line = createExampleLine();

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      ArrayListMultimap.create(),
      new DefaultEntityById<>(),
      netexEntityIndex.readOnlyView(),
      ZoneId.systemDefault().getId(),
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      NetexProfileSupport.NORDIC
    );

    Route route = routeMapper.mapRoute(line);

    assertEquals(MappingSupport.ID_FACTORY.createId(LINE_ID), route.getId());
    assertEquals("Line 1", route.getLongName().toString());
    assertEquals("L1", route.getShortName());
  }

  @Test
  void mapRouteWithAgencySpecified() {
    NetexEntityIndex netexIndex = new NetexEntityIndex();
    TransitDataImportBuilder transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );

    Network network = new Network()
      .withId(NETWORK_ID)
      .withTransportOrganisationRef(
        createJaxbElement(new TransportOrganisationRefStructure().withRef(AUTHORITY_ID))
      );

    netexIndex.networkById.add(network);
    netexIndex.authoritiesById.add(new Authority().withId(AUTHORITY_ID));

    transitBuilder.getAgenciesById().add(createAgency());

    Line line = createExampleLine();

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      transitBuilder.getAgenciesById(),
      transitBuilder.getOperatorsById(),
      transitBuilder.getBrandingsById(),
      transitBuilder.getGroupsOfRoutesByRouteId(),
      transitBuilder.getGroupOfRouteById(),
      netexIndex.readOnlyView(),
      TransitRepositoryForTest.TIME_ZONE_ID,
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      NetexProfileSupport.NORDIC
    );

    Route route = routeMapper.mapRoute(line);

    assertEquals(AUTHORITY_ID, route.getAgency().getId().getId());
  }

  @Test
  void mapRouteWithNetworkWithoutTransportOrganisation() {
    NetexEntityIndex netexIndex = new NetexEntityIndex();
    netexIndex.networkById.add(new Network().withId(NETWORK_ID));
    var issueStore = new DefaultDataImportIssueStore();

    Route route = createRouteMapper(netexIndex, issueStore).mapRoute(createExampleLine());

    assertEquals("N/A", route.getAgency().getName());
    assertThat(listIssueTypes(issueStore)).containsExactly("LineWithoutAuthority");
  }

  @Test
  void mapRouteWithoutNameUsesShortName() {
    var issueStore = new DefaultDataImportIssueStore();
    var line = createExampleLine();
    line.setName(null);
    line.setShortName(new MultilingualString().withValue("Short 1"));

    Route route = createRouteMapper(new NetexEntityIndex(), issueStore).mapRoute(line);

    assertEquals("Short 1", route.getLongName().toString());
    assertEquals("L1", route.getShortName());
    assertThat(listIssueTypes(issueStore)).contains("LineWithoutName");
  }

  @Test
  void skipRouteWithoutAnyName() {
    var issueStore = new DefaultDataImportIssueStore();
    var line = createExampleLine();
    line.setName(null);
    line.setPublicCode(null);

    assertNull(createRouteMapper(new NetexEntityIndex(), issueStore).mapRoute(line));
    assertThat(listIssueTypes(issueStore)).containsExactly("LineWithoutName");
  }

  @Test
  void mapRouteWithSubModeOnly() {
    var line = createExampleLine();
    line.setTransportMode(null);
    line.setTransportSubmode(
      new TransportSubmodeStructure().withWaterSubmode(WaterSubmodeEnumeration.LOCAL_CAR_FERRY)
    );

    Route route = createRouteMapper(new NetexEntityIndex(), DataImportIssueStore.NOOP).mapRoute(
      line
    );

    assertEquals(TransitMode.FERRY, route.getMode());
    assertEquals(BikeAccess.UNKNOWN, route.getBikesAllowed());
  }

  @Test
  void useAdditionalOperatorWithExtendedNetexSupport() {
    var transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );
    transitBuilder.getOperatorsById().add(createOperator(OPERATOR_ID));
    var line = createExampleLine().withAdditionalOperators(
      new TransportOrganisationRefs_RelStructure().withTransportOrganisationRef(
        createJaxbElement(new TransportOrganisationRefStructure().withRef(AUTHORITY_ID)),
        createJaxbElement(new TransportOrganisationRefStructure().withRef(OPERATOR_ID))
      )
    );

    Route route = createRouteMapper(
      new NetexEntityIndex(),
      transitBuilder,
      DataImportIssueStore.NOOP,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(OPERATOR_ID, route.getOperator().getId().getId());
  }

  @Test
  void ignoreAdditionalOperatorWithoutExtendedNetexSupport() {
    var transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );
    transitBuilder.getOperatorsById().add(createOperator(OPERATOR_ID));
    var line = createExampleLine().withAdditionalOperators(
      new TransportOrganisationRefs_RelStructure().withTransportOrganisationRef(
        createJaxbElement(new TransportOrganisationRefStructure().withRef(OPERATOR_ID))
      )
    );

    Route route = createRouteMapper(
      new NetexEntityIndex(),
      transitBuilder,
      DataImportIssueStore.NOOP,
      NetexProfileSupport.NORDIC
    ).mapRoute(line);

    assertNull(route.getOperator());
  }

  @Test
  void mapFerryModeWithExtendedNetex() {
    var line = createExampleLine();
    line.setTransportMode(AllVehicleModesOfTransportEnumeration.FERRY);

    Route route = createRouteMapper(
      new NetexEntityIndex(),
      new TransitDataImportBuilder(new SiteRepository(), DataImportIssueStore.NOOP),
      DataImportIssueStore.NOOP,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(TransitMode.FERRY, route.getMode());
    assertEquals(BikeAccess.ALLOWED, route.getBikesAllowed());
  }

  @Test
  void deriveLineModeFromServiceJourneysWithExtendedNetex() {
    var line = createExampleLine();
    line.setTransportMode(null);
    var index = new NetexEntityIndex();
    index.serviceJourneyById.add(
      serviceJourneyOfLine("SJ-1", AllVehicleModesOfTransportEnumeration.TRAM)
    );
    index.serviceJourneyById.add(
      serviceJourneyOfLine("SJ-2", AllVehicleModesOfTransportEnumeration.BUS)
    );
    index.serviceJourneyById.add(
      serviceJourneyOfLine("SJ-3", AllVehicleModesOfTransportEnumeration.BUS)
    );
    var issueStore = new DefaultDataImportIssueStore();

    Route route = createRouteMapper(
      index,
      new TransitDataImportBuilder(new SiteRepository(), issueStore),
      issueStore,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(TransitMode.BUS, route.getMode());
    assertThat(issueStore.listIssues().stream().map(DataImportIssue::getType)).contains(
      "LineTransportModeDerived"
    );
    assertNull(createRouteMapper(index, DataImportIssueStore.NOOP).mapRoute(line));
  }

  @Test
  void deriveLineModeIfTheModeIsUnknownWithExtendedNetex() {
    var line = createExampleLine();
    line.setTransportMode(AllVehicleModesOfTransportEnumeration.UNKNOWN);
    var index = new NetexEntityIndex();
    index.serviceJourneyById.add(
      serviceJourneyOfLine("SJ-1", AllVehicleModesOfTransportEnumeration.UNKNOWN)
    );
    index.serviceJourneyById.add(
      serviceJourneyOfLine("SJ-2", AllVehicleModesOfTransportEnumeration.BUS)
    );

    Route route = createRouteMapper(
      index,
      new TransitDataImportBuilder(new SiteRepository(), DataImportIssueStore.NOOP),
      DataImportIssueStore.NOOP,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(TransitMode.BUS, route.getMode());
    assertNull(createRouteMapper(index, DataImportIssueStore.NOOP).mapRoute(line));
  }

  @Test
  void reportLineWithUnknownModeWithTheHintsInTheData() {
    var line = createExampleLine();
    line.setTransportMode(AllVehicleModesOfTransportEnumeration.UNKNOWN);
    line.setNoticeAssignments(
      new NoticeAssignments_RelStructure().withNoticeAssignment_OrNoticeAssignmentView(
        createJaxbElement(notice("N-1", "GTFS Route Type : 1501")),
        createJaxbElement(notice("N-2", "Rufbus: Anmeldung bis 60 Min. vor Abfahrt"))
      )
    );
    var index = new NetexEntityIndex();
    index.serviceJourneyById.add(
      serviceJourneyOfLine("SJ-1", AllVehicleModesOfTransportEnumeration.UNKNOWN)
    );
    var issueStore = new DefaultDataImportIssueStore();

    var route = createRouteMapper(
      index,
      new TransitDataImportBuilder(new SiteRepository(), issueStore),
      issueStore,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertNull(route);
    var issue = issueStore
      .listIssues()
      .stream()
      .filter(it -> it.getType().equals("LineWithoutTransportMode"))
      .findFirst()
      .orElseThrow();
    assertThat(issue.getMessage()).contains("dropped with its 1 service journeys");
    assertThat(issue.getMessage()).contains("'GTFS Route Type : 1501' (communal taxi service)");
    assertThat(issue.getMessage()).contains("'Rufbus: Anmeldung bis 60 Min. vor Abfahrt'");
    assertThat(issue.getMessage()).contains("<TaxiSubmode>communalTaxi</TaxiSubmode>");
    assertThat(issue.getMessage()).contains("<BookingArrangements>");
  }

  private static NoticeAssignment notice(String id, String text) {
    return new NoticeAssignment().withNotice(
      new Notice().withId(id).withText(new MultilingualString().withValue(text))
    );
  }

  @Test
  void deriveLineModeFromStopPlacesWithExtendedNetex() {
    var line = createExampleLine();
    line.setTransportMode(null);
    var index = new NetexEntityIndex();
    index.journeyPatternsById.add(
      new JourneyPattern()
        .withId("JP-1")
        .withRouteView(
          new RouteView().withLineRef(createJaxbElement(new LineRefStructure().withRef(LINE_ID)))
        )
        .withPointsInSequence(
          new PointsInJourneyPattern_RelStructure().withPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern(
            new StopPointInJourneyPattern()
              .withId("SPIJP-1")
              .withScheduledStopPointRef(
                createJaxbElement(new ScheduledStopPointRefStructure().withRef("SSP-1"))
              )
          )
        )
    );
    index.serviceJourneyById.add(
      new ServiceJourney()
        .withId("SJ-1")
        .withJourneyPatternRef(createJaxbElement(new JourneyPatternRefStructure().withRef("JP-1")))
    );
    index.stopPlaceIdByStopPointRef.add("SSP-1", "SP-1");
    index.stopPlaceById.add(
      new StopPlace().withId("SP-1").withTransportMode(AllVehicleModesOfTransportEnumeration.RAIL)
    );

    Route route = createRouteMapper(
      index,
      new TransitDataImportBuilder(new SiteRepository(), DataImportIssueStore.NOOP),
      DataImportIssueStore.NOOP,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(TransitMode.RAIL, route.getMode());
  }

  private static ServiceJourney serviceJourneyOfLine(
    String id,
    AllVehicleModesOfTransportEnumeration mode
  ) {
    return new ServiceJourney()
      .withId(id)
      .withLineRef(createJaxbElement(new LineRefStructure().withRef(LINE_ID)))
      .withTransportMode(mode);
  }

  @Test
  void useOperatorAsAgencyIfLineHasNoAuthority() {
    var transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );
    transitBuilder.getOperatorsById().add(createOperator(OPERATOR_ID));
    var line = createExampleLine()
      .withRepresentedByGroupRef(null)
      .withOperatorRef(new OperatorRefStructure().withRef(OPERATOR_ID));
    var issueStore = new DefaultDataImportIssueStore();

    Route route = createRouteMapper(
      new NetexEntityIndex(),
      transitBuilder,
      issueStore,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(OPERATOR_ID, route.getAgency().getId().getId());
    assertEquals("Operator", route.getAgency().getName());
    assertThat(listIssueTypes(issueStore)).containsExactly("AgencyDerivedFromOperator");
  }

  @Test
  void useAuthorityRefIfGroupOfLinesCannotBeResolved() {
    var transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );
    transitBuilder.getAgenciesById().add(createAgency());
    var line = createExampleLine().withAuthorityRef(new AuthorityRef().withRef(AUTHORITY_ID));

    Route route = createRouteMapper(
      new NetexEntityIndex(),
      transitBuilder,
      DataImportIssueStore.NOOP,
      NetexProfileSupport.of(true, false)
    ).mapRoute(line);

    assertEquals(AUTHORITY_ID, route.getAgency().getId().getId());
  }

  @Test
  void mapRouteWithColor() {
    NetexEntityIndex netexEntityIndex = new NetexEntityIndex();
    Line line = createExampleLine();
    byte[] color = new byte[] { 127, 0, 0 };
    byte[] textColor = new byte[] { 0, 127, 0 };
    line.setPresentation(new PresentationStructure().withColour(color).withTextColour(textColor));

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      ArrayListMultimap.create(),
      new DefaultEntityById<>(),
      netexEntityIndex.readOnlyView(),
      ZoneId.systemDefault().getId(),
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      NetexProfileSupport.NORDIC
    );

    Route route = routeMapper.mapRoute(line);

    assertEquals("7F0000", route.getColor());
    assertEquals("007F00", route.getTextColor());
  }

  @Test
  void allowBicyclesOnFerries() {
    NetexEntityIndex netexEntityIndex = new NetexEntityIndex();
    Line lineWithBicycles = createExampleFerry(LINE_ID);
    Line lineWithOutBicycles = createExampleFerry(FERRY_WITHOUT_BICYCLES_ID);

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      ArrayListMultimap.create(),
      new DefaultEntityById<>(),
      netexEntityIndex.readOnlyView(),
      ZoneId.systemDefault().getId(),
      Set.of(FERRY_WITHOUT_BICYCLES_ID),
      NetexProfileSupport.NORDIC
    );

    Route ferryWithBicycles = routeMapper.mapRoute(lineWithBicycles);
    assertEquals(BikeAccess.ALLOWED, ferryWithBicycles.getBikesAllowed());

    Route ferryWithOutBicycles = routeMapper.mapRoute(lineWithOutBicycles);
    assertEquals(BikeAccess.NOT_ALLOWED, ferryWithOutBicycles.getBikesAllowed());
  }

  @Test
  void mapRouteWithoutBranding() {
    NetexEntityIndex netexEntityIndex = new NetexEntityIndex();
    Line line = createExampleLine();

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      ArrayListMultimap.create(),
      new DefaultEntityById<>(),
      netexEntityIndex.readOnlyView(),
      ZoneId.systemDefault().getId(),
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      NetexProfileSupport.NORDIC
    );

    Route route = routeMapper.mapRoute(line);

    assertNull(route.getBranding());
  }

  @Test
  void mapRouteWithBranding() {
    NetexEntityIndex netexIndex = new NetexEntityIndex();
    TransitDataImportBuilder transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );

    transitBuilder
      .getBrandingsById()
      .add(Branding.of(MappingSupport.ID_FACTORY.createId(BRANDING_ID)).build());

    Line line = createExampleLine();

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      transitBuilder.getAgenciesById(),
      transitBuilder.getOperatorsById(),
      transitBuilder.getBrandingsById(),
      ArrayListMultimap.create(),
      new DefaultEntityById<>(),
      netexIndex.readOnlyView(),
      TransitRepositoryForTest.TIME_ZONE_ID,
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      NetexProfileSupport.NORDIC
    );

    Route route = routeMapper.mapRoute(line);

    Branding branding = route.getBranding();
    assertNotNull(branding);
    assertEquals(BRANDING_ID, branding.getId().getId());
  }

  @Test
  void mapRouteWithGroupOfRoutes() {
    NetexEntityIndex netexIndex = new NetexEntityIndex();
    TransitDataImportBuilder transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );

    Line line = createExampleLine();

    line.getRepresentedByGroupRef().setRef(GOL_ID_1);
    transitBuilder.getGroupOfRouteById().add(createGroupOfRoutes(GOL_ID_1, GOL_NAME_1));
    transitBuilder
      .getGroupsOfRoutesByRouteId()
      .put(MappingSupport.ID_FACTORY.createId(LINE_ID), createGroupOfRoutes(GOL_ID_2, GOL_NAME_2));

    RouteMapper routeMapper = new RouteMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      transitBuilder.getAgenciesById(),
      transitBuilder.getOperatorsById(),
      transitBuilder.getBrandingsById(),
      transitBuilder.getGroupsOfRoutesByRouteId(),
      transitBuilder.getGroupOfRouteById(),
      netexIndex.readOnlyView(),
      TransitRepositoryForTest.TIME_ZONE_ID,
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      NetexProfileSupport.NORDIC
    );

    Route route = routeMapper.mapRoute(line);

    List<GroupOfRoutes> groupsOfLines = route.getGroupsOfRoutes();

    assertEquals(2, groupsOfLines.size());
    assertTrue(groupsOfLines.stream().anyMatch(gol -> GOL_ID_1.equals(gol.getId().getId())));
    assertTrue(groupsOfLines.stream().anyMatch(gol -> GOL_ID_2.equals(gol.getId().getId())));
  }

  private RouteMapper createRouteMapper(
    NetexEntityIndex netexIndex,
    DataImportIssueStore issueStore
  ) {
    return createRouteMapper(
      netexIndex,
      new TransitDataImportBuilder(new SiteRepository(), issueStore),
      issueStore,
      NetexProfileSupport.NORDIC
    );
  }

  private RouteMapper createRouteMapper(
    NetexEntityIndex netexIndex,
    TransitDataImportBuilder transitBuilder,
    DataImportIssueStore issueStore,
    NetexProfileSupport profileSupport
  ) {
    return new RouteMapper(
      issueStore,
      MappingSupport.ID_FACTORY,
      transitBuilder.getAgenciesById(),
      transitBuilder.getOperatorsById(),
      transitBuilder.getBrandingsById(),
      transitBuilder.getGroupsOfRoutesByRouteId(),
      transitBuilder.getGroupOfRouteById(),
      netexIndex.readOnlyView(),
      TransitRepositoryForTest.TIME_ZONE_ID,
      EMPTY_FERRY_WITHOUT_BICYCLE_IDS,
      profileSupport
    );
  }

  private static List<String> listIssueTypes(DefaultDataImportIssueStore issueStore) {
    return issueStore.listIssues().stream().map(DataImportIssue::getType).toList();
  }

  private Line createExampleLine() {
    Line line = new Line();
    line.setId(LINE_ID);
    line.setTransportMode(AllVehicleModesOfTransportEnumeration.METRO);
    line.setName(new MultilingualString().withValue("Line 1"));
    line.setPublicCode("L1");
    line.setRepresentedByGroupRef(new GroupOfLinesRefStructure().withRef(NETWORK_ID));
    line.setBrandingRef(new BrandingRefStructure().withRef(BRANDING_ID));
    return line;
  }

  private Line createExampleFerry(String id) {
    var ferry = createExampleLine();
    ferry.setId(id);
    ferry.setTransportMode(AllVehicleModesOfTransportEnumeration.WATER);
    return ferry;
  }

  private Operator createOperator(String id) {
    return Operator.of(MappingSupport.ID_FACTORY.createId(id)).withName("Operator").build();
  }

  private Agency createAgency() {
    return TransitRepositoryForTest.agency("Ruter AS")
      .copy()
      .withId(MappingSupport.ID_FACTORY.createId(AUTHORITY_ID))
      .build();
  }

  private GroupOfRoutes createGroupOfRoutes(String id, String name) {
    return GroupOfRoutes.of(MappingSupport.ID_FACTORY.createId(id)).withName(name).build();
  }
}
