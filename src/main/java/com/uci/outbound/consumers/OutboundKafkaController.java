package com.uci.outbound.consumers;

import com.uci.adapter.provider.factory.IProvider;
import com.uci.adapter.provider.factory.ProviderFactory;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.XMessage;
import com.uci.dao.utils.XMessageDAOUtils;
import com.uci.utils.cdn.samagra.MinioClientService;

import io.fusionauth.client.FusionAuthClient;
import io.fusionauth.domain.api.LoginRequest;
import messagerosa.xml.XMessageParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.ReceiverRecord;

import java.io.ByteArrayInputStream;
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
    private RedisTemplate<String, Object> redisTemplate;
    
    private HashOperations hashOperations; //to access Redis cache
    
    @Autowired
    private MinioClientService minioClientService;

    private Consumer<Throwable> genericError(String s) {
        return c -> {
            log.error(s + "::" + c.getMessage());
        };
    }

    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {
    	
        reactiveKafkaReceiver
                .doOnNext(new Consumer<ReceiverRecord<String, String>>() {
                    @Override
                    public void accept(ReceiverRecord<String, String> msg) {
                        XMessage currentXmsg = null;
                        try {
                            currentXmsg = XMessageParser.parse(new ByteArrayInputStream(msg.value().getBytes()));
                            String channel = currentXmsg.getChannelURI();
                            String provider = currentXmsg.getProviderURI();
                            IProvider iprovider = factoryProvider.getProvider(provider, channel, minioClientService);
                            iprovider.processOutBoundMessageF(currentXmsg)
                            	.doOnError(new Consumer<Throwable>() {
				                    @Override
				                    public void accept(Throwable e) {
				                        log.error("Exception in processOutBoundMessageF:"+e.getMessage());
				                    }
				                }).subscribe(new Consumer<XMessage>() {
				                	@Override
	                                public void accept(XMessage xMessage) {
	                                    XMessageDAO dao = XMessageDAOUtils.convertXMessageToDAO(xMessage);
	                                    
	                                    try {
	                                    	hashOperations = redisTemplate.opsForHash();
	                                        hashOperations.put(redisKeyWithPrefix("XMessageDAO"), redisKeyWithPrefix(xMessage.getTo().getUserID()), dao);
	                                	log.info("Updated redis cache with key: "+redisKeyWithPrefix("XMessageDAO")+", "+redisKeyWithPrefix(xMessage.getTo().getUserID()));    
					    } catch (Exception e) {
	                                    	/* If redis cache not able to set, delete cache */
	                                    	hashOperations.delete(redisKeyWithPrefix("XMessageDAO"), redisKeyWithPrefix(xMessage.getTo().getUserID()));
	                                    	
	                                    	log.info("Exception in redis put: "+e.getMessage());
	                                    	e.printStackTrace();
	                                    }
	                                    
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
    
    private String redisKeyWithPrefix(String key) {
    	return System.getenv("ENV")+"-"+key;
    }
}
