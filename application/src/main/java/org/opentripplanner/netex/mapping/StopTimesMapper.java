package org.opentripplanner.netex.mapping;

import static org.opentripplanner.model.PickDrop.COORDINATE_WITH_DRIVER;
import static org.opentripplanner.model.PickDrop.NONE;
import static org.opentripplanner.model.PickDrop.SCHEDULED;

import jakarta.xml.bind.JAXBElement;
import java.math.BigInteger;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMap;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMapById;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.netex.support.JourneyPatternHelper;
import org.opentripplanner.transit.model.framework.ImmutableEntityById;
import org.opentripplanner.transit.model.framework.TransitEntity;
import org.opentripplanner.transit.model.site.AreaStop;
import org.opentripplanner.transit.model.site.GroupStop;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.booking.BookingInfo;
import org.rutebanken.netex.model.DestinationDisplay;
import org.rutebanken.netex.model.DestinationDisplay_VersionStructure;
import org.rutebanken.netex.model.FlexibleLine;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.LineRefStructure;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.PointInLinkSequence_VersionedChildStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.rutebanken.netex.model.TimetabledPassingTime;
import org.rutebanken.netex.model.VersionOfObjectRefStructure;
import org.rutebanken.netex.model.Via_VersionedChildStructure;
import org.rutebanken.netex.model.Vias_RelStructure;

/**
 * This maps a list of TimetabledPassingTimes to a list of StopTimes. It also makes sure the
 * StopTime has a reference to the correct stop. DestinationDisplay is mapped to HeadSign. There is
 * logic to take care of the the fact that DestinationsDisplay is also valid for each subsequent
 * TimeTabledPassingTime, while HeadSign has to be explicitly defined for each StopTime.
 * This class does not take Daylight Saving Time transitions into account, this is an error and
 * should be fixed. See https://github.com/opentripplanner/OpenTripPlanner/issues/5109
 */
class StopTimesMapper {

  private static final int DAY_IN_SECONDS = 3600 * 24;
  private final DataImportIssueStore issueStore;
  private final FeedScopedIdFactory idFactory;

  private final ReadOnlyHierarchicalMap<String, DestinationDisplay> destinationDisplayById;

  private final ImmutableEntityById<Station> stationsById;
  private final ImmutableEntityById<RegularStop> stopsById;

  private final ImmutableEntityById<AreaStop> flexibleStopLocationsById;

  private final ImmutableEntityById<GroupStop> groupStopById;

  private final ReadOnlyHierarchicalMap<String, String> stopPlaceIdByStopPointRef;
  private final ReadOnlyHierarchicalMap<String, String> quayIdByStopPointRef;

  private final ReadOnlyHierarchicalMap<String, String> flexibleStopPlaceIdByStopPointRef;

  private final ReadOnlyHierarchicalMap<String, Route> routeByid;

  private final ReadOnlyHierarchicalMapById<FlexibleLine> flexibleLinesById;
  private final HeadsignMapper headsignMapper;
  private final NetexProfileSupport profileSupport;

  private I18NString currentHeadSign;

  private List<String> currentHeadSignVias;

