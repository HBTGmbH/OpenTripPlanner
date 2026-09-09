package org.opentripplanner.model.plan.itineraryreference;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.opentripplanner.framework.token.TokenSchema;
import org.opentripplanner.model.plan.legreference.LegReference;
import org.opentripplanner.model.plan.legreference.LegReferenceSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializer for {@link ItineraryReference}.
 * <p>
 * Each leg reference is encoded with the existing {@link LegReferenceSerializer} and the
 * resulting tokens are joined with {@link #LEG_REFERENCE_DELIMITER}, a character not present in
 * the URL-safe Base64 alphabet {@link LegReferenceSerializer} produces.
 */
public class ItineraryReferenceSerializer {

  private static final Logger LOG = LoggerFactory.getLogger(ItineraryReferenceSerializer.class);

  private static final String LEG_REFERENCES_FIELD = "legReferences";
  private static final String LEG_REFERENCE_DELIMITER = "~";

  private static final TokenSchema SCHEMA = TokenSchema.ofVersion(1)
    .addString(LEG_REFERENCES_FIELD)
    .build();

  /** private constructor to prevent instantiating this utility class */
  private ItineraryReferenceSerializer() {}

  @Nullable
  public static String encode(@Nullable ItineraryReference itineraryReference) {
    if (itineraryReference == null) {
      return null;
    }
    String joinedLegReferences = itineraryReference
      .legReferences()
      .stream()
      .map(ItineraryReferenceSerializer::encodeLegReference)
      .collect(Collectors.joining(LEG_REFERENCE_DELIMITER));

    return SCHEMA.encode().withString(LEG_REFERENCES_FIELD, joinedLegReferences).build();
  }

  @Nullable
  public static ItineraryReference decode(@Nullable String itineraryReference) {
    if (itineraryReference == null || itineraryReference.isEmpty()) {
      return null;
    }
    try {
      var token = SCHEMA.decode(itineraryReference);
      String joinedLegReferences = token.getString(LEG_REFERENCES_FIELD).orElseThrow();

      // limit = -1 keeps trailing empty segments (e.g. a trailing delimiter), which would
      // otherwise be silently dropped by split() and let a malformed token decode successfully.
      List<LegReference> legReferences = Arrays.stream(
        joinedLegReferences.split(LEG_REFERENCE_DELIMITER, -1)
      )
        .map(LegReferenceSerializer::decode)
        .map(Objects::requireNonNull)
        .toList();

      return new ItineraryReference(legReferences);
    } catch (RuntimeException e) {
      LOG.debug("Unable to decode itinerary reference: '{}'", itineraryReference, e);
      return null;
    }
  }

  private static String encodeLegReference(LegReference legReference) {
    return Objects.requireNonNull(
      LegReferenceSerializer.encode(legReference),
      "Unable to encode leg reference: " + legReference
    );
  }
}
