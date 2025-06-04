package com.sds.communicators.driver;

import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.web.reactive.function.server.RouterFunctions;

import java.io.FileWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
public class DriverStarterFileOutput extends DriverStarter {

    private final String responseFile;
    private final String statusFile;

    public static Builder Builder(String responseFile, String statusFile, String driverId, ClusterStarter.Builder clusterStarterBuilder) {
        return new Builder(responseFile, statusFile, driverId, clusterStarterBuilder);
    }

    public static class Builder extends DriverStarter.Builder {
        private final String responseFile;
        private final String statusFile;

        private Builder(String responseFile, String statusFile, String driverId, ClusterStarter.Builder clusterStarterBuilder) {
            super(driverId, clusterStarterBuilder);
            this.responseFile = responseFile;
            this.statusFile = statusFile;
        }

        @Override
        public DriverStarter build() throws Exception {
            return new DriverStarterFileOutput(
                    responseFile,
                    statusFile,
                    driverId,
                    loadBalancing,
                    defaultScript,
                    driverEvents,
                    driverBasePath,
                    clusterEvents,
                    routerFunctionBuilder,
                    clusterStarterBuilder);
        }
    }

    private DriverStarterFileOutput(String responseFile,
                                   String statusFile,
                                   String driverId,
                                   boolean loadBalancing,
                                   String defaultScript,
                                   DriverEvents driverEvents,
                                   String driverBasePath,
                                   ClusterEvents clusterEvents,
                                   RouterFunctions.Builder routerFunctionBuilder,
                                   ClusterStarter.Builder clusterStarterBuilder) throws Exception {
        super(driverId,
                loadBalancing,
                defaultScript,
                driverEvents,
                driverBasePath,
                clusterEvents,
                routerFunctionBuilder,
                clusterStarterBuilder);

        this.responseFile = responseFile;
        this.statusFile = statusFile;
    }

    @Override
    void sendResponse(List<Response> responses, String driverId, int nodeIndex) throws Exception {
        try {
            try (FileWriter fileWriter = new FileWriter("./" + responseFile + ".csv", true)) {
                try (CSVPrinter csvPrinter = new CSVPrinter(fileWriter, CSVFormat.RFC4180)) {
                    for (Response response : responses) {
                        var receivedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(response.getReceivedTime()), ZoneId.systemDefault());
                        csvPrinter.printRecord(response.getTagId(), response.getValue(), response.getDeviceId(), receivedTime);
                    }
                    csvPrinter.flush();
                }
            }
        } catch (Exception e) {
            throw new Exception("write response to " + responseFile + ".csv failed", e);
        }
    }

    @Override
    void sendStatus(Status deviceStatus, String driverId, int nodeIndex) throws Exception {
        try {
            try (FileWriter fileWriter = new FileWriter("./" + statusFile + ".csv", true)) {
                try (CSVPrinter csvPrinter = new CSVPrinter(fileWriter, CSVFormat.RFC4180)) {
                    csvPrinter.printRecord(deviceStatus.getDeviceId(), deviceStatus.getStatus().name(), deviceStatus.getIssuedTime());
                    csvPrinter.flush();
                }
            }
        } catch (Exception e) {
            throw new Exception("write status to " + statusFile + ".csv", e);
        }
    }
}
