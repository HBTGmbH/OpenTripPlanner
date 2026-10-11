package org.opentripplanner.ext.netexprofile.vdv462;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.index.api.NetexEntityIndexReadOnlyView;
import org.rutebanken.netex.model.JourneyPatternWaitTime;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.JourneyRunTime;
import org.rutebanken.netex.model.JourneyRunTimes_RelStructure;
import org.rutebanken.netex.model.LinkInLinkSequence_VersionedChildStructure;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.rutebanken.netex.model.StopPointInJourneyPatternRefStructure;
import org.rutebanken.netex.model.TimeDemandTypeRefStructure;
import org.rutebanken.netex.model.TimetabledPassingTime;
import org.rutebanken.netex.model.TimetabledPassingTimes_RelStructure;
import org.rutebanken.netex.model.TimingLinkInJourneyPattern_VersionedChildStructure;
import org.rutebanken.netex.model.TimingLinkRefStructure;
import org.rutebanken.netex.model.VehicleJourneyRunTime;
import org.rutebanken.netex.model.VehicleJourneyWaitTime;

/**
 * Derive the passing times of service journeys from the timing model of the German VDV 462
 * profile.
 * <p>
 * VDV 462 (chapters 13 and 15) does not exchange passing times. A journey has a departure time at
 * its first stop and a time demand type (travel time type, e.g. peak or off-peak). The run time
 * between two stop points is given on the link of the journey pattern
 * ({@code linksInSequence/…/runTimes/JourneyRunTime}) per time demand type, and the wait time at a
 * stop point on the stop point in journey pattern ({@code waitTimes/JourneyPatternWaitTime}) per
 * time demand type. A journey may replace the run time of a timing link ({@code runTimes}) and the
 * wait time at a stop point ({@code waitTimes}). There is no wait time at the first and the last
 * stop point; a missing wait time is zero.
 * <p>
 * The importer requires passing times, so they are derived before the data is validated: the
 * arrival at a stop point is the departure from the previous one plus the run time of the link
 * between them, and the departure is the arrival plus the wait time. If a run time is missing, no
 * passing times are derived and the journey is discarded by the validation as before.
 */
public final class TimingModelPassingTimes {

  private static final ObjectFactory OBJECT_FACTORY = new ObjectFactory();
  private static final int SECONDS_PER_DAY = 24 * 60 * 60;

  private final NetexEntityIndexReadOnlyView index;
  private final DataImportIssueStore issueStore;

  private TimingModelPassingTimes(
    NetexEntityIndexReadOnlyView index,
    DataImportIssueStore issueStore
  ) {
    this.index = index;
    this.issueStore = issueStore;
  }

  /**
   * Derive the passing times of the service journeys of the current file level which have none.
   */
  public static void derive(NetexEntityIndexReadOnlyView index, DataImportIssueStore issueStore) {
    new TimingModelPassingTimes(index, issueStore).deriveForLocalServiceJourneys();
  }

  private void deriveForLocalServiceJourneys() {
    int derived = 0;
    for (ServiceJourney serviceJourney : index.getServiceJourneyById().localValues()) {
      if (serviceJourney.getPassingTimes() != null) {
        continue;
      }
      var passingTimes = derive(serviceJourney);
      if (passingTimes != null) {
        serviceJourney.setPassingTimes(
          new TimetabledPassingTimes_RelStructure().withTimetabledPassingTime(passingTimes)
        );
        derived++;
      }
    }
    if (derived > 0) {
      issueStore.add(
        "PassingTimesDerivedFromTimingModel",
        "The passing times of %d service journeys are derived from run times and wait times.",
        derived
      );
    }
  }

