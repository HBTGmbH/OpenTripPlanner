package org.opentripplanner.transfer.regular.internal;

import org.opentripplanner.ext.flex.FlexTransferIndex;
import org.opentripplanner.framework.application.OTPFeature;

/**
 * Selects the transfer index implementation, mirroring the choice previously made in
 * {@code TransferRepositoryModule}. Kept here so both the build-time wiring and the snapshot's
 * {@code copyOnWrite()} pick the same index type.
 */
public final class TransferIndexFactory {

  private TransferIndexFactory() {}

  public static TransferIndex createIndex() {
    return OTPFeature.FlexRouting.isOn() ? new FlexTransferIndex() : new TransferIndex();
  }
}
