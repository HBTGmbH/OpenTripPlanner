package org.opentripplanner.netex.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.transfer.constrained.model.ConstrainedTransfer;
import org.opentripplanner.transit.model.site.StopLocation;
import org.rutebanken.netex.model.ServiceJourneyInterchange;
import org.rutebanken.netex.model.VehicleJourneyRefStructure;

/**
 * Entities x-referencing entries in more than on independent file must be mapped AFTER all
 * individual files are processed. This class is responsible for the "post-process-mapping", and
 * delegate to mappers for each specific type.
 */
class GroupNetexMapper {

  private final FeedScopedIdFactory idFactory;
  private final DataImportIssueStore issueStore;
  private final TransitDataImportBuilder transitBuilder;
  private final NetexProfileSupport profileSupport;
  private final List<ServiceJourneyInterchange> interchanges = new ArrayList<>();

  /**
   * The mapper of the shared files, which runs once after all file groups. It maps the
   * interchanges of a file group that reference a journey of another file group. {@code null} for
   * the mapper of the shared files itself.
   */
  @Nullable
  private final GroupNetexMapper sharedGroupMapper;

  /** Interchanges of file groups referencing a journey of another file group. */
  private final List<ServiceJourneyInterchange> interchangesAcrossGroups = new ArrayList<>();

  /**
   * A map from trip/serviceJourney id to an ordered list of scheduled stop point ids.
   */
  final Map<String, List<String>> scheduledStopPointsIndex = new HashMap<>();

  /** Create the mapper of the shared files. */
  GroupNetexMapper(
    FeedScopedIdFactory idFactory,
    DataImportIssueStore issueStore,
    TransitDataImportBuilder transitBuilder,
    NetexProfileSupport profileSupport
  ) {
    this(idFactory, issueStore, transitBuilder, profileSupport, null);
  }

  /** Create the mapper of a file group. */
  GroupNetexMapper(
    FeedScopedIdFactory idFactory,
    DataImportIssueStore issueStore,
    TransitDataImportBuilder transitBuilder,
    NetexProfileSupport profileSupport,
    @Nullable GroupNetexMapper sharedGroupMapper
  ) {
    this.idFactory = idFactory;
    this.issueStore = issueStore;
    this.transitBuilder = transitBuilder;
    this.profileSupport = profileSupport;
    this.sharedGroupMapper = sharedGroupMapper;
  }

  void addInterchange(Collection<ServiceJourneyInterchange> interchanges) {
    this.interchanges.addAll(interchanges);
  }

  void mapGroupEntries() {
    mapInterchanges();
    mapInterchangesAcrossGroups();
  }

  private void mapInterchanges() {
    var mapper = new TransferMapper(
      idFactory,
      issueStore,
      scheduledStopPointsIndex,
      transitBuilder.getTripsById(),
      profileSupport
    );
    for (ServiceJourneyInterchange it : interchanges) {
      if (referencesJourneyOfOtherGroup(it)) {
        sharedGroupMapper.interchangesAcrossGroups.add(it);
        continue;
      }
      ConstrainedTransfer result = mapper.mapToTransfer(it);
      if (result != null) {
        transitBuilder.getTransfers().add(result);
      }
    }
  }

  /**
   * The German NeTEx data delivers one file group per operating unit, and an interchange may
   * reference a journey of another operating unit. The scheduled stop points of the journeys of a
   * file group are not available after the group is mapped, so the position of the interchange
   * point is found in the stops of the trip pattern instead: the stop the scheduled stop point is
   * mapped to. Only done with extended NeTEx support, after all file groups are mapped.
   */
  private void mapInterchangesAcrossGroups() {
    if (interchangesAcrossGroups.isEmpty()) {
      return;
    }
    var mapper = new TransferMapper(
      idFactory,
      issueStore,
      stopsOfTripPatterns(interchangesAcrossGroups),
      transitBuilder.getTripsById(),
      profileSupport
    );
    for (ServiceJourneyInterchange it : interchangesAcrossGroups) {
      ConstrainedTransfer result = mapper.mapToTransfer(it);
      if (result != null) {
        transitBuilder.getTransfers().add(result);
      }
    }
  }

  private boolean referencesJourneyOfOtherGroup(ServiceJourneyInterchange it) {
    return (
      sharedGroupMapper != null &&
      profileSupport.extendedNetex() &&
      (isJourneyOfOtherGroup(it.getFromJourneyRef()) || isJourneyOfOtherGroup(it.getToJourneyRef()))
    );
  }

  private boolean isJourneyOfOtherGroup(@Nullable VehicleJourneyRefStructure ref) {
    return (
      ref != null && ref.getRef() != null && !scheduledStopPointsIndex.containsKey(ref.getRef())
    );
  }

  /** The stops of the trip patterns of the journeys referenced by the given interchanges. */
  private TransferMapper.StopSequences stopsOfTripPatterns(
    Collection<ServiceJourneyInterchange> interchanges
  ) {
    Set<FeedScopedId> tripIds = new HashSet<>();
    for (var it : interchanges) {
      for (var ref : List.of(it.getFromJourneyRef(), it.getToJourneyRef())) {
        if (ref != null && ref.getRef() != null) {
          tripIds.add(idFactory.createId(ref.getRef()));
        }
      }
    }
    Map<FeedScopedId, List<StopLocation>> stopsByTrip = new HashMap<>();
    for (var pattern : transitBuilder.getTripPatterns().values()) {
      pattern
        .scheduledTripsAsStream()
        .filter(trip -> tripIds.contains(trip.getId()))
        .forEach(trip -> stopsByTrip.put(trip.getId(), pattern.getStops()));
    }
    var stopsByScheduledStopPoint = transitBuilder.stopsByScheduledStopPoints();
    return new TransferMapper.StopSequences() {
      @Override
      public List<?> ofJourney(String serviceJourneyId) {
        return stopsByTrip.get(idFactory.createId(serviceJourneyId));
      }

      @Override
      public Object ofScheduledStopPoint(String scheduledStopPointId) {
        return stopsByScheduledStopPoint.get(idFactory.createId(scheduledStopPointId));
      }
    };
  }
}
