package com.ef.timers.model;

import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@ToString
public class TimerEntity {
    private String id;
    private String delayedMessageId;
    private long delay;

    public TimerEntity(String id, long delay) {
        this.id = id;
        this.delayedMessageId = UUID.randomUUID().toString();
        this.delay = delay;
    }
}
