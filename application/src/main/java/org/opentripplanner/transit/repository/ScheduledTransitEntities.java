package org.opentripplanner.transit.repository;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.flex.trip.FlexTrip;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripIdAndServiceDate;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;

/**
 * The scheduled (non-realtime) routes, trips, trip patterns and flex trips of the public
 * transportation network.
 * <p>
 * During graph build this instance is populated incrementally (one pattern/trip/flex-trip at a
 * time) via {@link #addTripPattern}, {@link #addTripOnServiceDate} and {@link #addFlexTrip}. Once
 * the graph is fully built, {@link #index()} builds the derived lookups ({@link #getRouteForId},
 * {@link #getPatternForTrip}, {@link #getPatternsForRoute}, etc.) that let {@code
 * DefaultTimetableRepository} resolve entities by id, trip or route with a fallback to scheduled
 * data. Shared unchanged by every snapshot produced from the same buffer, since scheduled data
 * never changes at runtime.
 */
public class ScheduledTransitEntities {

  private final Map<FeedScopedId, TripPattern> tripPatternForId = new HashMap<>();
  private final Map<FeedScopedId, TripOnServiceDate> tripOnServiceDateById = new HashMap<>();
  private final ListMultimap<FeedScopedId, TripOnServiceDate> replacedByTripOnServiceDates =
    ArrayListMultimap.create();
  private final Map<FeedScopedId, FlexTrip<?, ?>> flexTripForId = new HashMap<>();

  private final Map<FeedScopedId, Route> routeForId = new HashMap<>();
  private final Map<FeedScopedId, Trip> tripForId = new HashMap<>();
  private final Map<Trip, TripPattern> patternForTrip = new HashMap<>();
  private final Multimap<Route, TripPattern> patternsForRoute = ArrayListMultimap.create();
  private final Map<TripIdAndServiceDate, TripOnServiceDate> tripOnServiceDateForTripAndDay =
    new HashMap<>();

  private boolean indexed = false;

  /** Creates an empty, mutable instance to be populated during graph build. */
  public ScheduledTransitEntities() {}

  public void addTripPattern(FeedScopedId id, TripPattern tripPattern) {
    tripPatternForId.put(id, tripPattern);
  }

  @Nullable
  public TripPattern getTripPatternForId(FeedScopedId id) {
    return tripPatternForId.get(id);
  }

  public Collection<TripPattern> getAllTripPatterns() {
    return Collections.unmodifiableCollection(tripPatternForId.values());
  }

  public void addTripOnServiceDate(TripOnServiceDate tripOnServiceDate) {
    tripOnServiceDateById.put(tripOnServiceDate.getId(), tripOnServiceDate);
    for (var replacementFor : tripOnServiceDate.getReplacementFor()) {
      replacedByTripOnServiceDates.put(replacementFor.getId(), tripOnServiceDate);
    }
  }

  @Nullable
  public TripOnServiceDate getTripOnServiceDateById(FeedScopedId id) {
    return tripOnServiceDateById.get(id);
  }

  public Collection<TripOnServiceDate> getAllTripsOnServiceDate() {
    return Collections.unmodifiableCollection(tripOnServiceDateById.values());
  }

  public List<TripOnServiceDate> getReplacedByTripOnServiceDate(FeedScopedId id) {
    return replacedByTripOnServiceDates.get(id);
  }

  public void addFlexTrip(FeedScopedId id, FlexTrip<?, ?> flexTrip) {
    flexTripForId.put(id, flexTrip);
  }

  @Nullable
  public FlexTrip<?, ?> getFlexTrip(FeedScopedId id) {
    return flexTripForId.get(id);
  }

  public Collection<FlexTrip<?, ?>> getAllFlexTrips() {
    return Collections.unmodifiableCollection(flexTripForId.values());
  }

  public boolean hasFlexTrips() {
    return !flexTripForId.isEmpty();
  }

  /**
   * Builds the derived lookups (by route, by trip, by trip-and-service-date) from the raw
   * scheduled data added so far. Must be called once, after graph build has finished adding all
   * trip patterns, trips-on-service-date and flex trips.
   */
  public void index() {
    if (indexed) {
      return;
    }
    for (TripPattern pattern : tripPatternForId.values()) {
      patternsForRoute.put(pattern.getRoute(), pattern);
      pattern.scheduledTripsAsStream().forEach(trip -> {
        patternForTrip.put(trip, pattern);
        tripForId.put(trip.getId(), trip);
      });
    }
    for (Route route : patternsForRoute.asMap().keySet()) {
      routeForId.put(route.getId(), route);
    }
    for (TripOnServiceDate tripOnServiceDate : tripOnServiceDateById.values()) {
      tripOnServiceDateForTripAndDay.put(
        new TripIdAndServiceDate(
          tripOnServiceDate.getTrip().getId(),
          tripOnServiceDate.getServiceDate()
        ),
        tripOnServiceDate
      );
    }
    // Flex trips/routes are folded into the same indexes as regular trips/routes, matching
    // TransitRepositoryIndex's behavior, so getRoute/getTrip/listRoutes/listTrips/containsTrip
    // keep including them.
    for (FlexTrip<?, ?> flexTrip : flexTripForId.values()) {
      Route route = flexTrip.getTrip().getRoute();
      routeForId.put(route.getId(), route);
      tripForId.put(flexTrip.getTrip().getId(), flexTrip.getTrip());
    }
    indexed = true;
  }

  @Nullable
  Route getRouteForId(FeedScopedId id) {
    return routeForId.get(id);
  }

  Collection<Route> getAllRoutes() {
    return Collections.unmodifiableCollection(routeForId.values());
  }

  @Nullable
  Trip getTripForId(FeedScopedId id) {
    return tripForId.get(id);
  }

  Collection<Trip> getAllTrips() {
    return Collections.unmodifiableCollection(tripForId.values());
  }

  boolean containsTrip(FeedScopedId id) {
    return tripForId.containsKey(id);
  }

  @Nullable
  TripPattern getPatternForTrip(Trip trip) {
    return patternForTrip.get(trip);
  }

  Collection<TripPattern> getPatternsForRoute(Route route) {
    return Collections.unmodifiableCollection(patternsForRoute.get(route));
  }

  @Nullable
  TripOnServiceDate getTripOnServiceDateForTripAndDay(TripIdAndServiceDate tripIdAndServiceDate) {
    return tripOnServiceDateForTripAndDay.get(tripIdAndServiceDate);
  }
}
