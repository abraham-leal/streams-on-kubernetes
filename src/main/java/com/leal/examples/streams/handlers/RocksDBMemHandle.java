package com.leal.examples.streams.handlers;

import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Options;

import java.util.Map;

/*
Per State Store instance settings
If your application has 3 state stores, and this is set as the default config,
multiply these settings by 3 to get total size per thread.
 */

public class RocksDBMemHandle implements RocksDBConfigSetter {
    /*
    Pass in:
     - Total memory to give for in-memory reads
     - Exponential for number of shards in the memory (-1 lets the system decide)
     - Whether an insert should fail if the memory is full
     - The ratio of memory reserved for index and bloom filter tables
     */
    private static org.rocksdb.Cache cache = new org.rocksdb.LRUCache(100 * 1024 * 1024L, -1, false, 0.5);
    // Pass in: Total memory used for memtables (data tables used for writes)
    private static org.rocksdb.WriteBufferManager writeBufferManager = new org.rocksdb.WriteBufferManager(50 * 1024 * 1024L, cache);

    @Override
    public void setConfig(String s, Options options, Map<String, Object> map) {
        BlockBasedTableConfig tableConfig = (BlockBasedTableConfig) options.tableFormatConfig();

        /*
        Set block size for uncompressed data
        Consider setting this to a multiple of a record's key size for optimization
        Default is 4kb. For better memory state management keep it to a low multiple.
         */
        tableConfig.setBlockSize(16 * 1024 * 1024L);
        /*
        Setting these to true allow us to count the cache needed for indexes and bloom filters towards the
        max block size so we can limit it efficiently
         */
        tableConfig.setCacheIndexAndFilterBlocks(true);
        tableConfig.setCacheIndexAndFilterBlocksWithHighPriority(true);
        tableConfig.setPinTopLevelIndexAndFilter(true);
        tableConfig.setBlockCache(cache);
        options.setWriteBufferManager(writeBufferManager);



        /*
        Set:
        - Maximum amount of Mem-tables in the cache (Default 3)
        - Size of Write Buffer per column family aka MemTables aka The Original Pancakes (Default 16MB)

        These should really line up with your previous provisioning.
        For example, previously we have set the MAX about for our RocksDB block cache to be 100MB;
        However, we've given mem-tables a 50% ratio capacity (the other 50 is for index and bloom filter tables)
        This means we have 50 MB for memtables, If we want 3 memtables, their buffer size can be 50 / 3 ~= 16
         */

        options.setMaxWriteBufferNumber(3);
        options.setWriteBufferSize(16 * 1024 * 1024L);

    }

    /*
    Prevent memory leaks
     */

    @Override
    public void close(final String storeName, final Options options) {
        cache.close();
        writeBufferManager.close();
    }
}
