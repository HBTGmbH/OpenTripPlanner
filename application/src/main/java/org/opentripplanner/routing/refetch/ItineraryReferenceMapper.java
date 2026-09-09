package org.opentripplanner.routing.refetch;

import java.util.Objects;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.itineraryreference.ItineraryReference;
import org.opentripplanner.model.plan.legreference.LegReference;
import org.opentripplanner.model.plan.legreference.ScheduledTransitLegReference;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.request.StreetRequest;

/**
 * Maps an {@link Itinerary} and its original {@link RouteRequest} to a stable
 * {@link ItineraryReference}, and reconstructs the inputs required by
 * {@link RefetchItineraryService}.
 * <p>
 * Street legs do not form part of the serialized transit spine. Supported transit
 * {@link LegReference}s are extracted from the itinerary while generated access, transfer and
 * egress street legs are ignored.
 */
public final class ItineraryReferenceMapper {

  private ItineraryReferenceMapper() {}

  public static ItineraryReference toItineraryReference(
    Itinerary itinerary,
    RouteRequest routeRequest
  ) {
    var legReferences = itinerary
      .legs()
      .stream()
      .map(Leg::legReference)
      .filter(Objects::nonNull)
      .map(ItineraryReferenceMapper::requireSupportedLegReference)
      .toList();

    if (legReferences.isEmpty()) {
      throw new UnsupportedItineraryReferenceException(
        "Itinerary has no supported transit leg references"
      );
    }

    return new ItineraryReference(
      legReferences,
      routeRequest.journey().transfer().mode(),
      routeRequest.journey().wheelchair()
    );
  }

  public static Itinerary refetch(
    ItineraryReference itineraryReference,
    RouteRequest defaultRouteRequest,
    RefetchItineraryService refetchItineraryService
  ) {
    var routeRequest = defaultRouteRequest
      .copyOf()
      .withJourney(journey ->
        journey
          .withTransfer(new StreetRequest(itineraryReference.transferMode()))
          .withWheelchair(itineraryReference.wheelchair())
      )
      .buildRequest();

    return refetchItineraryService.refetchItinerary(
      null,
      null,
      itineraryReference.legReferences(),
      routeRequest
    );
  }

  private static LegReference requireSupportedLegReference(LegReference legReference) {
    if (legReference instanceof ScheduledTransitLegReference) {
      return legReference;
    }
    throw new UnsupportedItineraryReferenceException(
      "Unsupported leg reference type: " + legReference.getClass().getSimpleName()
    );
  }
}
