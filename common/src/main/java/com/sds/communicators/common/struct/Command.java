package com.sds.communicators.common.struct;

import com.sds.communicators.common.type.CommandType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString
public class Command {
    static final public int MINIMUM_PERIOD_GROUP = 500;
    /**
     * Key
     */
    private String id = UUID.randomUUID().toString();
    /**
     * command priority
     */
    private int order = 0;
    /**
     * command type
     *  - READ_REQUEST: request for read
     *  - STARTING_READ_REQUEST: read request at protocol starting
     *  - STOPPING_READ_REQUEST: read request at protocol stopping
     *  - WRITE_REQUEST: request for write
     *  - STARTING_WRITE_REQUEST: write request at protocol starting
     *  - STOPPING_WRITE_REQUEST: write request at protocol stopping
     *  - REQUEST: request without read/write
     *  - STARTING_REQUEST: request at protocol starting
     *  - STOPPING_REQUEST: request at protocol stopping
     */
    private CommandType type = CommandType.READ_REQUEST;
    /**
     * command period for periodic request [ms], if negative non-periodic
     */
    private int periodGroup = -1;
    /**
     * request info
     */
    private String requestInfo;
    /**
     * command delay [ms]
     */
    private int afterDelay = 0;
    /**
     * command timeout [ms] (for read-request)
     */
    private int commandTimeout = 5000;
    /**
     * command script function
     */
    private String cmdScript = null;
}
