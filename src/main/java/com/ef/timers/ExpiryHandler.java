package com.ef.timers;

import com.ef.timers.model.TimerType;

public interface ExpiryHandler {
    void handle(TimerType type, Object data);
}
