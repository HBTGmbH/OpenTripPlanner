package org.opentripplanner.netex.mapping;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import jakarta.xml.bind.JAXBElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.opentripplanner.netex.index.api.NetexEntityIndexReadOnlyView;
import org.opentripplanner.netex.support.JourneyPatternHelper;
import org.rutebanken.netex.model.AllVehicleModesOfTransportEnumeration;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.Line_VersionStructure;
import org.rutebanken.netex.model.Notice;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.NoticeAssignments_RelStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopPlace;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.rutebanken.netex.model.TransportSubmodeStructure;

/**
 * The transport mode of a line is optional in the European Passenger Information Profile (EPIP,
 * TS 16614-4 §7.1.1), but OTP needs a mode for each route. This class derives the mode of a line
 * without mode from the service journeys of the line, or if they have no mode either, from the
 * stop places they serve. If the journeys or stop places have different modes, the most frequent
 * mode is used. The mode {@code unknown} is treated like a missing mode.
 * <p>
 * Only the service journeys of the current file level are used, so the mode of a line in a shared
 * file can not be derived from journeys in a group file.
 */
class LineTransportModeResolver {

  private final NetexEntityIndexReadOnlyView index;
  private Multimap<String, ServiceJourney> serviceJourneysByLineId;

  LineTransportModeResolver(NetexEntityIndexReadOnlyView index) {
    this.index = index;
  }

  /**
   * The derived mode and the submode of the first journey with this mode. The submode is
   * {@code null} if the mode is derived from the stop places.
   */
  record DerivedMode(
    AllVehicleModesOfTransportEnumeration mode,
    @Nullable TransportSubmodeStructure submode,
    String source
  ) {}

  @Nullable
  DerivedMode resolve(String lineId) {
    var serviceJourneys = serviceJourneysByLineId().get(lineId);

    var journeyMode = mostFrequent(serviceJourneys.stream().map(ServiceJourney::getTransportMode));
    if (journeyMode != null) {
      var submode = serviceJourneys
        .stream()
        .filter(it -> it.getTransportMode() == journeyMode)
        .map(ServiceJourney::getTransportSubmode)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
      return new DerivedMode(journeyMode, submode, "service journeys");
    }

    var stopPlaceMode = mostFrequent(
      serviceJourneys
        .stream()
        .map(this::journeyPattern)
        .filter(Objects::nonNull)
        .distinct()
        .flatMap(this::stopPlaces)
        .map(StopPlace::getTransportMode)
    );
    return stopPlaceMode == null ? null : new DerivedMode(stopPlaceMode, null, "stop places");
  }

  /** The number of service journeys of the line in the current file level. */
  int numberOfServiceJourneys(String lineId) {
    return serviceJourneysByLineId().get(lineId).size();
  }

  /**
   * The distinct texts of the notices assigned to the line, its service journeys, their journey
   * patterns and the stop points of the patterns. Used to report hints in the data, see
   * {@link org.opentripplanner.netex.issues.LineWithoutTransportMode}.
   */
  List<String> noticeTexts(Line_VersionStructure line) {
    var assignments = new ArrayList<NoticeAssignment>();
    addNoticeAssignments(assignments, line.getNoticeAssignments());
    var serviceJourneys = serviceJourneysByLineId().get(line.getId());
    for (ServiceJourney serviceJourney : serviceJourneys) {
      addNoticeAssignments(assignments, serviceJourney.getNoticeAssignments());
    }
    serviceJourneys
      .stream()
      .map(this::journeyPattern)
      .filter(Objects::nonNull)
      .distinct()
      .forEach(journeyPattern -> {
        addNoticeAssignments(assignments, journeyPattern.getNoticeAssignments());
        if (journeyPattern.getPointsInSequence() != null) {
          for (var point : journeyPattern
            .getPointsInSequence()
            .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
            if (point instanceof StopPointInJourneyPattern stopPoint) {
              addNoticeAssignments(assignments, stopPoint.getNoticeAssignments());
            }
          }
        }
      });

    Map<String, Notice> inlineNotices = new HashMap<>();
    for (NoticeAssignment it : assignments) {
      if (it.getNotice() != null && it.getNotice().getId() != null) {
        inlineNotices.putIfAbsent(it.getNotice().getId(), it.getNotice());
      }
    }
    var result = new LinkedHashSet<String>();
    for (NoticeAssignment it : assignments) {
      var notice = it.getNotice();
      if (notice == null && it.getNoticeRef() != null) {
        notice = inlineNotices.get(it.getNoticeRef().getRef());
        if (notice == null) {
          notice = index.getNoticeById().lookup(it.getNoticeRef().getRef());
        }
      }
      if (notice != null && notice.getText() != null && notice.getText().getValue() != null) {
        result.add(notice.getText().getValue().strip());
      }
    }
    return List.copyOf(result);
  }

