package org.opentripplanner.netex.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentripplanner.framework.application.OTPFeature;

class NetexProfileSupportTest {

  @Test
  void nordicHasAllPartsDisabled() {
    assertSame(NetexProfileSupport.NORDIC, NetexProfileSupport.of(false, false));
    assertFalse(NetexProfileSupport.NORDIC.epip());
    assertFalse(NetexProfileSupport.NORDIC.vdv462());
    assertFalse(NetexProfileSupport.NORDIC.extendedNetex());
  }

  @Test
  void eachProfileEnablesExtendedNetex() {
    var epip = NetexProfileSupport.of(true, false);
    assertTrue(epip.epip());
    assertFalse(epip.vdv462());
    assertTrue(epip.extendedNetex());

    var vdv462 = NetexProfileSupport.of(false, true);
    assertFalse(vdv462.epip());
    assertTrue(vdv462.vdv462());
    assertTrue(vdv462.extendedNetex());
  }

  @Test
  void allowStopPlaceAsPassengerStopAssignment() {
    assertFalse(NetexProfileSupport.NORDIC.allowStopPlaceAsPassengerStopAssignment(false));
    assertTrue(NetexProfileSupport.NORDIC.allowStopPlaceAsPassengerStopAssignment(true));
    assertTrue(NetexProfileSupport.of(true, false).allowStopPlaceAsPassengerStopAssignment(false));
    assertTrue(NetexProfileSupport.of(false, true).allowStopPlaceAsPassengerStopAssignment(false));
  }

  @Test
  void ofFeatures() {
    assertSame(NetexProfileSupport.NORDIC, NetexProfileSupport.ofFeatures());
    OTPFeature.NetexEpip.testOn(() -> assertTrue(NetexProfileSupport.ofFeatures().epip()));
    OTPFeature.NetexVdv462.testOn(() -> assertTrue(NetexProfileSupport.ofFeatures().vdv462()));
  }
}
