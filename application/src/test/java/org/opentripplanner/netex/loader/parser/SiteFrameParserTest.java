package org.opentripplanner.netex.loader.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.netex.NetexTestDataSupport.createQuay;
import static org.opentripplanner.netex.NetexTestDataSupport.createStopPlace;

import jakarta.xml.bind.JAXBElement;
import java.util.Collection;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.netex.NetexTestDataSupport;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.SiteFrame;
import org.rutebanken.netex.model.SiteRefStructure;
import org.rutebanken.netex.model.Site_VersionStructure;
import org.rutebanken.netex.model.StopPlace;
import org.rutebanken.netex.model.TypeOfPlaceRefStructure;
import org.rutebanken.netex.model.TypeOfPlaceRefs_RelStructure;

class SiteFrameParserTest {

  private static final ObjectFactory OBJECT_FACTORY = new ObjectFactory();
  private static final String PARENT_ID = "DE::StopPlace:6989::";
  private static final String CHILD_ID = "DE::StopPlace:1069890000::";

  @Test
  void testParseQuays() {
    SiteFrameParser siteFrameParser = new SiteFrameParser(Set.of(), NetexProfileSupport.NORDIC);
    SiteFrame siteFrame = OBJECT_FACTORY.createSiteFrame();
    NetexEntityIndex netexEntityIndex = new NetexEntityIndex();

    Quay quay = createQuay();
    StopPlace stopPlace = createStopPlace(quay);
    JAXBElement<? extends Site_VersionStructure> jaxbStopPlace = OBJECT_FACTORY.createStopPlace(
      stopPlace
    );

    siteFrame.setStopPlaces(OBJECT_FACTORY.createStopPlacesInFrame_RelStructure());
    siteFrame.getStopPlaces().getStopPlace_().add(jaxbStopPlace);

    siteFrameParser.parse(siteFrame);
    siteFrameParser.setResultOnIndex(netexEntityIndex);
    Collection<Quay> mappedQuays = netexEntityIndex.quayById.lookup(NetexTestDataSupport.QUAY_ID);
    assertEquals(1, mappedQuays.size());
  }

  @Test
  void parentStopPlaceWithoutQuaysIsMultiModalWithExtendedNetex() {
    var index = parse(NetexProfileSupport.of(true, false), parent(), child());

    assertNotNull(index.multiModalStopPlaceById.lookup(PARENT_ID));
    assertTrue(index.stopPlaceById.lookup(PARENT_ID).isEmpty());
    assertFalse(index.stopPlaceById.lookup(CHILD_ID).isEmpty());
  }

  @Test
  void stopPlaceTypedAsGeneralIsMultiModalWithExtendedNetex() {
    var general = parent().withPlaceTypes(
      new TypeOfPlaceRefs_RelStructure().withTypeOfPlaceRef(
        new TypeOfPlaceRefStructure().withRef("epip:general")
      )
    );

    var index = parse(NetexProfileSupport.of(true, false), general);

    assertNotNull(index.multiModalStopPlaceById.lookup(PARENT_ID));
  }

  @Test
  void parentStopPlaceWithQuaysIsAStation() {
    var parentWithQuay = createStopPlace(createQuay()).withId(PARENT_ID);

    var index = parse(NetexProfileSupport.of(true, false), parentWithQuay, child());

    assertNull(index.multiModalStopPlaceById.lookup(PARENT_ID));
    assertFalse(index.stopPlaceById.lookup(PARENT_ID).isEmpty());
  }

  @Test
  void parentStopPlaceIsAStationForTheNordicProfile() {
    var index = parse(NetexProfileSupport.NORDIC, parent(), child());

    assertNull(index.multiModalStopPlaceById.lookup(PARENT_ID));
    assertFalse(index.stopPlaceById.lookup(PARENT_ID).isEmpty());
  }

  private static NetexEntityIndex parse(
    NetexProfileSupport profileSupport,
    StopPlace... stopPlaces
  ) {
    var parser = new SiteFrameParser(Set.of(), profileSupport);
    var siteFrame = OBJECT_FACTORY.createSiteFrame();
    siteFrame.setStopPlaces(OBJECT_FACTORY.createStopPlacesInFrame_RelStructure());
    for (StopPlace stopPlace : stopPlaces) {
      siteFrame.getStopPlaces().getStopPlace_().add(OBJECT_FACTORY.createStopPlace(stopPlace));
    }
    var index = new NetexEntityIndex();
    parser.parse(siteFrame);
    parser.setResultOnIndex(index);
    return index;
  }

  private static StopPlace parent() {
    return new StopPlace().withId(PARENT_ID).withVersion("1");
  }

  private static StopPlace child() {
    return createStopPlace(createQuay())
      .withId(CHILD_ID)
      .withParentSiteRef(new SiteRefStructure().withRef(PARENT_ID));
  }
}
