package org.opentripplanner.updater.trip.gtfs;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import gnu.trove.TCollections;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.function.Function;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.gtfs.mapping.DirectionMapper;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.timetable.Direction;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.repository.TimetableRepositorySnapshot;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.opentripplanner.utils.time.TimeUtils;

/**
 * This class is used for matching TripDescriptors without trip_ids to scheduled GTFS data and to
 * feed back that information into a new TripDescriptor with proper trip_id.
 * <p>
 * The class should only be used if we know that the feed producer is unable to produce trip_ids in
 * the GTFS-RT feed.
 */
public class GtfsRealtimeFuzzyTripMatcher {

  private static final TIntSet EMPTY_SERVICE_CODES = TCollections.unmodifiableSet(
    new TIntHashSet()
  );

  private final Function<FeedScopedId, Boolean> containsTrip;
  private final Function<FeedScopedId, Route> getRoute;
  private final Function<LocalDate, TIntSet> getServiceCodesRunningForDate;
  private final Function<Route, Collection<TripPattern>> findPatterns;

  // TODO: replace this with a runtime solution
  private final DirectionMapper directionMapper = new DirectionMapper(DataImportIssueStore.NOOP);

  /**
   * Look up entities against the mutable, uncommitted realtime-timetable repository — used by
   * updaters, which need to see in-progress real-time additions not yet published in a snapshot.
   */
  public GtfsRealtimeFuzzyTripMatcher(TimetableRepositorySnapshot timetableSnapshot) {
    this(
      timetableSnapshot::containsTrip,
      timetableSnapshot::getRoute,
      date ->
        timetableSnapshot
          .getTripCalendars()
          .getServiceCodesRunningForDate()
          .getOrDefault(date, EMPTY_SERVICE_CODES),
      timetableSnapshot::findPatterns
    );
  }

  /**
   * Look up entities against a request-scoped {@link TransitService} — used by the read-only
   * GraphQL {@code fuzzyTrip} query.
   */
  public GtfsRealtimeFuzzyTripMatcher(TransitService transitService) {
    this(
      transitService::containsTrip,
      transitService::getRoute,
      transitService::getServiceCodesRunningForDate,
      transitService::findPatterns
    );
  }

  private GtfsRealtimeFuzzyTripMatcher(
    Function<FeedScopedId, Boolean> containsTrip,
    Function<FeedScopedId, Route> getRoute,
    Function<LocalDate, TIntSet> getServiceCodesRunningForDate,
    Function<Route, Collection<TripPattern>> findPatterns
  ) {
    this.containsTrip = containsTrip;
    this.getRoute = getRoute;
    this.getServiceCodesRunningForDate = getServiceCodesRunningForDate;
    this.findPatterns = findPatterns;
  }

  public TripDescriptor match(String feedId, TripDescriptor trip) {
    if (trip.hasTripId() && containsTrip.apply(new FeedScopedId(feedId, trip.getTripId()))) {
      // trip_id already exists
      return trip;
    }

    if (
      !trip.hasRouteId() || !trip.hasDirectionId() || !trip.hasStartTime() || !trip.hasStartDate()
    ) {
      // Could not determine trip_id, returning original TripDescriptor
      return trip;
    }

    FeedScopedId routeId = new FeedScopedId(feedId, trip.getRouteId());
    int time = TimeUtils.time(trip.getStartTime());
    LocalDate date;
    try {
      date = ServiceDateUtils.parseString(trip.getStartDate());
    } catch (ParseException e) {
      return trip;
    }
    Route route = getRoute.apply(routeId);
    if (route == null) {
      return trip;
    }
    Direction direction = directionMapper.map(trip.getDirectionId());

    Trip matchedTrip = getTrip(route, direction, time, date);

    if (matchedTrip == null) {
      // Check if the trip is carried over from previous day
      date = date.minusDays(1);
      time += 24 * 60 * 60;
      matchedTrip = getTrip(route, direction, time, date);
    }

    if (matchedTrip == null) {
      return trip;
    }

    // If everything succeeds, build a new TripDescriptor with the matched trip_id
    return trip.toBuilder().setTripId(matchedTrip.getId().getId()).build();
  }

  public synchronized Trip getTrip(
    Route route,
    Direction direction,
    int startTime,
    LocalDate date
  ) {
    TIntSet servicesRunningForDate = getServiceCodesRunningForDate.apply(date);
    for (TripPattern pattern : findPatterns.apply(route)) {
      if (pattern.getDirection() != direction) {
        continue;
      }
      for (TripTimes times : pattern.getScheduledTimetable().getTripTimes()) {
        if (
          times.getScheduledDepartureTime(0) == startTime &&
          servicesRunningForDate.contains(times.getServiceCode())
        ) {
          return times.getTrip();
        }
      }
    }
    return null;
  }
}
