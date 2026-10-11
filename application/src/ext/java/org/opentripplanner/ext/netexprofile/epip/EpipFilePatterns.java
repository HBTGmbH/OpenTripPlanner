package org.opentripplanner.ext.netexprofile.epip;

import java.util.regex.Pattern;
import org.opentripplanner.netex.config.NetexFeedParameters;

/**
 * File name patterns for EPIP data sets. The default NeTEx file patterns follow the naming of the
 * Nordic profile, so EPIP files would not be loaded at all.
 * <p>
 * CEN/TS 16614-4 (EPIP), 8.14, recommends the file names
 * {@code NX-PI-<epip-version>_<country-code>_<provider-code>_<profile-type>_<doc-topic>_<date>.xml},
 * where the profile type is {@code LINE}, {@code NETWORK} or {@code STOP}, and the parts are
 * separated by "_" or "-". Examples: {@code NX-PI-01_DE_NAP_LINE_VHH-VHH-688_20230203.xml},
 * {@code NX-PI-01_GE_BVB_LINE-3_20161201.xml}, {@code NX-PI-01_UK_NAP_STOP_AA-471_20171212.xml}.
 * <p>
 * Stop offers are loaded as shared files, because line and network offers reference their stops.
 * Line and network offers are grouped by country and provider, so interchanges between the lines
 * of a provider can be resolved.
 */
public final class EpipFilePatterns {

  private static final String PREFIX = "NX-PI-\\d+_[A-Za-z]{2}_[^_]+";

  static final Pattern SHARED_FILE_PATTERN = Pattern.compile(PREFIX + "_STOP[-_].*\\.xml");
  static final Pattern SHARED_GROUP_FILE_PATTERN = Pattern.compile("$^");
  static final Pattern GROUP_FILE_PATTERN = Pattern.compile(
    "(" + PREFIX + ")_(?:LINE|NETWORK)[-_].*\\.xml"
  );

  private EpipFilePatterns() {}

  /**
   * Use the EPIP file patterns if the feed uses the default (Nordic) file patterns. Patterns
   * configured for the feed are kept.
   */
  public static NetexFeedParameters apply(NetexFeedParameters parameters) {
    if (!usesDefaultPatterns(parameters)) {
      return parameters;
    }
    return parameters
      .copyOf()
      .withSharedFilePattern(SHARED_FILE_PATTERN)
      .withSharedGroupFilePattern(SHARED_GROUP_FILE_PATTERN)
      .withGroupFilePattern(GROUP_FILE_PATTERN)
      .build();
  }

  private static boolean usesDefaultPatterns(NetexFeedParameters parameters) {
    var defaults = NetexFeedParameters.DEFAULT;
    return (
      same(defaults.sharedFilePattern(), parameters.sharedFilePattern()) &&
      same(defaults.sharedGroupFilePattern(), parameters.sharedGroupFilePattern()) &&
      same(defaults.groupFilePattern(), parameters.groupFilePattern())
    );
  }

  private static boolean same(Pattern a, Pattern b) {
    return a.pattern().equals(b.pattern());
  }
}
