package org.opentripplanner.routing.refetch;

import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.itineraryreference.ItineraryReference;
import org.opentripplanner.model.plan.legreference.LegReference;
import org.opentripplanner.routing.api.request.RouteRequest;

/**
 * Maps between an {@link Itinerary} and its stable {@link ItineraryReference}, and reconstructs
 * the inputs {@link RefetchItineraryService} needs from a decoded reference.
 * <p>
 * Currently only itineraries entirely composed of legs with a
 * {@link org.opentripplanner.model.plan.legreference.LegReference} are supported (see
 * {@link UnsupportedItineraryReferenceException}); access, egress and other routing state are
 * added to {@link ItineraryReference} once a supported use case requires them.
 */
public final class ItineraryReferenceMapper {

  private ItineraryReferenceMapper() {}

  public static ItineraryReference toItineraryReference(Itinerary itinerary) {
    var legReferences = itinerary
      .legs()
      .stream()
      .map(ItineraryReferenceMapper::requireLegReference)
      .toList();
    return new ItineraryReference(legReferences);
  }

  public static Itinerary refetch(
    ItineraryReference itineraryReference,
    RouteRequest routeRequest,
    RefetchItineraryService refetchItineraryService
  ) {
    return refetchItineraryService.refetchItinerary(
      null,
      null,
      itineraryReference.legReferences(),
      routeRequest
    );
  }

  private static LegReference requireLegReference(Leg leg) {
    var legReference = leg.legReference();
    if (legReference == null) {
      throw new UnsupportedItineraryReferenceException(
        "Leg has no LegReference, itinerary cannot be represented as an ItineraryReference: " + leg
      );
    }
    return legReference;
  }
}
