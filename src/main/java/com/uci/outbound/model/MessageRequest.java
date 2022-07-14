package com.uci.outbound.model;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

@Setter
@Getter
public class MessageRequest {
    @Nullable
    public String adapterId;
    @Nullable
    public String mobile;
    @Nullable
    public String message;
    @Nullable
    public String templateId;
    @Nullable
    public String fcmToken;
}
