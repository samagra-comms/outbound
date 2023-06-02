package com.uci.outbound.consumers;

import com.uci.adapter.provider.factory.IProvider;
import com.uci.adapter.provider.factory.ProviderFactory;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.dao.utils.XMessageDAOUtils;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.model.EmailDetails;
import com.uci.utils.service.EmailServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.XMessage;
import messagerosa.xml.XMessageParser;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundKafkaController {

    private final Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver;

    @Autowired
    private ProviderFactory factoryProvider;

    @Autowired
    private XMessageRepository xMessageRepo;

    @Autowired
    private RedisCacheService redisCacheService;

    private HashOperations hashOperations; //to access Redis cache

    @Autowired
    private EmailServiceImpl emailService;

    @Value("${spring.mail.recipient}")
    private String recipient;

    private long notificationCount, otherCount, consumeCount;

//    @Value("${outbound.buffer.size}")
//    private String bufferSize;
//    @Value("${outbound.buffer.duration}")
//    private String bufferDuration;
//    int buffSize;
//    int buffDuration;
//    {
//        try {
//            buffSize = Integer.parseInt(bufferSize);
//        } catch (Exception ex) {
//            buffSize = 1000;
//        }
//        try {
//            buffDuration = Integer.parseInt(bufferDuration);
//        } catch (Exception ex) {
//            buffDuration = 10;
//        }
//    }

    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {

        reactiveKafkaReceiver
                .doOnNext(this::logMessage)
                .flatMap(this::sendOutboundMessage)
                .onBackpressureBuffer()
                .bufferTimeout(1000, Duration.ofSeconds(10))
                .flatMap(this::persistToCassandra)
                .doOnError(this::handleKafkaFluxError)
                .subscribe();
    }

    public void handleKafkaFluxError(Throwable e) {
        HashMap<String, String> attachments = new HashMap<>();
        attachments.put("Exception", ExceptionUtils.getStackTrace(e));
        sentEmail(null, "Error in Outbound", "PFA", recipient, null, attachments);
        log.error("OutboundKafkaController:Exception: "+ e.getMessage());
    }

    public void logMessage(ReceiverRecord<String, String> msg) {
        log.info("kafka message received!");
    }

    public Mono<XMessage> sendOutboundMessage(ReceiverRecord<String, String> msg) {
        return Mono.defer(() -> {
            try {
                XMessage currentXmsg = XMessageParser.parse(new ByteArrayInputStream(msg.value().getBytes()));
                String channel = currentXmsg.getChannelURI();
                String provider = currentXmsg.getProviderURI();
                IProvider iprovider = factoryProvider.getProvider(provider, channel);
                return iprovider.processOutBoundMessageF(currentXmsg)
                        .onErrorResume(e -> {
                            HashMap<String, String> attachments = new HashMap<>();
                            attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                            attachments.put("XMessage", currentXmsg.toString());
                            sentEmail(currentXmsg, "Error in Outbound", "PFA", recipient, null, attachments);
                            log.error("OutboundKafkaController:Exception: Exception in processOutBoundMessageF:" + e.getMessage());
                            return Mono.error(e);
                        });
            } catch (Exception e) {
//                HashMap<String, String> attachments = new HashMap<>();
//                attachments.put("Exception", ExceptionUtils.getStackTrace(e));
//                attachments.put("XMessage", msg.toString());
//                sentEmail(msg, "Error in Outbound", "PFA", recipient, null, attachments);
                log.error("OutboundKafkaController:Exception: " + e.getMessage());
                return Mono.error(e);
            }
        });
    }

    public Flux<XMessage> persistToCassandra(List<XMessage> xMessageList) {
        log.info("Buffer data : " + xMessageList.size() + " [0] : " + xMessageList.get(0));
        return Flux.fromIterable(xMessageList)
                .doOnNext(this::saveXMessage)
                .doOnError(msg -> log.error("OutboundKafkaController:Exception: "+msg));
//        return saveXMessages(xMessageList)
//                .onErrorResume(e -> {
//                    HashMap<String, String> attachments = new HashMap<>();
//                    attachments.put("Exception", ExceptionUtils.getStackTrace(e));
//                    sentEmail(null, "Error in persistToCassandra", "PFA", recipient, null, attachments);
//                    log.error("An Error Occurred in persistToCassandra: " + e.getMessage());
//                    return Mono.error(e);
//                });
    }

    public void saveXMessage(XMessage xMessage) {
        if (xMessage.getApp() != null) {
            try {
                log.info("Outbound convertXMessageToDAO : " + xMessage.toString());
                XMessageDAO dao = null;
                dao = XMessageDAOUtils.convertXMessageToDAO(xMessage);
                redisCacheService.setXMessageDaoCache(xMessage.getTo().getUserID(), dao);
                xMessageRepo
                        .insert(dao)
                        .doOnError(new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable e) {
                                redisCacheService.deleteXMessageDaoCache(xMessage.getTo().getUserID());
                                log.error("OutboundKafkaController:Exception: " + e.getMessage());
                            }
                        })
                        .subscribe(new Consumer<XMessageDAO>() {
                            @Override
                            public void accept(XMessageDAO xMessageDAO) {
                                log.info("XMessage Object saved is with sent user ID >> " + xMessageDAO.getUserId());
                                notificationCount++;
                                log.info("OutboundKafkaController:Notification Insert Record in Cass : " + notificationCount);
//                                if (provider.toLowerCase().equals("firebase") && channel.toLowerCase().equals("web")) {

//                                logTimeTaken(startTime, 0, "OutboundKafkaController:Notification Insert Record in Cass : " + notificationCount + " ::: process-end: %d ms");
//                                } else {
//                                    otherCount++;
//                                    logTimeTaken(startTime, 0, "Other Insert Record in Cass : " + otherCount + " ::: process-end: %d ms");
//                                }
                            }
                        });
            } catch (Exception e) {
//                HashMap<String, String> attachments = new HashMap<>();
//                attachments.put("Exception", ExceptionUtils.getStackTrace(e));
//                attachments.put("XMessage", currentXmsg.toString());
//                sentEmail(xMessage, "Error in Outbound", "PFA", recipient, null, attachments);
                log.error("OutboundKafkaController:Exception: Exception in convertXMessageToDAO: " + e.getMessage());
            }
        } else {
            log.info("OutboundKafkaController:XMessage -> app is empty");
        }
    }

