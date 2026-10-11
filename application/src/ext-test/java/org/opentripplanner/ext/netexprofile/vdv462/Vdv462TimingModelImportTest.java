package org.opentripplanner.ext.netexprofile.vdv462;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.datastore.api.FileType;
import org.opentripplanner.datastore.file.DirectoryDataSource;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.graph_builder.model.ConfiguredCompositeDataSource;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.configure.NetexConfigure;
import org.opentripplanner.standalone.config.OtpConfigLoader;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.service.SiteRepository;
import org.opentripplanner.utils.time.TimeUtils;

/**
 * VDV 462 data without passing times: the journeys get their passing times from the run times and
 * wait times of the journey pattern with the NetexVdv462 feature; without it they are discarded.
 */
class Vdv462TimingModelImportTest {

  private static final String DATA_DIR =
    "src/ext-test/resources/org/opentripplanner/ext/netexprofile/vdv462/timing";

  private final DefaultDataImportIssueStore issueStore = new DefaultDataImportIssueStore();

  @Test
  void derivePassingTimesWithTheNetexVdv462Feature() {
    OTPFeature.NetexVdv462.testOn(() -> {
      var result = load();

      assertThat(result.getTripsById().values()).hasSize(2);
      assertThat(stopTimes(result, "VDV:ServiceJourney:1-0800"))
        .containsExactly("-/8:00", "8:05/8:06", "8:12/-")
        .inOrder();
      assertThat(stopTimes(result, "VDV:ServiceJourney:1-2000"))
        .containsExactly("-/20:00", "20:03/20:03", "20:07/-")
        .inOrder();
    });
  }

  @Test
  void discardJourneysWithoutPassingTimesWithoutTheFeature() {
    var result = load();

    assertThat(result.getTripsById().values()).isEmpty();
    assertThat(issueStore.listIssues().stream().map(DataImportIssue::getType)).contains(
      "ServiceJourneyWithoutPassingTimes"
    );
  }

  private TransitDataImportBuilder load() {
    var buildConfig = new OtpConfigLoader(new File(DATA_DIR)).loadBuildConfig();
    var dataSource = new DirectoryDataSource(new File(DATA_DIR, "netex"), FileType.NETEX);
    var transitBuilder = new TransitDataImportBuilder(new SiteRepository(), issueStore);
    try (
      var bundle = new NetexConfigure(buildConfig).netexBundle(
        transitBuilder,
        new ConfiguredCompositeDataSource<>(dataSource, buildConfig.netexDefaults)
      )
    ) {
      return bundle.loadBundle(new Deduplicator(), issueStore);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** The stop times of the trip as "arrival/departure", "-" at the first and last stop. */
  private static List<String> stopTimes(TransitDataImportBuilder result, String tripId) {
    var trip = result
      .getTripsById()
      .values()
      .stream()
      .filter(it -> it.getId().getId().equals(tripId))
      .findFirst()
      .orElseThrow();
    var stopTimes = result
      .getStopTimesSortedByTrip()
      .get(trip)
      .stream()
      .sorted(Comparator.comparingInt(StopTime::getStopSequence))
      .toList();
    var last = stopTimes.size() - 1;
    return stopTimes
      .stream()
      .map(it -> {
        int i = stopTimes.indexOf(it);
        return (
          (i == 0 ? "-" : TimeUtils.timeToStrCompact(it.getArrivalTime())) +
          "/" +
          (i == last ? "-" : TimeUtils.timeToStrCompact(it.getDepartureTime()))
        );
      })
      .toList();
  }
}
