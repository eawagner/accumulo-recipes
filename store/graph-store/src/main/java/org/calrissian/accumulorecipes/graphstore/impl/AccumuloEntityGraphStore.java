package org.calrissian.accumulorecipes.graphstore.impl;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import org.calrissian.accumulorecipes.commons.domain.Auths;
import org.calrissian.accumulorecipes.commons.domain.StoreConfig;
import org.calrissian.accumulorecipes.entitystore.impl.AccumuloEntityStore;
import org.calrissian.accumulorecipes.entitystore.model.EntityIndex;
import org.calrissian.accumulorecipes.entitystore.model.EntityRelationship;
import org.calrissian.accumulorecipes.graphstore.GraphStore;
import org.calrissian.accumulorecipes.graphstore.model.Direction;
import org.calrissian.accumulorecipes.graphstore.model.EdgeEntity;
import org.calrissian.accumulorecipes.graphstore.support.EdgeGroupingIterator;
import org.calrissian.accumulorecipes.graphstore.support.TupleCollectionCriteriaPredicate;
import org.calrissian.accumulorecipes.graphstore.tinkerpop.EntityGraph;
import org.calrissian.mango.collect.CloseableIterable;
import org.calrissian.mango.criteria.domain.Node;
import org.calrissian.mango.domain.BaseEntity;
import org.calrissian.mango.domain.Entity;
import org.calrissian.mango.domain.Tuple;

import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.accumulo.core.data.Range.prefix;
import static org.apache.commons.lang.StringUtils.defaultString;
import static org.apache.commons.lang.StringUtils.splitPreserveAllTokens;
import static org.calrissian.accumulorecipes.commons.support.Constants.DELIM;
import static org.calrissian.accumulorecipes.commons.support.Constants.EMPTY_VALUE;
import static org.calrissian.accumulorecipes.entitystore.model.RelationshipTypeEncoder.ALIAS;
import static org.calrissian.accumulorecipes.graphstore.model.Direction.IN;
import static org.calrissian.accumulorecipes.graphstore.model.Direction.OUT;
import static org.calrissian.accumulorecipes.graphstore.model.EdgeEntity.*;
import static org.calrissian.mango.accumulo.Scanners.closeableIterable;
import static org.calrissian.mango.collect.CloseableIterables.*;
import static org.calrissian.mango.criteria.support.NodeUtils.criteriaFromNode;

public class AccumuloEntityGraphStore extends AccumuloEntityStore implements GraphStore {

  public static final String DEFAULT_TABLE_NAME = "entityStore_graph";

  public static final int DEFAULT_BUFFER_SIZE = 50;

  public static final String ONE_BYTE = "\u0001";

  protected String table;
  protected int bufferSize = DEFAULT_BUFFER_SIZE;

  protected BatchWriter writer;

  public AccumuloEntityGraphStore(Connector connector)
          throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException {
    super(connector);
    table = DEFAULT_TABLE_NAME;
    init();
  }

  public AccumuloEntityGraphStore(Connector connector, String indexTable, String shardTable, String edgeTable, StoreConfig config)
          throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException {
    super(connector, indexTable, shardTable, config);
    table = edgeTable;
    init();
  }

  private void init() throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException {
    if(!getConnector().tableOperations().exists(table))
      getConnector().tableOperations().create(table);

    writer = getConnector().createBatchWriter(table, getConfig().getMaxMemory(), getConfig().getMaxLatency(),
            getConfig().getMaxWriteThreads());
  }

  public void setBufferSize(int bufferSize) {
    this.bufferSize = bufferSize;
  }

  @Override
  public CloseableIterable<EdgeEntity> adjacentEdges(List<EntityIndex> fromVertices,
                                                 Node query,
                                                 Direction direction,
                                                 Set<String> labels,
                                                 final Auths auths) {
    checkNotNull(labels);
    final CloseableIterable<Entity> entities = findAdjacentEdges(fromVertices, query, direction, labels, auths);
    return transform(entities, new Function<Entity, EdgeEntity>() {
      @Override
      public EdgeEntity apply(Entity entity) {
        return new EdgeEntity(entity);
      }
    });
  }