  StopTimesMapper(
    DataImportIssueStore issueStore,
    FeedScopedIdFactory idFactory,
    ImmutableEntityById<RegularStop> stopsById,
    ImmutableEntityById<Station> stationsById,
    ImmutableEntityById<AreaStop> areaStopById,
    ImmutableEntityById<GroupStop> groupStopById,
    ReadOnlyHierarchicalMap<String, DestinationDisplay> destinationDisplayById,
    ReadOnlyHierarchicalMap<String, String> stopPlaceIdByStopPointRef,
    ReadOnlyHierarchicalMap<String, String> quayIdByStopPointRef,
    ReadOnlyHierarchicalMap<String, String> flexibleStopPlaceIdByStopPointRef,
    ReadOnlyHierarchicalMapById<FlexibleLine> flexibleLinesById,
    ReadOnlyHierarchicalMap<String, Route> routeById,
    NetexProfileSupport profileSupport
  ) {
    this.profileSupport = profileSupport;
    this.issueStore = issueStore;
    this.idFactory = idFactory;
    this.destinationDisplayById = destinationDisplayById;
    this.stopsById = stopsById;
    this.stationsById = stationsById;
    this.flexibleStopLocationsById = areaStopById;
    this.groupStopById = groupStopById;
    this.stopPlaceIdByStopPointRef = stopPlaceIdByStopPointRef;
    this.quayIdByStopPointRef = quayIdByStopPointRef;
    this.flexibleStopPlaceIdByStopPointRef = flexibleStopPlaceIdByStopPointRef;
    this.flexibleLinesById = flexibleLinesById;
    this.routeByid = routeById;
    this.headsignMapper = new HeadsignMapper(issueStore);
  }

  static int calculateOtpTime(LocalTime time, BigInteger dayOffset) {
    int otpTime = time.toSecondOfDay();
    if (dayOffset != null) {
      otpTime += DAY_IN_SECONDS * dayOffset.intValue();
    }
    return otpTime;
  }

  /**
   * @return a map of stop-times indexed by the TimetabledPassingTime id.
   */
  @Nullable
  StopTimesMapperResult mapToStopTimes(
    JourneyPattern_VersionStructure journeyPattern,
    Trip trip,
    List<TimetabledPassingTime> passingTimes,
    ServiceJourney serviceJourney
  ) {
    StopTimesMapperResult result = new StopTimesMapperResult();
    List<String> scheduledStopPointIds = new ArrayList<>();

    var patternDestinationDisplay = findPatternDestinationDisplay(journeyPattern);
    if (patternDestinationDisplay != null) {
      currentHeadSign = headsignMapper.map(patternDestinationDisplay);
      currentHeadSignVias = null;
    }

    for (int i = 0; i < passingTimes.size(); i++) {
      TimetabledPassingTime currentPassingTime = passingTimes.get(i);

      String pointInJourneyPattern = currentPassingTime
        .getPointInJourneyPatternRef()
        .getValue()
        .getRef();

      StopPointInJourneyPattern stopPoint = findStopPoint(pointInJourneyPattern, journeyPattern);

      StopLocation stop = lookUpStopLocation(stopPoint);

      if (stop == null) {
        if (
          stopPoint != null &&
          isFalse(stopPoint.isForAlighting()) &&
          isFalse(stopPoint.isForBoarding())
        ) {
          // A stop point without boarding and alighting is not needed for passengers
          continue;
        }
        if (stopPoint != null) {
          issueStore.add(
            "StopPointInJourneyPatternMissingStopLocation",
            "No Quay, StopPlace or FlexibleStopPlace mapping found for stop point ref %s",
            stopPoint.getScheduledStopPointRef().getValue().getRef()
          );
        }
        issueStore.add(
          "JourneyPatternStopNotFound",
          "Stop with id %s not found for StopPoint %s in JourneyPattern %s. " +
            "Trip %s will not be mapped.",
          stopPoint != null && stopPoint.getScheduledStopPointRef() != null
            ? stopPoint.getScheduledStopPointRef().getValue().getRef()
            : "null",
          stopPoint != null ? stopPoint.getId() : "null",
          journeyPattern.getId(),
          trip.getId()
        );
        return null;
      }

      scheduledStopPointIds.add(stopPoint.getScheduledStopPointRef().getValue().getRef());

      StopTime stopTime = mapToStopTime(trip, stopPoint, stop, currentPassingTime, i);
      if (stopTime == null) {
        return null;
      }

      BookingInfo bookingInfo = new BookingInfoMapper(issueStore).map(
        stopPoint,
        serviceJourney,
        lookUpFlexibleLine(serviceJourney, journeyPattern)
      );
      stopTime.setDropOffBookingInfo(bookingInfo);
      stopTime.setPickupBookingInfo(bookingInfo);

      result.addStopTime(
        StopTimesMapperResult.stopTimeKey(serviceJourney, currentPassingTime),
        stopTime
      );
    }
    result.setScheduledStopPointIds(scheduledStopPointIds);

    return result;
  }

