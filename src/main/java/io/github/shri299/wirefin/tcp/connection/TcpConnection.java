package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.reliability.SequenceNumber;
import io.github.shri299.wirefin.tcp.state.TcpEvent;
import io.github.shri299.wirefin.tcp.state.TcpState;
import io.github.shri299.wirefin.tcp.state.TcpStateMachine;

import java.util.List;
import java.util.logging.Logger;

/** Per-connection TCP control block. All mutations are serialized by synchronized methods. */
public final class TcpConnection {
    private static final Logger LOG = Logger.getLogger(TcpConnection.class.getName());
    public static final int DEFAULT_RECEIVE_WINDOW = 65_535;

    private final TcpConnectionKey key;
    private final TcpStateMachine states = new TcpStateMachine(TcpState.LISTEN);
    private final long initialSendSequence;
    private final long initialReceiveSequence;
    private long sendUnacknowledged;
    private long sendNext;
    private long receiveNext;
    private int remoteWindow;

    private TcpConnection(TcpConnectionKey key, long initialSendSequence, TcpSegment syn) {
        this.key = key;
        this.initialSendSequence = initialSendSequence & SequenceNumber.MASK;
        this.initialReceiveSequence = syn.sequenceNumber();
        this.sendUnacknowledged = this.initialSendSequence;
        this.sendNext = SequenceNumber.add(this.initialSendSequence, 1);
        this.receiveNext = SequenceNumber.add(syn.sequenceNumber(), 1);
        this.remoteWindow = syn.windowSize();
        transition(TcpEvent.RECEIVE_SYN);
    }

    public static TcpConnection passiveOpen(TcpConnectionKey key, long initialSendSequence, TcpSegment syn) {
        if (!syn.has(TcpFlags.SYN) || syn.has(TcpFlags.ACK)) throw new IllegalArgumentException("passive open requires bare SYN");
        return new TcpConnection(key, initialSendSequence, syn);
    }

    public TcpSegment synAck() {
        return segment(initialSendSequence, receiveNext, TcpFlags.SYN | TcpFlags.ACK, new byte[0]);
    }

    public synchronized ProcessingResult receive(TcpSegment incoming) {
        if (incoming.has(TcpFlags.RST)) {
            states.reset();
            LOG.fine(() -> key + " reset by peer");
            return new ProcessingResult(List.of(), false, true);
        }
        remoteWindow = incoming.windowSize();
        if (states.state() == TcpState.SYN_RECEIVED) {
            if (!incoming.has(TcpFlags.ACK) || incoming.acknowledgementNumber() != sendNext)
                return new ProcessingResult(List.of(), false, false);
            sendUnacknowledged = incoming.acknowledgementNumber();
            transition(TcpEvent.RECEIVE_ACK);
            return new ProcessingResult(List.of(), true, false);
        }
        return new ProcessingResult(List.of(), false, false);
    }

    private TcpSegment segment(long sequence, long acknowledgement, int flags, byte[] payload) {
        return new TcpSegment(key.localPort(), key.remotePort(), sequence, acknowledgement, flags,
                DEFAULT_RECEIVE_WINDOW, 0, new byte[0], payload);
    }

    private void transition(TcpEvent event) {
        TcpState before = states.state();
        TcpState after = states.transition(event);
        LOG.fine(() -> key + " " + before + " -> " + after + " event=" + event);
    }

    public TcpConnectionKey key() { return key; }
    public synchronized TcpState state() { return states.state(); }
    public long initialSendSequence() { return initialSendSequence; }
    public long initialReceiveSequence() { return initialReceiveSequence; }
    public synchronized long sendUnacknowledged() { return sendUnacknowledged; }
    public synchronized long sendNext() { return sendNext; }
    public synchronized long receiveNext() { return receiveNext; }
    public synchronized int remoteWindow() { return remoteWindow; }

    public record ProcessingResult(List<TcpSegment> outbound, boolean justEstablished, boolean closed) {
        public ProcessingResult { outbound = List.copyOf(outbound); }
    }
}
