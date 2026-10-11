package org.opentripplanner.netex.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.accessibility.Accessibility;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMap;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMapById;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalVersionMapById;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.street.geometry.WgsCoordinate;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.service.SiteRepositoryBuilder;
import org.rutebanken.netex.model.ScheduledStopPoint;
import org.rutebanken.netex.model.StopPlace;

/**
 * Derive stops for scheduled stop points that are assigned to a stop place without quays.
 * <p>
 * In the Nordic profile every scheduled stop point is assigned to a quay, which becomes a stop in
 * OTP. EPIP (CEN/TS 16614-4) requires only the stop place in a passenger stop assignment, and the
 * German VDV 462 profile does not use quays at all; the position of the stop point is given by the
 * scheduled stop point itself. According to EPIP, the position of a scheduled stop point assigned
 * to a whole stop place is by convention the centroid of the stop place.
 * <p>
 * For each such scheduled stop point a stop is created with the id of the scheduled stop point,
 * located at the scheduled stop point, or at the stop place if the scheduled stop point has no
 * location. Its parent station is the station mapped from the stop place.
 */
class ScheduledStopPointStopMapper {

  private final FeedScopedIdFactory idFactory;
  private final DataImportIssueStore issueStore;
  private final SiteRepositoryBuilder siteRepositoryBuilder;
  private final StopPlaceTypeMapper stopPlaceTypeMapper = new StopPlaceTypeMapper();

  ScheduledStopPointStopMapper(
    FeedScopedIdFactory idFactory,
    DataImportIssueStore issueStore,
    SiteRepositoryBuilder siteRepositoryBuilder
  ) {
    this.idFactory = idFactory;
    this.issueStore = issueStore;
    this.siteRepositoryBuilder = siteRepositoryBuilder;
  }

  /**
   * The mean position of the scheduled stop points assigned to each stop place. This is used as the
   * position of a stop place which has neither a centroid nor quays with a centroid.
   */
  static Map<String, WgsCoordinate> meanPositionByStopPlaceId(
    ReadOnlyHierarchicalMap<String, String> stopPlaceIdByStopPointRef,
    ReadOnlyHierarchicalMapById<ScheduledStopPoint> scheduledStopPointById
  ) {
    Map<String, List<WgsCoordinate>> positions = new HashMap<>();
    for (String stopPointRef : stopPlaceIdByStopPointRef.localKeys()) {
      var coordinate = location(scheduledStopPointById.lookup(stopPointRef));
      if (coordinate != null) {
        positions
          .computeIfAbsent(stopPlaceIdByStopPointRef.lookup(stopPointRef), k -> new ArrayList<>())
          .add(coordinate);
      }
    }
    Map<String, WgsCoordinate> result = new HashMap<>();
    positions.forEach((stopPlaceId, list) -> result.put(stopPlaceId, WgsCoordinate.mean(list)));
    return result;
  }

  /**
   * Create a stop for every scheduled stop point in the local index which is assigned to a stop
   * place without quays.
   *
   * @return the created stops by scheduled stop point id
   */
  Map<String, RegularStop> mapStops(
    ReadOnlyHierarchicalMap<String, String> stopPlaceIdByStopPointRef,
    ReadOnlyHierarchicalMapById<ScheduledStopPoint> scheduledStopPointById,
    ReadOnlyHierarchicalVersionMapById<StopPlace> stopPlaceById
  ) {
    Map<String, RegularStop> result = new HashMap<>();
    for (String stopPointRef : stopPlaceIdByStopPointRef.localKeys()) {
      String stopPlaceId = stopPlaceIdByStopPointRef.lookup(stopPointRef);
      StopPlace stopPlace = stopPlaceById.lookupLastVersionById(stopPlaceId);
      Station station = siteRepositoryBuilder.stationById().get(idFactory.createId(stopPlaceId));
      if (stopPlace == null || station == null || hasQuays(stopPlace)) {
        continue;
      }
      var stop = mapStop(
        scheduledStopPointById.lookup(stopPointRef),
        stopPointRef,
        stopPlace,
        station
      );
      result.put(stopPointRef, stop);
    }
    return result;
  }

