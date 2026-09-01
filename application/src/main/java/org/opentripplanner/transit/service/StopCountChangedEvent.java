package org.opentripplanner.transit.service;

import org.opentripplanner.framework.event.DomainEvent;

/**
 * Published when the number of stops (the size of the dense stop-index space) is established or
 * changes. Owned by the site/transit domain, which is the source of truth for stops.
 * <p>
 * At startup an initial event is published to seed consumers with the current stop count. Once the
 * {@code SiteRepository} becomes updateable, the same event will be published on every change, so
 * consumers (e.g. the regular-transfer repository, which uses it to size its Raptor stop-index)
 * react without depending on the {@code SiteRepository} directly.
 *
 * @param stopCount the size of the dense stop-index space (i.e. {@code SiteRepository.stopIndexSize()})
 */
public record StopCountChangedEvent(int stopCount) implements DomainEvent {}