  /**
   * @return a map of stop-times indexed by the TimetabledPassingTime id.
   */
  @Nullable
  String findTripHeadsign(
    JourneyPattern_VersionStructure journeyPattern,
    TimetabledPassingTime firstPassingTime
  ) {
    String pointInJourneyPattern = firstPassingTime
      .getPointInJourneyPatternRef()
      .getValue()
      .getRef();

    var stopPoint = findStopPoint(pointInJourneyPattern, journeyPattern);

    if (stopPoint == null) {
      return null;
    }

    if (stopPoint.getDestinationDisplayRef() == null) {
      var patternDestinationDisplay = findPatternDestinationDisplay(journeyPattern);
      return patternDestinationDisplay == null
        ? null
        : MultilingualStringMapper.nullableValueOf(patternDestinationDisplay.getFrontText());
    }

    var destinationDisplay = destinationDisplayById.lookup(
      stopPoint.getDestinationDisplayRef().getRef()
    );

    return destinationDisplay == null
      ? null
      : MultilingualStringMapper.nullableValueOf(destinationDisplay.getFrontText());
  }

  /**
   * EPIP (CEN/TS 16614-4, 7.3.1) allows the destination display on the journey pattern; it applies
   * until a stop point states another one. Used with extended NeTEx support only.
   */
  @Nullable
  private DestinationDisplay findPatternDestinationDisplay(
    JourneyPattern_VersionStructure journeyPattern
  ) {
    if (!profileSupport.extendedNetex() || journeyPattern.getDestinationDisplayRef() == null) {
      return null;
    }
    return destinationDisplayById.lookup(journeyPattern.getDestinationDisplayRef().getRef());
  }

  @Nullable
  /** The journey pattern has no destination display, neither on the pattern nor on a stop point. */
  boolean hasNoDestinationDisplay(JourneyPattern_VersionStructure journeyPattern) {
    return (
      findPatternDestinationDisplay(journeyPattern) == null &&
      stopPoints(journeyPattern).noneMatch(it -> it.getDestinationDisplayRef() != null)
    );
  }

  /**
   * The name of the last stop of the journey pattern where passengers may alight: the name of its
   * station, or of the stop if it has no station. A stop point name often names the platform only.
   */
  @Nullable
  String lastStopName(JourneyPattern_VersionStructure journeyPattern) {
    var last = stopPoints(journeyPattern)
      .filter(it -> !Boolean.FALSE.equals(it.isForAlighting()))
      .reduce((first, second) -> second)
      .orElse(null);
    var stop = lookUpStopLocation(last);
    if (stop == null) {
      return null;
    }
    var name = stop.getParentStation() != null ? stop.getParentStation().getName() : stop.getName();
    return name == null ? null : name.toString();
  }

  private static Stream<StopPointInJourneyPattern> stopPoints(
    JourneyPattern_VersionStructure journeyPattern
  ) {
    if (journeyPattern.getPointsInSequence() == null) {
      return Stream.empty();
    }
    return journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
      .stream()
      .filter(StopPointInJourneyPattern.class::isInstance)
      .map(StopPointInJourneyPattern.class::cast);
  }

  private static StopPointInJourneyPattern findStopPoint(
    String pointInJourneyPatterRef,
    JourneyPattern_VersionStructure journeyPattern
  ) {
    var points = journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();

    for (PointInLinkSequence_VersionedChildStructure point : points) {
      if (point instanceof StopPointInJourneyPattern stopPoint) {
        if (stopPoint.getId().equals(pointInJourneyPatterRef)) {
          return stopPoint;
        }
      }
    }
    return null;
  }

