package com.uci.outbound.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.uci.outbound.consumers.OutboundKafkaController;
import com.uci.outbound.model.MessageRequest;
import com.uci.utils.BotService;
import com.uci.utils.model.ApiResponse;
import com.uci.utils.model.ApiResponseParams;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@RestController
@RequestMapping(value = "/message")
public class MessageController {
    @Autowired
    public BotService botService;

    @Autowired
    public OutboundKafkaController outboundService;

    @RequestMapping(value = "/send", method = RequestMethod.POST, produces = {"application/json", "text/json"})
    public Mono<ApiResponse> sendMessage(@RequestBody MessageRequest request) {
        ApiResponseParams params = ApiResponseParams.builder().build();
        ApiResponse response = ApiResponse.builder()
                .id("api.message.send")
                .responseCode(HttpStatus.OK.name())
                .params(params)
                .build();
        if(request.getAdapterId() == null || request.getAdapterId().isEmpty()
            || request.getMobile() == null || request.getMobile().isEmpty()
            || request.getMessage() == null || request.getMessage().isEmpty()
        ) {
            params.setStatus("failed");
            params.setErrmsg("Adapter id, mobile & message are required.");
            response.setParams(params);
            response.setResponseCode(HttpStatus.BAD_REQUEST.name());
        } else {
            SenderReceiverInfo from = new SenderReceiverInfo().builder().userID("admin").build();
            SenderReceiverInfo to = new SenderReceiverInfo().builder().userID(request.getMobile()).build();
            MessageId msgId = new MessageId().builder().channelMessageId(UUID.randomUUID().toString()).replyId(request.getMobile()).build();
            XMessagePayload payload = new XMessagePayload().builder().text(request.getMessage()).build();

            return botService.getAdapterByID(request.getAdapterId())
                    .map(new Function<JsonNode, ApiResponse>(){
                        @Override
                        public ApiResponse apply(JsonNode adapter) {
                            XMessage xmsg = new XMessage().builder()
                                    .app("Global Outbound Bot")
                                    .adapterId(request.getAdapterId())
                                    .sessionId(UUID.randomUUID())
                                    .ownerId(null)
                                    .ownerOrgId(null)
                                    .from(from)
                                    .to(to)
                                    .messageId(msgId)
                                    .messageState(XMessage.MessageState.REPLIED)
                                    .messageType(XMessage.MessageType.TEXT)
                                    .payload(payload)
                                    .providerURI(adapter.path("provider").asText())
                                    .channelURI(adapter.path("channel").asText())
                                    .timestamp(Timestamp.valueOf(LocalDateTime.now()).getTime())
                                    .build();

                            /* Template id required check for cdac sms adapter */
                            if(adapter.path("channel").asText().equals("sms")
                                    &&  adapter.path("provider").asText().equals("cdac")
                                    && (request.getTemplateId() == null || request.getTemplateId().isEmpty())) {
                                params.setStatus("failed");
                                params.setErrmsg("Template id is required for cdac-sms adapter.");
                                response.setParams(params);
                                response.setResponseCode(HttpStatus.BAD_REQUEST.name());
                                return response;
                            } else {
                                HashMap<String, String> transformerMeta = new HashMap<>();
                                transformerMeta.put("templateId", request.getTemplateId());
                                Transformer transformer = Transformer.builder().metaData(transformerMeta).build();
                                ArrayList<Transformer> transformers = new ArrayList<>();
                                transformers.add(transformer);

                                xmsg.setTransformers(transformers);
                            }

                            /* FCM token required check for firebase adapter */
                            if(adapter.path("channel").asText().equals("web")
                                    &&  adapter.path("provider").asText().equals("firebase")
                                    && (request.getFcmToken() == null || request.getFcmToken().isEmpty())) {
                                params.setStatus("failed");
                                params.setErrmsg("FCM Token is required for firebase adapter.");
                                response.setParams(params);
                                response.setResponseCode(HttpStatus.BAD_REQUEST.name());
                                return response;
                            } else {
                                Map<String, String> toMeta = new HashMap<>();
                                toMeta.put("fcmToken", request.getFcmToken());
                                to.setMeta(toMeta);
                                xmsg.setTo(to);
                            }

                            try {
                                outboundService.sendOutboundMessage(xmsg);
                            } catch (Exception e) {
                                log.error("Exception while sending outbound message: "+e.getMessage());
                            }

                            return response;
                        }
                    });
        }
        return Mono.just(response);
    }
}
