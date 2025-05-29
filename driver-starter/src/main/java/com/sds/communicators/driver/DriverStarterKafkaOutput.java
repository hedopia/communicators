package com.sds.communicators.driver;

import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.web.reactive.function.server.RouterFunctions;

import java.util.List;
import java.util.Properties;

@Slf4j
public class DriverStarterKafkaOutput extends DriverStarter {

    private final String responseTopic;
    private final String responseFormat;
    private final String statusTopic;
    private final String statusFormat;
    private final Producer<String, String> producer;

    public static class Builder extends DriverStarter.Builder {
        private final String bootstrapAddress;
        private final String responseTopic;
        private final String responseFormat;
        private final String statusTopic;
        private final String statusFormat;

        public Builder(String bootstrapAddress, String responseTopic, String responseFormat, String statusTopic, String statusFormat, String driverId, ClusterStarter.Builder clusterStarterBuilder) {
            super(driverId, clusterStarterBuilder);
            this.bootstrapAddress = bootstrapAddress;
            this.responseTopic = responseTopic;
            this.responseFormat = responseFormat;
            this.statusTopic = statusTopic;
            this.statusFormat = statusFormat;
        }

        @Override
        public DriverStarter build() throws Exception {
            return new DriverStarterKafkaOutput(
                    bootstrapAddress,
                    responseTopic,
                    responseFormat,
                    statusTopic,
                    statusFormat,
                    driverId,
                    loadBalancing,
                    reconnectWhenSplitBrainResolved,
                    defaultScript,
                    driverEvents,
                    driverBasePath,
                    clusterEvents,
                    routerFunctionBuilder,
                    clusterStarterBuilder);
        }
    }

    public DriverStarterKafkaOutput(String bootstrapAddress,
                                    String responseTopic,
                                    String responseFormat,
                                    String statusTopic,
                                    String statusFormat,
                                    String driverId,
                                    boolean loadBalancing,
                                    boolean reconnectWhenSplitBrainResolved,
                                    String defaultScript,
                                    DriverEvents driverEvents,
                                    String driverBasePath,
                                    ClusterEvents clusterEvents,
                                    RouterFunctions.Builder routerFunctionBuilder,
                                    ClusterStarter.Builder clusterStarterBuilder) throws Exception {
        super(driverId,
                loadBalancing,
                reconnectWhenSplitBrainResolved,
                defaultScript,
                driverEvents,
                driverBasePath,
                clusterEvents,
                routerFunctionBuilder,
                clusterStarterBuilder);

        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(props);
        this.responseTopic = responseTopic;
        this.responseFormat = responseFormat;
        this.statusTopic = statusTopic;
        this.statusFormat = statusFormat;
    }

    @Override
    void sendResponse(List<Response> responses, String driverId, int nodeIndex) throws Exception {
        try {
            for (String response : getResponseFormat(responses, driverId, nodeIndex, responseFormat)) {
                producer.send(new ProducerRecord<>(responseTopic, response));
            }
        } catch (Exception e) {
            throw new Exception("kafka send responses failed", e);
        }
    }

    @Override
    void sendStatus(Status deviceStatus, String driverId, int nodeIndex) throws Exception {
        try {
            producer.send(new ProducerRecord<>(statusTopic, getStatusFormat(deviceStatus, driverId, nodeIndex, statusFormat)));
        } catch (Exception e) {
            throw new Exception("kafka send status failed", e);
        }
    }

    @Override
    public void dispose() {
        producer.close();
        super.dispose();
    }
}
