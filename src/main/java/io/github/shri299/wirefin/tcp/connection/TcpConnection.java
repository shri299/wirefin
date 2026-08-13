package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.congestion.BasicCongestionController;
import io.github.shri299.wirefin.tcp.congestion.CongestionController;
import io.github.shri299.wirefin.tcp.reliability.RetransmissionManager;
import io.github.shri299.wirefin.tcp.reliability.SequenceNumber;
import io.github.shri299.wirefin.tcp.state.TcpEvent;
import io.github.shri299.wirefin.tcp.state.TcpState;
import io.github.shri299.wirefin.tcp.state.TcpStateMachine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/** Per-connection TCP control block. All mutations are serialized by synchronized methods. */
public final class TcpConnection {
    private static final Logger LOG = Logger.getLogger(TcpConnection.class.getName());
    public static final int DEFAULT_RECEIVE_WINDOW = 65_535;
    public static final int DEFAULT_MSS = 1400;

    private final TcpConnectionKey key;
    private final TcpStateMachine states = new TcpStateMachine(TcpState.LISTEN);
    private final long initialSendSequence;
    private final long initialReceiveSequence;
    private long sendUnacknowledged;
    private long sendNext;
    private long receiveNext;
    private int remoteWindow;
    private final RetransmissionManager retransmissions = new RetransmissionManager(Duration.ofSeconds(1));
    private final CongestionController congestion = new BasicCongestionController(DEFAULT_MSS);
    private final BlockingQueue<Inbound> applicationReceive = new LinkedBlockingQueue<>();
    private final Map<Long, byte[]> outOfOrder = new HashMap<>();
    private boolean endOfStream;
    private int duplicateAcks;

