package org.opentripplanner.netex.support;

import jakarta.xml.bind.JAXBElement;
import javax.annotation.Nullable;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMap;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.LineRefStructure;
import org.rutebanken.netex.model.Route;

/**
 * Utility class with helpers for the generated NeTEx XML model classes
 */
public class JourneyPatternHelper {

  private JourneyPatternHelper() {}

  /*
   * Get lineId from RouteRef (Nordic profile) or RouteView (EPIP)
   *
   * @param routeById needed in Nordic profile to look up the route reference
   * @param journeyPattern the JourneyPatten to get the lineId from
   * @return the line id, or {@code null} if the route or line reference can not be resolved
   */
  @Nullable
  public static String getLineFromRoute(
    ReadOnlyHierarchicalMap<String, Route> routeById,
    JourneyPattern_VersionStructure journeyPattern
  ) {
    JAXBElement<? extends LineRefStructure> lineRef = null;
    if (journeyPattern.getRouteRef() != null) {
      Route route = routeById.lookup(journeyPattern.getRouteRef().getRef());
      lineRef = route == null ? null : route.getLineRef();
    } else if (journeyPattern.getRouteView() != null) {
      lineRef = journeyPattern.getRouteView().getLineRef();
    }
    return lineRef == null ? null : lineRef.getValue().getRef();
  }
}
