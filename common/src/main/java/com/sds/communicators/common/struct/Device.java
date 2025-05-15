package com.sds.communicators.common.struct;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString
public class Device {
    /**
     * Key
     */
    private String id;
    /**
     * device group
     */
    private String group = "";
    /**
     * response timeout [sec] (0 or negative means infinite timeout)
     */
    private int responseTimeout = 0;
    /**
     * number of maximum retry to connect after connection failed (negative means retry to connect infinitely)
     */
    private int maxRetryConnect = 5;
    /**
     * delay time after connection failed to retry connect [ms]
     */
    private int retryConnectDelay = 5000;
    /**
     * socket timeout [ms]
     */
    private int socketTimeout = 5000;
    /**
     * delay after connected to execute commands [ms]
     */
    private int initialCommandDelay = 5000;
    /**
     * connection URL
     */
    private String connectionUrl = "tcp-client://127.0.0.1:5000";
    /**
     * protocol script
     */
    private String protocolScript = "";
    /**
     * command set
     */
    private Set<Command> commands = new HashSet<>();
    /**
     * connect when request command
     */
    private boolean connectionCommand = false;
    /**
     * data used in script
     */
    private Map<String, Object> data = new HashMap<>();
}
