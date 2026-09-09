package org.opentripplanner.routing.refetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.opentripplanner.model.plan.TestItineraryBuilder.newItinerary;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.plan.Place;
import org.opentripplanner.model.plan.itineraryreference.ItineraryReferenceSerializer;
import org.opentripplanner.model.plan.legreference.ScheduledTransitLegReference;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.impl.TransitAlertServiceImpl;
import org.opentripplanner.routing.linking.LinkingContextFactory;
import org.opentripplanner.routing.linking.internal.VertexCreationService;
import org.opentripplanner.service.vehiclerental.GeofencingZoneService;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.VertexLinker;
import org.opentripplanner.street.linking.VisibilityMode;
import org.opentripplanner.street.service.StreetLimitationParametersService;
import org.opentripplanner.transfer.regular.TransferServiceTestFactory;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TransitTestEnvironmentBuilder;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.transit.model.site.RegularStop;

/**
 * UC1 acceptance test for a single scheduled-transit itinerary without access, egress or
 * transfers.
 *
 * <p>Proves the chain:
 * {@code Itinerary -> ItineraryReference -> encode -> decode -> RefetchItineraryService ->
 * same scheduled transit itinerary}.
 */
class ItineraryReferenceMapperTest {

  private static final LocalDate SERVICE_DATE = LocalDate.of(2020, 3, 3);
  private static final TransitTestEnvironmentBuilder ENV_BUILDER = TransitTestEnvironment.of(
    SERVICE_DATE
  );
  private static final RegularStop STOP_A = ENV_BUILDER.stop("A");
  private static final RegularStop STOP_B = ENV_BUILDER.stop("B");

  private static final TransitTestEnvironment TRANSIT_ENV = ENV_BUILDER.addTrip(
    TripInput.of("tripX").addStop(STOP_A, "10:00").addStop(STOP_B, "11:00")
  ).build();

  private static final Graph GRAPH = new Graph();

  @Test
  void roundTripSingleScheduledTransitLeg() {
    var refetchService = createRefetchService();
    var routeRequest = routeRequest();

    var originalItinerary = refetchService.refetchItinerary(
      null,
      null,
      List.of(legRef()),
      routeRequest
    );

    var reference = ItineraryReferenceMapper.toItineraryReference(originalItinerary, routeRequest);

    var encoded = ItineraryReferenceSerializer.encode(reference);
    assertNotNull(encoded);
    var decodedReference = ItineraryReferenceSerializer.decode(encoded);
    assertNotNull(decodedReference);

    var refetchedItinerary = ItineraryReferenceMapper.refetch(
      decodedReference,
      routeRequest,
      refetchService
    );

    assertEquals(originalItinerary.toStr(), refetchedItinerary.toStr());
    assertEquals("A ~ BUS tripX 10:00 11:00 ~ B []", refetchedItinerary.toStr());
  }

  @Test
  void itineraryWithoutTransitLegReferenceIsUnsupported() {
    var from = Place.forStop(STOP_A);
    var to = Place.forStop(STOP_B);

    var streetOnlyItinerary = newItinerary(from, 0).walk(200, to).build();

    assertThrows(UnsupportedItineraryReferenceException.class, () ->
      ItineraryReferenceMapper.toItineraryReference(streetOnlyItinerary, routeRequest())
    );
  }

  @Test
  void ignoresStreetLegsWhenExtractingTransitSpine() {
    var boardPlace = Place.forStop(STOP_A);
    var alightPlace = Place.forStop(STOP_B);

    // WALK -> BUS -> WALK: the walk (access/egress) legs have no LegReference and must be
    // ignored rather than making the whole itinerary unsupported.
    var itinerary = newItinerary(boardPlace, 0)
      .walk(200, boardPlace)
      .bus(1, 200, 300, alightPlace)
      .walk(200, alightPlace)
      .build();

    var expectedLegReference = itinerary.legs().get(1).legReference();
    assertNotNull(expectedLegReference);

    var reference = ItineraryReferenceMapper.toItineraryReference(itinerary, routeRequest());

    assertEquals(List.of(expectedLegReference), reference.legReferences());
  }

  private ScheduledTransitLegReference legRef() {
    var tripData = TRANSIT_ENV.tripData("tripX");
    var stops = tripData.tripPattern().getStops();

    return new ScheduledTransitLegReference(
      tripData.trip().getId(),
      SERVICE_DATE,
      stops.indexOf(STOP_A),
      stops.indexOf(STOP_B),
      STOP_A.getId(),
      STOP_B.getId(),
      null
    );
  }

  private RefetchItineraryService createRefetchService() {
    var vertexCreationService = new VertexCreationService(
      new VertexLinker(
        GRAPH,
        GeofencingZoneService.EMPTY,
        VisibilityMode.TRAVERSE_AREA_EDGES,
        10,
        false
      )
    );

    var linkingContextFactory = new LinkingContextFactory(GRAPH, vertexCreationService);

    return new RefetchItineraryService(
      GRAPH,
      TRANSIT_ENV.transitService(),
      new TransitAlertServiceImpl(),
      TransferServiceTestFactory.defaultTransferService(),
      null,
      linkingContextFactory,
      StreetLimitationParametersService.DEFAULT
    );
  }

  private RouteRequest routeRequest() {
    return RouteRequest.of()
      .withFrom(GenericLocation.fromCoordinate(0, 0))
      .withTo(GenericLocation.fromCoordinate(1, 1))
      .buildRequest();
  }
}
