package org.opentripplanner.netex.loader.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
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

    NetexDocumentParser.parseAndPopulateIndex(index, document, Set.of(), false);

    assertEquals("Operator 1", index.operatorsById.lookup("OP_1").getName().getValue());
  }
}
