package org.opentripplanner.transfer.constrained.raptoradaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.opentripplanner.transfer.constrained.model.ConstrainedTransfer;
import org.opentripplanner.transfer.constrained.model.RouteStationTransferPoint;
import org.opentripplanner.transfer.constrained.model.RouteStopTransferPoint;
import org.opentripplanner.transfer.constrained.model.StationTransferPoint;
import org.opentripplanner.transfer.constrained.model.StopTransferPoint;
import org.opentripplanner.transfer.constrained.model.TransferPoint;
import org.opentripplanner.transfer.constrained.model.TripTransferPoint;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.RoutingTripPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Trip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransferIndexGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(TransferIndexGenerator.class);

  private static final boolean BOARD = true;
  private static final boolean ALIGHT = false;

  private final Collection<ConstrainedTransfer> constrainedTransfers;
  private final Map<Station, Set<RoutingTripPattern>> patternsByStation = new HashMap<>();
  private final Map<StopLocation, Set<RoutingTripPattern>> patternsByStop = new HashMap<>();
  private final Map<Route, Set<RoutingTripPattern>> patternsByRoute = new HashMap<>();
  private final Map<Trip, Set<RoutingTripPattern>> patternsByTrip = new HashMap<>();

  /**
   * One source point matcher per station and stop, shared by all transfers. A station matcher
   * holds the set of the stops of the station.
   */
  private final Map<Station, TransferPointMatcher> stationSourcePoints = new HashMap<>();
  private final Map<Integer, TransferPointMatcher> stopSourcePoints = new HashMap<>();

  private boolean dirty = false;
  private int prevProcessedNumberOfPatterns = 0;
  private ConstrainedTransfersForPatterns cachedResult = null;

  public TransferIndexGenerator(
    Collection<ConstrainedTransfer> constrainedTransfers,
    Collection<TripPattern> tripPatterns
  ) {
    this.constrainedTransfers = constrainedTransfers;
    setupPatterns(tripPatterns);
  }

  public ConstrainedTransfersForPatterns generateTransfers() {
    int nPatterns = RoutingTripPattern.indexCounter();

    if (isCacheValid(nPatterns)) {
      return cachedResult;
    }

    TransferForPatternByStopPos[] forwardTransfers = new TransferForPatternByStopPos[nPatterns];
    TransferForPatternByStopPos[] reverseTransfers = new TransferForPatternByStopPos[nPatterns];

    for (ConstrainedTransfer tx : constrainedTransfers) {
      var c = tx.getTransferConstraint();
      // Only add transfers which have an effect on the Raptor routing here.
      // Some transfers only have the priority set, and that is used in optimized-
      // transfers, but not in Raptor.
      if (!c.includeInRaptorRouting()) {
        continue;
      }

      try {
        var fromPoints = findTPoints(tx.getFrom(), ALIGHT)
          .stream()
          .filter(TPoint::canAlight)
          .toList();
        var toPoints = findTPoints(tx.getTo(), BOARD).stream().filter(TPoint::canBoard).toList();
        addTransferConstraints(tx, fromPoints, toPoints, forwardTransfers, reverseTransfers);
      } catch (Exception e) {
        LOG.error("Unable to generate transfers: {}. Affected transfer: {}", e, tx);
      }
    }

    sortTransfers(forwardTransfers);
    sortTransfers(reverseTransfers);

    return cacheResult(
      new ConstrainedTransfersForPatterns(
        Arrays.asList(forwardTransfers),
        Arrays.asList(reverseTransfers)
      ),
      nPatterns
    );
  }

  /**
   * Add information about a newly created pattern and timetables in the index, in order to be able
   * to create constrained transfers for these patterns.
   */
  public void addRealtimeTrip(TripPattern tripPattern, List<Trip> trips) {
    if (setupPattern(tripPattern, trips)) {
      invalidateCache();
    }
  }

  /**
   * The cache is valid if it is not null, not dirty and no other component created new patterns.
   * In theory all real-time pattern creations should be done through the TimetableRepository and would
   * be detected by the dirty flag.
   * But since the TripPattern builder is publicly accessible, there is no strong guarantee about this.
   * The risk is IndexOutOfBoundsException: The cached ConstrainedTransfersForPatterns wraps arrays
   * sized by the old nPatterns. If a new RoutingTripPattern is created (index = N), and routing tries
   * result.toStop(N) on a cached array of size N, it throws IndexOutOfBoundsException.
   * Hence, the additional check against the previous processed number of patterns.
   *
   */
  private boolean isCacheValid(int nPatterns) {
    return !dirty && nPatterns == prevProcessedNumberOfPatterns && cachedResult != null;
  }

  private void invalidateCache() {
    dirty = true;
  }

  private ConstrainedTransfersForPatterns cacheResult(
    ConstrainedTransfersForPatterns result,
    int nPatterns
  ) {
    this.cachedResult = result;
    this.dirty = false;
    this.prevProcessedNumberOfPatterns = nPatterns;
    return cachedResult;
  }

  /**
   * Add the transfer between all from and to points, except from a point to itself.
   * <p>
   * The entry of a forward search is stored at the to point and contains the source point matcher
   * of the from point, not the from point itself; the entry of a reverse search is stored at the
   * from point and contains the matcher of the to point. Station and stop transfer points use one
   * matcher for all their points, so all from points of a station give the same entry at a to
   * point. Each distinct entry is added once: the number of entries grows with the number of
   * points, not with the number of pairs. A station to station transfer at a stop served by
   * thousands of pattern stops would otherwise create millions of identical entries.
   */
  private static void addTransferConstraints(
    ConstrainedTransfer tx,
    List<TPoint> fromPoints,
    List<TPoint> toPoints,
    TransferForPatternByStopPos[] forwardTransfers,
    TransferForPatternByStopPos[] reverseTransfers
  ) {
    var fromGroups = groupBySourcePoint(fromPoints);
    var toGroups = groupBySourcePoint(toPoints);
    for (var toPoint : toPoints) {
      for (var from : fromGroups) {
        if (containsOtherPoint(from, toPoint)) {
          toPoint.addForwardTransfer(tx, from.getFirst().sourcePoint, forwardTransfers);
        }
      }
    }
    for (var fromPoint : fromPoints) {
      for (var to : toGroups) {
        if (containsOtherPoint(to, fromPoint)) {
          fromPoint.addReverseTransfer(tx, to.getFirst().sourcePoint, reverseTransfers);
        }
      }
    }
  }

  /** The points by their source point matcher (identity), in the order of the points. */
  private static Collection<List<TPoint>> groupBySourcePoint(List<TPoint> points) {
    Map<TransferPointMatcher, List<TPoint>> result = new LinkedHashMap<>();
    for (var point : points) {
      result.computeIfAbsent(point.sourcePoint, k -> new ArrayList<>()).add(point);
    }
    return result.values();
  }

  private static boolean containsOtherPoint(List<TPoint> points, TPoint point) {
    return points.size() > 1 || !points.getFirst().equals(point);
  }

  /**
   * Index scheduled patterns when loading the graph initially.
   */
  private void setupPatterns(Collection<TripPattern> tripPatterns) {
    for (TripPattern tripPattern : tripPatterns) {
      setupPattern(tripPattern, tripPattern.scheduledTripsAsStream().toList());
    }
  }

  /**
   * @return true if any new entries were added to the lookup maps, false if all entries already
   * existed.
   */
  private boolean setupPattern(TripPattern tripPattern, List<Trip> trips) {
    boolean added = false;
    RoutingTripPattern pattern = tripPattern.getRoutingTripPattern();
    added |= patternsByRoute
      .computeIfAbsent(tripPattern.getRoute(), t -> new HashSet<>())
      .add(pattern);

    for (Trip trip : trips) {
      added |= patternsByTrip.computeIfAbsent(trip, t -> new HashSet<>()).add(pattern);
    }

    for (StopLocation stop : tripPattern.getStops()) {
      added |= patternsByStop.computeIfAbsent(stop, t -> new HashSet<>()).add(pattern);
      Station station = stop.getParentStation();
      if (station != null) {
        added |= patternsByStation.computeIfAbsent(station, t -> new HashSet<>()).add(pattern);
      }
    }
    return added;
  }

  /** Sort trips in a TransferForPatternByStopPos, if it is not null */
  private void sortTransfers(TransferForPatternByStopPos[] transfers) {
    for (var transfersForStop : transfers) {
      if (transfersForStop != null) {
        transfersForStop.sortOnSpecificityRanking();
      }
    }
  }

  private Collection<TPoint> findTPoints(TransferPoint txPoint, boolean boarding) {
    if (txPoint.isStationTransferPoint()) {
      return findTPoints(txPoint.asStationTransferPoint());
    } else if (txPoint.isStopTransferPoint()) {
      return findTPoints(txPoint.asStopTransferPoint());
    } else if (txPoint.isRouteStationTransferPoint()) {
      return findTPoint(txPoint.asRouteStationTransferPoint(), boarding);
    } else if (txPoint.isRouteStopTransferPoint()) {
      return findTPoint(txPoint.asRouteStopTransferPoint(), boarding);
    } else {
      return findTPoints(txPoint.asTripTransferPoint());
    }
  }

  private List<TPoint> findTPoints(StationTransferPoint point) {
    var station = point.getStation();
    var patterns = patternsByStation.get(station);

    if (patterns == null) {
      return List.of();
    }

    var sourcePoint = stationSourcePoints.computeIfAbsent(
      station,
      TransferPointForPatternFactory::createTransferPointForPattern
    );
    var result = new ArrayList<TPoint>();

    for (RoutingTripPattern pattern : patterns) {
      var tripPattern = pattern.getPattern();
      for (int pos = 0; pos < tripPattern.numberOfStops(); ++pos) {
        if (point.getStation() == tripPattern.getStop(pos).getParentStation()) {
          result.add(new TPoint(pattern, sourcePoint, null, pos));
        }
      }
    }
    return result;
  }

  private List<TPoint> findTPoints(StopTransferPoint point) {
    var stop = point.asStopTransferPoint().getStop();
    var patterns = patternsByStop.get(stop);

    if (patterns == null) {
      return List.of();
    }

    var sourcePoint = stopSourcePoints.computeIfAbsent(
      stop.getIndex(),
      TransferPointForPatternFactory::createTransferPointForPattern
    );
    var result = new ArrayList<TPoint>();

    for (RoutingTripPattern pattern : patterns) {
      var p = pattern.getPattern();
      for (int pos = 0; pos < p.numberOfStops(); ++pos) {
        if (point.getStop() == p.getStop(pos)) {
          result.add(new TPoint(pattern, sourcePoint, null, pos));
        }
      }
    }
    return result;
  }

  private List<TPoint> findTPoint(RouteStationTransferPoint point, boolean boarding) {
    return findTPointForRoute(
      point.getRoute(),
      boarding
        ? p -> p.findBoardingStopPositionInPattern(point.getStation())
        : p -> p.findAlightStopPositionInPattern(point.getStation())
    );
  }

  private List<TPoint> findTPoint(RouteStopTransferPoint point, boolean boarding) {
    return findTPointForRoute(
      point.getRoute(),
      boarding
        ? p -> p.findBoardingStopPositionInPattern(point.getStop())
        : p -> p.findAlightStopPositionInPattern(point.getStop())
    );
  }

  private List<TPoint> findTPointForRoute(
    Route route,
    ToIntFunction<TripPattern> resolveStopPosInPattern
  ) {
    var patterns = patternsByRoute.get(route);

    // A route should have a pattern(trip), but it does not hurt to check here
    if (patterns == null) {
      return List.of();
    }

    var points = new ArrayList<TPoint>();

    for (var pattern : patterns) {
      int stopPosInPattern = resolveStopPosInPattern.applyAsInt(pattern.getPattern());
      // stopPosInPattern == -1 means stop is not on pattern
      if (stopPosInPattern >= 0) {
        int stopIndex = pattern.stopIndex(stopPosInPattern);
        var sourcePoint = TransferPointForPatternFactory.createTransferPointForPattern(
          route,
          stopIndex
        );
        points.add(new TPoint(pattern, sourcePoint, null, stopPosInPattern));
      }
    }
    return points;
  }

  private List<TPoint> findTPoints(TripTransferPoint point) {
    var trip = point.getTrip();

    // All trips have at least one pattern, no need to check for null here
    var patterns = patternsByTrip.get(trip);
    var patternsByRealtimeOrScheduled = patterns
      .stream()
      .collect(
        Collectors.groupingBy(pattern -> pattern.getPattern().isStopPatternModifiedInRealTime())
      );

    // Process first the pattern for which stopPosInPattern was calculated for
    List<RoutingTripPattern> scheduledPatterns = patternsByRealtimeOrScheduled.get(Boolean.FALSE);
    if (scheduledPatterns == null || scheduledPatterns.size() != 1) {
      LOG.warn(
        "Trip {} does not have exactly one scheduled trip pattern, found: {}. " +
          "Skipping transfer generation.",
        trip,
        scheduledPatterns
      );
      return List.of();
    }

    int stopPosInPattern = point.getStopPositionInPattern();
    var scheduledPattern = scheduledPatterns.get(0);
    int stopIndex = scheduledPattern.stopIndex(stopPosInPattern);
    var sourcePoint = TransferPointForPatternFactory.createTransferPointForPattern(trip, stopIndex);
    TPoint scheduledPoint = new TPoint(scheduledPattern, sourcePoint, trip, stopPosInPattern);

    // Return early if only scheduled pattern exists
    var realtimePatterns = patternsByRealtimeOrScheduled.get(Boolean.TRUE);
    if (realtimePatterns == null || realtimePatterns.isEmpty()) {
      return List.of(scheduledPoint);
    }

    // Process the other patterns based on the scheduled pattern
    StopLocation scheduledStop = scheduledPattern.getPattern().getStop(stopPosInPattern);

    List<TPoint> res = new ArrayList<>();
    res.add(scheduledPoint);
    for (RoutingTripPattern pattern : realtimePatterns) {
      // Check if the same stop or its sibling is at the same position, if yes, generate a transfer point
      if (stopPosInPattern < pattern.numberOfStopsInPattern()) {
        StopLocation stop = pattern.getPattern().getStop(stopPosInPattern);
        if (stop.equals(scheduledStop) || stop.isPartOfSameStationAs(scheduledStop)) {
          res.add(
            new TPoint(
              pattern,
              TransferPointForPatternFactory.createTransferPointForPattern(
                trip,
                pattern.stopIndex(stopPosInPattern)
              ),
              trip,
              stopPosInPattern
            )
          );
          continue;
        }
      }
      LOG.info(
        "Updated pattern for trip {}, does not match original for stop {} at pos {}. " +
          "Skipping transfer generation.",
        trip,
        scheduledStop,
        stopPosInPattern
      );
      // TODO - Find the expected stop in the new pattern, as its position in the pattern has been
      //  shifted due to added or removed (not just cancelled) stops in the realtime pattern.
    }

    return List.copyOf(res);
  }

  private static class TPoint {

    RoutingTripPattern pattern;
    TransferPointMatcher sourcePoint;
    Trip trip;
    int stopPosition;

    private TPoint(
      RoutingTripPattern pattern,
      TransferPointMatcher sourcePoint,
      Trip trip,
      int stopPosition
    ) {
      this.pattern = pattern;
      this.sourcePoint = sourcePoint;
      this.trip = trip;
      this.stopPosition = stopPosition;
    }

    @Override
    public int hashCode() {
      return Objects.hash(pattern, trip, stopPosition);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TPoint tPoint)) {
        return false;
      }
      return (
        stopPosition == tPoint.stopPosition &&
        Objects.equals(pattern, tPoint.pattern) &&
        Objects.equals(trip, tPoint.trip)
      );
    }

    boolean canBoard() {
      // We prevent boarding at the last stop, this might be enforced by the
      // canBoard method, but we do not trust it here.
      int lastStopPosition = pattern.numberOfStopsInPattern() - 1;
      return stopPosition != lastStopPosition && pattern.boardingPossibleAt(stopPosition);
    }

    boolean canAlight() {
      // We prevent alighting at the first stop, this might be enforced by the
      // canAlight method, but we do not trust it here.
      return stopPosition != 0 && pattern.alightingPossibleAt(stopPosition);
    }

    /** Add the entry of a forward search for a transfer from the given source to this point. */
    void addForwardTransfer(
      ConstrainedTransfer tx,
      TransferPointMatcher fromSourcePoint,
      TransferForPatternByStopPos[] forwardTransfers
    ) {
      if (forwardTransfers[pattern.patternIndex()] == null) {
        forwardTransfers[pattern.patternIndex()] = new TransferForPatternByStopPos();
      }
      forwardTransfers[pattern.patternIndex()].add(
        stopPosition,
        new TransferForPattern(
          fromSourcePoint,
          trip,
          tx.getSpecificityRanking(),
          tx.getTransferConstraint()
        )
      );
    }

    /** Add the entry of a reverse search for a transfer from this point to the given target. */
    void addReverseTransfer(
      ConstrainedTransfer tx,
      TransferPointMatcher toSourcePoint,
      TransferForPatternByStopPos[] reverseTransfers
    ) {
      if (reverseTransfers[pattern.patternIndex()] == null) {
        reverseTransfers[pattern.patternIndex()] = new TransferForPatternByStopPos();
      }
      reverseTransfers[pattern.patternIndex()].add(
        stopPosition,
        new TransferForPattern(
          toSourcePoint,
          trip,
          tx.getSpecificityRanking(),
          tx.getTransferConstraint()
        )
      );
    }
  }
}
