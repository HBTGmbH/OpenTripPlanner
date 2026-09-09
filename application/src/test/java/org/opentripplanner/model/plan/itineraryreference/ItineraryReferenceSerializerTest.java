package org.opentripplanner.model.plan.itineraryreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.core.model.id.FeedScopedIdForTestFactory;
import org.opentripplanner.framework.token.TokenSchema;
import org.opentripplanner.model.plan.legreference.LegReferenceSerializer;
import org.opentripplanner.model.plan.legreference.ScheduledTransitLegReference;

class ItineraryReferenceSerializerTest {

  private static final FeedScopedId TRIP_X_ID = FeedScopedIdForTestFactory.id("Trip X");
  private static final FeedScopedId TRIP_Y_ID = FeedScopedIdForTestFactory.id("Trip Y");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2022, 1, 31);
  private static final FeedScopedId STOP_A_ID = FeedScopedIdForTestFactory.id("Stop A");
  private static final FeedScopedId STOP_B_ID = FeedScopedIdForTestFactory.id("Stop B");
  private static final FeedScopedId STOP_C_ID = FeedScopedIdForTestFactory.id("Stop C");

  private static final ScheduledTransitLegReference LEG_A_TO_B = new ScheduledTransitLegReference(
    TRIP_X_ID,
    SERVICE_DATE,
    0,
    1,
    STOP_A_ID,
    STOP_B_ID,
    null
  );

  private static final ScheduledTransitLegReference LEG_B_TO_C = new ScheduledTransitLegReference(
    TRIP_Y_ID,
    SERVICE_DATE,
    0,
    1,
    STOP_B_ID,
    STOP_C_ID,
    null
  );

  @Test
  void roundTripSingleLeg() {
    var ref = new ItineraryReference(List.of(LEG_A_TO_B));

    var decoded = ItineraryReferenceSerializer.decode(ItineraryReferenceSerializer.encode(ref));

    assertEquals(ref, decoded);
  }

  @Test
  void roundTripMultipleLegs() {
    var ref = new ItineraryReference(List.of(LEG_A_TO_B, LEG_B_TO_C));

    var decoded = ItineraryReferenceSerializer.decode(ItineraryReferenceSerializer.encode(ref));

    assertEquals(ref, decoded);
  }

  @Test
  void nullInputEncodesToNull() {
    assertNull(ItineraryReferenceSerializer.encode(null));
  }

  @Test
  void nullTokenDecodesToNull() {
    assertNull(ItineraryReferenceSerializer.decode(null));
  }

  @Test
  void emptyTokenDecodesToNull() {
    assertNull(ItineraryReferenceSerializer.decode(""));
  }

  @Test
  void malformedTokenDecodesToNull() {
    assertNull(ItineraryReferenceSerializer.decode("this-is-not-a-valid-token::"));
  }

  @Test
  void truncatedTokenDecodesToNull() {

    var encoded = Objects.requireNonNull(
      ItineraryReferenceSerializer.encode(new ItineraryReference(List.of(LEG_A_TO_B))));

    var truncated = encoded.substring(0, encoded.length() / 2);
    assertNull(ItineraryReferenceSerializer.decode(truncated));
  }

  /**
   * A trailing delimiter must not be silently dropped by {@code split}, which would otherwise
   * let a corrupted "joined leg references" value decode as if the trailing empty segment
   * didn't exist.
   */
  @Test
  void malformedNestedLegReferenceDecodesToNull() {
    String validLegToken = LegReferenceSerializer.encode(LEG_A_TO_B);
    String joinedWithTrailingDelimiter = validLegToken + "~";

    var schema = TokenSchema.ofVersion(1).addString("legReferences").build();
    String craftedToken = schema
      .encode()
      .withString("legReferences", joinedWithTrailingDelimiter)
      .build();

    assertNull(ItineraryReferenceSerializer.decode(craftedToken));
  }
}
