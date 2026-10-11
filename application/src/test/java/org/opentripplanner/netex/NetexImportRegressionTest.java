package org.opentripplanner.netex;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueSummary;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.model.TransitDataImport;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.timetable.Trip;

/**
 * Guards the result of the NeTEx import against unintended changes. The test imports the small
 * Nordic and EPIP test data sets and compares the number of imported entities and the number of
 * data import issues by type with a recorded baseline.
 * <p>
 * Changes to the NeTEx import that are expected to alter the result for one of the data sets must
 * update the baseline in the same commit, so the effect is visible in the review.
 */
class NetexImportRegressionTest {

  private static final String NORDIC_BASELINE = """
  agencies=1
  groupsOfStations=0
  issues={NoticeAssignmentWithoutNotice=1, StopPlaceMissingFareZone=4}
  multiModalStations=3
  noticeAssignments=4
  operators=1
  regularStops=16
  routes=2
  serviceIds=3
  stations=5
  transfers=0
  tripOnServiceDates=1
  tripPatterns=4
  trips=5
  """;

  private static final String EPIP_BASELINE = """
  agencies=3
  groupsOfStations=0
  issues={}
  multiModalStations=0
  noticeAssignments=0
  operators=2
  regularStops=31
  routes=2
  serviceIds=2
  stations=20
  transfers=0
  tripOnServiceDates=0
  tripPatterns=4
  trips=110
  """;

  private static final String EPIP_WITH_FEATURE_BASELINE = """
  agencies=3
  groupsOfStations=0
  issues={}
  multiModalStations=0
  noticeAssignments=2032
  operators=2
  regularStops=31
  routes=2
  serviceIds=2
  stations=20
  transfers=0
  tripOnServiceDates=0
  tripPatterns=4
  trips=110
  """;

  /** The profile features only extend the import for constructs the Nordic data does not use. */
  @Test
  void nordicImportIsUnchangedWithProfileFeatures() {
    OTPFeature.NetexEpip.testOn(() ->
      OTPFeature.NetexVdv462.testOn(() ->
        assertThat(importSummary(ConstantsForTests::createMinimalNetexNordicBundle)).isEqualTo(
          NORDIC_BASELINE
        )
      )
    );
  }

  @Test
  void epipImportWithNetexEpipFeature() {
    OTPFeature.NetexEpip.testOn(() ->
      assertThat(importSummary(ConstantsForTests::createMinimalNetexEpipBundle)).isEqualTo(
        EPIP_WITH_FEATURE_BASELINE
      )
    );
  }

  @Test
  void nordicImportIsUnchanged() {
    assertThat(importSummary(ConstantsForTests::createMinimalNetexNordicBundle)).isEqualTo(
      NORDIC_BASELINE
    );
  }

  @Test
  void epipImportIsUnchanged() {
    assertThat(importSummary(ConstantsForTests::createMinimalNetexEpipBundle)).isEqualTo(
      EPIP_BASELINE
    );
  }

  private static String importSummary(Supplier<NetexBundle> bundleFactory) {
    var issueStore = new DefaultDataImportIssueStore();
    TransitDataImport result;
    try (NetexBundle bundle = bundleFactory.get()) {
      result = bundle.loadBundle(new Deduplicator(), issueStore).build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Map<String, Object> summary = new TreeMap<>();
    summary.put("agencies", result.getAllAgencies().size());
    summary.put("operators", result.getAllOperators().size());
    summary.put("regularStops", result.siteRepository().listRegularStops().size());
    summary.put("stations", result.siteRepository().listStations().size());
    summary.put("multiModalStations", result.siteRepository().listMultiModalStations().size());
    summary.put("groupsOfStations", result.siteRepository().listGroupOfStations().size());
    summary.put("routes", result.getAllTrips().stream().map(Trip::getRoute).distinct().count());
    summary.put("tripPatterns", result.getTripPatterns().size());
    summary.put("trips", result.getAllTrips().size());
    summary.put("tripOnServiceDates", result.getTripOnServiceDates().size());
    summary.put("serviceIds", result.getAllServiceIds().size());
    summary.put("transfers", result.getAllTransfers().size());
    summary.put("noticeAssignments", result.getNoticeAssignments().size());
    summary.put(
      "issues",
      new TreeMap<>(new DataImportIssueSummary(issueStore.listIssues()).asMap())
    );

    var buf = new StringBuilder();
    summary.forEach((key, value) -> buf.append(key).append('=').append(value).append('\n'));
    return buf.toString();
  }
}