    private TcpConnection(TcpConnectionKey key, long initialSendSequence, TcpSegment syn) {
        this.key = key;
        this.initialSendSequence = initialSendSequence & SequenceNumber.MASK;
        this.initialReceiveSequence = syn.sequenceNumber();
        this.sendUnacknowledged = this.initialSendSequence;
        this.sendNext = SequenceNumber.add(this.initialSendSequence, 1);
        this.receiveNext = SequenceNumber.add(syn.sequenceNumber(), 1);
        this.remoteWindow = syn.windowSize();
        transition(TcpEvent.RECEIVE_SYN);
        retransmissions.track(synAck(), System.nanoTime());
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
            acknowledge(incoming.acknowledgementNumber());
            transition(TcpEvent.RECEIVE_ACK);
            return new ProcessingResult(List.of(), true, false);
        }
        if (states.state() == TcpState.CLOSED || states.state() == TcpState.TIME_WAIT) return new ProcessingResult(List.of(), false, true);
        List<TcpSegment> outbound = new ArrayList<>();
        if (incoming.has(TcpFlags.ACK)) acknowledge(incoming.acknowledgementNumber());
        if (incoming.payload().length > 0) {
            acceptPayload(incoming.sequenceNumber(), incoming.payload());
            outbound.add(ack());
        }
        if (incoming.has(TcpFlags.FIN)) {
            long finSequence = SequenceNumber.add(incoming.sequenceNumber(), incoming.payload().length);
            if (finSequence == receiveNext) {
                receiveNext = SequenceNumber.add(receiveNext, 1);
                applicationReceive.offer(Inbound.eof());
                endOfStream = true;
                switch (states.state()) {
                    case ESTABLISHED -> transition(TcpEvent.RECEIVE_FIN);
                    case FIN_WAIT_1 -> transition(sendUnacknowledged == sendNext ? TcpEvent.RECEIVE_FIN_ACK : TcpEvent.RECEIVE_FIN);
                    case FIN_WAIT_2 -> transition(TcpEvent.RECEIVE_FIN);
                    default -> { }
                }
            }
            outbound.add(ack());
        }
        boolean closed = states.state() == TcpState.CLOSED;
        return new ProcessingResult(outbound, false, closed);
    }

    private void acknowledge(long acknowledgement) {
        if (!SequenceNumber.betweenInclusive(acknowledgement, sendUnacknowledged, sendNext)) return;
        if (acknowledgement == sendUnacknowledged) {
            duplicateAcks++;
            return;
        }
        int acknowledged = retransmissions.acknowledge(acknowledgement);
        sendUnacknowledged = acknowledgement;
        duplicateAcks = 0;
        congestion.onAcknowledgement(acknowledged);
        if (sendUnacknowledged == sendNext) {
            switch (states.state()) {
                case FIN_WAIT_1 -> transition(TcpEvent.RECEIVE_ACK_OF_FIN);
                case CLOSING, LAST_ACK -> transition(TcpEvent.RECEIVE_ACK_OF_FIN);
                default -> { }
            }
        }
    }

    private void acceptPayload(long sequence, byte[] payload) {
        if (sequence == receiveNext) {
            deliver(payload);
            receiveNext = SequenceNumber.add(receiveNext, payload.length);
            byte[] contiguous;
            while ((contiguous = outOfOrder.remove(receiveNext)) != null) {
                deliver(contiguous);
                receiveNext = SequenceNumber.add(receiveNext, contiguous.length);
            }
        } else if (SequenceNumber.greaterThan(sequence, receiveNext)) {
            outOfOrder.putIfAbsent(sequence, Arrays.copyOf(payload, payload.length));
            LOG.fine(() -> key + " buffered out-of-order segment seq=" + sequence);
        } else {
            LOG.fine(() -> key + " ignored duplicate segment seq=" + sequence);
        }
    }

    private void deliver(byte[] payload) { applicationReceive.offer(Inbound.data(payload)); }

    public synchronized List<TcpSegment> send(byte[] bytes, long nowNanos) {
        if (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT)
            throw new IllegalStateException("cannot write in " + states.state());
        long available = Math.min(remoteWindow - retransmissions.bytesInFlight(),
                congestion.congestionWindow() - retransmissions.bytesInFlight());
        if (bytes.length > available) throw new IllegalStateException("send window full: available=" + Math.max(0, available));
        List<TcpSegment> result = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            long allowance = Math.min(remoteWindow - retransmissions.bytesInFlight(),
                    congestion.congestionWindow() - retransmissions.bytesInFlight());
            if (allowance <= 0) break;
            int length = (int) Math.min(Math.min(DEFAULT_MSS, allowance), bytes.length - offset);
            byte[] payload = Arrays.copyOfRange(bytes, offset, offset + length);
            TcpSegment segment = segment(sendNext, receiveNext, TcpFlags.ACK | TcpFlags.PSH, payload);
            sendNext = SequenceNumber.add(sendNext, length);
            retransmissions.track(segment, nowNanos);
            result.add(segment);
            offset += length;
        }
        return result;
    }

    public synchronized List<TcpSegment> close(long nowNanos) {
        if (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT) return List.of();
        transition(TcpEvent.APP_CLOSE);
        TcpSegment fin = segment(sendNext, receiveNext, TcpFlags.FIN | TcpFlags.ACK, new byte[0]);
        sendNext = SequenceNumber.add(sendNext, 1);
        retransmissions.track(fin, nowNanos);
        return List.of(fin);
    }

    public byte[] read() throws InterruptedException {
        Inbound inbound = applicationReceive.take();
        return inbound.end ? null : inbound.bytes;
    }

    public synchronized List<TcpSegment> retransmissionsDue(long nowNanos) {
        List<TcpSegment> due = retransmissions.due(nowNanos);
        if (!due.isEmpty()) {
            congestion.onLoss();
            LOG.fine(() -> key + " retransmission timeout count=" + due.size());
        }
        return due;
    }

    public synchronized TcpSegment ack() { return segment(sendNext, receiveNext, TcpFlags.ACK, new byte[0]); }

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
    public synchronized int duplicateAcks() { return duplicateAcks; }
    public synchronized long bytesInFlight() { return retransmissions.bytesInFlight(); }
    public synchronized long congestionWindow() { return congestion.congestionWindow(); }
    public synchronized boolean endOfStream() { return endOfStream; }

    private record Inbound(byte[] bytes, boolean end) {
        static Inbound data(byte[] bytes) { return new Inbound(Arrays.copyOf(bytes, bytes.length), false); }
        static Inbound eof() { return new Inbound(new byte[0], true); }
    }

    public record ProcessingResult(List<TcpSegment> outbound, boolean justEstablished, boolean closed) {
        public ProcessingResult { outbound = List.copyOf(outbound); }
    }
}
