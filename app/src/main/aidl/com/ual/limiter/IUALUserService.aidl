package com.ual.limiter;

import com.ual.limiter.IUALServiceCallback;

interface IUALUserService {
    void startEngine();
    void stopEngine();
    void updateParameters(float threshold, float attack, float release, float makeupGain, boolean adaptiveGain, boolean dosimeterEnabled);
    void registerCallback(IUALServiceCallback callback);
    void exit();
}
