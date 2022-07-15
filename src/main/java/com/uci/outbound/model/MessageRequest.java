package com.uci.outbound.model;

import lombok.Getter;
import lombok.Setter;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.XMessage;
import messagerosa.core.model.XMessagePayload;

import javax.annotation.Nullable;
import javax.sound.midi.Receiver;

@Setter
@Getter
public class MessageRequest {
    public String adapterId;
    public SenderReceiverInfo to;
    public XMessagePayload payload;
}
