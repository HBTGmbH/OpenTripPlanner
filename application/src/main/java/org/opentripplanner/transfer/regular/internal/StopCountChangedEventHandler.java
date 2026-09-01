package org.opentripplanner.transfer.regular.internal;

import org.opentripplanner.framework.event.EventHandler;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transit.service.StopCountChangedEvent;

/**
 * Applies a {@link StopCountChangedEvent} to the mutable transfer repository by updating its stop
 * count. The stop-indexed transfer list itself is rebuilt at commit time by
 * {@link DefaultTransferRepository#freeze()} — this handler only records the new count within the
 * active write transaction.
 * <p>
 * This is how the transfer module stays decoupled from the {@code SiteRepository}: it learns the
 * stop count via this event rather than reading it from the site repository.
 */
public class StopCountChangedEventHandler
  implements EventHandler<StopCountChangedEvent, TransferRepository> {

  @Override
  public Class<StopCountChangedEvent> eventType() {
    return StopCountChangedEvent.class;
  }

  @Override
  public void handle(StopCountChangedEvent event, TransferRepository repository) {
    // The cast is safe: all transfer repositories are DefaultTransferRepository instances.
    // TODO Max: dont do the cast
    ((DefaultTransferRepository) repository).setStopCount(event.stopCount());
  }
}
