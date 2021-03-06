package org.calrissian.accumulorecipes.geospatialstore.impl;

import com.google.common.collect.Iterables;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.calrissian.accumulorecipes.commons.domain.Auths;
import org.calrissian.accumulorecipes.commons.domain.StoreEntry;
import org.calrissian.mango.collect.CloseableIterable;
import org.calrissian.mango.domain.Tuple;
import org.junit.Before;
import org.junit.Test;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccumuloGeoSpatialStoreTest {

    Connector connector;
    AccumuloGeoSpatialStore store;

    @Before
    public void setUp() throws AccumuloSecurityException, AccumuloException, TableExistsException, TableNotFoundException {
        Instance instance = new MockInstance();
        connector = instance.getConnector("root", "".getBytes());

        store = new AccumuloGeoSpatialStore(connector);
    }

    @Test
    public void test_singleEntryReturns() throws AccumuloSecurityException, AccumuloException, TableExistsException, TableNotFoundException {

        StoreEntry entry = new StoreEntry();
        entry.put(new Tuple("Key1", "Val1", ""));
        entry.put(new Tuple("key2", "val2", ""));

        store.put(singleton(entry), new Point2D.Double(-1, 1));

        CloseableIterable<StoreEntry> entries = store.get(new Rectangle2D.Double(-1.0, -1.0, 2.0, 2.0), new Auths());
        assertEquals(1, Iterables.size(entries));
        assertEquals(entry, Iterables.get(entries, 0));
    }

    @Test
    public void test_singleEntryReturns_withMultipleEntriesInStore() throws AccumuloSecurityException, AccumuloException, TableExistsException, TableNotFoundException {

        StoreEntry entry = new StoreEntry();
        entry.put(new Tuple("Key1", "Val1", ""));
        entry.put(new Tuple("key2", "val2", ""));

        StoreEntry entry2 = new StoreEntry();
        entry2.put(new Tuple("Key1", "Val1", ""));
        entry2.put(new Tuple("key2", "val2", ""));


        store.put(singleton(entry), new Point2D.Double(-1, 1));
        store.put(singleton(entry2), new Point2D.Double(-5, 1));

        CloseableIterable<StoreEntry> entries = store.get(new Rectangle2D.Double(-1.0, -1.0, 2.0, 2.0), new Auths());
        assertEquals(1, Iterables.size(entries));
        assertEquals(entry, Iterables.get(entries, 0));
    }

    @Test
    public void test_multipleEntriesReturn_withMultipleEntriesInStore() throws AccumuloSecurityException, AccumuloException, TableExistsException, TableNotFoundException {

        StoreEntry entry = new StoreEntry();
        entry.put(new Tuple("Key1", "Val1", ""));
        entry.put(new Tuple("key2", "val2", ""));

        StoreEntry entry2 = new StoreEntry();
        entry2.put(new Tuple("Key1", "Val1", ""));
        entry2.put(new Tuple("key2", "val2", ""));

        StoreEntry entry3 = new StoreEntry();
        entry3.put(new Tuple("Key1", "Val1", ""));
        entry3.put(new Tuple("key2", "val2", ""));


        store.put(singleton(entry), new Point2D.Double(-1, 1));
        store.put(singleton(entry2), new Point2D.Double(1, 1));
        store.put(singleton(entry3), new Point2D.Double(1, -1));

        CloseableIterable<StoreEntry> entries = store.get(new Rectangle2D.Double(-1.0, -1.0, 2.0, 2.0), new Auths());
        assertEquals(3, Iterables.size(entries));

        StoreEntry actualEntry1 = Iterables.get(entries, 0);
        assertTrue(actualEntry1.equals(entry) || actualEntry1.equals(entry2) || actualEntry1.equals(entry3));

        StoreEntry actualEntry2 = Iterables.get(entries, 0);
        assertTrue(actualEntry2.equals(entry) || actualEntry2.equals(entry2) || actualEntry2.equals(entry3));

        StoreEntry actualEntry3 = Iterables.get(entries, 0);
        assertTrue(actualEntry3.equals(entry) || actualEntry3.equals(entry2) || actualEntry3.equals(entry3));

    }

}