  @Nullable
  private List<TimetabledPassingTime> derive(ServiceJourney serviceJourney) {
    if (
      serviceJourney.getDepartureTime() == null || serviceJourney.getJourneyPatternRef() == null
    ) {
      return failed(serviceJourney, "it has no departure time or journey pattern");
    }
    var journeyPattern = index
      .getJourneyPatternsById()
      .lookup(serviceJourney.getJourneyPatternRef().getValue().getRef());
    if (journeyPattern == null || journeyPattern.getPointsInSequence() == null) {
      return failed(serviceJourney, "its journey pattern is unknown or has no stop points");
    }
    var stopPoints = stopPoints(journeyPattern);
    var links = links(journeyPattern);
    if (stopPoints.size() < 2 || links.size() != stopPoints.size() - 1) {
      return failed(
        serviceJourney,
        "its journey pattern has " +
          stopPoints.size() +
          " stop points, but " +
          links.size() +
          " links"
      );
    }
    var timeDemandTypeId = Optional.ofNullable(serviceJourney.getTimeDemandTypeRef())
      .map(TimeDemandTypeRefStructure::getRef)
      .orElse(null);

    var result = new ArrayList<TimetabledPassingTime>();
    long time = serviceJourney.getDepartureTime().toSecondOfDay() + dayOffset(serviceJourney);
    for (int i = 0; i < stopPoints.size(); i++) {
      var stopPoint = stopPoints.get(i);
      var passingTime = new TimetabledPassingTime().withPointInJourneyPatternRef(
        OBJECT_FACTORY.createStopPointInJourneyPatternRef(
          new StopPointInJourneyPatternRefStructure().withRef(stopPoint.getId())
        )
      );
      if (i > 0) {
        var runTime = runTime(serviceJourney, links.get(i - 1), timeDemandTypeId);
        if (runTime == null) {
          return failed(
            serviceJourney,
            "the run time to stop point " + stopPoint.getId() + " is missing"
          );
        }
        time += runTime.toSeconds();
        setArrival(passingTime, time);
      }
      if (i < stopPoints.size() - 1) {
        if (i > 0) {
          time += waitTime(serviceJourney, stopPoint, timeDemandTypeId).toSeconds();
        }
        setDeparture(passingTime, time);
      }
      result.add(passingTime);
    }
    return result;
  }

  @Nullable
  private Duration runTime(
    ServiceJourney serviceJourney,
    LinkInLinkSequence_VersionedChildStructure link,
    @Nullable String timeDemandTypeId
  ) {
    var timingLinkId = Optional.ofNullable(timingLinkRef(link))
      .map(TimingLinkRefStructure::getRef)
      .orElse(null);
    if (timingLinkId != null && serviceJourney.getRunTimes() != null) {
      for (VehicleJourneyRunTime it : serviceJourney.getRunTimes().getVehicleJourneyRunTime()) {
        if (it.getTimingLinkRef() != null && timingLinkId.equals(it.getTimingLinkRef().getRef())) {
          return it.getRunTime();
        }
      }
    }
    var runTimes = runTimes(link);
    if (runTimes == null) {
      return null;
    }
    return forTimeDemandType(
      runTimes.getJourneyRunTime(),
      JourneyRunTime::getTimeDemandTypeRef,
      JourneyRunTime::getRunTime,
      timeDemandTypeId
    );
  }

  private static Duration waitTime(
    ServiceJourney serviceJourney,
    StopPointInJourneyPattern stopPoint,
    @Nullable String timeDemandTypeId
  ) {
    var stopPointRef =
      stopPoint.getScheduledStopPointRef() == null
        ? null
        : stopPoint.getScheduledStopPointRef().getValue().getRef();
    if (stopPointRef != null && serviceJourney.getWaitTimes() != null) {
      for (VehicleJourneyWaitTime it : serviceJourney.getWaitTimes().getVehicleJourneyWaitTime()) {
        if (
          it.getTimingPointRef() != null &&
          stopPointRef.equals(it.getTimingPointRef().getValue().getRef())
        ) {
          return it.getWaitTime();
        }
      }
    }
    if (stopPoint.getWaitTimes() != null) {
      var waitTimes = stopPoint
        .getWaitTimes()
        .getJourneyPatternWaitTimeRefOrJourneyPatternWaitTime()
        .stream()
        .filter(JourneyPatternWaitTime.class::isInstance)
        .map(JourneyPatternWaitTime.class::cast)
        .toList();
      var waitTime = forTimeDemandType(
        waitTimes,
        JourneyPatternWaitTime::getTimeDemandTypeRef,
        JourneyPatternWaitTime::getWaitTime,
        timeDemandTypeId
      );
      if (waitTime != null) {
        return waitTime;
      }
    }
    return stopPoint.getWaitTime() == null ? Duration.ZERO : stopPoint.getWaitTime();
  }

