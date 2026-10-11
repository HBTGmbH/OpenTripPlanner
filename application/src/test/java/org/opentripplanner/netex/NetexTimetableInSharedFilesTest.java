package org.opentripplanner.netex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.opentripplanner.datastore.api.FileType;
import org.opentripplanner.datastore.file.DirectoryDataSource;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.model.ConfiguredCompositeDataSource;
import org.opentripplanner.model.TransitDataImport;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.configure.NetexConfigure;
import org.opentripplanner.standalone.config.OtpConfigLoader;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.service.SiteRepository;

/**
 * The Nordic profile puts timetable data only in group files, but data sets delivered as a single
 * file (common for EPIP) may be configured as shared files. This test loads the EPIP test data
 * set with every file configured as a shared file and expects the same timetable as when the
 * files are loaded as group files.
 */
class NetexTimetableInSharedFilesTest {

  private static final String EPIP_DIR = "src/test/resources/netex/epip/";

  @Test
  void loadTimetableFromSharedFiles() throws IOException {
    var buildConfig = new OtpConfigLoader(new File(EPIP_DIR)).loadBuildConfig();
    var parameters = buildConfig.netexDefaults
      .copyOf()
      .withSharedFilePattern(Pattern.compile(".*\\.xml"))
      .build();
    var dataSource = new DirectoryDataSource(
      new File(EPIP_DIR, "netex_epip_minimal"),
      FileType.NETEX
    );
    var transitBuilder = new TransitDataImportBuilder(
      new SiteRepository(),
      DataImportIssueStore.NOOP
    );

    TransitDataImport result;
    try (
      var bundle = new NetexConfigure(buildConfig).netexBundle(
        transitBuilder,
        new ConfiguredCompositeDataSource<>(dataSource, parameters)
      )
    ) {
      result = bundle.loadBundle(new Deduplicator(), DataImportIssueStore.NOOP).build();
    }

    assertEquals(4, result.getTripPatterns().size());
    assertEquals(110, result.getAllTrips().size());
  }
}
