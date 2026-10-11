package org.opentripplanner.netex.mapping;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import jakarta.xml.bind.JAXBElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.Notice;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.NoticeAssignments_RelStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

/**
 * Collects notice assignments embedded in the element they annotate. The Nordic profile places
 * notice assignments in the frame, with an explicit reference to the noticed object. Other
 * profiles, for example EPIP (CEN/TS 16614-4, §7.11.2), embed the assignment in the noticed element
 * and omit the reference, which is then given by the context. The first assignment usually
 * contains the notice, later assignments reference it with a {@code NoticeRef}.
 * <p>
 * Assignments on a journey pattern are applied to all service journeys of the pattern, because the
 * OTP model has no entity a notice can be attached to for a journey pattern. Assignments on stop
 * points without boarding and alighting are ignored.
 */
class InlineNoticeAssignments {

  /** A notice assignment together with the id of the element it is embedded in. */
  record Assignment(String noticedObjectId, NoticeAssignment noticeAssignment) {}

  private final List<Assignment> assignments = new ArrayList<>();
  private final Map<String, Notice> noticesById = new HashMap<>();

  private InlineNoticeAssignments() {}

  static InlineNoticeAssignments collect(
    Collection<JourneyPattern_VersionStructure> journeyPatterns,
    Collection<Line> lines,
    Collection<ServiceJourney> serviceJourneys
  ) {
    var result = new InlineNoticeAssignments();

    for (Line line : lines) {
      result.addAll(line.getId(), line.getNoticeAssignments());
    }
    for (ServiceJourney serviceJourney : serviceJourneys) {
      result.addAll(serviceJourney.getId(), serviceJourney.getNoticeAssignments());
    }
    Multimap<String, String> serviceJourneyIdsByPatternId = ArrayListMultimap.create();
    for (ServiceJourney serviceJourney : serviceJourneys) {
      var patternId = journeyPatternId(serviceJourney);
      if (patternId != null) {
        serviceJourneyIdsByPatternId.put(patternId, serviceJourney.getId());
      }
    }
    for (JourneyPattern_VersionStructure journeyPattern : journeyPatterns) {
      for (String serviceJourneyId : serviceJourneyIdsByPatternId.get(journeyPattern.getId())) {
        result.addAll(serviceJourneyId, journeyPattern.getNoticeAssignments());
      }
      if (journeyPattern.getPointsInSequence() == null) {
        continue;
      }
      for (var point : journeyPattern
        .getPointsInSequence()
        .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
        if (point instanceof StopPointInJourneyPattern stopPoint && isForPassengers(stopPoint)) {
          result.addAll(stopPoint.getId(), stopPoint.getNoticeAssignments());
        }
      }
    }
    return result;
  }

  List<Assignment> assignments() {
    return assignments;
  }

  /** Notices defined inside one of the collected assignments, by id. */
  Map<String, Notice> noticesById() {
    return noticesById;
  }

  private void addAll(String noticedObjectId, @Nullable NoticeAssignments_RelStructure container) {
    if (container == null) {
      return;
    }
    for (JAXBElement<?> element : container.getNoticeAssignment_OrNoticeAssignmentView()) {
      if (element.getValue() instanceof NoticeAssignment noticeAssignment) {
        assignments.add(new Assignment(noticedObjectId, noticeAssignment));
        var notice = noticeAssignment.getNotice();
        if (notice != null && notice.getId() != null) {
          noticesById.putIfAbsent(notice.getId(), notice);
        }
      }
    }
  }

  /**
   * A stop point without boarding and alighting is skipped when the stop times are mapped, so
   * notices on it can not be attached to anything.
   */
  private static boolean isForPassengers(StopPointInJourneyPattern stopPoint) {
    return !(
      Boolean.FALSE.equals(stopPoint.isForBoarding()) &&
      Boolean.FALSE.equals(stopPoint.isForAlighting())
    );
  }

  @Nullable
  private static String journeyPatternId(ServiceJourney serviceJourney) {
    var ref = serviceJourney.getJourneyPatternRef();
    return ref == null ? null : ref.getValue().getRef();
  }
}
