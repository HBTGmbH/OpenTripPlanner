package org.opentripplanner.netex.loader.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.opentripplanner.netex.loader.NetexXmlParser;

class NetexDocumentParserTest {

  /**
   * A composite frame may carry only metadata, for example versions, and no nested frames. The
   * content frames can then follow on the top level of the document.
   */
  private static final String COMPOSITE_FRAME_WITHOUT_FRAMES = """
  <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
    <PublicationTimestamp>2012-10-25T13:53:18</PublicationTimestamp>
    <ParticipantRef>TEST</ParticipantRef>
    <dataObjects>
      <CompositeFrame id="CF_1" version="1">
        <Name>Metadata only</Name>
      </CompositeFrame>
      <ResourceFrame id="RF_1" version="1">
        <organisations>
          <Operator id="OP_1" version="1">
            <Name>Operator 1</Name>
          </Operator>
        </organisations>
      </ResourceFrame>
    </dataObjects>
  </PublicationDelivery>
  """;

  @Test
  void parseCompositeFrameWithoutFrames() throws Exception {
    var index = new NetexEntityIndex();
    var document = new NetexXmlParser().parseXmlDoc(
      new ByteArrayInputStream(COMPOSITE_FRAME_WITHOUT_FRAMES.getBytes(StandardCharsets.UTF_8))
    );

    NetexDocumentParser.parseAndPopulateIndex(
      index,
      document,
      Set.of(),
      false,
      NetexProfileSupport.NORDIC,
      NetexDocumentParser.DefaultTimeZone.GMT
    );

    assertEquals("Operator 1", index.operatorsById.lookup("OP_1").getName().getValue());
  }

  @Test
  void useGmtIfTheDocumentHasNoTimeZone() throws Exception {
    var index = new NetexEntityIndex();

    parse(index, NetexDocumentParser.DefaultTimeZone.GMT);

    assertEquals("GMT", index.timeZone.get());
  }

  @Test
  void useTheDefaultTimeZoneIfTheDocumentHasNoTimeZone() throws Exception {
    var index = new NetexEntityIndex();
    var issueStore = new DefaultDataImportIssueStore();
    var defaultTimeZone = NetexDocumentParser.DefaultTimeZone.of(
      ZoneId.of("Europe/Berlin"),
      issueStore
    );

    parse(index, defaultTimeZone);
    parse(index, defaultTimeZone);

    assertEquals("Europe/Berlin", index.timeZone.get());
    assertEquals(
      List.of("NetexTimeZoneFallback"),
      issueStore.listIssues().stream().map(DataImportIssue::getType).toList()
    );
  }

  private static void parse(
    NetexEntityIndex index,
    NetexDocumentParser.DefaultTimeZone defaultTimeZone
  ) throws Exception {
    var document = new NetexXmlParser().parseXmlDoc(
      new ByteArrayInputStream(COMPOSITE_FRAME_WITHOUT_FRAMES.getBytes(StandardCharsets.UTF_8))
    );
    NetexDocumentParser.parseAndPopulateIndex(
      index,
      document,
      Set.of(),
      false,
      NetexProfileSupport.of(true, false),
      defaultTimeZone
    );
  }
}
