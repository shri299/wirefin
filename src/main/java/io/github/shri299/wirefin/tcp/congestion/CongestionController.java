package io.github.shri299.wirefin.tcp.congestion;

public interface CongestionController {
    void onAcknowledgement(int newlyAcknowledgedBytes);
    void onTimeout(long bytesInFlight);
    void onFastRetransmit(long bytesInFlight);
    long congestionWindow();
    long slowStartThreshold();
}
