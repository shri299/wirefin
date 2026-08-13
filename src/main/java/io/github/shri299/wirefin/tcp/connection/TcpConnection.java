package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.congestion.BasicCongestionController;
import io.github.shri299.wirefin.tcp.congestion.CongestionController;
import io.github.shri299.wirefin.tcp.reliability.RetransmissionManager;
import io.github.shri299.wirefin.tcp.reliability.RtoEstimator;
import io.github.shri299.wirefin.tcp.reliability.SequenceNumber;
import io.github.shri299.wirefin.tcp.state.TcpEvent;
import io.github.shri299.wirefin.tcp.state.TcpState;
import io.github.shri299.wirefin.tcp.state.TcpStateMachine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/** Per-connection TCP control block. All protocol mutations are serialized. */
public final class TcpConnection {
    private static final Logger LOG = Logger.getLogger(TcpConnection.class.getName());
    public static final int DEFAULT_RECEIVE_WINDOW = 65_535;
    public static final int DEFAULT_MSS = 1400;

    private final TcpConnectionKey key;
    private final TcpStateMachine states = new TcpStateMachine(TcpState.LISTEN);
    private final Config config;
    private final long initialSendSequence;
    private final long initialReceiveSequence;
    private final ReceiveBuffer receiveBuffer;
    private final RetransmissionManager retransmissions;
    private final CongestionController congestion;
    private final int sendMss;
    private long sendUnacknowledged;
    private long sendNext;
    private int remoteWindow;
    private byte[] pendingSend = new byte[0];
    private Long pendingFinSequence;
    private long timeWaitDeadline = Long.MAX_VALUE;
    private boolean closeRequested;
    private boolean endOfStream;
    private int duplicateAcks;

    private TcpConnection(TcpConnectionKey key, long initialSendSequence, TcpSegment syn, long nowNanos, Config config) {
        this.key = key;
        this.config = config;
        this.initialSendSequence = initialSendSequence & SequenceNumber.MASK;
        this.initialReceiveSequence = syn.sequenceNumber();
        this.sendUnacknowledged = this.initialSendSequence;
        this.sendNext = SequenceNumber.add(this.initialSendSequence, 1);
        this.receiveBuffer = new ReceiveBuffer(SequenceNumber.add(syn.sequenceNumber(), 1), config.receiveCapacity());
        this.remoteWindow = syn.windowSize();
        int peerMss = TcpOptions.maximumSegmentSize(syn.options()).orElse(config.defaultPeerMss());
        this.sendMss = Math.max(1, Math.min(config.localMss(), peerMss));
        this.retransmissions = new RetransmissionManager(new RtoEstimator(
                config.initialRto(), config.minimumRto(), config.maximumRto()));
        this.congestion = new BasicCongestionController(sendMss);
        transition(TcpEvent.RECEIVE_SYN);
        retransmissions.track(synAck(), nowNanos);
    }

    public static TcpConnection passiveOpen(TcpConnectionKey key, long isn, TcpSegment syn) {
        return passiveOpen(key, isn, syn, System.nanoTime(), Config.defaults());
    }
    public static TcpConnection passiveOpen(TcpConnectionKey key, long isn, TcpSegment syn, long nowNanos, Config config) {
        if (!syn.has(TcpFlags.SYN) || syn.has(TcpFlags.ACK)) throw new IllegalArgumentException("passive open requires bare SYN");
        return new TcpConnection(key, isn, syn, nowNanos, config);
    }

    public synchronized TcpSegment synAck() {
        return segment(initialSendSequence, receiveNext(), TcpFlags.SYN | TcpFlags.ACK,
                TcpOptions.mss(config.localMss()), new byte[0]);
    }

    public synchronized ProcessingResult receive(TcpSegment incoming) {
        return receive(incoming, System.nanoTime());
    }

