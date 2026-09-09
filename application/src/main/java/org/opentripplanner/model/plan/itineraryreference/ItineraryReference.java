package org.opentripplanner.model.plan.itineraryreference;

import java.util.List;
import java.util.Objects;
import org.opentripplanner.model.plan.legreference.LegReference;

/**
 * A stable reference containing the information required to refetch an itinerary using
 * {@link org.opentripplanner.routing.refetch.RefetchItineraryService}.
 * <p>
 * The reference is serialized as a versioned opaque token and is independent of the API exposing
 * it.
 */
public record ItineraryReference(List<LegReference> legReferences) {
  public ItineraryReference {
    Objects.requireNonNull(legReferences);
    // List.copyOf() also rejects null elements and guards against a caller mutating the list
    // after construction.
    legReferences = List.copyOf(legReferences);
    if (legReferences.isEmpty()) {
      throw new IllegalArgumentException("legReferences must not be empty");
    }
  }
}
