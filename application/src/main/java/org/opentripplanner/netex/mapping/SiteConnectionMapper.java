package org.opentripplanner.netex.mapping;

import java.time.Duration;
import java.util.ArrayList;
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
 * The German data repeats a site connection in the files of every operating unit serving the stop
 * place, with another id. A connection with the same ends and duration as one mapped before is
 * mapped once; the set of mapped connections is shared by the mappers of all file groups.
 */
class SiteConnectionMapper {

  private final FeedScopedIdFactory idFactory;
  private final DataImportIssueStore issueStore;
  private final ImmutableEntityById<Station> stationsById;
  private final ImmutableEntityById<RegularStop> stopsById;
  private final Set<String> mappedConnections;

  /**
   * @param mappedConnections the connections mapped so far, as ends and duration, shared by the
   *                          mappers of all file groups
   */
  SiteConnectionMapper(
    FeedScopedIdFactory idFactory,
    DataImportIssueStore issueStore,
    ImmutableEntityById<Station> stationsById,
    ImmutableEntityById<RegularStop> stopsById,
    Set<String> mappedConnections
  ) {
    this.idFactory = idFactory;
    this.issueStore = issueStore;
    this.stationsById = stationsById;
    this.stopsById = stopsById;
    this.mappedConnections = mappedConnections;
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

    int seconds = (int) duration.toSeconds();
    var constraint = TransferConstraint.of().minTransferTime(seconds).build();
    var result = new ArrayList<ConstrainedTransfer>();
    if (mappedConnections.add(key(from, to, seconds))) {
      result.add(
        new ConstrainedTransfer(idFactory.createId(connection.getId()), from, to, constraint)
      );
    }
    // A connection within one stop place or quay is the same in both directions
    if (
      Boolean.TRUE.equals(connection.isBothWays()) && mappedConnections.add(key(to, from, seconds))
    ) {
      var reverseId = idFactory.createId(connection.getId() + ":reverse");
      result.add(new ConstrainedTransfer(reverseId, to, from, constraint));
    }
    return result;
  }

  private static String key(TransferPoint from, TransferPoint to, int seconds) {
    return key(from) + ">" + key(to) + "/" + seconds;
  }

  private static String key(TransferPoint point) {
    return point.isStationTransferPoint()
      ? "station:" + point.asStationTransferPoint().getStation().getId()
      : "stop:" + point.asStopTransferPoint().getStop().getId();
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

  @Nullable
  private static Duration duration(@Nullable TransferDurationStructure transferDuration) {
    return transferDuration == null ? null : transferDuration.getDefaultDuration();
  }
}