  @Override
  public CloseableIterable<EdgeEntity> adjacentEdges(List<EntityIndex> fromVertices, Node query, Direction direction, final Auths auths) {
    final CloseableIterable<Entity> entities = findAdjacentEdges(fromVertices, query, direction, null, auths);
    return transform(entities, new Function<Entity, EdgeEntity>() {
      @Override
      public EdgeEntity apply(Entity entity) {
        return new EdgeEntity(entity);
      }
    });
  }

  @Override
  public CloseableIterable<Entity> adjacencies(List<EntityIndex> fromVertices,
                                               Node query, Direction direction,
                                               Set<String> labels,
                                               final Auths auths) {
    CloseableIterable<Entity> edges = findAdjacentEdges(fromVertices, query, direction, labels, auths);
    CloseableIterable<EntityIndex> indexes = transform(edges, new EntityGraph.EdgeToVertexIndexXform(direction));
    return concat(transform(partition(indexes, bufferSize),
      new Function<List<EntityIndex>, Iterable<Entity>>() {
        @Override
        public Iterable<Entity> apply(List<EntityIndex> entityIndexes) {
          List<Entity> entityCollection = new LinkedList<Entity>();
          CloseableIterable<Entity> entities = get(entityIndexes, null, auths);
          Iterables.addAll(entityCollection, entities);
          entities.closeQuietly();
          return entityCollection;
        }
      }
    ));
  }

  @Override
  public CloseableIterable<Entity> adjacencies(List<EntityIndex> fromVertices, Node query, Direction direction, final Auths auths) {
    return adjacencies(fromVertices, query, direction, null, auths);
  }

