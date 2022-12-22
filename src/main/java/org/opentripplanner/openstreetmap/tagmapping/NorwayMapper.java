package org.opentripplanner.openstreetmap.tagmapping;

import static org.opentripplanner.openstreetmap.wayproperty.MixinPropertiesBuilder.ofBicycleSafety;
import static org.opentripplanner.openstreetmap.wayproperty.WayPropertiesBuilder.withModes;
import static org.opentripplanner.street.model.StreetTraversalPermission.ALL;
import static org.opentripplanner.street.model.StreetTraversalPermission.BICYCLE;
import static org.opentripplanner.street.model.StreetTraversalPermission.BICYCLE_AND_CAR;
import static org.opentripplanner.street.model.StreetTraversalPermission.CAR;
import static org.opentripplanner.street.model.StreetTraversalPermission.NONE;
import static org.opentripplanner.street.model.StreetTraversalPermission.PEDESTRIAN;
import static org.opentripplanner.street.model.StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE;

import org.opentripplanner.openstreetmap.wayproperty.WayPropertySet;
import org.opentripplanner.openstreetmap.wayproperty.specifier.BestMatchSpecifier;
import org.opentripplanner.openstreetmap.wayproperty.specifier.Condition;
import org.opentripplanner.openstreetmap.wayproperty.specifier.ExactMatchSpecifier;
import org.opentripplanner.openstreetmap.wayproperty.specifier.LogicalOrSpecifier;

/**
 * OSM way properties for Norwegian roads. The main difference compared to the default property set
 * is that most of the highway=trunk roads also allows walking and biking, where as some does not.
 * http://wiki.openstreetmap.org/wiki/Tag:highway%3Dtrunk http://wiki.openstreetmap.org/wiki/Highway:International_equivalence
 *
 * @author seime
 * @see OsmTagMapper
 * @see DefaultMapper
 */
class NorwayMapper implements OsmTagMapper {

