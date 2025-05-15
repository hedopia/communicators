package com.sds.communicators.common.struct;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Response {
    private String deviceId;
    private String tagId;
    private String value;
    private long receivedTime;
}
