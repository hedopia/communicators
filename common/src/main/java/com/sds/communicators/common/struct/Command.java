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
    private String id = UUID.randomUUID().toString();
    /** command priority */
    private int order = 0;
    private CommandType type = CommandType.READ_REQUEST;
    /** command period for periodic request [ms], if negative non-periodic */
    private int periodGroup = -1;
    private String requestInfo;
    /** delay after the command [ms] */
    private int afterDelay = 0;
    /** command timeout [ms] (for read-request) */
    private int commandTimeout = 5000;
    private String cmdScript = null;
}