  private static int calculateOtpTime(
    LocalTime time,
    BigInteger dayOffset,
    LocalTime fallbackTime,
    BigInteger fallbackDayOffset
  ) {
    return time != null
      ? calculateOtpTime(time, dayOffset)
      : calculateOtpTime(fallbackTime, fallbackDayOffset);
  }

  private static boolean isFalse(Boolean value) {
    return value != null && !value;
  }

  private StopTime mapToStopTime(
    Trip trip,
    StopPointInJourneyPattern stopPoint,
    StopLocation stop,
    TimetabledPassingTime passingTime,
    int stopSequence
  ) {
    StopTime stopTime = new StopTime();
    stopTime.setTrip(trip);
    stopTime.setStopSequence(stopSequence);
    stopTime.setStop(stop);
    if (passingTime.getArrivalTime() != null || passingTime.getDepartureTime() != null) {
      stopTime.setArrivalTime(
        calculateOtpTime(
          passingTime.getArrivalTime(),
          passingTime.getArrivalDayOffset(),
          passingTime.getDepartureTime(),
          passingTime.getDepartureDayOffset()
        )
      );
      stopTime.setDepartureTime(
        calculateOtpTime(
          passingTime.getDepartureTime(),
          passingTime.getDepartureDayOffset(),
          passingTime.getArrivalTime(),
          passingTime.getArrivalDayOffset()
        )
      );

      // From NeTEx we define timepoint as a waitpoint with waiting time defined (also 0)
      if (Boolean.TRUE.equals(stopPoint.isIsWaitPoint()) && passingTime.getWaitingTime() != null) {
        stopTime.setTimepoint(1);
      }
    } else if (
      passingTime.getEarliestDepartureTime() != null && passingTime.getLatestArrivalTime() != null
    ) {
      stopTime.setFlexWindowStart(
        calculateOtpTime(
          passingTime.getEarliestDepartureTime(),
          passingTime.getEarliestDepartureDayOffset()
        )
      );
      stopTime.setFlexWindowEnd(
        calculateOtpTime(
          passingTime.getLatestArrivalTime(),
          passingTime.getLatestArrivalDayOffset()
        )
      );
    } else {
      return null;
    }

    if (stopPoint != null) {
      if (isFalse(stopPoint.isForAlighting())) {
        stopTime.setDropOffType(NONE);
      } else if (Boolean.TRUE.equals(stopPoint.isRequestStop())) {
        stopTime.setDropOffType(COORDINATE_WITH_DRIVER);
      } else {
        stopTime.setDropOffType(SCHEDULED);
      }

      if (isFalse(stopPoint.isForBoarding())) {
        stopTime.setPickupType(NONE);
      } else if (Boolean.TRUE.equals(stopPoint.isRequestStop())) {
        stopTime.setPickupType(COORDINATE_WITH_DRIVER);
      } else {
        stopTime.setPickupType(SCHEDULED);
      }

      if (stopPoint.getDestinationDisplayRef() != null) {
        DestinationDisplay destinationDisplay = destinationDisplayById.lookup(
          stopPoint.getDestinationDisplayRef().getRef()
        );

        if (destinationDisplay != null) {
          currentHeadSign = headsignMapper.map(destinationDisplay);
          Vias_RelStructure viaValues = destinationDisplay.getVias();
          if (viaValues != null && viaValues.getVia() != null) {
            currentHeadSignVias = viaValues
              .getVia()
              .stream()
              .map(Via_VersionedChildStructure::getDestinationDisplayRef)
              .filter(Objects::nonNull)
              .map(VersionOfObjectRefStructure::getRef)
              .filter(Objects::nonNull)
              .map(destinationDisplayById::lookup)
              .filter(Objects::nonNull)
              .map(DestinationDisplay_VersionStructure::getFrontText)
              .filter(Objects::nonNull)
              .map(MultilingualString::getValue)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());

            if (currentHeadSignVias.isEmpty()) {
              currentHeadSignVias = null;
            }
          } else {
            currentHeadSignVias = null;
          }
        }
      }
    }