    public synchronized ProcessingResult receive(TcpSegment incoming, long nowNanos) {
        if (incoming.has(TcpFlags.RST)) {
            states.reset();
            receiveBuffer.markEof();
            return result(List.of(), false, true, "peer reset");
        }
        if (states.state() == TcpState.TIME_WAIT) {
            if (incoming.has(TcpFlags.FIN)) {
                timeWaitDeadline = nowNanos + config.timeWaitDuration().toNanos();
                return result(List.of(ack()), false, false, "re-ACK FIN in TIME_WAIT");
            }
            return result(List.of(), false, false, "ignore in TIME_WAIT");
        }
        if (states.state() == TcpState.CLOSED) return result(List.of(), false, true, "already closed");

        boolean establishedNow = false;
        List<TcpSegment> outbound = new ArrayList<>();
        if (states.state() == TcpState.SYN_RECEIVED) {
            if (incoming.has(TcpFlags.SYN) && !incoming.has(TcpFlags.ACK) &&
                    incoming.sequenceNumber() == initialReceiveSequence)
                return result(List.of(synAck()), false, false, "duplicate SYN");
            if (!incoming.has(TcpFlags.ACK)) return result(List.of(), false, false, "expected handshake ACK");
            if (incoming.acknowledgementNumber() != sendNext || incoming.sequenceNumber() != receiveNext() ||
                    incoming.has(TcpFlags.SYN)) {
                states.reset();
                return result(List.of(rstFor(incoming)), false, true, "invalid handshake ACK");
            }
            acknowledge(incoming, nowNanos, outbound);
            transition(TcpEvent.RECEIVE_ACK);
            establishedNow = true;
        } else if (!sequenceAcceptable(incoming)) {
            return result(List.of(ack()), false, false, "unacceptable receive sequence");
        } else if (incoming.has(TcpFlags.ACK)) {
            acknowledge(incoming, nowNanos, outbound);
        }
        remoteWindow = incoming.windowSize();

        if (incoming.payload().length > 0 && canReceiveData()) {
            receiveBuffer.accept(incoming.sequenceNumber(), incoming.payload());
            outbound.add(ack());
        }
        if (incoming.has(TcpFlags.FIN)) {
            long finSequence = SequenceNumber.add(incoming.sequenceNumber(), incoming.payload().length);
            if (finSequence == receiveNext()) acceptFin(nowNanos);
            else if (SequenceNumber.greaterThan(finSequence, receiveNext()) &&
                    SequenceNumber.distance(receiveNext(), finSequence) < receiveBuffer.advertisedWindow())
                pendingFinSequence = finSequence;
            outbound.add(ack());
        }
        if (pendingFinSequence != null && pendingFinSequence == receiveNext()) {
            pendingFinSequence = null;
            acceptFin(nowNanos);
            outbound.add(ack());
        }
        outbound.addAll(flushSend(nowNanos));
        outbound.addAll(maybeSendFin(nowNanos));
        return result(outbound, establishedNow, states.state() == TcpState.CLOSED, "segment processed");
    }

    private boolean canReceiveData() {
        return states.state() == TcpState.ESTABLISHED || states.state() == TcpState.FIN_WAIT_1 ||
                states.state() == TcpState.FIN_WAIT_2;
    }

    private boolean sequenceAcceptable(TcpSegment segment) {
        int window = receiveBuffer.advertisedWindow();
        int length = segment.payload().length + (segment.has(TcpFlags.FIN) ? 1 : 0) + (segment.has(TcpFlags.SYN) ? 1 : 0);
        long startDistance = SequenceNumber.distance(receiveNext(), segment.sequenceNumber());
        if (window == 0) return length == 0 && segment.sequenceNumber() == receiveNext();
        if (length == 0) return startDistance < window;
        long last = SequenceNumber.add(segment.sequenceNumber(), length - 1L);
        long lastDistance = SequenceNumber.distance(receiveNext(), last);
        return (startDistance < window) || (lastDistance < window) ||
                (SequenceNumber.lessThan(segment.sequenceNumber(), receiveNext()) && SequenceNumber.greaterThan(last, receiveNext()));
    }

