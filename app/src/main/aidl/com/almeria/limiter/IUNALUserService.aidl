package com.almeria.limiter;

import com.almeria.limiter.IUNALServiceCallback;

interface IUNALUserService {
    void startEngine();
    void stopEngine();
    void updateParameters(float threshold, float attack, float release, float makeupGain, boolean adaptiveGain, boolean dosimeterEnabled);
    void registerCallback(IUNALServiceCallback callback);
    void exit();
    void onFftUpdated(in float[] frequencies);
}
