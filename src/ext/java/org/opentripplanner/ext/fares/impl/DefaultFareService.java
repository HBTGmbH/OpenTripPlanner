package org.opentripplanner.ext.fares.impl;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.opentripplanner.ext.fares.model.FareAttribute;
import org.opentripplanner.ext.fares.model.FareRuleSet;
import org.opentripplanner.ext.flex.FlexibleTransitLeg;
import org.opentripplanner.model.fare.ItineraryFares;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.ScheduledTransitLeg;
import org.opentripplanner.routing.core.FareComponent;
import org.opentripplanner.routing.core.FareType;
import org.opentripplanner.routing.fares.FareService;
import org.opentripplanner.transit.model.basic.Money;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.site.FareZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds information for doing the graph search on fares */
class FareSearch {

  // Cell [i,j] holds the best (lowest) cost for a trip from rides[i] to rides[j]
  float[][] resultTable;

  // Cell [i,j] holds the index of the ride to pass through for the best cost
  // This is used for reconstructing which rides are grouped together
  int[][] next;

  // Cell [i,j] holds the id of the fare that corresponds to the relevant cost
  // we can't just use FareAndId for resultTable because you need to sum them
  FeedScopedId[][] fareIds;

  // Cell [i] holds the index of the last ride that ride[i] has a fare to
  // If it's -1, the ride does not have fares to anywhere
  int[] endOfComponent;

  FareSearch(int size) {
    resultTable = new float[size][size];
    next = new int[size][size];
    fareIds = new FeedScopedId[size][size];
    endOfComponent = new int[size];
    Arrays.fill(endOfComponent, -1);
  }
}

/** Holds fare and corresponding fareId */
record FareAndId(Money fare, FeedScopedId fareId) {}

/**
 * This fare service module handles GTFS fares in multiple feeds separately so that each fare attribute
 * is only applicable for legs that operated by an agency within the same feed. Interfeed transfer rules
 * are not considered in this fare service and for those situations you get to implement your own Fare Service
 * See this thread on gtfs-changes explaining the proper interpretation of
 * fares.txt:
 * http://groups.google.com/group/gtfs-changes/browse_thread/thread/8a4a48ae1e742517/4f81b826cb732f3b
 */
