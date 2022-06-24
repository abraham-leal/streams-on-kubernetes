package com.shapira.examples.streams.stockstats;

import com.leal.examples.streams.handlers.BoundedMemoryRocksDBConfig;
import com.leal.examples.streams.handlers.BoundedMemoryRocksDBConfigSetter;
import com.leal.examples.streams.handlers.sendToKafka;
import com.leal.examples.streams.handlers.writeToLog;
import com.leal.examples.streams.status.StreamsStatus;
import com.shapira.examples.streams.stockstats.serde.JsonDeserializer;
import com.shapira.examples.streams.stockstats.serde.JsonSerializer;
import com.shapira.examples.streams.stockstats.serde.WrapperSerde;
import com.shapira.examples.streams.stockstats.model.Trade;
import com.shapira.examples.streams.stockstats.model.TradeStats;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Input is a stream of trades
 * Output is two streams: One with minimum and avg "ASK" price for every 10 seconds window
 * Another with the top-3 stocks with lowest minimum ask every minute
 */
public class StockStatsExample {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(StockStatsExample.class);

    public static void main(String[] args) throws Exception {

        Properties props = new Properties();

        // Overrideable properties
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "stockstat-2");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, TradeSerde.class.getName());
        props.put(StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG, "DEBUG");
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 2);
        props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, "1");
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 1000000);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        if (args.length==1)
            props = LoadConfigs.loadConfig(props, args[0]);
        else
            props = LoadConfigs.loadConfig(props);

        HashMap<String, Object> rocksDBConfigs = new HashMap<>();
        rocksDBConfigs.put(BoundedMemoryRocksDBConfig.BLOCK_CACHE_SIZE, 2 * 1024 * 1024L);
        rocksDBConfigs.put(BoundedMemoryRocksDBConfig.WRITE_BUFFER_LIMIT, 1024 * 1024L);
        rocksDBConfigs.put(BoundedMemoryRocksDBConfig.N_BACKGROUND_THREADS_CONFIG, 1);

        BoundedMemoryRocksDBConfigSetter boundMemory = new BoundedMemoryRocksDBConfigSetter();
        boundMemory.configure(rocksDBConfigs);

        // Non-overridable properties
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, sendToKafka.class);
        props.put(StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG, writeToLog.class);
        props.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, boundMemory.getClass());

        // creating an AdminClient and checking the number of brokers in the cluster, so I'll know how many replicas we want...
        AdminClient ac = AdminClient.create(props);
        DescribeClusterResult dcr = ac.describeCluster();
        int clusterSize = dcr.nodes().get().size();
        ac.close();

        if (clusterSize<3)
            props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG,clusterSize);
        else
            props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG,3);

        Topology topology = getTopology().build();

        KafkaStreams streams = new KafkaStreams(topology, props);

        streams.setUncaughtExceptionHandler((t) -> {
            log.warn("This is bound to die, so I just wanted to say goodbye... here is the culprit though: {}",
                    t.getMessage());
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
        });

        streams.cleanUp();
        streams.start();

        StreamsStatus exposeStatus = new StreamsStatus(streams);
        exposeStatus.start();


        // Add shutdown hooks to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(exposeStatus::stop));
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

    }

    @SuppressWarnings("unchecked")
    public static StreamsBuilder getTopology () {
        Predicate<String, Trade> isNotValidRecord = (key, value) -> (value.ticker == null && value.type == null);
        Predicate<String, Trade> isValidRecord = (key, value) -> (value.ticker != null && value.type != null);

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, Trade> source = builder.stream(Constants.STOCK_TOPIC);

        Map<String, KStream<String, Trade>> healthCheck = source // We check whether this record will throw any exceptions
                .split(Named.as("ExceptionCheck-"))
                .branch(isNotValidRecord, Branched.as("IsNotValid"))
                .branch(isValidRecord, Branched.as("IsValid"))
                .noDefaultBranch(); // by evaluating the predicates before entering the flow

        // dlq-stockstat-noncompliant
        healthCheck.get("ExceptionCheck-IsNotValid").to("dlq-stockstat-noncompliant"); // We send non compliant records to a topic named

        KStream<Windowed<String>, TradeStats> stats = healthCheck.get("ExceptionCheck-IsValid")
                .groupByKey()
                .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMillis(5000),Duration.ofMillis(1000)).advanceBy(Duration.ofMillis(1000)))
                .aggregate(TradeStats::new,(k, v, tradestats) -> tradestats.add(v),
                        Materialized.<String, TradeStats, WindowStore<Bytes, byte[]>>as("trade-aggregates")
                                .withValueSerde(new TradeStatsSerde()))
                .toStream()
                .mapValues(TradeStats::computeAvgPrice);

        stats.to("stockstats-output", Produced.keySerde(WindowedSerdes.timeWindowedSerdeFrom(String.class,5000L)));

        return builder;
    }

    static public final class TradeSerde extends WrapperSerde<Trade> {
        public TradeSerde() {
            super(new JsonSerializer<Trade>(), new JsonDeserializer<Trade>(Trade.class));
        }
    }

    static public final class TradeStatsSerde extends WrapperSerde<TradeStats> {
        public TradeStatsSerde() {
            super(new JsonSerializer<TradeStats>(), new JsonDeserializer<TradeStats>(TradeStats.class));
        }
    }

}
