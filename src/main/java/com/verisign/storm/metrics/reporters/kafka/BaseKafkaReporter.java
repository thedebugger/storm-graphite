/*
 * Copyright 2014 VeriSign, Inc.
 *
 * VeriSign licenses this file to you under the Apache License, version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 */
package com.verisign.storm.metrics.reporters.kafka;

import com.google.common.base.Throwables;
import com.verisign.ie.styx.avro.graphingMetrics.GraphingMetrics;
import com.verisign.storm.metrics.reporters.AbstractReporter;
import com.verisign.storm.metrics.util.ConnectionFailureException;
import com.verisign.storm.metrics.util.GraphiteCodec;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.lang.SerializationException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;

/**
 * This class encapsulates an Apache Kafka producer, sending generated metrics into a configurable Kafka topic.
 */
public abstract class BaseKafkaReporter extends AbstractReporter {

  private static final Logger LOG = LoggerFactory.getLogger(BaseKafkaReporter.class);
  public static final String KAFKA_TOPIC_NAME_FIELD = "metrics.kafka.topic";
  public static final String KAFKA_BROKER_LIST_FIELD = "metrics.kafka.metadata.broker.list";
  public static final String KAFKA_PRODUCER_BOOTSTRAP_SERVERS_FIELD = "bootstrap.servers";

  private String kafkaTopicName;
  private String kafkaBrokerList;
  private LinkedList<GraphingMetrics> buffer;

  private KafkaProducer kafkaProducer;

  private int failures;

  public BaseKafkaReporter() {
    super();
  }

  @Override public void prepare(Map<String, Object> conf) {
    LOG.info(conf.toString());
    
    if (conf.containsKey(KAFKA_TOPIC_NAME_FIELD)) {
      kafkaTopicName = (String) conf.get(KAFKA_TOPIC_NAME_FIELD);
      conf.remove(KAFKA_TOPIC_NAME_FIELD);
    }
    else {
      throw new IllegalArgumentException("Field " + KAFKA_TOPIC_NAME_FIELD + " required.");
    }

    if (conf.containsKey(KAFKA_BROKER_LIST_FIELD)) {
      kafkaBrokerList = (String) conf.get(KAFKA_BROKER_LIST_FIELD);
      conf.remove(KAFKA_BROKER_LIST_FIELD);
      conf.put(KAFKA_PRODUCER_BOOTSTRAP_SERVERS_FIELD, kafkaBrokerList);
    }
    else if (conf.containsKey(KAFKA_PRODUCER_BOOTSTRAP_SERVERS_FIELD)) {
      kafkaBrokerList = (String) conf.get(KAFKA_PRODUCER_BOOTSTRAP_SERVERS_FIELD);
    }
    else {
      throw new IllegalArgumentException("Field " + KAFKA_BROKER_LIST_FIELD + " required.");
    }

    Properties producerProps = new Properties();
    for (String key : conf.keySet()) {
      if (conf.get(key) != null) {
        producerProps.setProperty(key, conf.get(key).toString());
      }
    }

    kafkaProducer = configureKafkaProducer(producerProps);
    buffer = new LinkedList<GraphingMetrics>();
    failures = 0;
  }

  public abstract KafkaProducer<GenericRecord, GenericRecord> configureKafkaProducer(Properties producerProps);

  @Override public void connect() throws ConnectionFailureException {
  }

  @Override public void disconnect() throws ConnectionFailureException {
  }

  @Override public void appendToBuffer(String prefix, Map<String, Double> metrics, long timestamp) {
    Map<String, Double> metricsDoubleMap = new HashMap<String, Double>();

    for (String key : metrics.keySet()) {
      try {
        Double value = Double.parseDouble(GraphiteCodec.format(metrics.get(key)));
        metricsDoubleMap.put(key, value);
      }
      catch (NumberFormatException e) {
        String trace = Throwables.getStackTraceAsString(e);
        LOG.error("Error parsing metric value {} in path {}: {}", metrics.get(key), prefix + key, trace);
      }
      catch (NullPointerException npe) {
        LOG.error("Error appending metric with name {} to buffer, retrieved value is null.", key);
      }
    }

    buffer.add(new GraphingMetrics(prefix, timestamp, metricsDoubleMap));
  }

  @Override public void emptyBuffer() {
    buffer.clear();
  }

  @Override public void sendBufferContents() throws IOException {
    for (GraphingMetrics metric : buffer) {
      try {
        ProducerRecord<GenericRecord, GenericRecord> record = new ProducerRecord<GenericRecord, GenericRecord>(
            kafkaTopicName, metric, metric);
        kafkaProducer.send(record);
      }
      catch (SerializationException e) {
        failures++;

        //Pass this exception up to the metrics consumer for it to handle
        throw e;
      }
    }
  }

  @Override public long getFailures() {
    return failures;
  }

  @Override public String getBackendFingerprint() {
    return kafkaBrokerList;
  }

}