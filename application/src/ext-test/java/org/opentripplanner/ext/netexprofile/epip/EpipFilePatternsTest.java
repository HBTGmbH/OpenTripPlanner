package org.opentripplanner.ext.netexprofile.epip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentripplanner.netex.config.NetexFeedParameters;

class EpipFilePatternsTest {

  @ParameterizedTest
  @CsvSource({
    "NX-PI-01_DE_NAP_LINE_VHH-VHH-688_20230203.xml, NX-PI-01_DE_NAP",
    "NX-PI-01_DE_NAP_LINE_HHA-B-HHA-B-X86_20230203.xml, NX-PI-01_DE_NAP",
    "NX-PI-01_GE_BVB_LINE-3_20161201.xml, NX-PI-01_GE_BVB",
    "NX-PI-01_CZ_CISJR-JDF_LINE-3710_20241101.xml, NX-PI-01_CZ_CISJR-JDF",
    "NX-PI-01_LU_NAP_LINE_AVL-AVL-91_20210113.xml, NX-PI-01_LU_NAP",
    "NX-PI-02_FR_FR1_NETWORK_RATP_20171105.xml, NX-PI-02_FR_FR1",
    "hvv_d_VHH/NX-PI-01_DE_NAP_LINE_VHH-VHH-688_20261003.xml, hvv_d_VHH/NX-PI-01_DE_NAP",
    "de/hvv_d_HHA-B/NX-PI-01_DE_NAP_LINE_HHA-B-X86_20261003.xml, de/hvv_d_HHA-B/NX-PI-01_DE_NAP",
  })
  void lineAndNetworkOffersAreGroupedByCountryAndProvider(String fileName, String group) {
    var matcher = EpipFilePatterns.GROUP_FILE_PATTERN.matcher(fileName);
    assertTrue(matcher.matches());
    assertEquals(group, matcher.group(1));
    assertFalse(EpipFilePatterns.SHARED_FILE_PATTERN.matcher(fileName).matches());
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "NX-PI-01_UK_NAP_STOP_AA-471_20171212.xml",
      "NX-PI-01_DE_NAP_STOP-HH_20230203.xml",
      "stops/NX-PI-01_DE_NAP_STOP_DE_20261003.xml",
    }
  )
  void stopOffersAreSharedFiles(String fileName) {
    assertTrue(EpipFilePatterns.SHARED_FILE_PATTERN.matcher(fileName).matches());
    assertFalse(EpipFilePatterns.GROUP_FILE_PATTERN.matcher(fileName).matches());
  }

  @Test
  void applyEpipPatternsIfTheFeedUsesTheDefaultPatterns() {
    var result = EpipFilePatterns.apply(NetexFeedParameters.DEFAULT);

    assertEquals(
      EpipFilePatterns.GROUP_FILE_PATTERN.pattern(),
      result.groupFilePattern().pattern()
    );
    assertEquals(
      EpipFilePatterns.SHARED_FILE_PATTERN.pattern(),
      result.sharedFilePattern().pattern()
    );
  }

  @Test
  void ignoreZipDirectoryEntries() {
    var result = EpipFilePatterns.apply(NetexFeedParameters.DEFAULT);

    assertTrue(result.ignoreFilePattern().matcher("hvv_d_VHH/").matches());
    assertFalse(
      result
        .ignoreFilePattern()
        .matcher("hvv_d_VHH/NX-PI-01_DE_NAP_LINE_VHH-1_20261003.xml")
        .matches()
    );
  }

  @Test
  void keepConfiguredPatterns() {
    var configured = NetexFeedParameters.of()
      .withGroupFilePattern(Pattern.compile("(.*)_.*\\.xml"))
      .build();

    assertSame(configured, EpipFilePatterns.apply(configured));
  }
}