    private void acknowledge(TcpSegment incoming, long nowNanos, List<TcpSegment> outbound) {
        long acknowledgement = incoming.acknowledgementNumber();
        if (!SequenceNumber.betweenInclusive(acknowledgement, sendUnacknowledged, sendNext)) return;
        if (acknowledgement == sendUnacknowledged) {
            if (incoming.payload().length == 0 && !incoming.has(TcpFlags.SYN) && !incoming.has(TcpFlags.FIN) && bytesInFlight() > 0) {
                duplicateAcks++;
                if (duplicateAcks == 3) {
                    TcpSegment retransmit = retransmissions.fastRetransmit(nowNanos);
                    if (retransmit != null) {
                        congestion.onFastRetransmit(bytesInFlight());
                        outbound.add(retransmit);
                    }
                }
            }
            return;
        }
        RetransmissionManager.AckResult result = retransmissions.acknowledge(acknowledgement, nowNanos);
        sendUnacknowledged = acknowledgement;
        duplicateAcks = 0;
        if (states.state() != TcpState.SYN_RECEIVED && states.state() != TcpState.FIN_WAIT_1 &&
                states.state() != TcpState.CLOSING && states.state() != TcpState.LAST_ACK)
            congestion.onAcknowledgement(result.newlyAcknowledgedBytes());
        if (sendUnacknowledged == sendNext) {
            switch (states.state()) {
                case FIN_WAIT_1 -> transition(TcpEvent.RECEIVE_ACK_OF_FIN);
                case CLOSING, LAST_ACK -> transition(TcpEvent.RECEIVE_ACK_OF_FIN);
                default -> { }
            }
            if (states.state() == TcpState.TIME_WAIT) enterTimeWait(nowNanos);
        }
    }

    private void acceptFin(long nowNanos) {
        receiveBuffer.markEof();
        endOfStream = true;
        receiveBuffer.advanceControlSequence();
        switch (states.state()) {
            case ESTABLISHED -> transition(TcpEvent.RECEIVE_FIN);
            case FIN_WAIT_1 -> transition(sendUnacknowledged == sendNext ? TcpEvent.RECEIVE_FIN_ACK : TcpEvent.RECEIVE_FIN);
            case FIN_WAIT_2 -> { transition(TcpEvent.RECEIVE_FIN); enterTimeWait(nowNanos); }
            default -> { }
        }
        if (states.state() == TcpState.TIME_WAIT) enterTimeWait(nowNanos);
    }

    public synchronized List<TcpSegment> send(byte[] bytes, long nowNanos) {
        if (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT)
            throw new IllegalStateException("cannot write in " + states.state());
        byte[] combined = Arrays.copyOf(pendingSend, pendingSend.length + bytes.length);
        System.arraycopy(bytes, 0, combined, pendingSend.length, bytes.length);
        pendingSend = combined;
        return flushSend(nowNanos);
    }

    private List<TcpSegment> flushSend(long nowNanos) {
        List<TcpSegment> result = new ArrayList<>();
        while (pendingSend.length > 0) {
            long allowance = Math.min((long) remoteWindow - bytesInFlight(), congestion.congestionWindow() - bytesInFlight());
            if (allowance <= 0) break;
            int length = (int) Math.min(Math.min(sendMss, allowance), pendingSend.length);
            byte[] payload = Arrays.copyOf(pendingSend, length);
            pendingSend = Arrays.copyOfRange(pendingSend, length, pendingSend.length);
            TcpSegment segment = segment(sendNext, receiveNext(), TcpFlags.ACK | TcpFlags.PSH, new byte[0], payload);
            sendNext = SequenceNumber.add(sendNext, length);
            retransmissions.track(segment, nowNanos);
            result.add(segment);
        }
        return result;
    }

    public synchronized List<TcpSegment> close(long nowNanos) {
        if (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT) return List.of();
        closeRequested = true;
        List<TcpSegment> result = new ArrayList<>(flushSend(nowNanos));
        result.addAll(maybeSendFin(nowNanos));
        return result;
    }

    private List<TcpSegment> maybeSendFin(long nowNanos) {
        if (!closeRequested || pendingSend.length > 0 ||
                (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT)) return List.of();
        closeRequested = false;
        transition(TcpEvent.APP_CLOSE);
        TcpSegment fin = segment(sendNext, receiveNext(), TcpFlags.FIN | TcpFlags.ACK, new byte[0], new byte[0]);
        sendNext = SequenceNumber.add(sendNext, 1);
        retransmissions.track(fin, nowNanos);
        return List.of(fin);
    }

    public int read(byte[] destination, int offset, int length) throws InterruptedException {
        return receiveBuffer.read(destination, offset, length);
    }
    public byte[] read() throws InterruptedException {
        byte[] buffer = new byte[Math.min(config.receiveCapacity(), 8192)];
        int count = read(buffer, 0, buffer.length);
        return count < 0 ? null : Arrays.copyOf(buffer, count);
    }

