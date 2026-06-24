package com.almeria.limiter;

import com.almeria.limiter.IUALServiceCallback;

interface IUALUserService {
    void startEngine();
    void stopEngine();
    void updateParameters(float threshold, float attack, float release, float makeupGain, boolean adaptiveGain, boolean dosimeterEnabled);
    void registerCallback(IUALServiceCallback callback);
    void exit();
    void onFftUpdated(in float[] frequencies);
}
