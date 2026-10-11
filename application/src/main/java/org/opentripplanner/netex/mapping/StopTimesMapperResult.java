package org.opentripplanner.netex.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opentripplanner.model.StopTime;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.TimetabledPassingTime;

/**
 * Keep a list of stop times and the stopTimes by the timetabled-passing-time id.
 */
class StopTimesMapperResult {

  /**
   * stopTimes by the timetabled-passing-time id
   */
  final Map<String, StopTime> stopTimeByNetexId = new HashMap<>();
  final List<StopTime> stopTimes = new ArrayList<>();
  /**
   * A ordered list of scheduled stop point ids for all timetable stop points in the order of the
   * time-table-passing-times.
   */
  List<String> scheduledStopPointIds;

  void addStopTime(String timetabledPassingTimeId, StopTime stopTime) {
    stopTimeByNetexId.put(timetabledPassingTimeId, stopTime);
    stopTimes.add(stopTime);
  }

  /**
   * The key of the stop time for the given passing time in {@link #stopTimeByNetexId}. The id of a
   * timetabled passing time is optional in NeTEx; without id the stop time is identified by the
   * service journey and the stop point in journey pattern, which is unique within a journey.
   */
  static String stopTimeKey(ServiceJourney serviceJourney, TimetabledPassingTime passingTime) {
    if (passingTime.getId() != null) {
      return passingTime.getId();
    }
    return (
      serviceJourney.getId() + "/" + passingTime.getPointInJourneyPatternRef().getValue().getRef()
    );
  }

  void setScheduledStopPointIds(List<String> scheduledStopPointIds) {
    this.scheduledStopPointIds = scheduledStopPointIds;
  }
}
