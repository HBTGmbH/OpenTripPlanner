package org.opentripplanner.netex.mapping;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 */
class SiteConnectionMapper {

  private final FeedScopedIdFactory idFactory;
  private final DataImportIssueStore issueStore;
  private final ImmutableEntityById<Station> stationsById;
  private final ImmutableEntityById<RegularStop> stopsById;

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

    var constraint = TransferConstraint.of()
      .minTransferTime((int) duration.toSeconds())
      .build();
    var id = idFactory.createId(connection.getId());
    var result = new ArrayList<ConstrainedTransfer>();
    result.add(new ConstrainedTransfer(id, from, to, constraint));

    if (Boolean.TRUE.equals(connection.isBothWays()) && !sameEnd(connection)) {
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

  /** A connection within one stop place (or quay) is the same in both directions. */
  private static boolean sameEnd(SiteConnection connection) {
    var from = endRef(connection.getFrom());
    return from != null && from.equals(endRef(connection.getTo()));
  }

  @Nullable
  private static String endRef(SiteConnectionEndStructure end) {
    if (end.getQuayRef() != null) {
      return end.getQuayRef().getValue().getRef();
    }
    return end.getStopPlaceRef() == null ? null : end.getStopPlaceRef().getValue().getRef();
  }

  @Nullable
  private static Duration duration(@Nullable TransferDurationStructure transferDuration) {
    return transferDuration == null ? null : transferDuration.getDefaultDuration();
  }
}
