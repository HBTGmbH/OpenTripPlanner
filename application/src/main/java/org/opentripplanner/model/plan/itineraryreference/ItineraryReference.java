package org.opentripplanner.model.plan.itineraryreference;

import java.util.List;
import java.util.Objects;
import org.opentripplanner.model.plan.legreference.LegReference;
import org.opentripplanner.street.model.StreetMode;

/**
 * A stable reference containing the information required to refetch an itinerary using
 * {@link org.opentripplanner.routing.refetch.RefetchItineraryService}.
 * <p>
 * The reference is serialized as a versioned opaque token and is independent of the API exposing
 * it.
 * <p>
 * Custom {@code WheelchairPreferences} are currently not encoded in the reference.
 */
public record ItineraryReference(
  List<LegReference> legReferences,
  StreetMode transferMode,
  boolean wheelchair
) {
  public ItineraryReference {
    Objects.requireNonNull(legReferences);
    Objects.requireNonNull(transferMode);
    // List.copyOf() also rejects null elements and guards against a caller mutating the list
    // after construction.
    legReferences = List.copyOf(legReferences);
    if (legReferences.isEmpty()) {
      throw new IllegalArgumentException("legReferences must not be empty");
    }
  }
}
