package com.shapira.examples.streams.stockstats;

import com.leal.examples.streams.handlers.sendToKafka;
import com.leal.examples.streams.handlers.writeToLog;
import com.shapira.examples.streams.stockstats.model.Trade;
import com.shapira.examples.streams.stockstats.model.TradeStats;
import com.shapira.examples.streams.stockstats.serde.JsonDeserializer;
import com.shapira.examples.streams.stockstats.serde.JsonSerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.WindowedSerdes;
import org.apache.log4j.BasicConfigurator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class StockStatsExampleTest {
    private  TopologyTestDriver testDriver;
    private  TestOutputTopic<Windowed<String>, TradeStats> complete_record;
    private  TestOutputTopic<String, Trade> noncompliant;
    private  TestOutputTopic<byte[], byte[]> badSerialization;

    @Before
    public void SetUp() {
        BasicConfigurator.configure();


        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "stockstat-2");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, StockStatsExample.TradeSerde.class.getName());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "mock:1234");
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, sendToKafka.class);
        props.put(StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG, writeToLog.class);

        Topology myStream = StockStatsExample.getTopology().build();
        testDriver = new TopologyTestDriver(myStream, props);


        Deserializer<Trade> tradeDeserializer = new JsonDeserializer<>(Trade.class);
        Deserializer<TradeStats> tradeStatsDeserializer = new JsonDeserializer<TradeStats>(TradeStats.class);

        // Create Topics to test with

        complete_record = testDriver.createOutputTopic("stockstats-output", WindowedSerdes.timeWindowedSerdeFrom(String.class).deserializer(), tradeStatsDeserializer);
        noncompliant = testDriver.createOutputTopic("dlq-stockstat-noncompliant", Serdes.String().deserializer(), tradeDeserializer);

    }

    @Test
    public void shouldHandleLogicError () {
        // Set up our input topic
        Serializer<Trade> tradeSerializer = new JsonSerializer<Trade>();
        TestInputTopic<String, Trade> metric_measure =  testDriver.createInputTopic("metric_measure", Serdes.String().serializer(), tradeSerializer);

        // We create a record with null values
        Trade badTrade = new Trade (null, null, 2,2);

        // We send a non-compliant error, with a fake ticker as key
        metric_measure.pipeInput(Constants.TICKERS[2],badTrade);

        // We expect our non compliant error to end up in our DLQ for records that cannot be processed
        assertEquals(badTrade.toString(), noncompliant.readKeyValue().value.toString());
    }

    @Test
    public void shouldProduceCorrectStockStat () {
        //Set up our input topic
        Serializer<Trade> tradeSerializer = new JsonSerializer<Trade>();
        TestInputTopic<String, Trade> metric_measure =  testDriver.createInputTopic("metric_measure", Serdes.String().serializer(), tradeSerializer);

        // Generate two fake trades
        Trade goodTrade = new Trade ("ASK",Constants.TICKERS[2], 2, 2);
        Trade goodTrade2 = new Trade ("ASK",Constants.TICKERS[2], 3, 3);

        Instant sameTime = Instant.now();

        //Pipe it in at the same time
        metric_measure.pipeInput(Constants.TICKERS[2],goodTrade, sameTime);
        metric_measure.pipeInput(Constants.TICKERS[2],goodTrade2, sameTime);

        // Get the latest value of our stat
        int topOfLog = (int)complete_record.getQueueSize()-1;
        List<TradeStats> myTradeStats = complete_record.readValuesToList();
        TradeStats actualStat = myTradeStats.get(topOfLog);

        // Generate the expected output
        TradeStats resultingStat = new TradeStats();
        resultingStat.add(goodTrade);
        resultingStat.add(goodTrade2);
        resultingStat.computeAvgPrice();

        //Assert expected is equal to actual
        assertTrue(resultingStat.equals(actualStat));
    }


    @After
    public void tearDown(){
        testDriver.close();
    }

}