  private static void addNoticeAssignments(
    List<NoticeAssignment> result,
    @Nullable NoticeAssignments_RelStructure container
  ) {
    if (container == null) {
      return;
    }
    for (JAXBElement<?> element : container.getNoticeAssignment_OrNoticeAssignmentView()) {
      if (element.getValue() instanceof NoticeAssignment noticeAssignment) {
        result.add(noticeAssignment);
      }
    }
  }

  private Multimap<String, ServiceJourney> serviceJourneysByLineId() {
    if (serviceJourneysByLineId == null) {
      serviceJourneysByLineId = ArrayListMultimap.create();
      for (ServiceJourney it : index.getServiceJourneyById().localValues()) {
        var lineId = lineId(it);
        if (lineId != null) {
          serviceJourneysByLineId.put(lineId, it);
        }
      }
    }
    return serviceJourneysByLineId;
  }

  @Nullable
  private String lineId(ServiceJourney serviceJourney) {
    if (serviceJourney.getLineRef() != null) {
      return serviceJourney.getLineRef().getValue().getRef();
    }
    var journeyPattern = journeyPattern(serviceJourney);
    return journeyPattern == null
      ? null
      : JourneyPatternHelper.getLineFromRoute(index.getRouteById(), journeyPattern);
  }

  @Nullable
  private JourneyPattern_VersionStructure journeyPattern(ServiceJourney serviceJourney) {
    return serviceJourney.getJourneyPatternRef() == null
      ? null
      : index
          .getJourneyPatternsById()
          .lookup(serviceJourney.getJourneyPatternRef().getValue().getRef());
  }

  private Stream<StopPlace> stopPlaces(JourneyPattern_VersionStructure journeyPattern) {
    if (journeyPattern.getPointsInSequence() == null) {
      return Stream.empty();
    }
    return journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
      .stream()
      .filter(StopPointInJourneyPattern.class::isInstance)
      .map(StopPointInJourneyPattern.class::cast)
      .filter(it -> it.getScheduledStopPointRef() != null)
      .map(it ->
        index
          .getStopPlaceIdByStopPointRef()
          .lookup(it.getScheduledStopPointRef().getValue().getRef())
      )
      .filter(Objects::nonNull)
      .map(id -> index.getStopPlaceById().lookupLastVersionById(id))
      .filter(Objects::nonNull);
  }

  /** The most frequent mode, the first one if several modes are equally frequent. */
  @Nullable
  private static AllVehicleModesOfTransportEnumeration mostFrequent(
    Stream<AllVehicleModesOfTransportEnumeration> modes
  ) {
    Map<AllVehicleModesOfTransportEnumeration, Integer> counts = new LinkedHashMap<>();
    modes
      .filter(it -> it != null && it != AllVehicleModesOfTransportEnumeration.UNKNOWN)
      .forEach(it -> counts.merge(it, 1, Integer::sum));
    AllVehicleModesOfTransportEnumeration result = null;
    for (var it : counts.entrySet()) {
      if (result == null || it.getValue() > counts.get(result)) {
        result = it.getKey();
      }
    }
    return result;
  }
}
