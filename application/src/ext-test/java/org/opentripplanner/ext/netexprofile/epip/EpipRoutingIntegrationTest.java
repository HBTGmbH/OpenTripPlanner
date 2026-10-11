package org.opentripplanner.ext.netexprofile.epip;

import static com.google.common.truth.Truth.assertThat;
import static org.opentripplanner.street.model.StreetMode.NOT_SET;
import static org.opentripplanner.street.model.StreetMode.WALK;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.framework.transaction.internal.TransactionFactory;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.datastore.api.FileType;
import org.opentripplanner.datastore.file.DirectoryDataSource;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.model.ConfiguredCompositeDataSource;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.netex.configure.NetexConfigure;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RaptorTransitData;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.RaptorTransitDataMapper;
import org.opentripplanner.routing.api.RoutingService;
import org.opentripplanner.routing.api.request.RequestModes;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.service.streetdetails.internal.DefaultStreetDetailsRepository;
import org.opentripplanner.service.vehicleparking.internal.DefaultVehicleParkingRepository;
import org.opentripplanner.standalone.api.TestServerContext;
import org.opentripplanner.standalone.config.OtpConfigLoader;
import org.opentripplanner.standalone.config.RouterConfig;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.transfer.regular.TransferServiceTestFactory;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.repository.DefaultTimetableRepository;
import org.opentripplanner.transit.repository.TimetableRepositoryLifecycle;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.SiteRepository;
import org.opentripplanner.transit.service.TransitRepository;

/**
 * Builds a graph from a small hand-built EPIP data set and plans a trip with one transfer.
 * <p>
 * The data set (see {@code routing/netex} in the test resources) uses the EPIP file names, stop
 * places without quays, no time zone, a line with an operator but no authority, a line with an
 * additional operator only, inline notice assignments, destination displays on the journey
 * patterns, timetabled passing times without id and a site connection. Bus line A runs from Alpha
 * to Kreuz at 08:00 and 09:00, tram line B from Kreuz to Beta at 08:05 and 08:20 on working days.
 */
class EpipRoutingIntegrationTest {

  private static final String DATA_DIR =
    "src/ext-test/resources/org/opentripplanner/ext/netexprofile/epip/routing";
  private static final String FEED_ID = "EP";
  private static final ZoneId TIME_ZONE = ZoneId.of("Europe/Berlin");
  private static final LocalDate MONDAY = LocalDate.of(2025, 6, 2);

  private static final FeedScopedId ALPHA = stopId("Alpha-1");
  private static final FeedScopedId KREUZ = stopId("Kreuz-1");
  private static final FeedScopedId BETA = stopId("Beta-1");

  private static TransitRepository transitRepository;
  private static RoutingService routingService;

  @BeforeAll
  static void buildGraph() {
    OTPFeature.NetexEpip.testOn(EpipRoutingIntegrationTest::buildGraphWithNetexEpip);
  }

  @Test
  void importStopsLinesAndTransfers() {
    var siteRepository = transitRepository.getSiteRepository();
    assertThat(siteRepository.getRegularStop(ALPHA)).isNotNull();
    assertThat(
      siteRepository.getRegularStop(KREUZ).getParentStation().getName().toString()
    ).isEqualTo("Kreuz");

    var agencies = transitRepository
      .getAgencies()
      .stream()
      .map(it -> it.getName())
      .toList();
    assertThat(agencies).containsExactly("Testbus GmbH", "Testverbund");
    assertThat(transitRepository.getTimeZone()).isEqualTo(TIME_ZONE);
    assertThat(transitRepository.getConstrainedTransferService().listAll()).hasSize(2);
  }

