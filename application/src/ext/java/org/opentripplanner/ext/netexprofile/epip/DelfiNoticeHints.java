package org.opentripplanner.ext.netexprofile.epip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Hints in the notice texts of the German DELFI data set about data which should be given in
 * NeTEx elements instead.
 * <p>
 * The DELFI data set states the GTFS route type of a line in a notice like
 * {@code GTFS Route Type : 1501}, and the booking conditions of demand responsive services in free
 * text notices like {@code AnrufSammelTaxi (AST): Anmeldung bis 30 Min. vor Abfahrt erforderlich
 * unter 0800-939-0800}. These hints are not used to import the data, but they are reported, so the
 * data can be corrected at the source: the transport mode belongs into {@code TransportMode} and
 * {@code TransportSubmode}, the booking conditions into {@code BookingArrangements}.
 */
public final class DelfiNoticeHints {

  private static final Pattern GTFS_ROUTE_TYPE = Pattern.compile("GTFS Route Type\\s*:\\s*(\\d+)");

  /** German words used in notices about booking a demand responsive service. */
  private static final Pattern BOOKING = Pattern.compile(
    "anmeld|bestell|buchung|buchbar|voranm|anruf|rufbus|ruftaxi|linientaxi",
    Pattern.CASE_INSENSITIVE
  );

  private static final int MAX_BOOKING_NOTICES = 3;

  private DelfiNoticeHints() {}

  /**
   * The hints about the transport mode and the booking conditions of a line.
   *
   * @param gtfsRouteType   the GTFS route type stated in a notice, or {@code null}
   * @param description     the meaning of the GTFS route type, or {@code null}
   * @param expectedMode    how the transport mode should be given in NeTEx
   * @param bookingNotices  notices about booking the service, at most three
   * @param bookingRequired if the service needs booking, by its route type or its notices
   */
  public record LineHints(
    @Nullable Integer gtfsRouteType,
    @Nullable String description,
    String expectedMode,
    List<String> bookingNotices,
    boolean bookingRequired
  ) {}

  public static LineHints lineHints(Collection<String> noticeTexts) {
    Integer routeType = null;
    var bookingNotices = new ArrayList<String>();
    for (String text : noticeTexts) {
      var m = GTFS_ROUTE_TYPE.matcher(text);
      if (m.find()) {
        routeType = routeType == null ? Integer.valueOf(m.group(1)) : routeType;
      } else if (
        BOOKING.matcher(text).find() &&
        bookingNotices.size() < MAX_BOOKING_NOTICES &&
        !bookingNotices.contains(text)
      ) {
        bookingNotices.add(text);
      }
    }
    boolean demandResponsive = routeType != null && (routeType == 715 || isTaxi(routeType));
    return new LineHints(
      routeType,
      routeType == null ? null : description(routeType),
      expectedMode(routeType),
      bookingNotices,
      demandResponsive || !bookingNotices.isEmpty()
    );
  }

  /** The meaning of the extended GTFS route types used in the DELFI data set. */
  @Nullable
  private static String description(int routeType) {
    return switch (routeType) {
      case 700 -> "bus service";
      case 715 -> "demand and response bus service";
      case 1500 -> "taxi service";
      case 1501 -> "communal taxi service";
      default -> null;
    };
  }

  private static String expectedMode(@Nullable Integer routeType) {
    if (routeType == null) {
      return "<TransportMode> of the line, for example bus or taxi";
    }
    if (routeType == 715) {
      return "<TransportMode>bus</TransportMode> with <BusSubmode>demandAndResponseBus</BusSubmode>";
    }
    if (routeType == 1501) {
      return "<TransportMode>taxi</TransportMode> with <TaxiSubmode>communalTaxi</TaxiSubmode>";
    }
    if (isTaxi(routeType)) {
      return "<TransportMode>taxi</TransportMode>";
    }
    if (routeType >= 700 && routeType < 800) {
      return "<TransportMode>bus</TransportMode>";
    }
    return "<TransportMode> matching GTFS route type " + routeType;
  }

  private static boolean isTaxi(int routeType) {
    return routeType >= 1500 && routeType < 1600;
  }
}
