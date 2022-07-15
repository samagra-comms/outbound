package com.uci.outbound.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.uci.outbound.consumers.OutboundKafkaController;
import com.uci.outbound.model.MessageRequest;
import com.uci.utils.BotService;
import com.uci.utils.model.ApiResponse;
import com.uci.utils.model.ApiResponseParams;
import com.uci.utils.model.HttpApiResponse;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import javax.ws.rs.BadRequestException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
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
    public Mono<ResponseEntity<HttpApiResponse>> sendMessage(@RequestBody MessageRequest request) {
        HttpApiResponse response = HttpApiResponse.builder()
                .status(HttpStatus.OK.value())
                .path("/message/send")
                .build();
        if(request.getAdapterId() == null || request.getAdapterId().isEmpty()
            || request.getMobile() == null || request.getMobile().isEmpty()
            || request.getPayload() == null
        ) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setError(HttpStatus.BAD_REQUEST.name());
            response.setMessage("Adapter id, mobile & payload are required.");
            return Mono.just(ResponseEntity.badRequest().body(response));
            //        } else if(request.getPayload().getMedia() != null && !(
//                request.getPayload().getMedia().getCategory().equals(MediaCategory.IMAGE_URL)
//                || request.getPayload().getMedia().getCategory().equals(MediaCategory.AUDIO_URL)
//                || request.getPayload().getMedia().getCategory().equals(MediaCategory.VIDEO_URL)
//                || request.getPayload().getMedia().getCategory().equals(MediaCategory.FILE_URL))
//        ) {
//            params.setStatus("failed");
//            params.setErrmsg("Media category can be image_url/audio_url/video_url/document_url only.");
//            response.setParams(params);
//            response.setResponseCode(HttpStatus.BAD_REQUEST.name());
        } else if(request.getPayload().getText() == null && request.getPayload().getMedia() == null) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setError(HttpStatus.BAD_REQUEST.name());
            response.setMessage("Payload should have either text or media.");
            return Mono.just(ResponseEntity.badRequest().body(response));
        } else if(request.getPayload().getMedia() != null
                && (request.getPayload().getMedia().getUrl() == null || request.getPayload().getMedia().getCategory() == null)
        ) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setError(HttpStatus.BAD_REQUEST.name());
            response.setMessage("Payload media should have category and url.");
            return Mono.just(ResponseEntity.badRequest().body(response));
        } else {
            SenderReceiverInfo from = new SenderReceiverInfo().builder().userID("admin").build();
            SenderReceiverInfo to = new SenderReceiverInfo().builder().userID(request.getMobile()).build();
            MessageId msgId = new MessageId().builder().channelMessageId(UUID.randomUUID().toString()).replyId(request.getMobile()).build();
            XMessagePayload payload = request.payload;

            return botService.getAdapterByID(request.getAdapterId())
                    .map(new Function<JsonNode, ResponseEntity<HttpApiResponse>>(){
                        @Override
                        public ResponseEntity<HttpApiResponse> apply(JsonNode adapter) {
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

                            if(request.getPayload().getMedia() != null
                                    && !adapter.path("channel").asText().equalsIgnoreCase("whatsapp")
                            ) {
                                response.setStatus(HttpStatus.BAD_REQUEST.value());
                                response.setError(HttpStatus.BAD_REQUEST.name());
                                response.setMessage("Media type is allowed only for gupshup whatsapp & netcore whatsapp adapter.");
                                return ResponseEntity.badRequest().body(response);
                            } else {

                            }

                            /* Template id required check for cdac sms adapter */
//                            if(adapter.path("channel").asText().equalsIgnoreCase("sms")
//                                    &&  adapter.path("provider").asText().equalsIgnoreCase("cdac")
//                                    && (request.getTemplateId() == null || request.getTemplateId().isEmpty())) {
//                                params.setStatus("failed");
//                                params.setErrmsg("Template id is required for cdac-sms adapter.");
//                                response.setParams(params);
//                                response.setResponseCode(HttpStatus.BAD_REQUEST.name());
//                                return response;
//                            } else {
//                                HashMap<String, String> transformerMeta = new HashMap<>();
//                                transformerMeta.put("templateId", request.getTemplateId());
//                                Transformer transformer = Transformer.builder().metaData(transformerMeta).build();
//                                ArrayList<Transformer> transformers = new ArrayList<>();
//                                transformers.add(transformer);
//
//                                xmsg.setTransformers(transformers);
//                            }

                            /* FCM token required check for firebase adapter */
//                            if(adapter.path("channel").asText().equalsIgnoreCase("web")
//                                    &&  adapter.path("provider").asText().equalsIgnoreCase("firebase")
//                                    && (request.getFcmToken() == null || request.getFcmToken().isEmpty())) {
//                                params.setStatus("failed");
//                                params.setErrmsg("FCM Token is required for firebase adapter.");
//                                response.setParams(params);
//                                response.setResponseCode(HttpStatus.BAD_REQUEST.name());
//                                return response;
//                            } else {
//                                Map<String, String> toMeta = new HashMap<>();
//                                toMeta.put("fcmToken", request.getFcmToken());
//                                to.setMeta(toMeta);
//                                xmsg.setTo(to);
//                            }

                            try {
                                outboundService.sendOutboundMessage(xmsg);
                                response.setMessage("Message processed.");
                            } catch (Exception e) {
                                log.error("Exception while sending outbound message: "+e.getMessage());
                            }

                            return ResponseEntity.ok(response);
                        }
                    });
        }
    }
}
