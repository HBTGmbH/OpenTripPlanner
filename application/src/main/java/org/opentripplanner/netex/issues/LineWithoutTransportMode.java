package org.opentripplanner.netex.issues;

import java.util.List;
import javax.annotation.Nullable;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;

/**
 * A line is dropped with all its journeys, because neither the line, its journeys nor the stop
 * places they serve have a known transport mode. The message names the hints found in the data
 * and tells how the line must be described to be imported, so the data can be fixed at the source.
 */
public class LineWithoutTransportMode implements DataImportIssue {

  private static final String BOOKING =
    " Give the booking conditions as <BookingArrangements> (<BookingContact> with phone or URL," +
    " <BookingMethods>, <LatestBookingTime> or <MinimumBookingPeriod>) on the line, the journeys" +
    " or the stop points in journey pattern, not as notice text.";

  private final String lineId;

  @Nullable
  private final String lineName;

  private final String transportMode;
  private final int numberOfJourneys;
  private final List<String> hints;
  private final String expectedMode;
  private final boolean bookingRequired;

  /**
   * @param transportMode    the mode given on the line, "missing" if none
   * @param hints            the hints about the mode and the booking found in the data
   * @param expectedMode     how the transport mode should be given
   * @param bookingRequired  if the hints show that the service needs booking
   */
  public LineWithoutTransportMode(
    String lineId,
    @Nullable String lineName,
    String transportMode,
    int numberOfJourneys,
    List<String> hints,
    String expectedMode,
    boolean bookingRequired
  ) {
    this.lineId = lineId;
    this.lineName = lineName;
    this.transportMode = transportMode;
    this.numberOfJourneys = numberOfJourneys;
    this.hints = List.copyOf(hints);
    this.expectedMode = expectedMode;
    this.bookingRequired = bookingRequired;
  }

  @Override
  public String getMessage() {
    var buf = new StringBuilder();
    buf.append(
      String.format(
        "Line %s%s is dropped with its %d service journeys, because its TransportMode is %s and " +
          "neither its journeys nor the stop places they serve have a known mode.",
        lineId,
        lineName == null ? "" : " (" + lineName + ")",
        numberOfJourneys,
        transportMode
      )
    );
    if (!hints.isEmpty()) {
      buf.append(" Hints in the data: ").append(String.join("; ", hints)).append('.');
    }
    buf.append(" To import the line, give ").append(expectedMode).append(" on the line.");
    if (bookingRequired) {
      buf.append(BOOKING);
    }
    return buf.toString();
  }

  /** Lines with many journeys first. */
  @Override
  public int getPriority() {
    return numberOfJourneys;
  }
}