    if (
      passingTime.getArrivalTime() == null &&
      passingTime.getDepartureTime() == null &&
      passingTime.getEarliestDepartureTime() == null &&
      passingTime.getLatestArrivalTime() == null
    ) {
      issueStore.add("TripWithoutTime", "Time missing for trip %s", trip.getId());
    }

    if (currentHeadSign != null) {
      stopTime.setStopHeadsign(currentHeadSign);
    }
    if (currentHeadSignVias != null) {
      stopTime.setHeadsignVias(currentHeadSignVias);
    }
    return stopTime;
  }

  @Nullable
  private StopLocation lookUpStopLocation(StopPointInJourneyPattern stopPointInJourneyPattern) {
    if (stopPointInJourneyPattern == null) {
      return null;
    }

    String stopPointRef = stopPointInJourneyPattern.getScheduledStopPointRef().getValue().getRef();

    var quayId = quayIdByStopPointRef.lookup(stopPointRef);
    var stopPlaceId = stopPlaceIdByStopPointRef.lookup(stopPointRef);
    var flexibleStopPlaceId = flexibleStopPlaceIdByStopPointRef.lookup(stopPointRef);
    StopLocation stopLocation = null;
    if (quayId != null) {
      stopLocation = stopsById.get(idFactory.createId(quayId));
    } else if (stopPlaceId != null) {
      var station = stationsById.get(idFactory.createId(stopPlaceId));
      // A stop derived from the scheduled stop point, see ScheduledStopPointStopMapper. These
      // exist only with extended NeTEx support, for stop places without quays.
      var derivedStop = stopsById.get(idFactory.createId(stopPointRef));
      if (
        derivedStop != null && station != null && station.equals(derivedStop.getParentStation())
      ) {
        return derivedStop;
      }
      if (station == null) {
        // The station was not mapped, for example because it has no coordinate
        return null;
      }
      // Without extended NeTEx support the validation makes sure that the stop place has at least
      // one quay. With it, a stop place without quays gets stops derived from the scheduled stop
      // points; if none could be derived, the stop is reported as missing by the caller.
      stopLocation = station
        .getChildStops()
        .stream()
        .min(TransitEntity.idComparator())
        .orElse(null);
    } else if (flexibleStopPlaceId != null) {
      AreaStop areaStop = flexibleStopLocationsById.get(idFactory.createId(flexibleStopPlaceId));
      GroupStop groupStop = groupStopById.get(idFactory.createId(flexibleStopPlaceId));

      if (areaStop != null) {
        stopLocation = areaStop;
      } else {
        stopLocation = groupStop;
      }
    } else if (profileSupport.extendedNetex()) {
      // A stop derived from a scheduled stop point without assignment, see
      // ScheduledStopPointStopMapper#mapStopsWithoutAssignment
      var derivedStop = stopsById.get(idFactory.createId(stopPointRef));
      if (derivedStop != null && derivedStop.getParentStation() == null) {
        stopLocation = derivedStop;
      }
    }

    return stopLocation;
  }

  private FlexibleLine lookUpFlexibleLine(
    ServiceJourney serviceJourney,
    JourneyPattern_VersionStructure journeyPattern
  ) {
    if (serviceJourney == null) {
      return null;
    }
    String lineRef = null;
    // Check for direct connection to Line
    JAXBElement<? extends LineRefStructure> lineRefStruct = serviceJourney.getLineRef();

    if (lineRefStruct != null) {
      // Connect to Line referenced directly from ServiceJourney
      lineRef = lineRefStruct.getValue().getRef();
    } else if (serviceJourney.getJourneyPatternRef() != null) {
      // Connect to Line referenced through JourneyPattern->Route
      lineRef = JourneyPatternHelper.getLineFromRoute(routeByid, journeyPattern);
    }
    return flexibleLinesById.lookup(lineRef);
  }
}