  /**
   * Create a stop for every scheduled stop point which is used in a journey pattern of the local
   * index, but is not assigned to any quay, stop place or flexible stop place. A passenger stop
   * assignment is required for each scheduled stop point, but some exports omit it (seen in the
   * German DELFI data set). The stop is located at the scheduled stop point and has no parent
   * station. A scheduled stop point without name or location is skipped.
   *
   * @param usedStopPointRefs the scheduled stop points used in the local journey patterns
   * @param isAssigned        tells if a scheduled stop point is assigned to a stop
   * @return the created stops by scheduled stop point id
   */
  Map<String, RegularStop> mapStopsWithoutAssignment(
    Collection<String> usedStopPointRefs,
    ReadOnlyHierarchicalMapById<ScheduledStopPoint> scheduledStopPointById,
    Predicate<String> isAssigned
  ) {
    Map<String, RegularStop> result = new HashMap<>();
    for (String stopPointRef : usedStopPointRefs) {
      if (isAssigned.test(stopPointRef)) {
        continue;
      }
      var scheduledStopPoint = scheduledStopPointById.lookup(stopPointRef);
      var coordinate = location(scheduledStopPoint);
      var name =
        scheduledStopPoint == null || scheduledStopPoint.getName() == null
          ? null
          : NonLocalizedString.ofNullable(scheduledStopPoint.getName().getValue());
      if (coordinate == null || name == null) {
        continue;
      }
      var stop = siteRepositoryBuilder.computeRegularStopIfAbsent(
        idFactory.createId(stopPointRef),
        it -> {
          issueStore.add(
            "StopDerivedFromUnassignedScheduledStopPoint",
            "Scheduled stop point %s is not assigned to a stop. A stop without parent station is created at its location.",
            stopPointRef
          );
          return siteRepositoryBuilder
            .regularStop(it)
            .withName(name)
            .withPlatformCode(publicCode(scheduledStopPoint))
            .withCoordinate(coordinate)
            .build();
        }
      );
      result.put(stopPointRef, stop);
    }
    return result;
  }

  private RegularStop mapStop(
    @Nullable ScheduledStopPoint scheduledStopPoint,
    String stopPointRef,
    StopPlace stopPlace,
    Station station
  ) {
    var id = idFactory.createId(stopPointRef);
    return siteRepositoryBuilder.computeRegularStopIfAbsent(id, it -> {
      var coordinate = location(scheduledStopPoint);
      issueStore.add(
        "StopDerivedFromScheduledStopPoint",
        "Stop place %s has no quays. A stop is created for scheduled stop point %s at the location of the %s.",
        stopPlace.getId(),
        stopPointRef,
        coordinate == null ? "stop place" : "scheduled stop point"
      );
      var transitMode = stopPlaceTypeMapper.map(stopPlace);
      return siteRepositoryBuilder
        .regularStop(it)
        .withParentStation(station)
        .withName(name(scheduledStopPoint, station))
        .withPlatformCode(publicCode(scheduledStopPoint))
        .withCoordinate(coordinate == null ? station.getCoordinate() : coordinate)
        .withWheelchairAccessibility(
          WheelChairMapper.wheelchairAccessibility(
            stopPlace.getAccessibilityAssessment(),
            Accessibility.NO_INFORMATION,
            true
          )
        )
        .withVehicleType(transitMode.mainMode())
        .withNetexVehicleSubmode(transitMode.subMode())
        .build();
    });
  }

  private static boolean hasQuays(StopPlace stopPlace) {
    return stopPlace.getQuays() != null && !stopPlace.getQuays().getQuayRefOrQuay().isEmpty();
  }

  @Nullable
  private static WgsCoordinate location(@Nullable ScheduledStopPoint scheduledStopPoint) {
    return scheduledStopPoint == null
      ? null
      : WgsCoordinateMapper.mapLocationToDomain(scheduledStopPoint.getLocation());
  }

  private static I18NString name(@Nullable ScheduledStopPoint scheduledStopPoint, Station station) {
    if (scheduledStopPoint == null || scheduledStopPoint.getName() == null) {
      return station.getName();
    }
    var name = NonLocalizedString.ofNullable(scheduledStopPoint.getName().getValue());
    return name == null ? station.getName() : name;
  }

  @Nullable
  private static String publicCode(@Nullable ScheduledStopPoint scheduledStopPoint) {
    return scheduledStopPoint == null || scheduledStopPoint.getPublicCode() == null
      ? null
      : scheduledStopPoint.getPublicCode().getValue();
  }
}