//    private String createInsertQuery(List<XMessage> xMessageList) {
//        String query = "";
//        for (XMessage xMessage : xMessageList) {
//            ""
//        }
//        return query;
//    }


//    @EventListener(ApplicationStartedEvent.class)
//    public void onMessage() {
//
//        reactiveKafkaReceiver
//                .doOnNext(new Consumer<ReceiverRecord<String, String>>() {
//                    @Override
//                    public Mono<XMessage> accept(ReceiverRecord<String, String> msg) {
//                        final long startTime = System.nanoTime();
//                        logTimeTaken(startTime, 0, "process-start: %d ms");
//                        XMessage currentXmsg = null;
//                        try {
//                            currentXmsg = XMessageParser.parse(new ByteArrayInputStream(msg.value().getBytes()));
//                            if (currentXmsg != null && currentXmsg.getProviderURI() != null && currentXmsg.getProvider().equalsIgnoreCase("firebase")) {
//                                consumeCount++;
//                                log.info("OutboundKafkaController:Notification topic consume from kafka: " + consumeCount);
//                            }
//                            sendOutboundMessage(currentXmsg, startTime);
//                        } catch (Exception e) {
//                            HashMap<String, String> attachments = new HashMap<>();
//                            attachments.put("Exception", ExceptionUtils.getStackTrace(e));
//                            attachments.put("XMessage", currentXmsg.toString());
//                            sentEmail(currentXmsg, "Error in Outbound", "PFA", recipient, null, attachments);
//                            log.error("OutboundKafkaController:Exception: " + e.getMessage());
//                        }
//                    }
//                })
//                .buffer(1000)
//                .doOnError(new Consumer<Throwable>() {
//                    @Override
//                    public void accept(Throwable e) {
//                        HashMap<String, String> attachments = new HashMap<>();
//                        attachments.put("Exception", ExceptionUtils.getStackTrace(e));
//                        sentEmail(null, "Error in Outbound", "PFA", recipient, null, attachments);
//                        log.error("OutboundKafkaController:Exception: KafkaFlux exception: " + e.getMessage());
//                    }
//                })
//                .subscribe();
//    }

