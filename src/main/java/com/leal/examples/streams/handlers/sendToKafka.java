package com.leal.examples.streams.handlers;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.BytesSerializer;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Properties;

public class sendToKafka implements DeserializationExceptionHandler {
    KafkaProducer<byte[],byte[]> dlqProducer;
    private static final Logger log = LoggerFactory.getLogger(sendToKafka.class);

    @Override
    public DeserializationHandlerResponse handle(ProcessorContext processorContext, ConsumerRecord<byte[], byte[]> consumerRecord, Exception e) {
        ProducerRecord<byte[],byte[]> badRecord;

        badRecord = new ProducerRecord<>(
                "dlq-" + processorContext.topic()
                , consumerRecord.key()
                , consumerRecord.value());
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(consumerRecord.offset());
        badRecord.headers().add("partition", BigInteger.valueOf(consumerRecord.partition()).toByteArray());
        badRecord.headers().add("offset", buffer.array());
        badRecord.headers().add("exception", e.getMessage().getBytes());

        if (dlqProducer != null) {
            dlqProducer.send(badRecord);
        } else {
            log.warn("No DLQ Producer is initialized, Logging and continuing.");
            log.warn("Bad deserialization on record from topic: {}, record key : {}, record value {}, partition: {}, offset {}, exeption: {}",
                    consumerRecord.topic(), consumerRecord.key(), consumerRecord.value(), consumerRecord.partition(),
                    consumerRecord.offset(), e.getMessage());
        }

        return DeserializationHandlerResponse.CONTINUE;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Properties forClient = new Properties();
        forClient.putAll(configs);
        forClient.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, BytesSerializer.class);
        forClient.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, BytesSerializer.class);
        try {
            dlqProducer = new KafkaProducer<>(forClient);
        } catch (KafkaException e){
            log.warn("Could not construct Kafka Producer, no DLQ for bad deserialization is active.");
        }

    }
}
