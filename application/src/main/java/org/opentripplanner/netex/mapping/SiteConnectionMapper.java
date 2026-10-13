package org.opentripplanner.netex.mapping;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.transfer.constrained.model.ConstrainedTransfer;
import org.opentripplanner.transfer.constrained.model.StationTransferPoint;
import org.opentripplanner.transfer.constrained.model.StopTransferPoint;
import org.opentripplanner.transfer.constrained.model.TransferConstraint;
import org.opentripplanner.transfer.constrained.model.TransferPoint;
import org.opentripplanner.transit.model.framework.ImmutableEntityById;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.StopLocation;
import org.rutebanken.netex.model.SiteConnection;
import org.rutebanken.netex.model.SiteConnectionEndStructure;
import org.rutebanken.netex.model.TransferDurationStructure;

/**
 * Map a NeTEx site connection to constrained transfers with a minimum transfer time.
 * <p>
 * A site connection states the time needed to walk between two stop places, or between two quays,
 * or within a stop place (CEN/TS 16614-4, 7.5.5). An end without quay applies to any quay of the
 * stop place. The walking time is used as the minimum transfer time between the two ends, like
 * {@code min_transfer_time} in GTFS {@code transfers.txt}.
 * <p>
 * A connection within one site - from a stop place or quay to itself, or from a quay to its own
 * stop place - is ignored and reported once per stop place. OTP has no minimum transfer time within
 * a stop place, and a constrained transfer for it would apply to every pair of stops served there:
 * the size of the transfer index grows with the square of the patterns at the stop place.
 */
class SiteConnectionMapper {

  private final FeedScopedIdFactory idFactory;
  private final DataImportIssueStore issueStore;
  private final ImmutableEntityById<Station> stationsById;
  private final ImmutableEntityById<RegularStop> stopsById;
  private final Set<Station> reportedStations = new HashSet<>();

  SiteConnectionMapper(
    FeedScopedIdFactory idFactory,
    DataImportIssueStore issueStore,
    ImmutableEntityById<Station> stationsById,
    ImmutableEntityById<RegularStop> stopsById
  ) {
    this.idFactory = idFactory;
    this.issueStore = issueStore;
    this.stationsById = stationsById;
    this.stopsById = stopsById;
  }

  List<ConstrainedTransfer> map(SiteConnection connection) {
    Duration duration = duration(connection.getWalkTransferDuration());
    if (duration == null) {
      duration = duration(connection.getTransferDuration());
    }
    var from = transferPoint(connection.getFrom());
    var to = transferPoint(connection.getTo());

    if (duration == null || from == null || to == null) {
      issueStore.add(
        "InvalidSiteConnection",
        "SiteConnection %s is ignored, because it has no transfer duration or an unknown end.",
        connection.getId()
      );
      return List.of();
    }
    if (withinOneSite(from, to)) {
      reportWithinSite(connection, from);
      return List.of();
    }

    var constraint = TransferConstraint.of()
      .minTransferTime((int) duration.toSeconds())
      .build();
    var id = idFactory.createId(connection.getId());
    var result = new ArrayList<ConstrainedTransfer>();
    result.add(new ConstrainedTransfer(id, from, to, constraint));

    if (Boolean.TRUE.equals(connection.isBothWays())) {
      var reverseId = idFactory.createId(connection.getId() + ":reverse");
      result.add(new ConstrainedTransfer(reverseId, to, from, constraint));
    }
    return result;
  }

  @Nullable
  private TransferPoint transferPoint(@Nullable SiteConnectionEndStructure end) {
    if (end == null) {
      return null;
    }
    if (end.getQuayRef() != null) {
      var stop = stopsById.get(idFactory.createId(end.getQuayRef().getValue().getRef()));
      return stop == null ? null : new StopTransferPoint(stop);
    }
    if (end.getStopPlaceRef() != null) {
      var station = stationsById.get(idFactory.createId(end.getStopPlaceRef().getValue().getRef()));
      return station == null ? null : new StationTransferPoint(station);
    }
    return null;
  }

  /** Both ends are the same stop place or quay, or one end is a quay of the other end. */
  private static boolean withinOneSite(TransferPoint from, TransferPoint to) {
    if (from.isStationTransferPoint() && to.isStationTransferPoint()) {
      return from.asStationTransferPoint().getStation() == to.asStationTransferPoint().getStation();
    }
    if (from.isStopTransferPoint() && to.isStopTransferPoint()) {
      return from.asStopTransferPoint().getStop() == to.asStopTransferPoint().getStop();
    }
    var quay = from.isStopTransferPoint() ? from : to;
    var stopPlace = from.isStationTransferPoint() ? from : to;
    return (
      quay.asStopTransferPoint().getStop().getParentStation() ==
      stopPlace.asStationTransferPoint().getStation()
    );
  }

  private void reportWithinSite(SiteConnection connection, TransferPoint end) {
    Station station = end.isStationTransferPoint()
      ? end.asStationTransferPoint().getStation()
      : end.asStopTransferPoint().getStop().getParentStation();
    if (station != null && !reportedStations.add(station)) {
      return;
    }
    StopLocation site = end.isStopTransferPoint() ? end.asStopTransferPoint().getStop() : null;
    issueStore.add(
      "SiteConnectionWithinSite",
      "SiteConnection %s within %s is ignored, like all connections within this stop place: OTP " +
        "has no minimum transfer time within a stop place. State the walking times between its " +
        "quays (SiteConnection from quay to quay) instead.",
      connection.getId(),
      station != null ? station.getId() : site.getId()
    );
  }

  @Nullable
  private static Duration duration(@Nullable TransferDurationStructure transferDuration) {
    return transferDuration == null ? null : transferDuration.getDefaultDuration();
  }
}
