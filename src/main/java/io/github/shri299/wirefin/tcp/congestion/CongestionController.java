package io.github.shri299.wirefin.tcp.congestion;

public interface CongestionController {
    void onAcknowledgement(int newlyAcknowledgedBytes);
    void onLoss();
    long congestionWindow();
    long slowStartThreshold();
}
