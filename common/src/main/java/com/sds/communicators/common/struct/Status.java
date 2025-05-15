package com.sds.communicators.common.struct;

import com.sds.communicators.common.type.StatusCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class Status {
    String deviceId;
    StatusCode status;
    long issuedTime;
}
