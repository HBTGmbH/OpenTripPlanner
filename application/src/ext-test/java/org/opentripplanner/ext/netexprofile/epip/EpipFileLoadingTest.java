package org.opentripplanner.ext.netexprofile.epip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.opentripplanner.datastore.api.FileType;
import org.opentripplanner.datastore.file.DirectoryDataSource;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.model.ConfiguredCompositeDataSource;
import org.opentripplanner.model.TransitDataImport;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.config.NetexFeedParameters;
import org.opentripplanner.netex.configure.NetexConfigure;
import org.opentripplanner.standalone.config.OtpConfigLoader;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.service.SiteRepository;

/**
 * EPIP files from a national access point use EPIP file names. With the default (Nordic) file
 * patterns they are not loaded at all; with the NetexEpip feature the EPIP patterns are used.
 */
class EpipFileLoadingTest {

  private static final String EPIP_DIR = "src/test/resources/netex/epip/";

  @Test
  void epipFilesAreNotLoadedWithTheDefaultPatterns() {
    assertEquals(0, load().getAllTrips().size());
  }

  @Test
  void epipFilesAreLoadedWithTheNetexEpipFeature() {
    OTPFeature.NetexEpip.testOn(() -> assertEquals(110, load().getAllTrips().size()));
  }

  private static TransitDataImport load() {
    var buildConfig = new OtpConfigLoader(new File(EPIP_DIR)).loadBuildConfig();
    var dataSource = new DirectoryDataSource(
      new File(EPIP_DIR, "netex_epip_minimal"),
      FileType.NETEX
    );
    var transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );
    try (
      var bundle = new NetexConfigure(buildConfig).netexBundle(
        transitBuilder,
        new ConfiguredCompositeDataSource<>(dataSource, NetexFeedParameters.DEFAULT)
      )
    ) {
      return bundle.loadBundle(new Deduplicator(), DataImportIssueStore.NOOP).build();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
