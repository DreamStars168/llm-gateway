package com.zhouyu.gateway.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用量事件发布器。
 *
 * <p>三级保障：
 * <ol>
 *   <li>正常：直接发 MQ（异步，不阻塞请求）</li>
 *   <li>MQ 不可用：进入本地内存队列（有界，防 OOM）</li>
 *   <li>内存队列溢出：丢弃并计数告警（宁可少量丢，不可拖垮网关）</li>
 * </ol>
 *
 * <p>发布失败不抛异常——用量统计失败绝不能影响用户的对话请求。
 */
@Component
public class UsagePublisher {

    private static final Logger log = LoggerFactory.getLogger(UsagePublisher.class);
    private static final int BUFFER_LIMIT = 20_000;

    private final ObjectProvider<RabbitTemplate> templateProvider;
    private final ConcurrentLinkedQueue<UsageEvent> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong buffered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public UsagePublisher(ObjectProvider<RabbitTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    public void publish(UsageEvent event) {
        RabbitTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            enqueue(event);
            return;
        }
        try {
            template.convertAndSend(UsageMessaging.EXCHANGE, UsageMessaging.ROUTING_KEY, event);
            published.incrementAndGet();
            drainBuffer(template);
        } catch (Exception e) {
            log.warn("用量发布失败，转入本地缓冲: {}", e.getMessage());
            enqueue(event);
        }
    }

    private void enqueue(UsageEvent event) {
        if (buffer.size() >= BUFFER_LIMIT) {
            dropped.incrementAndGet();
            log.error("本地缓冲已满({})，丢弃用量事件 requestId={}", BUFFER_LIMIT, event.requestId());
            return;
        }
        buffer.offer(event);
        buffered.incrementAndGet();
    }

    private void drainBuffer(RabbitTemplate template) {
        UsageEvent e;
        int drained = 0;
        while ((e = buffer.poll()) != null && drained < 100) {
            try {
                template.convertAndSend(UsageMessaging.EXCHANGE, UsageMessaging.ROUTING_KEY, e);
                published.incrementAndGet();
                drained++;
            } catch (Exception ex) {
                buffer.offer(e);
                break;
            }
        }
    }

    public long publishedCount() { return published.get(); }
    public long bufferedCount() { return buffer.size(); }
    public long droppedCount() { return dropped.get(); }
}
