package org.calrissian.accumulorecipes.geospatialstore;


import org.calrissian.accumulorecipes.commons.domain.Auths;
import org.calrissian.accumulorecipes.commons.domain.StoreEntry;
import org.calrissian.mango.collect.CloseableIterable;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * A store that allows {@link StoreEntry} objects to be indexed at a given geo location and
 * further queried back given a bounding box.
 */
public interface GeoSpatialStore {

    /**
     * Index store entries at the given point.
     */
    void put(Iterable<StoreEntry> entry, Point2D.Double location);

    /**
     * Return all {@link StoreEntry} objects that lie within the given bounding box
     */
    CloseableIterable<StoreEntry> get(Rectangle2D.Double location, Auths auths);
}