  @Test
  void planTripWithOneTransfer() {
    var itinerary = plan(ALPHA, BETA, LocalTime.of(7, 55));

    List<Leg> legs = itinerary.legs();
    assertThat(legs).hasSize(2);

    var bus = legs.get(0);
    assertThat(bus.route().getMode()).isEqualTo(TransitMode.BUS);
    assertThat(bus.route().getShortName()).isEqualTo("A");
    assertThat(bus.from().stop.getId()).isEqualTo(ALPHA);
    assertThat(bus.to().stop.getId()).isEqualTo(KREUZ);
    assertThat(bus.headsign().toString()).isEqualTo("Kreuz");
    assertThat(bus.startTime()).isEqualTo(time(8, 0));

    var tram = legs.get(1);
    assertThat(tram.route().getMode()).isEqualTo(TransitMode.TRAM);
    assertThat(tram.route().getOperator().getName()).isEqualTo("Testtram AG");
    assertThat(tram.from().stop.getId()).isEqualTo(KREUZ);
    assertThat(tram.to().stop.getId()).isEqualTo(BETA);
    assertThat(tram.headsign().toString()).isEqualTo("Beta");
    assertThat(tram.startTime()).isEqualTo(time(8, 20));
    assertThat(tram.endTime()).isEqualTo(time(8, 30));
  }

  private static Itinerary plan(FeedScopedId from, FeedScopedId to, LocalTime departure) {
    var request = RouteRequest.of()
      .withFrom(GenericLocation.fromStopId(from))
      .withTo(GenericLocation.fromStopId(to))
      .withDateTime(time(departure.getHour(), departure.getMinute()).toInstant())
      .withNumItineraries(1)
      .withJourney(journey ->
        journey.withModes(
          RequestModes.of()
            .withDirectMode(NOT_SET)
            .withAccessMode(WALK)
            .withTransferMode(WALK)
            .withEgressMode(WALK)
            .build()
        )
      )
      .buildRequest();

    var itineraries = routingService.route(request).getTripPlan().itineraries;
    assertThat(itineraries).isNotEmpty();
    return itineraries.getFirst();
  }

  private static void buildGraphWithNetexEpip() {
    var buildConfig = new OtpConfigLoader(new File(DATA_DIR)).loadBuildConfig();
    var dataSource = new DirectoryDataSource(new File(DATA_DIR, "netex"), FileType.NETEX);
    var graph = new Graph();
    transitRepository = new TransitRepository(new SiteRepository());
    var transferRepository = TransferServiceTestFactory.defaultTransferRepository();

    new NetexConfigure(buildConfig)
      .createNetexModule(
        List.of(new ConfiguredCompositeDataSource<>(dataSource, buildConfig.netexDefaults)),
        transitRepository,
        new DefaultVehicleParkingRepository(),
        new DefaultStreetDetailsRepository(),
        graph,
        new Deduplicator(),
        DataImportIssueStore.NOOP
      )
      .buildGraph();
    transitRepository.index();
    graph.index();

    var raptorTransitData = RaptorTransitDataMapper.map(
      RouterConfig.DEFAULT.transitTuningConfig(),
      transitRepository,
      transferRepository
    );
    transitRepository.initRaptorTransitData(raptorTransitData);
    var registry = TransactionFactory.createRepositoryRegistry();
    var timetableRepository = new DefaultTimetableRepository(
      new RaptorTransitData(raptorTransitData),
      transitRepository.getTripCalendar()
    );
    var timetableHandle = registry.registerRepositorySnapshot(
      timetableRepository,
      new TimetableRepositoryLifecycle(timetableRepository, false, () -> MONDAY)
    );
    var transitService = new DefaultTransitService(
      transitRepository,
      timetableHandle.repositorySnapshot(registry.scope())
    );
    routingService = TestServerContext.createRoutingService(
      graph,
      transitService,
      transferRepository
    );
  }

  private static FeedScopedId stopId(String name) {
    return new FeedScopedId(FEED_ID, "DE::ScheduledStopPoint:" + name + "::");
  }

  private static ZonedDateTime time(int hour, int minute) {
    return ZonedDateTime.of(MONDAY, LocalTime.of(hour, minute), TIME_ZONE);
  }
}
