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
public class NotificationConsumerReactive {
    @Autowired
    private EmailServiceImpl emailService;
    @Value("${spring.mail.recipient}")
    private String recipient;
    @Autowired
    private ProviderFactory factoryProvider;
    @Autowired
    private RedisCacheService redisCacheService;
    @Autowired
    private XMessageRepository xMessageRepo;

    private final Flux<ReceiverRecord<String, String>> reactiveKafkaReceiverNotification;

    private long notificationCount, otherCount, consumeCount;


    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {
        try {
            reactiveKafkaReceiverNotification
                    .doOnNext(this::logMessage)
                    .flatMap(this::sendOutboundMessage)
                    .onBackpressureBuffer()
                    .bufferTimeout(1000, Duration.ofSeconds(10))
                    .flatMap(this::persistToCassandra)
                    .doOnError(this::handleKafkaFluxError)
                    .subscribe();
        } catch (Exception ex) {
            log.error("NotificationConsumerReactive:Exception: Exception: " + ex.getMessage());
        }
    }

    private void logMessage(ReceiverRecord<String, String> msg) {
        log.info("NotificationConsumerReactive:Notification topic consume from kafka: " + consumeCount);
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
                            sentEmail(currentXmsg, "Error in Outbound", "PFA", null, attachments);
                            log.error("NotificationConsumerReactive:Exception: Exception in processOutBoundMessageF:" + e.getMessage());
                            return Mono.just(new XMessage());
                        });
            } catch (Exception e) {
                HashMap<String, String> attachments = new HashMap<>();
                attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                attachments.put("XMessage", msg.toString());
                sentEmail(null, "Error in Outbound", "PFA", null, attachments);
                log.error("NotificationConsumerReactive:Exception: " + e.getMessage());
                return Mono.just(new XMessage());
            }
        });
    }

    public Flux<XMessage> persistToCassandra(List<XMessage> xMessageList) {
        log.info("Buffer data : " + xMessageList.size() + " [0] : " + xMessageList.get(0));
        return Flux.fromIterable(xMessageList)
                .doOnNext(this::saveXMessage)
                .doOnError(msg -> log.error("NotificationConsumerReactive:Exception: " + msg));
    }


    public void saveXMessage(XMessage xMessage) {
        if (xMessage.getApp() != null) {
            try {
                log.info("NotificationConsumerReactive:saveXMessage::convertXMessageToDAO : " + xMessage.toString());
                XMessageDAO dao = null;
                dao = XMessageDAOUtils.convertXMessageToDAO(xMessage);
                redisCacheService.setXMessageDaoCache(xMessage.getTo().getUserID(), dao);
                xMessageRepo
                        .insert(dao)
                        .doOnError(new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable e) {
                                redisCacheService.deleteXMessageDaoCache(xMessage.getTo().getUserID());
                                log.error("NotificationConsumerReactive:Exception: " + e.getMessage());
                            }
                        })
                        .subscribe(new Consumer<XMessageDAO>() {
                            @Override
                            public void accept(XMessageDAO xMessageDAO) {
                                log.info("NotificationConsumerReactive: XMessage Object saved is with sent user ID >> " + xMessageDAO.getUserId());

                                String channel = xMessage.getChannelURI();
                                String provider = xMessage.getProviderURI();

                                if (provider.toLowerCase().equals("firebase") && channel.toLowerCase().equals("web")) {
                                    notificationCount++;
                                    log.info("NotificationConsumerReactive:Notification Insert Record in Cass : " + notificationCount);
//                                    logTimeTaken(startTime, 0, "NotificationConsumerReactive:Notification Insert Record in Cass : " + notificationCount + " ::: process-end: %d ms");
                                } else {
                                    otherCount++;
//                                    logTimeTaken(startTime, 0, "Other Insert Record in Cass : " + otherCount + " ::: process-end: %d ms");
                                    log.info("Other Insert Record in Cass : " + otherCount);
                                }
                            }
                        });
            } catch (Exception e) {
                HashMap<String, String> attachments = new HashMap<>();
                attachments.put("Exception", ExceptionUtils.getStackTrace(e));
                attachments.put("XMessage", xMessage.toString());
                sentEmail(xMessage, "Error in Outbound", "PFA", null, attachments);
                log.error("NotificationConsumerReactive:Exception: Exception in convertXMessageToDAO: " + e.getMessage());
            }
        } else {
            log.info("NotificationConsumerReactive:XMessage -> app is empty " + xMessage);
        }
    }

    private void handleKafkaFluxError(Throwable e) {
        HashMap<String, String> attachments = new HashMap<>();
        attachments.put("Exception", ExceptionUtils.getStackTrace(e));
        try {
            sentEmail(null, "Error in Outbound", "PFA", null, attachments);
        } catch (Exception ex) {
            log.error("NotificationConsumerReactive:Exception:" + ex.getMessage());
        }
        log.error("NotificationConsumerReactive:Exception: " + e.getMessage());
    }

    private void sentEmail(XMessage xMessage, String subject, String body, String attachmentFileName, HashMap<String, String> attachments) {
        log.info("Email Sending....");
        EmailDetails emailDetails = new EmailDetails().builder()
                .subject(subject)
                .msgBody(body)
                .recipient(recipient)
                .attachment(xMessage == null ? "" : xMessage.toString())
                .attachmentFileName(attachmentFileName)
                .attachments(attachments)
                .build();
        emailService.sendMailWithAttachment(emailDetails);
    }
}
