package io.github.shri299.wirefin.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkMetricsTest {
    @Test void snapshotsExposeProtocolResourceAndRateCounters() {
        NetworkMetrics metrics=new NetworkMetrics(true);
        metrics.received(100);metrics.transmitted(200);metrics.connectionOpened();metrics.connectionClosed();
        metrics.connectionReset();metrics.udpDatagram();metrics.icmpMessage();metrics.malformedPacket();
        metrics.checksumFailure();metrics.fragmentReceived();metrics.fragmentAssembled();metrics.fragmentTimeouts(4);metrics.sackEvents(2);
        metrics.zeroWindowEvents(3);metrics.resourceRejected();
        var snapshot=metrics.snapshot();
        assertEquals(1,snapshot.connectionsOpened());assertEquals(1,snapshot.connectionsClosed());
        assertEquals(1,snapshot.connectionsReset());assertEquals(1,snapshot.udpDatagrams());
        assertEquals(1,snapshot.icmpMessages());assertEquals(1,snapshot.malformedPackets());
        assertEquals(1,snapshot.checksumFailures());assertEquals(1,snapshot.fragmentsReceived());
        assertEquals(1,snapshot.fragmentsAssembled());assertEquals(4,snapshot.fragmentTimeouts());assertEquals(2,snapshot.sackEvents());
        assertEquals(3,snapshot.zeroWindowEvents());assertEquals(1,snapshot.resourceRejections());
        assertTrue(snapshot.packetsPerSecond()>0);assertTrue(snapshot.gigabitsPerSecond()>0);
    }
}
