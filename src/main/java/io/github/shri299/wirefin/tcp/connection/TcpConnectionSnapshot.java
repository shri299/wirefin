package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.tcp.state.TcpState;

/** Immutable diagnostic snapshot of a TCP control block. */
public record TcpConnectionSnapshot(
        long id,
        TcpConnectionKey key,
        TcpState state,
        long sendUnacknowledged,
        long sendNext,
        long receiveNext,
        long congestionWindow,
        long slowStartThreshold,
        long peerReceiveWindow,
        long smoothedRttNanos,
        long rttVariationNanos,
        long rtoNanos,
        long retransmissions,
        long fastRetransmits,
        long rtoEvents,
        int duplicateAcks,
        int sackScoreboardBlocks,
        long sackEvents,
        long zeroWindowEvents,
        long bytesSent,
        long bytesReceived,
        long bytesInFlight,
        int pendingSendBytes,
        int readableBytes,
        int outOfOrderBytes) {}