  /**
   * The time for the given time demand type. A time without time demand type applies to all, and
   * if the journey has no time demand type, a single given time is used.
   */
  @Nullable
  private static <T> Duration forTimeDemandType(
    List<T> times,
    Function<T, TimeDemandTypeRefStructure> timeDemandType,
    Function<T, Duration> duration,
    @Nullable String timeDemandTypeId
  ) {
    Duration withoutTimeDemandType = null;
    for (T it : times) {
      var ref = timeDemandType.apply(it);
      if (ref == null) {
        withoutTimeDemandType = duration.apply(it);
      } else if (ref.getRef().equals(timeDemandTypeId)) {
        return duration.apply(it);
      }
    }
    if (withoutTimeDemandType != null) {
      return withoutTimeDemandType;
    }
    return timeDemandTypeId == null && times.size() == 1 ? duration.apply(times.getFirst()) : null;
  }

  private static List<StopPointInJourneyPattern> stopPoints(
    JourneyPattern_VersionStructure journeyPattern
  ) {
    var points = journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
      .stream()
      .filter(StopPointInJourneyPattern.class::isInstance)
      .map(StopPointInJourneyPattern.class::cast)
      .toList();
    return sortedByOrder(points, StopPointInJourneyPattern::getOrder);
  }

  private static List<LinkInLinkSequence_VersionedChildStructure> links(
    JourneyPattern_VersionStructure journeyPattern
  ) {
    if (journeyPattern.getLinksInSequence() == null) {
      return List.of();
    }
    var links = journeyPattern
      .getLinksInSequence()
      .getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
    return sortedByOrder(links, LinkInLinkSequence_VersionedChildStructure::getOrder);
  }

  /** Sort by order; elements without order keep their position in the sequence. */
  private static <T> List<T> sortedByOrder(List<T> elements, Function<T, BigInteger> order) {
    if (elements.stream().map(order).anyMatch(Objects::isNull)) {
      return elements;
    }
    return elements.stream().sorted(Comparator.comparing(order)).toList();
  }

  @Nullable
  private static TimingLinkRefStructure timingLinkRef(
    LinkInLinkSequence_VersionedChildStructure link
  ) {
    if (link instanceof ServiceLinkInJourneyPattern_VersionedChildStructure serviceLink) {
      return serviceLink.getTimingLinkRef();
    }
    if (link instanceof TimingLinkInJourneyPattern_VersionedChildStructure timingLink) {
      return timingLink.getTimingLinkRef();
    }
    return null;
  }

  @Nullable
  private static JourneyRunTimes_RelStructure runTimes(
    LinkInLinkSequence_VersionedChildStructure link
  ) {
    if (link instanceof ServiceLinkInJourneyPattern_VersionedChildStructure serviceLink) {
      return serviceLink.getRunTimes();
    }
    if (link instanceof TimingLinkInJourneyPattern_VersionedChildStructure timingLink) {
      return timingLink.getRunTimes();
    }
    return null;
  }

  private static long dayOffset(ServiceJourney serviceJourney) {
    var offset = serviceJourney.getDepartureDayOffset();
    return offset == null ? 0 : offset.longValue() * SECONDS_PER_DAY;
  }

  private static void setArrival(TimetabledPassingTime passingTime, long time) {
    passingTime.setArrivalTime(LocalTime.ofSecondOfDay(time % SECONDS_PER_DAY));
    if (time >= SECONDS_PER_DAY) {
      passingTime.setArrivalDayOffset(BigInteger.valueOf(time / SECONDS_PER_DAY));
    }
  }

  private static void setDeparture(TimetabledPassingTime passingTime, long time) {
    passingTime.setDepartureTime(LocalTime.ofSecondOfDay(time % SECONDS_PER_DAY));
    if (time >= SECONDS_PER_DAY) {
      passingTime.setDepartureDayOffset(BigInteger.valueOf(time / SECONDS_PER_DAY));
    }
  }

  @Nullable
  private List<TimetabledPassingTime> failed(ServiceJourney serviceJourney, String reason) {
    issueStore.add(
      "PassingTimesNotDerived",
      "The passing times of service journey %s can not be derived from the timing model, because %s.",
      serviceJourney.getId(),
      reason
    );
    return null;
  }
}
