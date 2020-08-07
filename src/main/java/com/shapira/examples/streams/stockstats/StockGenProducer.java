package com.shapira.examples.streams.stockstats;

import com.shapira.examples.streams.stockstats.serde.JsonSerializer;
import com.shapira.examples.streams.stockstats.model.Trade;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;


public class StockGenProducer {

    public static KafkaProducer<String, Trade> producer = null;

    public static void main(String[] args) throws Exception {

        System.out.println("Press CTRL-C to stop generating data");
        Logger.getRootLogger().setLevel(Level.INFO);


        // add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Shutting Down");
                if (producer != null)
                    producer.close();
            }
        });

        JsonSerializer<Trade> tradeSerializer = new JsonSerializer<>();

        // Configuring producer
        Properties props;
        if (args.length==1)
            props = LoadConfigs.loadConfig(args[0]);
        else
            props = LoadConfigs.loadConfig();

        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", tradeSerializer.getClass().getName());

        // Starting producer
        producer = new KafkaProducer<>(props);


        // initialize

        Random random = new Random();
        long iter = 0;

        Map<String, Integer> prices = new HashMap<>();

        for (String ticker : Constants.TICKERS)
            prices.put(ticker, Constants.START_PRICE);

        // Start generating events, stop when CTRL-C

        while (true) {
            iter++;
            for (String ticker : Constants.TICKERS) {
                double log = random.nextGaussian() * 0.25 + 1; // random var from lognormal dist with stddev = 0.25 and mean=1
                int size = random.nextInt(100);
                int price = prices.get(ticker);

                // flunctuate price sometimes
                if (iter % 10 == 0) {
                    price = price + random.nextInt(Constants.MAX_PRICE_CHANGE * 2) - Constants.MAX_PRICE_CHANGE;
                    prices.put(ticker, price);
                }

                Trade trade = new Trade("ASK",ticker,(price+log),size);
                // Note that we are using ticker as the key - so all asks for same stock will be in same partition
                ProducerRecord<String, Trade> record = new ProducerRecord<>(Constants.STOCK_TOPIC, ticker, trade);

                producer.send(record, (RecordMetadata r, Exception e) -> {
                    if (e != null) {
                        System.out.println("Error producing events");
                        e.printStackTrace();
                    }
                });

                // Sleep a bit, otherwise it is frying my machine
                Thread.sleep(Constants.DELAY);
            }
        }
    }
}
