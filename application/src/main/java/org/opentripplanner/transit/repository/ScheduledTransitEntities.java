package org.opentripplanner.transit.repository;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
 * Indexed access to the scheduled (non-realtime) routes, trips and trip patterns needed to
 * resolve entities by id with a fallback to scheduled data. Built once, from a subset of {@code
 * TransitRepository}'s public accessors, and shared unchanged by every snapshot produced from
 * the same buffer, since scheduled data never changes at runtime.
 */
class ScheduledTransitEntities {

  private final Map<FeedScopedId, Route> routeForId = new HashMap<>();
  private final Map<FeedScopedId, Trip> tripForId = new HashMap<>();
  private final Map<Trip, TripPattern> patternForTrip = new HashMap<>();
  private final Multimap<Route, TripPattern> patternsForRoute = ArrayListMultimap.create();
  private final Map<FeedScopedId, TripOnServiceDate> tripOnServiceDateById = new HashMap<>();
  private final Map<TripIdAndServiceDate, TripOnServiceDate> tripOnServiceDateForTripAndDay =
    new HashMap<>();

  ScheduledTransitEntities(
    Collection<TripPattern> scheduledTripPatterns,
    Collection<TripOnServiceDate> scheduledTripsOnServiceDate,
    Collection<FlexTrip<?, ?>> scheduledFlexTrips
  ) {
    for (TripPattern pattern : scheduledTripPatterns) {
      patternsForRoute.put(pattern.getRoute(), pattern);
      pattern.scheduledTripsAsStream().forEach(trip -> {
        patternForTrip.put(trip, pattern);
        tripForId.put(trip.getId(), trip);
      });
    }
    for (Route route : patternsForRoute.asMap().keySet()) {
      routeForId.put(route.getId(), route);
    }
    for (TripOnServiceDate tripOnServiceDate : scheduledTripsOnServiceDate) {
      tripOnServiceDateById.put(tripOnServiceDate.getId(), tripOnServiceDate);
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
    for (FlexTrip<?, ?> flexTrip : scheduledFlexTrips) {
      Route route = flexTrip.getTrip().getRoute();
      routeForId.put(route.getId(), route);
      tripForId.put(flexTrip.getTrip().getId(), flexTrip.getTrip());
    }
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
  TripOnServiceDate getTripOnServiceDateById(FeedScopedId id) {
    return tripOnServiceDateById.get(id);
  }

  @Nullable
  TripOnServiceDate getTripOnServiceDateForTripAndDay(TripIdAndServiceDate tripIdAndServiceDate) {
    return tripOnServiceDateForTripAndDay.get(tripIdAndServiceDate);
  }

  Collection<TripOnServiceDate> getAllTripsOnServiceDate() {
    return Collections.unmodifiableCollection(tripOnServiceDateById.values());
  }
}
