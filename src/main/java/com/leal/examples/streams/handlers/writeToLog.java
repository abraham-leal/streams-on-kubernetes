package com.leal.examples.streams.handlers;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.ProductionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class writeToLog implements ProductionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(writeToLog.class);

    @Override
    public ProductionExceptionHandlerResponse handle(ProducerRecord<byte[], byte[]> producerRecord, Exception e) {

        log.warn("Exception caught during production, " +
                "topic: {}, partition: {}, record key: {}, record value: {}, exception: {}"
                , producerRecord.topic(), producerRecord.partition(), producerRecord.key(), producerRecord.value(), e);
        return ProductionExceptionHandlerResponse.CONTINUE;
    }

    @Override
    public void configure(Map<String, ?> map) {

    }
}
