package org.opentripplanner.standalone.config.buildconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.standalone.config.framework.JsonSupport.newNodeAdapterForTest;

import org.junit.jupiter.api.Test;
import org.opentripplanner.gtfs.graphbuilder.GtfsFeedParameters;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.transit.model.site.StopTransferPriority;

class GtfsConfigTest {

  @Test
  void mapGtfsDefaultParameters() {
    NodeAdapter nodeAdapter = newNodeAdapterForTest(
      """
      {
        gtfsDefaults: {
         'removeRepeatedStops': false,
         'stationTransferPreference' : 'preferred',
         'discardMinTransferTimes': true,
         'blockBasedInterlining': false
       }
      }
      """
    );

    var subject = GtfsConfig.mapGtfsDefaultParameters(nodeAdapter, "gtfsDefaults");

    assertFalse(subject.removeRepeatedStops());
    assertEquals(StopTransferPriority.PREFERRED, subject.stationTransferPreference());
    assertTrue(subject.discardMinTransferTimes());
    assertFalse(subject.blockBasedInterlining());
  }

  @Test
  void mapMissingGtfsDefaultParameters() {
    NodeAdapter nodeAdapter = newNodeAdapterForTest("""
      {
      }
      """);

    var subject = GtfsConfig.mapGtfsDefaultParameters(nodeAdapter, "gtfsDefaults");

    assertTrue(subject.removeRepeatedStops());
    assertEquals(StopTransferPriority.ALLOWED, subject.stationTransferPreference());
    assertFalse(subject.discardMinTransferTimes());
    assertTrue(subject.blockBasedInterlining());
  }

  @Test
  void mapGtfsFeedWithDefaults() {
    NodeAdapter defaultsAdapter = newNodeAdapterForTest(
      """
      {
        gtfsDefaults: {
         'removeRepeatedStops': false,
         'stationTransferPreference' : 'preferred',
         'discardMinTransferTimes': true,
         'blockBasedInterlining': false
       }
      }
      """
    );

    var defaults = GtfsConfig.mapGtfsDefaultParameters(defaultsAdapter, "gtfsDefaults");

    NodeAdapter nodeAdapter = newNodeAdapterForTest(
      """
      {
         'source': 'https://foo.bar/gtfs.zip',
         'feedId': 'test'
      }
      """
    );

    GtfsFeedParameters subject = GtfsConfig.mapGtfsFeed(nodeAdapter, defaults);

    assertEquals("https://foo.bar/gtfs.zip", subject.source().toASCIIString());
    assertEquals("test", subject.feedId().get());
    assertFalse(subject.removeRepeatedStops());
    assertEquals(StopTransferPriority.PREFERRED, subject.stationTransferPreference());
    assertTrue(subject.discardMinTransferTimes());
    assertFalse(subject.blockBasedInterlining());
  }

  @Test
  void mapGtfsFeedWithConflictingDefaults() {
    NodeAdapter defaultsAdapter = newNodeAdapterForTest(
      """
      {
        gtfsDefaults: {
         'removeRepeatedStops': true,
         'stationTransferPreference' : 'allowed',
         'discardMinTransferTimes': true,
         'blockBasedInterlining': true
       }
      }
      """
    );

    var defaults = GtfsConfig.mapGtfsDefaultParameters(defaultsAdapter, "gtfsDefaults");

    NodeAdapter nodeAdapter = newNodeAdapterForTest(
      """
      {
         'source': 'https://foo.bar/gtfs.zip',
         'feedId': 'test',
         'removeRepeatedStops': false,
         'stationTransferPreference' : 'preferred',
         'discardMinTransferTimes': false,
         'blockBasedInterlining': false
      }
      """
    );

    GtfsFeedParameters subject = GtfsConfig.mapGtfsFeed(nodeAdapter, defaults);

    assertEquals("https://foo.bar/gtfs.zip", subject.source().toASCIIString());
    assertEquals("test", subject.feedId().get());
    assertFalse(subject.removeRepeatedStops());
    assertEquals(StopTransferPriority.PREFERRED, subject.stationTransferPreference());
    assertFalse(subject.discardMinTransferTimes());
    assertFalse(subject.blockBasedInterlining());
  }

  @Test
  void mapGtfsFeedWithNoDefaults() {
    NodeAdapter noDefaultsAdapter = newNodeAdapterForTest("""
      {
      }
      """);

    var defaults = GtfsConfig.mapGtfsDefaultParameters(noDefaultsAdapter, "gtfsDefaults");

    NodeAdapter nodeAdapter = newNodeAdapterForTest(
      """
      {
         'source': 'https://foo.bar/gtfs.zip',
         'feedId': 'test',
         'removeRepeatedStops': 'false',
         'stationTransferPreference' : 'preferred',
         'discardMinTransferTimes': true,
         'blockBasedInterlining': false
      }
      """
    );

    GtfsFeedParameters subject = GtfsConfig.mapGtfsFeed(nodeAdapter, defaults);

    assertEquals("https://foo.bar/gtfs.zip", subject.source().toASCIIString());
    assertEquals("test", subject.feedId().get());
    assertFalse(subject.removeRepeatedStops());
    assertEquals(StopTransferPriority.PREFERRED, subject.stationTransferPreference());
    assertTrue(subject.discardMinTransferTimes());
    assertFalse(subject.blockBasedInterlining());
  }
}
