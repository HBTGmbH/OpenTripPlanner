package org.opentripplanner.netex.mapping;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMap;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.transit.model.basic.Notice;
import org.opentripplanner.transit.model.framework.AbstractTransitEntity;
import org.opentripplanner.transit.model.framework.EntityById;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.timetable.Trip;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.TimetabledPassingTime;

/**
 * Maps NeTEx NoticeAssignment, which is the connection between a Notice and the object it refers
 * to. In the case of a notice referring to a StopPointInJourneyPattern, which has no OTP
 * equivalent, it will be assigned to its corresponding TimeTabledPassingTimes for each
 * ServiceJourney in the same JourneyPattern.
 */
class NoticeAssignmentMapper {

  private final DataImportIssueStore issueStore;

  private final FeedScopedIdFactory idFactory;

  /** The keys of the stop times by stop point in journey pattern id. */
  private final Multimap<String, String> stopTimeKeysByStopPointId = ArrayListMultimap.create();

  private final ReadOnlyHierarchicalMap<String, org.rutebanken.netex.model.Notice> noticesById;

  /** Notices defined inside notice assignments embedded in the noticed element. */
  private final Map<String, org.rutebanken.netex.model.Notice> inlineNoticesById;

  private final EntityById<Route> routesById;

  private final EntityById<Trip> tripsById;

  private final ReadOnlyHierarchicalMap<String, StopTime> stopTimesByNetexId;

  /** Note! The notice mapper caches notices, making sure duplicates are not created. */
  private final NoticeMapper noticeMapper;

  NoticeAssignmentMapper(
    DataImportIssueStore issueStore,
    FeedScopedIdFactory idFactory,
    Collection<ServiceJourney> serviceJourneys,
    ReadOnlyHierarchicalMap<String, org.rutebanken.netex.model.Notice> noticesById,
    Map<String, org.rutebanken.netex.model.Notice> inlineNoticesById,
    EntityById<Route> routesById,
    EntityById<Trip> tripsById,
    ReadOnlyHierarchicalMap<String, StopTime> stopTimesByNetexId
  ) {
    this.issueStore = issueStore;
    this.idFactory = idFactory;
    this.noticeMapper = new NoticeMapper(idFactory);
    this.noticesById = noticesById;
    this.inlineNoticesById = inlineNoticesById;
    this.routesById = routesById;
    this.tripsById = tripsById;
    this.stopTimesByNetexId = stopTimesByNetexId;

    // Index passing time by stopPoint id
    for (ServiceJourney sj : serviceJourneys) {
      for (TimetabledPassingTime it : sj.getPassingTimes().getTimetabledPassingTime()) {
        stopTimeKeysByStopPointId.put(
          it.getPointInJourneyPatternRef().getValue().getRef(),
          StopTimesMapperResult.stopTimeKey(sj, it)
        );
      }
    }
  }

  Multimap<AbstractTransitEntity, Notice> map(NoticeAssignment noticeAssignment) {
    return map(noticeAssignment, noticeAssignment.getNoticedObjectRef().getRef());
  }

  /**
   * Map a notice assignment to the given noticed object. This is used for assignments embedded in
   * the noticed element, where the noticed object is given by the context.
   */
  Multimap<AbstractTransitEntity, Notice> map(
    NoticeAssignment noticeAssignment,
    String noticedObjectId
  ) {
    // TODO OTP2 - Idealy this should en up as one key,value pair.
    //             The `StopPointInJourneyPattern` which result in more than one key/valye pair,
    //             can be replaced with a new compound key type.
    Multimap<AbstractTransitEntity, Notice> noticiesByEntity = ArrayListMultimap.create();

    Notice otpNotice = getOrMapNotice(noticeAssignment);

    if (otpNotice == null) {
      issueStore.add(
        "NoticeAssignmentWithoutNotice",
        "Notice in notice assignment is missing for assignment %s",
        noticeAssignment
      );
      return noticiesByEntity;
    }

    // Special case for StopPointInJourneyPattern. The OTP model do not have this element, so
    // we attach the notice to all StopTimes for the pattern at the given stop.
    Collection<String> stopTimeKeys = stopTimeKeysByStopPointId.get(noticedObjectId);
    if (!stopTimeKeys.isEmpty()) {
      for (String stopTimeKey : stopTimeKeys) {
        addStopTimeNotice(noticiesByEntity, stopTimeKey, otpNotice);
      }
    } else if (stopTimesByNetexId.containsKey(noticedObjectId)) {
      addStopTimeNotice(noticiesByEntity, noticedObjectId, otpNotice);
    } else {
      FeedScopedId otpId = idFactory.createId(noticedObjectId);

      if (routesById.containsKey(otpId)) {
        noticiesByEntity.put(routesById.get(otpId), otpNotice);
      } else if (tripsById.containsKey(otpId)) {
        noticiesByEntity.put(tripsById.get(otpId), otpNotice);
      } else {
        issueStore.add(
          "NoticeAssignmentWithUnknownEntity",
          "Could not map notice assignment %s for element with id %s",
          noticeAssignment.getId(),
          noticedObjectId
        );
      }
    }
    return noticiesByEntity;
  }

  @Nullable
  private Notice getOrMapNotice(NoticeAssignment assignment) {
    org.rutebanken.netex.model.Notice notice = assignment.getNotice();
    if (notice == null && assignment.getNoticeRef() != null) {
      String noticeId = assignment.getNoticeRef().getRef();
      notice = noticesById.lookup(noticeId);
      if (notice == null) {
        notice = inlineNoticesById.get(noticeId);
      }
    }

    return notice == null ? null : noticeMapper.map(notice);
  }

  private void addStopTimeNotice(
    Multimap<AbstractTransitEntity, Notice> map,
    String stopTimeId,
    Notice notice
  ) {
    StopTime stopTime = stopTimesByNetexId.lookup(stopTimeId);
    if (stopTime == null) {
      issueStore.add(
        "NoticeAssigmentWithoutStopTime",
        "NoticeAssigment mapping failed, StopTime not found. StopTime id: %s",
        stopTimeId
      );
      return;
    }
    map.put(stopTime.getId(), notice);
  }
}
