package org.opentripplanner.netex.mapping;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.core.model.i18n.TranslatedString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.issues.StopPlaceWithoutCoordinates;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.netex.support.JAXBUtils;
import org.opentripplanner.street.geometry.WgsCoordinate;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.service.SiteRepositoryBuilder;
import org.rutebanken.netex.model.LimitedUseTypeEnumeration;
import org.rutebanken.netex.model.LocaleStructure;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.NameTypeEnumeration;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.StopPlace;
import org.rutebanken.netex.model.Zone_VersionStructure;

class StationMapper {

  private final DataImportIssueStore issueStore;

  private final FeedScopedIdFactory idFactory;

  private final ZoneId defaultTimeZone;

  private final boolean noTransfersOnIsolatedStops;

  private final Set<FeedScopedId> routeToCentroidStopPlaceIds;

  private final SiteRepositoryBuilder siteRepositoryBuilder;

  /**
   * Used if neither the stop place nor its quays have a coordinate, for example the mean position
   * of the scheduled stop points assigned to the stop place.
   */
  private final Map<String, WgsCoordinate> fallbackCoordinateByStopPlaceId;

  StationMapper(
    DataImportIssueStore issueStore,
    FeedScopedIdFactory idFactory,
    ZoneId defaultTimeZone,
    boolean noTransfersOnIsolatedStops,
    Set<FeedScopedId> routeToCentroidStopPlaceIds,
    SiteRepositoryBuilder siteRepositoryBuilder,
    Map<String, WgsCoordinate> fallbackCoordinateByStopPlaceId
  ) {
    this.issueStore = issueStore;
    this.idFactory = idFactory;
    this.defaultTimeZone = defaultTimeZone;
    this.noTransfersOnIsolatedStops = noTransfersOnIsolatedStops;
    this.routeToCentroidStopPlaceIds = routeToCentroidStopPlaceIds;
    this.siteRepositoryBuilder = siteRepositoryBuilder;
    this.fallbackCoordinateByStopPlaceId = fallbackCoordinateByStopPlaceId;
  }

  /**
   * Map the stop place to a station, or return {@code null} if no coordinate can be found for it.
   * A station requires a coordinate in the OTP model.
   */
  @Nullable
  Station map(StopPlace stopPlace) {
    var id = idFactory.createId(stopPlace.getId());
    return siteRepositoryBuilder.computeStationIfAbsent(id, it ->
      mapStopPlaceToStation(it, stopPlace)
    );
  }

  @Nullable
  private Station mapStopPlaceToStation(FeedScopedId id, StopPlace stopPlace) {
    var coordinate = findCoordinate(stopPlace);
    if (coordinate == null) {
      issueStore.add(new StopPlaceWithoutCoordinates(stopPlace.getId()));
      return null;
    }
    var builder = Station.of(id)
      .withName(resolveName(stopPlace))
      .withCoordinate(coordinate)
      .withShouldRouteToCentroid(shouldRouteToCentroid(id))
      .withDescription(
        NonLocalizedString.ofNullable(stopPlace.getDescription(), MultilingualString::getValue)
      )
      .withPriority(StopTransferPriorityMapper.mapToDomain(stopPlace.getWeighting()))
      .withTimezone(
        Optional.ofNullable(stopPlace.getLocale())
          .map(LocaleStructure::getTimeZone)
          .map(zoneId -> ofZoneId(stopPlace.getId(), zoneId))
          .orElse(defaultTimeZone)
      );

    if (noTransfersOnIsolatedStops) {
      builder.withTransfersNotAllowed(
        LimitedUseTypeEnumeration.ISOLATED.equals(stopPlace.getLimitedUse())
      );
    }

    return builder.build();
  }

  private boolean shouldRouteToCentroid(FeedScopedId stopPlaceId) {
    return routeToCentroidStopPlaceIds.contains(stopPlaceId);
  }

  private ZoneId ofZoneId(String stopPlaceId, String zoneId) {
    try {
      return ZoneId.of(zoneId);
    } catch (DateTimeException e) {
      issueStore.add(
        "InvalidTimeZone",
        "Invalid ID for ZoneOffset at StopPlace with ID: %s and value %s",
        stopPlaceId,
        zoneId
      );
    }
    return null;
  }

  private I18NString resolveName(StopPlace stopPlace) {
    final I18NString name;
    if (stopPlace.getName() == null) {
      name = new NonLocalizedString("N/A");
    } else if (stopPlace.getAlternativeNames() != null) {
      Map<String, String> translations = new HashMap<>();
      translations.put(null, stopPlace.getName().getValue());
      for (var translation : stopPlace.getAlternativeNames().getAlternativeName()) {
        if (translation.getNameType() == NameTypeEnumeration.TRANSLATION) {
          String lang =
            translation.getLang() != null ? translation.getLang() : translation.getName().getLang();
          translations.put(lang, translation.getName().getValue());
        }
      }

      name = TranslatedString.getDeduplicatedI18NString(translations, false);
    } else {
      name = new NonLocalizedString(stopPlace.getName().getValue());
    }
    return name;
  }

  /**
   * Map the centroid to coordinate, if not present the mean coordinate for the child quays is
   * returned, then the fallback coordinate. If none of them exist, {@code null} is returned.
   */
  @Nullable
  private WgsCoordinate findCoordinate(StopPlace stopPlace) {
    var centroid = WgsCoordinateMapper.mapToDomain(stopPlace.getCentroid());
    if (centroid != null) {
      return centroid;
    }
    issueStore.add(
      "StationWithoutCoordinates",
      "Station %s does not contain any coordinates.",
      stopPlace.getId() + " " + stopPlace.getName()
    );
    if (stopPlace.getQuays() == null) {
      return fallbackCoordinateByStopPlaceId.get(stopPlace.getId());
    }
    List<WgsCoordinate> coordinates = JAXBUtils.streamJAXBElementValue(
      Quay.class,
      stopPlace.getQuays().getQuayRefOrQuay()
    )
      .map(Zone_VersionStructure::getCentroid)
      .filter(Objects::nonNull)
      .map(WgsCoordinateMapper::mapToDomain)
      .toList();
    return coordinates.isEmpty()
      ? fallbackCoordinateByStopPlaceId.get(stopPlace.getId())
      : WgsCoordinate.mean(coordinates);
  }
}