    public synchronized List<TcpSegment> retransmissionsDue(long nowNanos) {
        long flight = bytesInFlight();
        List<TcpSegment> due = retransmissions.due(nowNanos);
        if (!due.isEmpty()) congestion.onTimeout(flight);
        return due;
    }

    public synchronized boolean expireTimeWait(long nowNanos) {
        if (states.state() != TcpState.TIME_WAIT || nowNanos < timeWaitDeadline) return false;
        transition(TcpEvent.TIMEOUT);
        return true;
    }
    private void enterTimeWait(long nowNanos) { timeWaitDeadline = nowNanos + config.timeWaitDuration().toNanos(); }

    public synchronized TcpSegment ack() { return segment(sendNext, receiveNext(), TcpFlags.ACK, new byte[0], new byte[0]); }
    private TcpSegment rstFor(TcpSegment incoming) {
        return segment(incoming.acknowledgementNumber(), 0, TcpFlags.RST, new byte[0], new byte[0]);
    }
    private TcpSegment segment(long sequence, long acknowledgement, int flags, byte[] options, byte[] payload) {
        return new TcpSegment(key.localPort(), key.remotePort(), sequence, acknowledgement, flags,
                receiveBuffer.advertisedWindow(), 0, options, payload);
    }

    private void transition(TcpEvent event) {
        TcpState before = states.state();
        TcpState after = states.transition(event);
        LOG.fine(() -> key + " " + before + " -> " + after + " event=" + event);
    }
    private ProcessingResult result(List<TcpSegment> outbound, boolean established, boolean closed, String reason) {
        LOG.fine(() -> key + " state=" + states.state() + " reason=" + reason + " SND.UNA=" + sendUnacknowledged +
                " SND.NXT=" + sendNext + " RCV.NXT=" + receiveNext() + " rwnd=" + receiveBuffer.advertisedWindow() +
                " peerWnd=" + remoteWindow + " cwnd=" + congestion.congestionWindow() + " flight=" + bytesInFlight() +
                " rtoMs=" + retransmissions.rtoNanos() / 1_000_000);
        return new ProcessingResult(outbound, established, closed);
    }

    public TcpConnectionKey key() { return key; }
    public synchronized TcpState state() { return states.state(); }
    public long initialSendSequence() { return initialSendSequence; }
    public long initialReceiveSequence() { return initialReceiveSequence; }
    public synchronized long sendUnacknowledged() { return sendUnacknowledged; }
    public synchronized long sendNext() { return sendNext; }
    public long receiveNext() { return receiveBuffer.receiveNext(); }
    public synchronized int remoteWindow() { return remoteWindow; }
    public synchronized int duplicateAcks() { return duplicateAcks; }
    public synchronized long bytesInFlight() { return retransmissions.bytesInFlight(); }
    public synchronized long congestionWindow() { return congestion.congestionWindow(); }
    public synchronized long rtoNanos() { return retransmissions.rtoNanos(); }
    public int receiveWindow() { return receiveBuffer.advertisedWindow(); }
    public int readableBytes() { return receiveBuffer.readableBytes(); }
    public int outOfOrderBytes() { return receiveBuffer.outOfOrderBytes(); }
    public synchronized int sendMss() { return sendMss; }
    public synchronized int pendingSendBytes() { return pendingSend.length; }
    public synchronized boolean endOfStream() { return endOfStream; }

    public record Config(int receiveCapacity, int localMss, int defaultPeerMss, Duration initialRto,
                         Duration minimumRto, Duration maximumRto, Duration timeWaitDuration) {
        public Config {
            if (receiveCapacity < 1 || receiveCapacity > 65_535 || localMss < 1 || localMss > 65_535 ||
                    defaultPeerMss < 1 || defaultPeerMss > 65_535) throw new IllegalArgumentException("invalid TCP buffer/MSS config");
            if (timeWaitDuration == null || timeWaitDuration.isNegative() || timeWaitDuration.isZero())
                throw new IllegalArgumentException("TIME_WAIT duration must be positive");
        }
        public static Config defaults() {
            return new Config(DEFAULT_RECEIVE_WINDOW, DEFAULT_MSS, 536, Duration.ofSeconds(1),
                    Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(60));
        }
    }

    public record ProcessingResult(List<TcpSegment> outbound, boolean justEstablished, boolean closed) {
        public ProcessingResult { outbound = List.copyOf(outbound); }
    }
}
