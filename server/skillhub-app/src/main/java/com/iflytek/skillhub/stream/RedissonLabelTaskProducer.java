package com.iflytek.skillhub.stream;

import com.iflytek.skillhub.domain.label.LabelTask;
import com.iflytek.skillhub.domain.label.LabelTaskProducer;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class RedissonLabelTaskProducer implements LabelTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(RedissonLabelTaskProducer.class);

    private final RedissonClient redissonClient;
    private final String streamKey;

    public RedissonLabelTaskProducer(RedissonClient redissonClient, String streamKey) {
        this.redissonClient = redissonClient;
        this.streamKey = streamKey;
    }

    @Override
    public void publishLabelTask(LabelTask task) {
        Map<String, String> fields = new HashMap<>();
        fields.put("taskId", task.taskId());
        fields.put("skillId", String.valueOf(task.skillId()));
        if (task.skillName() != null && !task.skillName().isBlank()) {
            fields.put("skillName", task.skillName());
        }
        if (task.summary() != null && !task.summary().isBlank()) {
            fields.put("summary", task.summary());
        }
        if (task.bodySample() != null && !task.bodySample().isBlank()) {
            fields.put("bodySample", task.bodySample());
        }
        fields.put("operatorId", task.operatorId() != null ? task.operatorId() : "");
        fields.put("createdAtMillis", String.valueOf(task.createdAtMillis()));
        if (task.metadata() != null) {
            fields.putAll(task.metadata());
        }

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamMessageId messageId = stream.add(StreamAddArgs.entries(fields));
        log.info("Published label task: taskId={}, skillId={}, recordId={}",
                task.taskId(), task.skillId(), messageId);
    }
}
