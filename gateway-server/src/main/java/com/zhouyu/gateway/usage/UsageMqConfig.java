package com.zhouyu.gateway.usage;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：三队列共用一次发布（由各自 binding 分发），外带死信。
 *
 * <pre>
 * gateway.usage (direct) ──usage.record──▶ persist / aggregate / billing
 *        └─(消费失败)──▶ gateway.usage.dlx ──▶ gateway.usage.dlq
 * </pre>
 */
@Configuration
public class UsageMqConfig {

    @Bean
    DirectExchange usageExchange() {
        return new DirectExchange(UsageMessaging.EXCHANGE, true, false);
    }

    @Bean
    DirectExchange usageDlx() {
        return new DirectExchange(UsageMessaging.DLX, true, false);
    }

    @Bean
    Queue persistQueue() {
        return QueueBuilder.durable(UsageMessaging.QUEUE_PERSIST)
                .withArgument("x-dead-letter-exchange", UsageMessaging.DLX)
                .withArgument("x-dead-letter-routing-key", UsageMessaging.QUEUE_DLQ)
                .build();
    }

    @Bean
    Queue aggregateQueue() {
        return QueueBuilder.durable(UsageMessaging.QUEUE_AGGREGATE)
                .withArgument("x-dead-letter-exchange", UsageMessaging.DLX)
                .withArgument("x-dead-letter-routing-key", UsageMessaging.QUEUE_DLQ)
                .build();
    }

    @Bean
    Queue billingQueue() {
        return QueueBuilder.durable(UsageMessaging.QUEUE_BILLING)
                .withArgument("x-dead-letter-exchange", UsageMessaging.DLX)
                .withArgument("x-dead-letter-routing-key", UsageMessaging.QUEUE_DLQ)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(UsageMessaging.QUEUE_DLQ).build();
    }

    @Bean
    Binding persistBinding(Queue persistQueue, DirectExchange usageExchange) {
        return BindingBuilder.bind(persistQueue).to(usageExchange).with(UsageMessaging.ROUTING_KEY);
    }

    @Bean
    Binding aggregateBinding(Queue aggregateQueue, DirectExchange usageExchange) {
        return BindingBuilder.bind(aggregateQueue).to(usageExchange).with(UsageMessaging.ROUTING_KEY);
    }

    @Bean
    Binding billingBinding(Queue billingQueue, DirectExchange usageExchange) {
        return BindingBuilder.bind(billingQueue).to(usageExchange).with(UsageMessaging.ROUTING_KEY);
    }

    @Bean
    Binding dlqBinding(Queue deadLetterQueue, DirectExchange usageDlx) {
        return BindingBuilder.bind(deadLetterQueue).to(usageDlx).with(UsageMessaging.QUEUE_DLQ);
    }

    @Bean
    Jackson2JsonMessageConverter usageMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate usageRabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(converter);
        return template;
    }
}