//    /**
//     * Send outbound message to user using the current xmsg
//     *
//     * @param currentXmsg
//     * @throws Exception
//     */
////    public Mono<XMessage> sendOutboundMessage(XMessage currentXmsg, long startTime) throws Exception {
//        String channel = currentXmsg.getChannelURI();
//        String provider = currentXmsg.getProviderURI();
//        IProvider iprovider = factoryProvider.getProvider(provider, channel);
//        return iprovider.processOutBoundMessageF(currentXmsg)
//                .doOnError(new Consumer<Throwable>() {
//                    @Override
//                    public void accept(Throwable e) {
//                        HashMap<String, String> attachments = new HashMap<>();
//                        attachments.put("Exception", ExceptionUtils.getStackTrace(e));
//                        attachments.put("XMessage", currentXmsg.toString());
//                        sentEmail(currentXmsg, "Error in Outbound", "PFA", recipient, null, attachments);
//                        log.error("Exception in processOutBoundMessageF:" + e.getMessage());
//                    }
//                });
////                .subscribe(new Consumer<XMessage>() {
////                    @Override
////                    public void accept(XMessage xMessage) {
////                        if (xMessage.getApp() != null) {
////                            try {
////                                log.info("Outbound convertXMessageToDAO : " + xMessage.toString());
////                                XMessageDAO dao = null;
////                                dao = XMessageDAOUtils.convertXMessageToDAO(xMessage);
////                                redisCacheService.setXMessageDaoCache(xMessage.getTo().getUserID(), dao);
////                                xMessageRepo
////                                        .insert(dao)
////                                        .doOnError(new Consumer<Throwable>() {
////                                            @Override
////                                            public void accept(Throwable e) {
////                                                redisCacheService.deleteXMessageDaoCache(xMessage.getTo().getUserID());
////                                                log.error("OutboundKafkaController:Exception: " + e.getMessage());
////                                            }
////                                        })
////                                        .subscribe(new Consumer<XMessageDAO>() {
////                                            @Override
////                                            public void accept(XMessageDAO xMessageDAO) {
////                                                log.info("XMessage Object saved is with sent user ID >> " + xMessageDAO.getUserId());
////                                                if (provider.toLowerCase().equals("firebase") && channel.toLowerCase().equals("web")) {
////                                                    notificationCount++;
////                                                    logTimeTaken(startTime, 0, "OutboundKafkaController:Notification Insert Record in Cass : " + notificationCount + " ::: process-end: %d ms");
////                                                } else {
////                                                    otherCount++;
////                                                    logTimeTaken(startTime, 0, "Other Insert Record in Cass : " + otherCount + " ::: process-end: %d ms");
////                                                }
////                                            }
////                                        });
////                            } catch (Exception e) {
////                                HashMap<String, String> attachments = new HashMap<>();
////                                attachments.put("Exception", ExceptionUtils.getStackTrace(e));
////                                attachments.put("XMessage", currentXmsg.toString());
////                                sentEmail(xMessage, "Error in Outbound", "PFA", recipient, null, attachments);
////                                log.error("OutboundKafkaController:Exception: Exception in convertXMessageToDAO: " + e.getMessage());
////                                try {
////                                    log.error("The current XMessage was : " + xMessage);
////                                } catch (Exception ge) {
////                                    log.error("Unable to parse the current XMessage : " + ge.getMessage() + " Xmessage : " + ge.getMessage());
////                                }
////                            }
////                        } else {
////                            log.info("OutboundKafkaController:XMessage -> app is empty");
////                        }
////                    }
////                });
//    }

    private String redisKeyWithPrefix(String key) {
        return System.getenv("ENV") + "-" + key;
    }

    private void sentEmail(XMessage xMessage, String subject, String body, String recipient, String attachmentFileName, HashMap<String, String> attachments) {
        log.info("Email Sending....");
        EmailDetails emailDetails = new EmailDetails().builder()
                .subject(subject)
                .msgBody(body)
                .recipient(recipient)
                .attachment(xMessage == null ? "" : xMessage.toString())
                .attachmentFileName(attachmentFileName)
                .attachments(attachments)
                .build();
//        log.info("EmailDetails :" + emailDetails);
        emailService.sendMailWithAttachment(emailDetails);
    }

    private void logTimeTaken(long startTime, int checkpointID, String formatedMsg) {
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        if (formatedMsg == null) {
            log.info(String.format("CP-%d: %d ms", checkpointID, duration));
        } else {
            log.info(String.format(formatedMsg, duration));
        }
    }
}