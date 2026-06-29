package com.almeria.limiter;

interface IUALServiceCallback {
    void onRmsUpdated(float rmsDb);
    void onGainReductionUpdated(float reductionDb);
    void onDoseUpdated(float dosePercentage);
}