public class DefaultFareService implements FareService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultFareService.class);

  private final float UNKNOWN_FARE_PRICE = -0.01f;

  /** For each fare type (regular, student, etc...) the collection of rules that apply. */
  protected Map<FareType, Collection<FareRuleSet>> fareRulesPerType;

  public DefaultFareService() {
    fareRulesPerType = new HashMap<>();
  }

  public void addFareRules(FareType fareType, Collection<FareRuleSet> fareRules) {
    fareRulesPerType.put(fareType, new ArrayList<>(fareRules));
  }

  public Map<FareType, Collection<FareRuleSet>> getFareRulesPerType() {
    return fareRulesPerType;
  }

  @Override
  public ItineraryFares calculateFares(Itinerary itinerary) {
    var fareLegs = itinerary
      .getLegs()
      .stream()
      .filter(l -> l instanceof ScheduledTransitLeg || l instanceof FlexibleTransitLeg)
      .map(Leg.class::cast)
      .toList();

    fareLegs = combineInterlinedLegs(fareLegs);

    // If there are no rides, there's no fare.
    if (fareLegs.isEmpty()) {
      return null;
    }
    var fareLegsByFeed = fareLegs
      .stream()
      .collect(Collectors.groupingBy(leg -> leg.getAgency().getId().getFeedId()));
    var fareRulesByTypeAndFeed = fareRulesPerType
      .entrySet()
      .stream()
      .collect(
        Collectors.toMap(
          Map.Entry::getKey,
          rules ->
            rules
              .getValue()
              .stream()
              .collect(Collectors.groupingBy(rule -> rule.getFareAttribute().getId().getFeedId()))
        )
      );

    ItineraryFares fare = ItineraryFares.empty();
    boolean hasFare = false;
    for (FareType fareType : fareRulesPerType.keySet()) {
      List<FareComponent> components = new ArrayList<>();
      List<Money> fares = new ArrayList<>();
      ItineraryFares currentFare = ItineraryFares.empty();
      boolean legWithoutRulesFound = false;
      boolean legsWithoutMatchingRulesFound = false;
      for (String feedId : fareLegsByFeed.keySet()) {
        var fareRules = fareRulesByTypeAndFeed.get(fareType).get(feedId);

        // Get the currency from the first fareAttribute, assuming that all tickets use the same currency.
        if (fareRules != null && fareRules.size() > 0) {
          Currency currency = Currency.getInstance(
            fareRules.iterator().next().getFareAttribute().getCurrencyType()
          );
          boolean feedHasFare = false;
          feedHasFare =
            populateFare(currentFare, currency, fareType, fareLegsByFeed.get(feedId), fareRules);

          if (!feedHasFare) legsWithoutMatchingRulesFound = true;
          hasFare = feedHasFare || hasFare; // Other feeds might still have fare for some legs

          components.addAll(currentFare.getComponents(fareType));
          fare.addFare(fareType, currentFare.getFare(fareType));
          fares.add(currentFare.getFare(fareType));

          // If all the legs are from one feed, consider itinerary products
          if (fareLegs.equals(fareLegsByFeed.get(feedId))) fare.addItineraryProducts(
            currentFare.getItineraryProducts()
          );
        } else {
          legWithoutRulesFound = true;
        }
      }

      fare.addFareComponent(fareType, components);

      // No fares will be discovered after this point
      if (!hasFare) {
        return null;
      }

      // Accumulate the final price of the fare or indicate that no final fare could be found
      if (legWithoutRulesFound || legsWithoutMatchingRulesFound) {
        fare.addFare(
          fareType,
          Money.ofFractionalAmount(fares.get(0).currency(), UNKNOWN_FARE_PRICE)
        );
      } else {
        fare.addFare(
          fareType,
          fares
            .stream()
            .reduce(
              Money.ofFractionalAmount(fare.getFare(fareType).currency(), 0),
              (r1, r2) -> r1.plus(r2)
            )
        );
      }
    }
    return fare;
  }

  /**
   * Builds the Fare object for the given currency, fareType and fareRules.
   * <p>
   * Besides calculating the lowest fare, we also break down the fare and which routes correspond to
   * which components. Note that even if we cannot get a lowest fare (if some rides don't have fare
   * rules), there will still be a breakdown for those parts which have fares.
   * <p>
   * As an example, given the rides A-B and B-C. Where A-B and B-C have fares of 10 each, 2 fare
   * detail objects are added, one with fare 10 for A-B and one with fare 10 for B-C.
   * <p>
   * If we add the rule for A-C with a fare of 15, we will get 1 fare detail object with fare 15,
   * which lists both A-B and B-C as routes involved.
   * <p>
   * If our only rule were A-B with a fare of 10, we would have no lowest fare, but we will still
   * have one fare detail with fare 10 for the route A-B. B-C will not just not be listed at all.
   */
  protected boolean populateFare(
    ItineraryFares fare,
    Currency currency,
    FareType fareType,
    List<Leg> legs,
    Collection<FareRuleSet> fareRules
  ) {
    FareSearch r = performSearch(fareType, legs, fareRules);

    List<FareComponent> components = new ArrayList<>();
    int count = 0;
    int start = 0;
    int end = legs.size() - 1;
    while (start <= end) {
      // skip parts where no fare is present, we want to return something
      // even if not all legs have fares
      while (start <= end && r.endOfComponent[start] < 0) {
        ++start;
      }
      if (start > end) {
        break;
      }

      int via = r.next[start][r.endOfComponent[start]];
      float cost = r.resultTable[start][via];
      FeedScopedId fareId = r.fareIds[start][via];

      var componentLegs = new ArrayList<Leg>();
      for (int i = start; i <= via; ++i) {
        componentLegs.add(legs.get(i));
      }
      components.add(
        new FareComponent(fareId, Money.ofFractionalAmount(currency, cost), componentLegs)
      );
      ++count;
      start = via + 1;
    }

    var amount = r.resultTable[0][legs.size() - 1];
    fare.addFare(fareType, Money.ofFractionalAmount(currency, amount));
    fare.addFareComponent(fareType, components);
    return count > 0;
  }

  protected Optional<Money> calculateCost(
    FareType fareType,
    List<Leg> rides,
    Collection<FareRuleSet> fareRules
  ) {
    return getBestFareAndId(fareType, rides, fareRules).map(FareAndId::fare);
  }

  protected Optional<FareAndId> getBestFareAndId(
    FareType fareType,
    List<Leg> legs,
    Collection<FareRuleSet> fareRules
  ) {
    Set<String> zones = new HashSet<>();
    Set<FeedScopedId> routes = new HashSet<>();
    Set<FeedScopedId> trips = new HashSet<>();
    int transfersUsed = -1;

    var firstRide = legs.get(0);
    ZonedDateTime startTime = firstRide.getStartTime();
    String startZone = firstRide.getFrom().stop.getFirstZoneAsString();
    String endZone = null;
    // stops don't really have an agency id, they have the per-feed default id
    String feedId = firstRide.getAgency().getId().getFeedId(); //getTrip().getId().getFeedId();
    ZonedDateTime lastRideStartTime = null;
    ZonedDateTime lastRideEndTime = null;
    for (var leg : legs) {
      if (!leg.getAgency().getId().getFeedId().equals(feedId)) {
        LOG.debug("skipped multi-feed ride sequence {}", legs);
        return Optional.empty();
      }
      lastRideStartTime = leg.getStartTime();
      lastRideEndTime = leg.getEndTime();
      endZone = leg.getTo().stop.getFirstZoneAsString();
      routes.add(leg.getRoute().getId());
      trips.add(leg.getTrip().getId());
      for (FareZone z : leg.getFareZones()) {
        zones.add(z.getId().getId());
      }
      transfersUsed += 1;
    }

    @Nullable
    FareAttribute bestAttribute = null;
    @Nullable
    Money bestFare = null;
    Duration tripTime = Duration.between(startTime, lastRideStartTime);
    Duration journeyTime = Duration.between(startTime, lastRideEndTime);

    // find the best fare that matches this set of rides
    for (FareRuleSet ruleSet : fareRules) {
      FareAttribute attribute = ruleSet.getFareAttribute();
      // fares also don't really have an agency id, they will have the per-feed default id
      // check only if the fare is not mapped to an agency
      if (!attribute.getId().getFeedId().equals(feedId)) continue;

      if (
        ruleSet.matches(
          startZone,
          endZone,
          zones,
          routes,
          trips,
          transfersUsed,
          tripTime,
          journeyTime
        )
      ) {
        Money newFare = getFarePrice(attribute, fareType);
        if (bestFare == null || newFare.lessThan(bestFare)) {
          bestAttribute = attribute;
          bestFare = newFare;
        }
      }
    }
    LOG.debug("{} best for {}", bestAttribute, legs);
    Money finalBestFare = bestFare;
    return Optional
      .ofNullable(bestAttribute)
      .map(attribute -> new FareAndId(finalBestFare, attribute.getId()));
  }

  protected Money getFarePrice(FareAttribute fare, FareType type) {
    var currency = Currency.getInstance(fare.getCurrencyType());
    return switch (type) {
      case senior:
        if (fare.getSeniorPrice() >= 0) {
          yield Money.ofFractionalAmount(currency, fare.getSeniorPrice());
        }
      case youth:
        if (fare.getYouthPrice() >= 0) {
          yield Money.ofFractionalAmount(currency, fare.getYouthPrice());
        }
      default:
        yield Money.ofFractionalAmount(currency, fare.getPrice());
    };
  }

  /**
   * Returns true if two interlined legs (those with a stay-seated transfer between them) should be
   * treated as a single leg.
   * <p>
   * By default it's disabled since this is unspecified in the GTFS fares spec.
   *
   * @see DefaultFareService#combineInterlinedLegs(List)
   * @see HighestFareInFreeTransferWindowFareService#shouldCombineInterlinedLegs(ScheduledTransitLeg, ScheduledTransitLeg)
   */
  protected boolean shouldCombineInterlinedLegs(
    ScheduledTransitLeg previousLeg,
    ScheduledTransitLeg currentLeg
  ) {
    return false;
  }

  /**
   * This operation is quite poorly defined: - Should the combined leg have the properties of the
   * first or the second leg? - What are the indices of the start/end stops?
   * <p>
   * For this reason it's best to only activate this feature when you really need it.
   */
  private List<Leg> combineInterlinedLegs(List<Leg> fareLegs) {
    var result = new ArrayList<Leg>();
    for (var leg : fareLegs) {
      if (
        leg.isInterlinedWithPreviousLeg() &&
        leg instanceof ScheduledTransitLeg currentLeg &&
        result.get(result.size() - 1) instanceof ScheduledTransitLeg previousLeg &&
        shouldCombineInterlinedLegs(previousLeg, currentLeg)
      ) {
        var combinedLeg = new CombinedInterlinedTransitLeg(previousLeg, currentLeg);
        // overwrite the previous leg with the combined one
        result.set(result.size() - 1, combinedLeg);
      } else {
        result.add(leg);
      }
    }
    return result;
  }

  private FareSearch performSearch(
    FareType fareType,
    List<Leg> rides,
    Collection<FareRuleSet> fareRules
  ) {
    FareSearch r = new FareSearch(rides.size());

    // Dynamic algorithm to calculate fare cost.
    // This is a modified Floyd-Warshall algorithm, a key thing to remember is that
    // rides are already edges, so when comparing "via" routes, i -> k is connected
    // to k+1 -> j.
    for (int i = 0; i < rides.size(); i++) {
      // each diagonal
      for (int j = 0; j < rides.size() - i; j++) {
        Optional<FareAndId> best = getBestFareAndId(
          fareType,
          rides.subList(j, j + i + 1),
          fareRules
        );
        float cost = best
          .map(b -> b.fare().fractionalAmount().floatValue())
          .orElse(Float.POSITIVE_INFINITY);
        if (cost < 0) {
          LOG.error("negative cost for a ride sequence");
          cost = Float.POSITIVE_INFINITY;
        }
        if (cost < Float.POSITIVE_INFINITY) {
          r.endOfComponent[j] = j + i;
          r.next[j][j + i] = j + i;
        }
        r.resultTable[j][j + i] = cost;
        r.fareIds[j][j + i] = best.map(FareAndId::fareId).orElse(null);
        for (int k = 0; k < i; k++) {
          float via = r.resultTable[j][j + k] + r.resultTable[j + k + 1][j + i];
          if (r.resultTable[j][j + i] > via) {
            r.resultTable[j][j + i] = via;
            r.endOfComponent[j] = j + i;
            r.next[j][j + i] = r.next[j][j + k];
          }
        }
      }
    }
    return r;
  }
}
