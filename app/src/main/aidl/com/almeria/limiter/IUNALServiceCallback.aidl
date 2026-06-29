package com.almeria.limiter;

interface IUNALServiceCallback {
    void onRmsUpdated(float rmsDb);
    void onGainReductionUpdated(float reductionDb);
    void onDoseUpdated(float dosePercentage);
}
