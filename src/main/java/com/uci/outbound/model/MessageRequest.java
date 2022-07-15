package com.uci.outbound.model;

import lombok.Getter;
import lombok.Setter;
import messagerosa.core.model.XMessagePayload;

import javax.annotation.Nullable;

@Setter
@Getter
public class MessageRequest {
    public String adapterId;
    public String mobile;
    public XMessagePayload payload;
}
