package io.github.shri299.wirefin.tcp.congestion;

public interface CongestionController {
    void onAcknowledgement(int newlyAcknowledgedBytes);
    void onTimeout(long bytesInFlight);
    void onFastRetransmit(long bytesInFlight);
    default void onFastRetransmit(long bytesInFlight, long recoveryPoint) { onFastRetransmit(bytesInFlight); }
    default void onDuplicateAck() {}
    default void onPartialAcknowledgement(int newlyAcknowledgedBytes) {}
    default void onRecoveryComplete() {}
    default boolean inFastRecovery() { return false; }
    default long recoveryPoint() { return 0; }
    long congestionWindow();
    long slowStartThreshold();
}
