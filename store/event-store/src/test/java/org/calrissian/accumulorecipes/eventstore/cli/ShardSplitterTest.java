package org.calrissian.accumulorecipes.eventstore.cli;

import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.calrissian.accumulorecipes.commons.support.Constants.DEFAULT_PARTITION_SIZE;
import static org.junit.Assert.assertEquals;

public class ShardSplitterTest {

    public static Instance instance = new MockInstance("mock");

    @Test
    public void test() throws AccumuloSecurityException, AccumuloException, IOException, TableExistsException, TableNotFoundException, InterruptedException {

        TemporaryFolder folder = new TemporaryFolder();
        folder.create();

        MiniAccumuloCluster mac = new MiniAccumuloCluster(folder.getRoot(), "secret");
        mac.start();

        Instance instance = new ZooKeeperInstance(mac.getInstanceName(), mac.getZooKeepers());
        Connector connector = instance.getConnector("root", "secret".getBytes());
        connector.tableOperations().create("event_shard");

        ShardSplitter.main(new String[] {
            mac.getZooKeepers(),
            mac.getInstanceName(),
            "root",
            "secret",
            "event_shard",
            "1969-01-01",
            "1969-01-01"
        });

        assertEquals(DEFAULT_PARTITION_SIZE, connector.tableOperations().getSplits("event_shard").size());

        ShardSplitter.main(new String[] {
                mac.getZooKeepers(),
                mac.getInstanceName(),
                "root",
                "secret",
                "event_shard",
                "1969-01-01",
                "1969-01-02"
        });

        System.out.println(connector.tableOperations().getSplits("event_shard"));

        assertEquals(24 * DEFAULT_PARTITION_SIZE, connector.tableOperations().getSplits("event_shard").size());



        mac.stop();
        folder.delete();
    }
}
