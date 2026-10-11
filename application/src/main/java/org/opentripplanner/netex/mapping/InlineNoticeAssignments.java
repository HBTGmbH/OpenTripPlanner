package org.opentripplanner.netex.mapping;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import jakarta.xml.bind.JAXBElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>
 * A notice assigned to every stop point of a journey pattern (used for boarding or alighting) is
 * treated like a notice on the journey pattern. The German DELFI data set repeats journey wide
 * notices, like "wheelchair accessible", on every stop point: in the Hamburg part this applies to
 * 99.6 % of the notices on stop points, and attaching them to every stop time would create 28
 * million notice assignments instead of about one per journey and notice.
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
      result.addStopPointAssignments(
        journeyPattern,
        serviceJourneyIdsByPatternId.get(journeyPattern.getId())
      );
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
    for (NoticeAssignment noticeAssignment : noticeAssignments(container)) {
      assignments.add(new Assignment(noticedObjectId, noticeAssignment));
    }
  }

  /**
   * Add the assignments of the stop points of the pattern. Notices assigned to every stop point
   * are assigned to the service journeys of the pattern instead.
   */
  private void addStopPointAssignments(
    JourneyPattern_VersionStructure journeyPattern,
    Collection<String> serviceJourneyIds
  ) {
    var stopPoints = journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
      .stream()
      .filter(StopPointInJourneyPattern.class::isInstance)
      .map(StopPointInJourneyPattern.class::cast)
      .filter(InlineNoticeAssignments::isForPassengers)
      .toList();

    // The first assignment of each notice, and the number of stop points it is assigned to
    Map<String, NoticeAssignment> firstAssignmentByNoticeId = new LinkedHashMap<>();
    Map<String, Integer> stopPointCountByNoticeId = new HashMap<>();
    for (var stopPoint : stopPoints) {
      var noticeIds = new HashSet<String>();
      for (NoticeAssignment it : noticeAssignments(stopPoint.getNoticeAssignments())) {
        var noticeId = noticeId(it);
        if (noticeId != null && noticeIds.add(noticeId)) {
          firstAssignmentByNoticeId.putIfAbsent(noticeId, it);
          stopPointCountByNoticeId.merge(noticeId, 1, Integer::sum);
        }
      }
    }
    var journeyWideNoticeIds = new LinkedHashSet<String>();
    for (String noticeId : firstAssignmentByNoticeId.keySet()) {
      if (stopPointCountByNoticeId.get(noticeId) == stopPoints.size()) {
        journeyWideNoticeIds.add(noticeId);
      }
    }

    for (String serviceJourneyId : serviceJourneyIds) {
      for (String noticeId : journeyWideNoticeIds) {
        assignments.add(new Assignment(serviceJourneyId, firstAssignmentByNoticeId.get(noticeId)));
      }
    }
    for (var stopPoint : stopPoints) {
      for (NoticeAssignment it : noticeAssignments(stopPoint.getNoticeAssignments())) {
        if (!journeyWideNoticeIds.contains(noticeId(it))) {
          assignments.add(new Assignment(stopPoint.getId(), it));
        }
      }
    }
  }

  /** The notice assignments of the container; notices defined inside them are indexed. */
  private List<NoticeAssignment> noticeAssignments(
    @Nullable NoticeAssignments_RelStructure container
  ) {
    if (container == null) {
      return List.of();
    }
    var result = new ArrayList<NoticeAssignment>();
    for (JAXBElement<?> element : container.getNoticeAssignment_OrNoticeAssignmentView()) {
      if (element.getValue() instanceof NoticeAssignment noticeAssignment) {
        result.add(noticeAssignment);
        var notice = noticeAssignment.getNotice();
        if (notice != null && notice.getId() != null) {
          noticesById.putIfAbsent(notice.getId(), notice);
        }
      }
    }
    return result;
  }

  @Nullable
  private static String noticeId(NoticeAssignment noticeAssignment) {
    if (noticeAssignment.getNotice() != null) {
      return noticeAssignment.getNotice().getId();
    }
    return noticeAssignment.getNoticeRef() == null
      ? null
      : noticeAssignment.getNoticeRef().getRef();
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
