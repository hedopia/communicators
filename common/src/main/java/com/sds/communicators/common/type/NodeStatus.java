package com.sds.communicators.common.type;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class NodeStatus {
    int nodeIndex;
    Position position;
    boolean isActivated;
}
