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
import reactor.kafka.receiver.ReceiverRecord;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
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

    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {

        reactiveKafkaReceiver
                .doOnNext(new Consumer<ReceiverRecord<String, String>>() {
                    @Override
                    public void accept(ReceiverRecord<String, String> msg) {
                        final long startTime = System.nanoTime();
                        logTimeTaken(startTime, 0, "process-start: %d ms");
                        XMessage currentXmsg = null;
                        try {
                            currentXmsg = XMessageParser.parse(new ByteArrayInputStream(msg.value().getBytes()));
                            if (currentXmsg != null && currentXmsg.getProviderURI() != null && currentXmsg.getProvider().equalsIgnoreCase("firebase")) {
                                consumeCount++;
                                log.info("OutboundKafkaController:Notification topic consume from kafka: " + consumeCount);
                            }
                            sendOutboundMessage(currentXmsg, startTime);
                        } catch (Exception e) {
                            HashMap<String, String> attachments = new HashMap<>();
                            attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                            attachments.put("XMessage", currentXmsg.toString());
                            sentEmail(currentXmsg, "Error in Outbound", "PFA", recipient, null, attachments);
                            log.error("OutboundKafkaController:Exception: " + e.getMessage());
                        }
                    }
                })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable e) {
                        HashMap<String, String> attachments = new HashMap<>();
                        attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                        sentEmail(null, "Error in Outbound", "PFA", recipient, null, attachments);
                        log.error("OutboundKafkaController:Exception: KafkaFlux exception: " + e.getMessage());
                    }
                })
                .subscribe();
    }

    /**
     * Send outbound message to user using the current xmsg
     *
     * @param currentXmsg
     * @throws Exception
     */
    public void sendOutboundMessage(XMessage currentXmsg, long startTime) throws Exception {
        String channel = currentXmsg.getChannelURI();
        String provider = currentXmsg.getProviderURI();
        IProvider iprovider = factoryProvider.getProvider(provider, channel);
        iprovider.processOutBoundMessageF(currentXmsg)
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable e) {
                        HashMap<String, String> attachments = new HashMap<>();
                        attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                        attachments.put("XMessage", currentXmsg.toString());
                        sentEmail(currentXmsg, "Error in Outbound", "PFA", recipient, null, attachments);
                        log.error("Exception in processOutBoundMessageF:" + e.getMessage());
                    }
                }).subscribe(new Consumer<XMessage>() {
                    @Override
                    public void accept(XMessage xMessage) {
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
                                                if (provider.toLowerCase().equals("firebase") && channel.toLowerCase().equals("web")) {
                                                    notificationCount++;
                                                    logTimeTaken(startTime, 0, "OutboundKafkaController:Notification Insert Record in Cass : " + notificationCount + " ::: process-end: %d ms");
                                                } else {
                                                    otherCount++;
                                                    logTimeTaken(startTime, 0, "Other Insert Record in Cass : " + otherCount + " ::: process-end: %d ms");
                                                }
                                            }
                                        });
                            } catch (Exception e) {
                                HashMap<String, String> attachments = new HashMap<>();
                                attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                                attachments.put("XMessage", currentXmsg.toString());
                                sentEmail(xMessage, "Error in Outbound", "PFA", recipient, null, attachments);
                                log.error("OutboundKafkaController:Exception: Exception in convertXMessageToDAO: " + e.getMessage());
                            }
                        } else {
                            log.info("OutboundKafkaController:XMessage -> app is empty : " + xMessage);
                        }
                    }
                });
    }

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