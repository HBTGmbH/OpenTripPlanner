package org.opentripplanner.netex.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.netex.index.hierarchy.HierarchicalMap;
import org.opentripplanner.netex.index.hierarchy.HierarchicalMapById;
import org.opentripplanner.transit.model._data.TransitRepositoryForTest;
import org.opentripplanner.transit.model.framework.AbstractTransitEntity;
import org.opentripplanner.transit.model.framework.DefaultEntityById;
import org.opentripplanner.transit.model.framework.EntityById;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.timetable.Trip;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.Notice;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.NoticeRefStructure;
import org.rutebanken.netex.model.PointInJourneyPatternRefStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.TimetabledPassingTime;
import org.rutebanken.netex.model.TimetabledPassingTimes_RelStructure;
import org.rutebanken.netex.model.VersionOfObjectRefStructure;

public class NoticeAssignmentMapperTest {

  private static final String ROUTE_ID = "RUT:Route:1";
  private static final String TRIP_ID = "RUT:ServiceJourney:1";
  private static final String STOP_POINT_ID = "RUT:StopPointInJourneyPattern:1";
  private static final String NOTICE_ID = "RUT:Notice:1";
  private static final String TIMETABLED_PASSING_TIME1 = "RUT:TimetabledPassingTime:1";
  private static final String TIMETABLED_PASSING_TIME2 = "RUT:TimetabledPassingTime:2";

  private static final Notice NOTICE = new org.rutebanken.netex.model.Notice()
    .withId(NOTICE_ID)
    .withPublicCode("Notice Code")
    .withText(new MultilingualString().withValue("Notice text"));

  @Test
  public void mapNoticeAssignment() {
    NoticeAssignment noticeAssignment = new NoticeAssignment();

    noticeAssignment.setNoticedObjectRef(new VersionOfObjectRefStructure().withRef(ROUTE_ID));
    noticeAssignment.setNotice(NOTICE);

    Route route = TransitRepositoryForTest.route(ROUTE_ID).build();

    EntityById<Route> routesById = new DefaultEntityById<>();
    routesById.add(route);

    NoticeAssignmentMapper noticeAssignmentMapper = new NoticeAssignmentMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      List.of(),
      new HierarchicalMapById<>(),
      Map.of(),
      routesById,
      new DefaultEntityById<>(),
      new HierarchicalMap<>()
    );

    Multimap<
      AbstractTransitEntity,
      org.opentripplanner.transit.model.basic.Notice
    > noticesByElement = noticeAssignmentMapper.map(noticeAssignment);

    org.opentripplanner.transit.model.basic.Notice notice2 = noticesByElement
      .get(route)
      .iterator()
      .next();

    assertEquals(NOTICE_ID, notice2.getId().getId());
  }

  @Test
  public void mapNoticeAssignmentOnStopPoint() {
    HierarchicalMapById<Notice> noticesById = new HierarchicalMapById<>();

    Collection<ServiceJourney> serviceJourneys = List.of(
      new ServiceJourney().withPassingTimes(
        new TimetabledPassingTimes_RelStructure().withTimetabledPassingTime(
          createTimetabledPassingTime(TIMETABLED_PASSING_TIME1, STOP_POINT_ID),
          createTimetabledPassingTime(TIMETABLED_PASSING_TIME2, STOP_POINT_ID)
        )
      )
    );

    var trip = TransitRepositoryForTest.trip("1").build();
    StopTime stopTime1 = createStopTime(1, trip);
    StopTime stopTime2 = createStopTime(2, trip);

    var stopTimesById = new HierarchicalMap<String, StopTime>();
    stopTimesById.add(TIMETABLED_PASSING_TIME1, stopTime1);
    stopTimesById.add(TIMETABLED_PASSING_TIME2, stopTime2);

    noticesById.add(NOTICE);

    NoticeAssignment noticeAssignment = new NoticeAssignment()
      .withNoticedObjectRef(new VersionOfObjectRefStructure().withRef(STOP_POINT_ID))
      .withNoticeRef(new NoticeRefStructure().withRef(NOTICE_ID));

    NoticeAssignmentMapper noticeAssignmentMapper = new NoticeAssignmentMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      serviceJourneys,
      noticesById,
      Map.of(),
      new DefaultEntityById<>(),
      new DefaultEntityById<>(),
      stopTimesById
    );

    Multimap<
      AbstractTransitEntity,
      org.opentripplanner.transit.model.basic.Notice
    > noticesByElement = noticeAssignmentMapper.map(noticeAssignment);

    org.opentripplanner.transit.model.basic.Notice notice2a = noticesByElement
      .get(stopTime1.getId())
      .stream()
      .findFirst()
      .orElseThrow(IllegalStateException::new);

    org.opentripplanner.transit.model.basic.Notice notice2b = noticesByElement
      .get(stopTime2.getId())
      .stream()
      .findFirst()
      .orElseThrow(IllegalStateException::new);

    assertEquals(NOTICE_ID, notice2a.getId().getId());
    assertEquals(NOTICE_ID, notice2b.getId().getId());
  }

  @Test
  public void mapInlineNoticeAssignmentReferencingInlineNotice() {
    var trip = TransitRepositoryForTest.trip(TRIP_ID).build();
    var tripsById = new DefaultEntityById<Trip>();
    tripsById.add(trip);

    NoticeAssignmentMapper noticeAssignmentMapper = new NoticeAssignmentMapper(
      DataImportIssueStore.NOOP,
      MappingSupport.ID_FACTORY,
      List.of(),
      new HierarchicalMapById<>(),
      Map.of(NOTICE_ID, NOTICE),
      new DefaultEntityById<>(),
      tripsById,
      new HierarchicalMap<>()
    );

    // Embedded in the service journey: no noticed object ref, notice given by reference
    var noticeAssignment = new NoticeAssignment().withNoticeRef(
      new NoticeRefStructure().withRef(NOTICE_ID)
    );

    var noticesByElement = noticeAssignmentMapper.map(noticeAssignment, TRIP_ID);

    assertEquals(NOTICE_ID, noticesByElement.get(trip).iterator().next().getId().getId());
  }

  private static TimetabledPassingTime createTimetabledPassingTime(String id, String stopPointId) {
    return new TimetabledPassingTime()
      .withId(id)
      .withPointInJourneyPatternRef(
        MappingSupport.createJaxbElement(
          new PointInJourneyPatternRefStructure().withRef(stopPointId)
        )
      );
  }

  private StopTime createStopTime(int stopSequence, Trip trip) {
    StopTime stopTime1 = new StopTime();
    stopTime1.setStopSequence(stopSequence);
    stopTime1.setTrip(trip);
    return stopTime1;
  }
}