  private CloseableIterable<Entity> findAdjacentEdges(List<EntityIndex> fromVertices,
                                                      Node query, Direction direction,
                                                      Set<String> labels,
                                                      final Auths auths) {
    checkNotNull(fromVertices);
    checkNotNull(auths);

    TupleCollectionCriteriaPredicate filter =
            query != null ? new TupleCollectionCriteriaPredicate(criteriaFromNode(query)) : null;

    // this one is fairly easy- return the adjacent edges that match the given query
    try {
      BatchScanner scanner = getConnector().createBatchScanner(table, auths.getAuths(), getConfig().getMaxQueryThreads());
      IteratorSetting setting = new IteratorSetting(15, EdgeGroupingIterator.class);
      scanner.addScanIterator(setting);

      Collection<Range> ranges = new ArrayList<Range>();
      for(EntityIndex entity : fromVertices) {
        String row = ENTITY_TYPES.encode(new EntityRelationship(entity.getType(), entity.getId()));
        if(labels != null) {
          for(String label : labels)
            populateRange(ranges, row, direction, label);
        } else
          populateRange(ranges, row, direction, null);
      }

      scanner.setRanges(ranges);

      /**
       * This partitions the initial Accumulo rows in the scanner into buffers of <bufferSize> so that the full entities
       * can be grabbed from the server in batches instead of one at a time.
       */
      CloseableIterable<Entity> entities = transform(closeableIterable(scanner), edgeRowXform);

      if(filter != null)
        return filter(entities, filter);
      else
        return entities;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void populateRange(Collection<Range> ranges, String row, Direction direction, String label) {
    ranges.add(prefix(row, direction.toString() + DELIM + defaultString(label)));
  }

  /**
   * Extracts an edge/vertex (depending on what is requested) on the far side of a given vertex
   */
  private Function<Map.Entry<Key,Value>, Entity> edgeRowXform = new Function<Map.Entry<Key, Value>, Entity>() {

    @Override
    public Entity apply(Map.Entry<Key, Value> keyValueEntry) {

      String cq = keyValueEntry.getKey().getColumnQualifier().toString();

      int idx = cq.indexOf(DELIM);
      String edge = cq.substring(0, idx);

      try {
        EntityRelationship edgeRel = (EntityRelationship) ENTITY_TYPES.decode(ALIAS, edge);
        Entity entity = new BaseEntity(edgeRel.getTargetType(), edgeRel.getTargetId());
        SortedMap<Key,Value> entries = EdgeGroupingIterator.decodeRow(keyValueEntry.getKey(), keyValueEntry.getValue());

        for(Map.Entry<Key,Value> entry : entries.entrySet()) {
          if(entry.getKey().getColumnQualifier().toString().indexOf(ONE_BYTE) == -1)
            continue;

          String[] qualParts = splitPreserveAllTokens(entry.getKey().getColumnQualifier().toString(), ONE_BYTE);
          String[] keyALiasValue = splitPreserveAllTokens(qualParts[1], DELIM);

          entity.put(new Tuple(keyALiasValue[0], ENTITY_TYPES.decode(keyALiasValue[1], keyALiasValue[2]),
                     entry.getKey().getColumnVisibility().toString()));

        }

        return entity;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  };

  @Override
  public void save(Iterable<Entity> entities) {
    super.save(entities);

    // here is where we want to store the edge index everytime an entity is saved
    for(Entity entity : entities) {
      if(isEdge(entity)) {

        EntityRelationship edgeRelationship = new EntityRelationship(entity);
        EntityRelationship toVertex = entity.<EntityRelationship>get(TAIL).getValue();
        EntityRelationship fromVertex = entity.<EntityRelationship>get(HEAD).getValue();

        String toVertexVis = entity.get(TAIL).getVisibility();
        String fromVertexVis = entity.get(HEAD).getVisibility();

        String label = entity.<String>get(LABEL).getValue();

        try {
          String fromEncoded = ENTITY_TYPES.encode(fromVertex);
          String toEncoded = ENTITY_TYPES.encode(toVertex);
          String edgeEncoded = ENTITY_TYPES.encode(edgeRelationship);
          Mutation forward = new Mutation(fromEncoded);
          Mutation reverse = new Mutation(toEncoded);

          forward.put(new Text(OUT.toString() + DELIM + label), new Text(edgeEncoded + DELIM + toEncoded),
                  new ColumnVisibility(toVertexVis), EMPTY_VALUE);

          reverse.put(new Text(IN.toString() + DELIM + label), new Text(edgeEncoded + DELIM + fromEncoded),
                  new ColumnVisibility(fromVertexVis), EMPTY_VALUE);

          for(Tuple tuple : entity.getTuples()) {
            String key = tuple.getKey();
            String alias = ENTITY_TYPES.getAlias(tuple.getValue());
            String value = ENTITY_TYPES.encode(tuple.getValue());

            String keyAliasValue = key + DELIM + alias + DELIM + value;

            forward.put(new Text(OUT.toString() + DELIM + label),
                        new Text(edgeEncoded + DELIM + toEncoded + ONE_BYTE + keyAliasValue),
                        new ColumnVisibility(tuple.getVisibility()), EMPTY_VALUE);

            reverse.put(new Text(IN.toString() + DELIM + label),
                        new Text(edgeEncoded + DELIM + fromEncoded + ONE_BYTE + keyAliasValue),
                        new ColumnVisibility(tuple.getVisibility()), EMPTY_VALUE);
          }

          writer.addMutation(forward);
          writer.addMutation(reverse);

        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    try {
      writer.flush();
    } catch (MutationsRejectedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void shutdown() throws MutationsRejectedException {
    super.shutdown();
    writer.close();
  }

  private boolean isEdge(Entity entity) {
    return entity.get(HEAD) != null &&
           entity.get(TAIL) != null &&
           entity.get(LABEL) != null;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName().toLowerCase() + "{" +
            "table='" + table + '\'' +
            ", bufferSize=" + bufferSize +
            ", writer=" + writer +
            '}';
  }
}