  @Override
  public void populateProperties(WayPropertySet props) {
    var very_high_traffic = 8;
    var high_traffic = 2.25;
    var medium_high_traffic = 2.06;
    var medium_traffic = 1.5;
    var medium_low_traffic = 1.42;
    var low_traffic = 1.1;
    var very_low_traffic = 0.94;

    var cycle_lane_medium_traffic = 0.76;
    var cycle_lane_low_traffic = 0.66;

    var dedicated_footway = 0.85;
    var sidewalk = 1.16;
    var footway_crossing = 1.4;
    var mixed_cycleway = 0.67;
    var dedicated_cycleway = 0.62;
    var dual_lane_or_oneway_cycleway = 0.6;

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","motorway", "motorway_link")
      ),
      withModes(CAR)
    );

    // Walking and cycling illegal on "Motortrafikkvei"
    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","trunk", "trunk_link", "primary", "primary_link"),
        new Condition.Equals("motorroad", "yes")
      ),
      withModes(CAR)
    );

    /* "highway=trunk" roads ("Riksveier") are often single carriageway, and not only legal for bicycles,
    but assumed to be  much safer than the  default profile (Except when the speedlimt exceeds 90). */
    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","trunk", "trunk_link", "primary", "primary_link")
      ),
      withModes(ALL).bicycleSafety(high_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified")
      ),
      withModes(ALL).bicycleSafety(medium_high_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","residential", "service")
      ),
      withModes(ALL).bicycleSafety(low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link"),
        new Condition.GreaterThan("maxspeed", 80)
      ),
      withModes(ALL).bicycleSafety(very_high_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link"),
        new Condition.Equals("maxspeed", "60")
      ),
      withModes(ALL).bicycleSafety(medium_high_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","secondary", "secondary_link", "tertiary", "tertiary_link"),
        new Condition.Equals("maxspeed", "60")
      ),
      withModes(ALL).bicycleSafety(medium_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "unclassified", "residential"),
        new Condition.Equals("maxspeed", "60")
      ),
      withModes(ALL).bicycleSafety(medium_low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.Equals("highway", "unclassified"),
        new Condition.LessThan("maxspeed", 60)
      ),
      withModes(ALL).bicycleSafety(low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link"),
        new Condition.InclusiveRange("maxspeed", 50, 40)
      ),
      withModes(ALL).bicycleSafety(medium_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "secondary", "secondary_link"),
        new Condition.InclusiveRange("maxspeed", 50, 40)
      ),
      withModes(ALL).bicycleSafety(medium_low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "tertiary", "tertiary_link"),
        new Condition.Equals("maxspeed", "50")
      ),
      withModes(ALL).bicycleSafety(low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "tertiary", "tertiary_link"),
        new Condition.LessThan("maxspeed", 50)
      ),
      withModes(ALL).bicycleSafety(low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link"),
        new Condition.LessThan("highway", 40)
      ),
      withModes(ALL).bicycleSafety(low_traffic)
    );

    // Discourage cycling on roads with no infrastructure for neither walking nor cycling
    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"),
        new Condition.Equals("foot", "no")
      ),
      withModes(BICYCLE_AND_CAR).bicycleSafety(very_high_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"),
        new Condition.Equals("foot", "no")
      ),
      withModes(BICYCLE_AND_CAR).bicycleSafety(very_high_traffic)
    );

    // Discourage cycling on trunk road tunnels
    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway","unclassified", "residential", "service"),
        new Condition.Equals("foot", "no")
      ),
      withModes(BICYCLE_AND_CAR).bicycleSafety(medium_traffic)
    );

    /* No motor vehicle thro traffic means less traffic and safer cycling.
     Idea: use isMotorVehicleThroughTrafficExplicitlyDisallowed method of OSMTagMapper? */
    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.EqualsAnyIn("highway", "unclassified", "residential", "service"),
        new Condition.EqualsAnyIn("motor_vehicle", "no", "private", "permit", "destination")
      ),
      withModes(ALL).bicycleSafety(very_low_traffic)
    );

    // Cycling around reversing cars on a parking lot feels unsafe
    props.setProperties(
      "highway=service;service=parking_aisle",
      withModes(ALL).bicycleSafety(medium_traffic)
    );
    props.setProperties(
      "highway=service;service=drive-through",
      withModes(ALL).bicycleSafety(medium_traffic)
    );

    /* bicycle infrastructure */
    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.Equals("cycleway", "track"),
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street")
      ),
      withModes(ALL).bicycleSafety(dual_lane_or_oneway_cycleway)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.Equals("cycleway", "lane"),
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link")
      ),
      withModes(ALL).bicycleSafety(cycle_lane_medium_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.Equals("cycleway", "lane"),
        new Condition.LessThan("maxspeed", 50),
        new Condition.EqualsAnyIn("highway", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link")
      ),
      withModes(ALL).bicycleSafety(cycle_lane_low_traffic)
    );

    props.setProperties(
      new ExactMatchSpecifier(
        new Condition.Equals("cycleway", "lane"),
        new Condition.EqualsAnyIn("highway", "unclassified", "residential", "living_street")
      ),
      withModes(ALL).bicycleSafety(cycle_lane_low_traffic)
    );

    /* Pedestrian, living and cyclestreet */
    props.setProperties("highway=living_street", withModes(ALL).bicycleSafety(low_traffic));
    props.setProperties("highway=pedestrian", withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(1.2));

    props.setProperties(
      "highway=footway",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(dedicated_footway)
    );
    // "motor_vehicle=destination" indicates unwanted car traffic, signposted "Kjøring til eiendommene tillatt"
    props.setProperties(
      "highway=footway;motor_vehicle=destination",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(very_low_traffic)
    );

    props.setProperties(
      "highway=footway;footway=sidewalk",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(sidewalk)
    );
    props.setProperties(
      "highway=footway;footway=crossing",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(footway_crossing)
    );
    props.setProperties(
      "highway=cycleway;footway=sidewalk",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(dedicated_footway)
    );
    props.setProperties(
      "highway=cycleway;footway=crossing",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(footway_crossing)
    );
    props.setProperties(
      "highway=cycleway;cycleway=crossing",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(footway_crossing)
    );

    props.setProperties(
      "highway=cycleway",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(dedicated_cycleway)
    );
    props.setProperties(
      "highway=cycleway;lanes=2",
      withModes(BICYCLE).bicycleSafety(dual_lane_or_oneway_cycleway)
    );
    props.setProperties(
      "highway=cycleway;oneway=yes",
      withModes(BICYCLE).bicycleSafety(dual_lane_or_oneway_cycleway)
    );
    // "motor_vehicle=destination" indicates unwanted car traffic, signposted "Kjøring til eiendommene tillatt"
    props.setProperties(
      "highway=cycleway;motor_vehicle=destination",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(very_low_traffic)
    );

    // segregated=no takes' precedence if there is no "segregated" key. There is no penalty for a tag mismatch
    props.setProperties(
      "highway=cycleway;foot=designated;segregated=no",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(mixed_cycleway)
    );
    props.setProperties(
      "highway=cycleway;foot=designated;segregated=yes",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(dedicated_cycleway)
    );
    props.setProperties(
      "highway=cycleway;foot=designated;segregated=yes;lanes=2",
      withModes(BICYCLE).bicycleSafety(dual_lane_or_oneway_cycleway)
    );
    props.setProperties(
      "highway=path;foot=designated;bicycle=designated;segregated=no",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(mixed_cycleway)
    );
    props.setProperties(
      "highway=path;foot=designated;bicycle=designated;segregated=yes",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(dedicated_cycleway)
    );
    // "motor_vehicle=destination" indicates unwanted car traffic, signposted "Kjøring til eiendommene tillatt"
    props.setProperties(
      "highway=cycleway;foot=designated;segregated=*;motor_vehicle=destination",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(very_low_traffic)
    );
    props.setProperties(
      "highway=path;foot=designated;bicycle=designated;segregated=*;motor_vehicle=destination",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(very_low_traffic)
    );

    //relation properties are copied over to ways
    props.setMixinProperties(
      new LogicalOrSpecifier("lcn=yes", "rcn=yes", "ncn=yes"),
      ofBicycleSafety(0.7)
    );

    props.setProperties(
      "highway=busway",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(low_traffic)
    );
    props.setProperties("highway=track", withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(1.1));
    props.setProperties("highway=bridleway", withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(1.1));
    props.setProperties("highway=path", withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(1.5));
    props.setProperties("highway=steps", withModes(PEDESTRIAN));
    props.setProperties("highway=corridor", withModes(PEDESTRIAN));
    props.setProperties("highway=footway;indoor=yes", withModes(PEDESTRIAN));
    props.setProperties("highway=platform", withModes(PEDESTRIAN));
    props.setProperties("public_transport=platform", withModes(PEDESTRIAN));

    props.setMixinProperties("smoothness=intermediate", ofBicycleSafety(1.5));
    props.setMixinProperties("smoothness=bad", ofBicycleSafety(2));
    props.setProperties("highway=*;smoothness=very_bad", withModes(PEDESTRIAN));
    props.setProperties("highway=*;smoothness=horrible", withModes(NONE));
    props.setProperties("highway=*;smoothness=very_horrible", withModes(NONE));
    props.setProperties("highway=*;smoothness=impassable", withModes(NONE));

    props.setProperties("highway=*;mtb:scale=1", withModes(PEDESTRIAN));
    props.setProperties("highway=*;mtb:scale=2", withModes(PEDESTRIAN));
    props.setProperties("highway=*;mtb:scale=3", withModes(NONE));
    props.setProperties("highway=*;mtb:scale=4", withModes(NONE));
    props.setProperties("highway=*;mtb:scale=5", withModes(NONE));
    props.setProperties("highway=*;mtb:scale=6", withModes(NONE));

    props.setProperties(
      "highway=track;tracktype=grade1",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(very_low_traffic)
    );
    props.setProperties(
      "highway=track;tracktype=grade2",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(1.1)
    );
    props.setProperties(
      "highway=track;tracktype=grade3",
      withModes(PEDESTRIAN_AND_BICYCLE).bicycleSafety(1.5)
    );
    props.setProperties("highway=track;tracktype=grade4", withModes(PEDESTRIAN));
    props.setProperties("highway=track;tracktype=grade5", withModes(PEDESTRIAN));

    props.setProperties("highway=path;trail_visibility=bad", withModes(NONE));
    props.setProperties("highway=path;trail_visibility=no", withModes(NONE));
    props.setProperties("highway=path;trail_visibility=low", withModes(NONE));
    props.setProperties("highway=path;trail_visibility=poor", withModes(NONE));

    props.setProperties("highway=path;sac_scale=mountain_hiking", withModes(NONE));
    props.setProperties("highway=path;sac_scale=demanding_mountain_hiking", withModes(NONE));
    props.setProperties("highway=path;sac_scale=alpine_hiking", withModes(NONE));
    props.setProperties("highway=path;sac_scale=demanding_alpine_hiking", withModes(NONE));
    props.setProperties("highway=path;sac_scale=difficult_alpine_hiking", withModes(NONE));

    // paved but unfavorable
    props.setMixinProperties("surface=grass_paver", ofBicycleSafety(1.2));
    props.setMixinProperties("surface=sett", ofBicycleSafety(1.2));
    props.setMixinProperties("surface=cobblestone", ofBicycleSafety(1.2));
    props.setMixinProperties("surface=unhewn_cobblestone", ofBicycleSafety(1.5));
    // Can be slick if wet, but otherwise not unfavorable to bikes
    props.setMixinProperties("surface=metal_grid", ofBicycleSafety(1.2));
    props.setMixinProperties("surface=metal", ofBicycleSafety(1.2));

    // unpaved
    props.setMixinProperties("surface=unpaved", ofBicycleSafety(1.2));
    props.setMixinProperties("surface=compacted", ofBicycleSafety(1.2));
    props.setMixinProperties("surface=fine_gravel", ofBicycleSafety(1.3));
    props.setMixinProperties("surface=pebblestone", ofBicycleSafety(1.3));
    props.setMixinProperties("surface=gravel", ofBicycleSafety(1.3));
    props.setMixinProperties("surface=woodchip", ofBicycleSafety(1.5));
    props.setMixinProperties("surface=ground", ofBicycleSafety(1.5));
    props.setMixinProperties("surface=dirt", ofBicycleSafety(1.5));
    props.setMixinProperties("surface=earth", ofBicycleSafety(1.5));
    props.setMixinProperties("surface=grass", ofBicycleSafety(1.5));
    props.setMixinProperties("surface=mud", ofBicycleSafety(2));
    props.setMixinProperties("surface=sand", ofBicycleSafety(2));

    /*
     * Automobile speeds in Norway. General speed limit is 80kph unless signs says otherwise
     *
     */

    props.setCarSpeed("highway=motorway", 22.22f); // 80 km/t
    props.setCarSpeed("highway=motorway_link", 22.22f); // 80 km/t
    props.setCarSpeed("highway=trunk", 22.22f); // 80 km/t
    props.setCarSpeed("highway=trunk_link", 22.22f); // 80 km/t
    props.setCarSpeed("highway=primary", 22.22f); // 80 km/t
    props.setCarSpeed("highway=primary_link", 22.22f); // 80 km/t
    props.setCarSpeed("highway=secondary", 22.22f); // 80 km/t
    props.setCarSpeed("highway=secondary_link", 22.22f); // 80 km/t
    props.setCarSpeed("highway=tertiary", 22.22f); // 80 km/t
    props.setCarSpeed("highway=tertiary_link", 22.22f); // 80 km/t
    props.setCarSpeed("highway=living_street", 1.94f); // 7 km/t

    props.setCarSpeed("highway=pedestrian", 1.94f); // 7 km/t

    props.setCarSpeed("highway=residential", 8.33f); // 30 km/t
    props.setCarSpeed("highway=unclassified", 22.22f); // 80 km/t
    props.setCarSpeed("highway=service", 13.89f); // 50 km/t
    props.setCarSpeed("highway=track", 8.33f); // 30 km/t
    props.setCarSpeed("highway=road", 13.89f); // 50 km/t

    props.defaultSpeed = 22.22f; // 80kph

    new DefaultMapper().populateNotesAndNames(props);

    props.setSlopeOverride(new BestMatchSpecifier("bridge=*"), true);
    props.setSlopeOverride(new BestMatchSpecifier("embankment=*"), false);
    props.setSlopeOverride(new BestMatchSpecifier("tunnel=*"), true);
    props.setSlopeOverride(new BestMatchSpecifier("location=underground"), true);
    props.setSlopeOverride(new BestMatchSpecifier("indoor=yes"), true);
  }
}
