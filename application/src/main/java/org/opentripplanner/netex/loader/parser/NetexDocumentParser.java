package org.opentripplanner.netex.loader.parser;

import static org.opentripplanner.netex.config.IgnorableFeature.FARE_FRAME;

import jakarta.xml.bind.JAXBElement;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.config.IgnorableFeature;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.rutebanken.netex.model.Common_VersionFrameStructure;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.FareFrame;
import org.rutebanken.netex.model.GeneralFrame;
import org.rutebanken.netex.model.InfrastructureFrame;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.ResourceFrame;
import org.rutebanken.netex.model.ServiceCalendarFrame;
import org.rutebanken.netex.model.ServiceFrame;
import org.rutebanken.netex.model.SiteFrame;
import org.rutebanken.netex.model.TimetableFrame;
import org.rutebanken.netex.model.VersionFrameDefaultsStructure;
import org.rutebanken.netex.model.VersionFrame_VersionStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the root parser for a Netex XML Document. The parser ONLY read the document and populate
 * the index with entities. The parser is only responsible for populating the index, not for
 * validating the document, nor linking of entities or mapping the OTP internal data structures.
 */
public class NetexDocumentParser {

  private static final Logger LOG = LoggerFactory.getLogger(NetexDocumentParser.class);

  private final NetexEntityIndex netexIndex;
  private final Set<IgnorableFeature> ignoredFeatures;
  private final boolean allowStopPlaceAsPassengerStopAssignment;
  private final NetexProfileSupport profileSupport;
  private final DefaultTimeZone defaultTimeZone;

  private NetexDocumentParser(
    NetexEntityIndex netexIndex,
    Set<IgnorableFeature> ignoredFeatures,
    boolean allowStopPlaceAsPassengerStopAssignment,
    NetexProfileSupport profileSupport,
    DefaultTimeZone defaultTimeZone
  ) {
    this.netexIndex = netexIndex;
    this.ignoredFeatures = ignoredFeatures;
    this.allowStopPlaceAsPassengerStopAssignment = allowStopPlaceAsPassengerStopAssignment;
    this.profileSupport = profileSupport;
    this.defaultTimeZone = defaultTimeZone;
  }

  /**
   * This static method create a new parser and parse the document. The result is added to given
   * index for further processing.
   */
  public static void parseAndPopulateIndex(
    NetexEntityIndex index,
    PublicationDeliveryStructure doc,
    Set<IgnorableFeature> ignoredFeatures,
    boolean allowStopPlaceAsPassengerStopAssignment,
    NetexProfileSupport profileSupport,
    DefaultTimeZone defaultTimeZone
  ) {
    new NetexDocumentParser(
      index,
      ignoredFeatures,
      allowStopPlaceAsPassengerStopAssignment,
      profileSupport,
      defaultTimeZone
    ).parse(doc);
  }

  /** Top level parse method - parses the document. */
  private void parse(PublicationDeliveryStructure doc) {
    parseFrameList(doc.getDataObjects().getCompositeFrameOrCommonFrame());
  }

  private void parseFrameList(List<JAXBElement<? extends Common_VersionFrameStructure>> frames) {
    for (JAXBElement<? extends Common_VersionFrameStructure> frame : frames) {
      parseCommonFrame(frame.getValue());
    }
  }

  private void parseCommonFrame(Common_VersionFrameStructure value) {
    if (value instanceof ResourceFrame frame) {
      parse(frame, new ResourceFrameParser());
    } else if (value instanceof ServiceCalendarFrame frame) {
      parse(frame, new ServiceCalendarFrameParser(profileSupport));
    } else if (value instanceof TimetableFrame frame) {
      parse(frame, new TimeTableFrameParser());
    } else if (value instanceof ServiceFrame frame) {
      parse(
        frame,
        new ServiceFrameParser(
          netexIndex.flexibleStopPlaceById,
          allowStopPlaceAsPassengerStopAssignment,
          profileSupport
        )
      );
    } else if (value instanceof SiteFrame frame) {
      parse(frame, new SiteFrameParser(ignoredFeatures));
    } else if (!ignoredFeatures.contains(FARE_FRAME) && value instanceof FareFrame fareFrame) {
      parse(fareFrame, new FareFrameParser());
    } else if (value instanceof CompositeFrame frame) {
      // We recursively parse composite frames and content until there
      // is no more nested frames - this is accepting documents which
      // are not withing the specification, but we leave this for the
      // document schema validation - not a OTP responsibility
      parseCompositeFrame(frame);
    } else if (value instanceof GeneralFrame || value instanceof InfrastructureFrame) {
      NetexParser.informOnElementIntentionallySkipped(LOG, value);
    } else {
      NetexParser.warnOnMissingMapping(LOG, value);
    }
  }

  private void parseCompositeFrame(CompositeFrame frame) {
    // Declare some ugly types to prevent obstructing the reading later...
    Collection<JAXBElement<? extends Common_VersionFrameStructure>> frames;

    netexIndex.timeZone.set(resolveTimeZone(frame.getFrameDefaults()));

    // A composite frame without nested frames is valid NeTEx, it may only carry metadata
    if (frame.getFrames() == null) {
      return;
    }
    frames = frame.getFrames().getCommonFrame();

    for (JAXBElement<? extends Common_VersionFrameStructure> it : frames) {
      parseCommonFrame(it.getValue());
    }
  }

  private <T> void parse(T node, NetexParser<T> parser) {
    parser.parse(node);
    parser.setResultOnIndex(netexIndex);

    if (node instanceof VersionFrame_VersionStructure frame) {
      netexIndex.timeZone.set(resolveTimeZone(frame.getFrameDefaults()));
    }
  }

  private String resolveTimeZone(VersionFrameDefaultsStructure frameDefaults) {
    if (frameDefaults != null) {
      var defaultLocale = frameDefaults.getDefaultLocale();
      if (defaultLocale != null && defaultLocale.getTimeZone() != null) {
        return defaultLocale.getTimeZone();
      }
    }
    // Fallback to previously set time zone in hierarchy
    if (netexIndex.timeZone.get() != null) {
      return netexIndex.timeZone.get();
    }
    return defaultTimeZone.timeZoneForFrameWithoutTimeZone();
  }

  /**
   * The time zone used when neither the frame nor any frame above it in the hierarchy state one.
   * The Nordic profile requires the time zone, so this is GMT unless an explicit default is given.
   */
  public static final class DefaultTimeZone {

    public static final DefaultTimeZone GMT = new DefaultTimeZone(null, null);

    @Nullable
    private final ZoneId timeZone;

    @Nullable
    private final DataImportIssueStore issueStore;

    private boolean reported = false;

    private DefaultTimeZone(@Nullable ZoneId timeZone, @Nullable DataImportIssueStore issueStore) {
      this.timeZone = timeZone;
      this.issueStore = issueStore;
    }

    /** Use the given time zone and report its use once per feed. */
    public static DefaultTimeZone of(ZoneId timeZone, DataImportIssueStore issueStore) {
      return new DefaultTimeZone(timeZone, issueStore);
    }

    private String timeZoneForFrameWithoutTimeZone() {
      if (timeZone == null) {
        return "GMT";
      }
      if (!reported && issueStore != null) {
        issueStore.add(
          "NetexTimeZoneFallback",
          "The NeTEx data does not state a time zone (FrameDefaults/DefaultLocale/TimeZone). The " +
            "transitModelTimeZone %s from the build configuration is used.",
          timeZone
        );
        reported = true;
      }
      return timeZone.getId();
    }
  }
}
