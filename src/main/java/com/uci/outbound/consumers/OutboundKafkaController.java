package com.uci.outbound.consumers;

import com.uci.adapter.provider.factory.IProvider;
import com.uci.adapter.provider.factory.ProviderFactory;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.XMessage;
import com.uci.dao.utils.XMessageDAOUtils;
import com.uci.utils.kafka.adapter.TextMapGetterAdapter;
import com.uci.utils.kafka.adapter.TextMapSetterAdapter;

import io.grpc.Context;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import messagerosa.xml.XMessageParser;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.ReceiverRecord;

import java.io.ByteArrayInputStream;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundKafkaController {

    private final Flux<ConsumerRecord<String, String>> reactiveKafkaReceiver;

    @Autowired
    private ProviderFactory factoryProvider;

    @Autowired
    private XMessageRepository xMessageRepo;
    
    @Autowired
	private Tracer tracer;

    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {
    	
        reactiveKafkaReceiver
                .doOnNext(new Consumer<ConsumerRecord<String, String>>() {
                    @Override
                    public void accept(ConsumerRecord<String, String> msg) {
                    	log.info("kafka message receieved");                    	
                    	XMessage currentXmsg = null;
//                    	Context extractedContext = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.current(), msg.headers(), TextMapGetterAdapter.getter);
//                        log.info("Opentelemetry extracted context : "+extractedContext);
                        
//                		try (Scope scope = extractedContext.makeCurrent()) {
                    	try {
                            currentXmsg = XMessageParser.parse(new ByteArrayInputStream(msg.value().getBytes()));
                            String channel = currentXmsg.getChannelURI();
                            String provider = currentXmsg.getProviderURI();
                            IProvider iprovider = factoryProvider.getProvider(provider, channel);
                            iprovider.processOutBoundMessageF(currentXmsg).subscribe(new Consumer<XMessage>() {
                                @Override
                                public void accept(XMessage xMessage) {
                                    XMessageDAO dao = XMessageDAOUtils.convertXMessageToDAO(xMessage);
                                    xMessageRepo
                                            .insert(dao)
                                            .subscribe(new Consumer<XMessageDAO>() {
                                                @Override
                                                public void accept(XMessageDAO xMessageDAO) {
                                                    log.info("XMessage Object saved is with sent user ID >> " + xMessageDAO.getUserId());
                                                }
                                            });
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                    }
                })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable e) {
                        System.out.println(e.getMessage());
                        log.error("KafkaFlux exception", e);
                    }
                })
                .subscribe();
    }
}
