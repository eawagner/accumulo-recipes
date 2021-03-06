package org.calrissian.accumulorecipes.temporal.lastn;

import org.calrissian.accumulorecipes.commons.domain.Auths;
import org.calrissian.accumulorecipes.commons.domain.StoreEntry;
import org.calrissian.mango.collect.CloseableIterable;

import java.util.Collection;
import java.util.Date;

/**
 * The temporal last n store will return the last n events in some series of groups
 * for some range of time. It's especially useful for logs and news feeds as it gives
 * the ability to zero-in on a window of those events and, the case where multiple groups
 * are provided to the get method, provides a holistic view of the events in the those
 * groups.
 */
public interface TemporalLastNStore {

    /**
     * Puts an event into the store under the specified group.
     */
    void put(String group, StoreEntry entry);

    /**
     * Gets the last-n events from the store in a holistic view of the specified groups
     * for the specified time range.
     */
    CloseableIterable<StoreEntry> get(Date start, Date stop, Collection<String> groups, int n, Auths auths);
}
