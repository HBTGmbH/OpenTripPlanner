package org.opentripplanner.ext.netexprofile.epip;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DelfiNoticeHintsTest {

  private static final String AST_BOOKING =
    "AnrufSammelTaxi (AST): Anmeldung bis 30 Min. vor Abfahrt erforderlich unter 0800-939-0800";

  @Test
  void communalTaxiWithBookingNotice() {
    var hints = DelfiNoticeHints.lineHints(
      List.of("Zahl der Sitzplätze : 8", "GTFS Route Type : 1501", AST_BOOKING)
    );

    assertThat(hints.gtfsRouteType()).isEqualTo(1501);
    assertThat(hints.description()).isEqualTo("communal taxi service");
    assertThat(hints.expectedMode()).contains("<TaxiSubmode>communalTaxi</TaxiSubmode>");
    assertThat(hints.bookingNotices()).containsExactly(AST_BOOKING);
    assertThat(hints.bookingRequired()).isTrue();
  }

  @Test
  void demandAndResponseBusNeedsBookingEvenWithoutNotice() {
    var hints = DelfiNoticeHints.lineHints(List.of("GTFS Route Type : 715"));

    assertThat(hints.expectedMode()).contains("<BusSubmode>demandAndResponseBus</BusSubmode>");
    assertThat(hints.bookingNotices()).isEmpty();
    assertThat(hints.bookingRequired()).isTrue();
  }

  @Test
  void regularBus() {
    var hints = DelfiNoticeHints.lineHints(List.of("GTFS Route Type : 700", "Niederflurfahrzeug"));

    assertThat(hints.expectedMode()).isEqualTo("<TransportMode>bus</TransportMode>");
    assertThat(hints.bookingRequired()).isFalse();
  }

  @Test
  void noHints() {
    var hints = DelfiNoticeHints.lineHints(List.of("Niederflurfahrzeug"));

    assertThat(hints.gtfsRouteType()).isNull();
    assertThat(hints.expectedMode()).contains("for example bus or taxi");
    assertThat(hints.bookingRequired()).isFalse();
  }

  @Test
  void reportAtMostThreeDistinctBookingNotices() {
    var hints = DelfiNoticeHints.lineHints(
      List.of("Rufbus 1", "Rufbus 1", "Rufbus 2", "Rufbus 3", "Rufbus 4")
    );

    assertThat(hints.bookingNotices()).containsExactly("Rufbus 1", "Rufbus 2", "Rufbus 3");
  }
}
