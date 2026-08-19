package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.congestion.CongestionController;
import io.github.shri299.wirefin.tcp.congestion.CongestionControlAlgorithm;
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
import java.util.concurrent.atomic.AtomicLong;

/** Per-connection TCP control block. All protocol mutations are serialized. */
public final class TcpConnection {
    private static final Logger LOG = Logger.getLogger(TcpConnection.class.getName());
    public static final int DEFAULT_RECEIVE_WINDOW = 65_535;
    public static final int DEFAULT_MSS = 1400;
    public static final int DEFAULT_MAX_PENDING_SEND = 1 << 20;
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long id = NEXT_ID.getAndIncrement();
    private final TcpConnectionKey key;
    private final TcpStateMachine states;
    private final Config config;
    private final long initialSendSequence;
    private long initialReceiveSequence;
    private final ReceiveBuffer receiveBuffer;
    private final RetransmissionManager retransmissions;
    private final CongestionController congestion;
    private int sendMss;
    private long sendUnacknowledged;
    private long sendNext;
    private long remoteWindow;
    private byte[] pendingSend = new byte[0];
    private Long pendingFinSequence;
    private long timeWaitDeadline = Long.MAX_VALUE;
    private boolean closeRequested;
    private boolean endOfStream;
    private int duplicateAcks;
    private int localWindowScale;
    private int peerWindowScale;
    private boolean windowScaling;
    private boolean timestamps;
    private boolean sackPermitted;
    private long recentTimestamp;
    private long lastNowNanos;
    private long persistDeadline = Long.MAX_VALUE;
    private long persistIntervalNanos;
    private int synTransmissions = 1;
    private long metricRetransmissions, metricFastRetransmits, metricRtoEvents, metricSackEvents, metricZeroWindowEvents;
    private long totalRetransmissions, totalFastRetransmits, totalRtoEvents;
    private long totalBytesSent, totalBytesReceived, sackEvents, zeroWindowEvents;

    private TcpConnection(TcpConnectionKey key, long isn, TcpSegment peerSyn, long nowNanos, Config config,
                          boolean active) {
        this.key = key;
        this.config = config;
        this.initialSendSequence = isn & SequenceNumber.MASK;
        this.sendUnacknowledged = initialSendSequence;
        this.sendNext = SequenceNumber.add(initialSendSequence, 1);
        this.lastNowNanos = nowNanos;
        this.persistIntervalNanos = config.persistInitial().toNanos();
        this.retransmissions = new RetransmissionManager(new RtoEstimator(
                config.initialRto(), config.minimumRto(), config.maximumRto()));
        this.sendMss = config.defaultPeerMss();
        if (active) {
            states = new TcpStateMachine(TcpState.CLOSED);
            receiveBuffer = new ReceiveBuffer(0, config.receiveCapacity());
            transition(TcpEvent.ACTIVE_OPEN);
            retransmissions.track(syn(), nowNanos);
        } else {
            states = new TcpStateMachine(TcpState.LISTEN);
            initialReceiveSequence = peerSyn.sequenceNumber();
            receiveBuffer = new ReceiveBuffer(SequenceNumber.add(peerSyn.sequenceNumber(), 1), config.receiveCapacity());
            applyPeerSynOptions(peerSyn);
            remoteWindow = peerSyn.windowSize(); // SYN windows are never scaled.
            transition(TcpEvent.RECEIVE_SYN);
            retransmissions.track(synAck(), nowNanos);
        }
        this.congestion = config.congestionControl().create(sendMss);
    }

    public static TcpConnection passiveOpen(TcpConnectionKey key, long isn, TcpSegment syn) {
        return passiveOpen(key, isn, syn, System.nanoTime(), Config.defaults());
    }
    public static TcpConnection passiveOpen(TcpConnectionKey key, long isn, TcpSegment syn, long nowNanos, Config config) {
        if (!syn.has(TcpFlags.SYN) || syn.has(TcpFlags.ACK)) throw new IllegalArgumentException("passive open requires bare SYN");
        return new TcpConnection(key, isn, syn, nowNanos, config, false);
    }
    public static TcpConnection activeOpen(TcpConnectionKey key, long isn, long nowNanos, Config config) {
        return new TcpConnection(key, isn, null, nowNanos, config, true);
    }

    public synchronized TcpSegment syn() {
        return segment(initialSendSequence, 0, TcpFlags.SYN, synOptions(0), new byte[0]);
    }
    public synchronized TcpSegment synAck() {
        return segment(initialSendSequence, receiveNext(), TcpFlags.SYN | TcpFlags.ACK,
                synOptions(recentTimestamp), new byte[0]);
    }
    private byte[] synOptions(long echoedTimestamp) {
        Integer scale = config.windowScalingEnabled() ? config.localWindowScale() : null;
        Long timestamp = config.timestampsEnabled() ? timestampValue(lastNowNanos) : null;
        return TcpOptions.syn(config.localMss(), scale, config.sackEnabled(), timestamp, echoedTimestamp);
    }

    private void applyPeerSynOptions(TcpSegment syn) {
        TcpOptions.Parsed options = TcpOptions.parse(syn.options());
        sendMss = Math.max(1, Math.min(config.localMss(),
                options.maximumSegmentSize().orElse(config.defaultPeerMss())));
        windowScaling = config.windowScalingEnabled() && options.windowScale().isPresent();
        peerWindowScale = windowScaling ? options.windowScale().getAsInt() : 0;
        localWindowScale = windowScaling ? config.localWindowScale() : 0;
        sackPermitted = config.sackEnabled() && options.sackPermitted();
        timestamps = config.timestampsEnabled() && options.timestamp().isPresent();
        options.timestamp().ifPresent(timestamp -> recentTimestamp = timestamp.value());
    }

    public synchronized ProcessingResult receive(TcpSegment incoming) { return receive(incoming, System.nanoTime()); }

    public synchronized ProcessingResult receive(TcpSegment incoming, long nowNanos) {
        lastNowNanos = nowNanos;
        if (states.state() == TcpState.SYN_SENT) return receiveSynSent(incoming, nowNanos);
        if (incoming.has(TcpFlags.RST)) return receiveReset(incoming);
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
                reset();
                return result(List.of(rstFor(incoming)), false, true, "invalid handshake ACK");
            }
            updatePeerOptions(incoming);
            updateRemoteWindow(incoming);
            acknowledge(incoming, nowNanos, outbound, false);
            transition(TcpEvent.RECEIVE_ACK);
            establishedNow = true;
        } else if (!sequenceAcceptable(incoming)) {
            return result(List.of(ack()), false, false, "unacceptable receive sequence");
        } else {
            updatePeerOptions(incoming);
            long previousWindow = remoteWindow;
            updateRemoteWindow(incoming);
            if (incoming.has(TcpFlags.ACK)) acknowledge(incoming, nowNanos, outbound, previousWindow != remoteWindow);
        }

        if (incoming.payload().length > 0 && canReceiveData()) {
            totalBytesReceived += receiveBuffer.accept(incoming.sequenceNumber(), incoming.payload());
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
            pendingFinSequence = null; acceptFin(nowNanos); outbound.add(ack());
        }
        outbound.addAll(flushSend(nowNanos));
        outbound.addAll(maybeSendFin(nowNanos));
        return result(outbound, establishedNow, states.state() == TcpState.CLOSED, "segment processed");
    }

    private ProcessingResult receiveSynSent(TcpSegment incoming, long nowNanos) {
        if (incoming.has(TcpFlags.RST)) {
            if (incoming.has(TcpFlags.ACK) && incoming.acknowledgementNumber() == sendNext) {
                reset();
                return result(List.of(), false, true, "connection refused");
            }
            return result(List.of(), false, false, "unacceptable reset in SYN_SENT");
        }
        if (!incoming.has(TcpFlags.SYN) || !incoming.has(TcpFlags.ACK) || incoming.acknowledgementNumber() != sendNext)
            return result(List.of(), false, false, "invalid SYN-ACK");
        initialReceiveSequence = incoming.sequenceNumber();
        receiveBuffer.resetReceiveNext(SequenceNumber.add(initialReceiveSequence, 1));
        applyPeerSynOptions(incoming);
        remoteWindow = incoming.windowSize();
        retransmissions.acknowledge(incoming.acknowledgementNumber(), nowNanos);
        sendUnacknowledged = incoming.acknowledgementNumber();
        transition(TcpEvent.RECEIVE_SYN_ACK);
        return result(List.of(ack()), true, false, "active open established");
    }

    private ProcessingResult receiveReset(TcpSegment incoming) {
        if (incoming.sequenceNumber() == receiveNext()) {
            reset();
            return result(List.of(), false, true, "acceptable peer reset");
        }
        if (sequenceAcceptable(incoming)) return result(List.of(ack()), false, false, "challenge ACK for in-window reset");
        return result(List.of(), false, false, "ignored out-of-window reset");
    }

    private void updatePeerOptions(TcpSegment incoming) {
        TcpOptions.Parsed options = TcpOptions.parse(incoming.options());
        if (timestamps) options.timestamp().ifPresent(timestamp -> recentTimestamp = timestamp.value());
        if (sackPermitted && !options.sackBlocks().isEmpty()) {
            sackEvents++;
            metricSackEvents++;
            retransmissions.updateSack(options.sackBlocks());
        }
    }
    private void updateRemoteWindow(TcpSegment incoming) {
        long previous = remoteWindow;
        remoteWindow = (long) incoming.windowSize() << (incoming.has(TcpFlags.SYN) ? 0 : peerWindowScale);
        if (previous > 0 && remoteWindow == 0) { zeroWindowEvents++; metricZeroWindowEvents++; }
        if (remoteWindow > 0) { persistDeadline = Long.MAX_VALUE; persistIntervalNanos = config.persistInitial().toNanos(); }
    }

    private boolean canReceiveData() {
        return states.state() == TcpState.ESTABLISHED || states.state() == TcpState.FIN_WAIT_1 || states.state() == TcpState.FIN_WAIT_2;
    }
    private boolean sequenceAcceptable(TcpSegment segment) {
        long window = receiveBuffer.advertisedWindow();
        int length = segment.sequenceSpaceLength();
        long startDistance = SequenceNumber.distance(receiveNext(), segment.sequenceNumber());
        if (window == 0) return length == 0 && segment.sequenceNumber() == receiveNext();
        if (length == 0) return startDistance < window;
        long last = SequenceNumber.add(segment.sequenceNumber(), length - 1L);
        long lastDistance = SequenceNumber.distance(receiveNext(), last);
        return startDistance < window || lastDistance < window ||
                (SequenceNumber.lessThan(segment.sequenceNumber(), receiveNext()) &&
                        SequenceNumber.greaterThan(last, receiveNext()));
    }

    private void acknowledge(TcpSegment incoming, long nowNanos, List<TcpSegment> outbound, boolean windowChanged) {
        long acknowledgement = incoming.acknowledgementNumber();
        if (!SequenceNumber.betweenInclusive(acknowledgement, sendUnacknowledged, sendNext)) return;
        if (acknowledgement == sendUnacknowledged) {
            if (!windowChanged && incoming.payload().length == 0 && !incoming.has(TcpFlags.SYN) &&
                    !incoming.has(TcpFlags.FIN) && bytesInFlight() > 0) {
                duplicateAcks++;
                if (duplicateAcks == 3) {
                    TcpSegment retransmit = retransmissions.fastRetransmit(nowNanos);
                    if (retransmit != null) {
                        metricRetransmissions++;
                        metricFastRetransmits++;
                        totalRetransmissions++;
                        totalFastRetransmits++;
                        congestion.onFastRetransmit(bytesInFlight(), sendNext);
                        outbound.add(retransmit);
                    }
                } else if (duplicateAcks > 3 && congestion.inFastRecovery()) congestion.onDuplicateAck();
            }
            return;
        }
        RetransmissionManager.AckResult result = retransmissions.acknowledge(acknowledgement, nowNanos);
        sendUnacknowledged = acknowledgement;
        duplicateAcks = 0;
        if (congestion.inFastRecovery()) {
            if (SequenceNumber.lessThan(acknowledgement, congestion.recoveryPoint())) {
                congestion.onPartialAcknowledgement(result.newlyAcknowledgedBytes());
                TcpSegment retransmit = retransmissions.fastRetransmit(nowNanos);
                if (retransmit != null) {
                    metricRetransmissions++;
                    metricFastRetransmits++;
                    totalRetransmissions++;
                    totalFastRetransmits++;
                    outbound.add(retransmit);
                }
            } else congestion.onRecoveryComplete();
        } else if (states.state() != TcpState.SYN_RECEIVED && states.state() != TcpState.FIN_WAIT_1 &&
                states.state() != TcpState.CLOSING && states.state() != TcpState.LAST_ACK)
            congestion.onAcknowledgement(result.newlyAcknowledgedBytes(), nowNanos);
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
        receiveBuffer.markEof(); endOfStream = true; receiveBuffer.advanceControlSequence();
        switch (states.state()) {
            case ESTABLISHED -> transition(TcpEvent.RECEIVE_FIN);
            case FIN_WAIT_1 -> transition(sendUnacknowledged == sendNext ? TcpEvent.RECEIVE_FIN_ACK : TcpEvent.RECEIVE_FIN);
            case FIN_WAIT_2 -> { transition(TcpEvent.RECEIVE_FIN); enterTimeWait(nowNanos); }
            default -> { }
        }
        if (states.state() == TcpState.TIME_WAIT) enterTimeWait(nowNanos);
    }

    public synchronized List<TcpSegment> send(byte[] bytes, long nowNanos) {
        lastNowNanos = nowNanos;
        if (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT)
            throw new IllegalStateException("cannot write in " + states.state());
        if (bytes.length > config.maximumPendingSendBytes() - pendingSend.length)
            throw new IllegalStateException("TCP send buffer full");
        byte[] combined = Arrays.copyOf(pendingSend, pendingSend.length + bytes.length);
        System.arraycopy(bytes, 0, combined, pendingSend.length, bytes.length);
        pendingSend = combined;
        return flushSend(nowNanos);
    }

    private List<TcpSegment> flushSend(long nowNanos) {
        List<TcpSegment> result = new ArrayList<>();
        while (pendingSend.length > 0) {
            long allowance = Math.min(remoteWindow - bytesInFlight(), congestion.congestionWindow() - bytesInFlight());
            if (allowance <= 0) { if (remoteWindow == 0 && bytesInFlight() == 0) armPersist(nowNanos); break; }
            int length = (int) Math.min(Math.min(sendMss, allowance), pendingSend.length);
            byte[] payload = Arrays.copyOf(pendingSend, length);
            pendingSend = Arrays.copyOfRange(pendingSend, length, pendingSend.length);
            TcpSegment segment = segment(sendNext, receiveNext(), TcpFlags.ACK | TcpFlags.PSH,
                    establishedOptions(), payload);
            sendNext = SequenceNumber.add(sendNext, length);
            totalBytesSent += length;
            retransmissions.track(segment, nowNanos); result.add(segment);
        }
        return result;
    }
    private void armPersist(long nowNanos) {
        if (persistDeadline == Long.MAX_VALUE) persistDeadline = nowNanos + persistIntervalNanos;
    }
    private TcpSegment persistProbe(long nowNanos) {
        if (remoteWindow != 0 || pendingSend.length == 0 || nowNanos < persistDeadline || bytesInFlight() != 0) return null;
        TcpSegment probe = segment(sendNext, receiveNext(), TcpFlags.ACK, establishedOptions(),
                new byte[] {pendingSend[0]});
        persistIntervalNanos = Math.min(config.persistMaximum().toNanos(), persistIntervalNanos * 2);
        persistDeadline = nowNanos + persistIntervalNanos;
        return probe;
    }

    public synchronized List<TcpSegment> close(long nowNanos) {
        lastNowNanos = nowNanos;
        if (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT) return List.of();
        closeRequested = true;
        List<TcpSegment> result = new ArrayList<>(flushSend(nowNanos)); result.addAll(maybeSendFin(nowNanos)); return result;
    }
    private List<TcpSegment> maybeSendFin(long nowNanos) {
        if (!closeRequested || pendingSend.length > 0 ||
                (states.state() != TcpState.ESTABLISHED && states.state() != TcpState.CLOSE_WAIT)) return List.of();
        closeRequested = false; transition(TcpEvent.APP_CLOSE);
        TcpSegment fin = segment(sendNext, receiveNext(), TcpFlags.FIN | TcpFlags.ACK, establishedOptions(), new byte[0]);
        sendNext = SequenceNumber.add(sendNext, 1); retransmissions.track(fin, nowNanos); return List.of(fin);
    }

    public int read(byte[] destination, int offset, int length) throws InterruptedException { return receiveBuffer.read(destination, offset, length); }
    public byte[] read() throws InterruptedException {
        byte[] buffer = new byte[Math.min(config.receiveCapacity(), 8192)];
        int count = read(buffer, 0, buffer.length); return count < 0 ? null : Arrays.copyOf(buffer, count);
    }

    public synchronized List<TcpSegment> retransmissionsDue(long nowNanos) {
        lastNowNanos = nowNanos;
        TcpSegment probe = persistProbe(nowNanos);
        if (probe != null) return List.of(probe);
        List<TcpSegment> due = retransmissions.due(nowNanos);
        if (!due.isEmpty()) {
            long flight = bytesInFlight();
            metricRetransmissions += due.size();
            metricRtoEvents++;
            totalRetransmissions += due.size();
            totalRtoEvents++;
            if (states.state() == TcpState.SYN_SENT && ++synTransmissions > config.maximumSynTransmissions()) {
                reset(); return List.of();
            }
            congestion.onTimeout(flight);
        }
        return due;
    }

    public synchronized MetricDeltas consumeMetricDeltas() {
        MetricDeltas deltas = new MetricDeltas(metricRetransmissions, metricFastRetransmits, metricRtoEvents,
                metricSackEvents,metricZeroWindowEvents);
        metricRetransmissions = metricFastRetransmits = metricRtoEvents = metricSackEvents = metricZeroWindowEvents = 0;
        return deltas;
    }

    public synchronized boolean expireTimeWait(long nowNanos) {
        if (states.state() != TcpState.TIME_WAIT || nowNanos < timeWaitDeadline) return false;
        transition(TcpEvent.TIMEOUT); return true;
    }
    private void enterTimeWait(long nowNanos) { timeWaitDeadline = nowNanos + config.timeWaitDuration().toNanos(); }

    public synchronized TcpSegment ack() { return segment(sendNext, receiveNext(), TcpFlags.ACK, establishedOptions(), new byte[0]); }
    private byte[] establishedOptions() {
        TcpOptions.Timestamp timestamp = timestamps ? new TcpOptions.Timestamp(timestampValue(lastNowNanos), recentTimestamp) : null;
        return TcpOptions.established(timestamp, sackPermitted ? receiveBuffer.sackBlocks() : List.of());
    }
    private static long timestampValue(long nowNanos) { return nowNanos / 1_000_000L & SequenceNumber.MASK; }
    private TcpSegment rstFor(TcpSegment incoming) { return segment(incoming.acknowledgementNumber(), 0, TcpFlags.RST, new byte[0], new byte[0]); }
    private TcpSegment segment(long sequence, long acknowledgement, int flags, byte[] options, byte[] payload) {
        int window = windowField(TcpFlags.has(flags, TcpFlags.SYN));
        return new TcpSegment(key.localPort(), key.remotePort(), sequence, acknowledgement, flags, window, 0, options, payload);
    }
    private int windowField(boolean syn) {
        long available = receiveBuffer.advertisedWindow();
        int shift = syn ? 0 : localWindowScale;
        return (int) Math.min(65_535, available >>> shift);
    }

    private synchronized void transition(TcpEvent event) {
        TcpState before = states.state(); TcpState after = states.transition(event);
        notifyAll();
        LOG.fine(() -> key + " " + before + " -> " + after + " event=" + event);
    }
    private synchronized void reset() { states.reset(); receiveBuffer.markEof(); notifyAll(); }
    private ProcessingResult result(List<TcpSegment> outbound, boolean established, boolean closed, String reason) {
        LOG.fine(() -> key + " state=" + states.state() + " reason=" + reason + " SND.UNA=" + sendUnacknowledged +
                " SND.NXT=" + sendNext + " RCV.NXT=" + receiveNext() + " rwnd=" + receiveBuffer.advertisedWindow() +
                " peerWnd=" + remoteWindow + " cwnd=" + congestion.congestionWindow() + " flight=" + bytesInFlight() +
                " rtoMs=" + retransmissions.rtoNanos() / 1_000_000 + " ws=" + localWindowScale + "/" + peerWindowScale +
                " sack=" + sackPermitted + " ts=" + timestamps + " recovery=" + congestion.inFastRecovery());
        return new ProcessingResult(outbound, established, closed);
    }

    public TcpConnectionKey key() { return key; }
    public synchronized TcpState state() { return states.state(); }
    public long initialSendSequence() { return initialSendSequence; }
    public long initialReceiveSequence() { return initialReceiveSequence; }
    public synchronized long sendUnacknowledged() { return sendUnacknowledged; }
    public synchronized long sendNext() { return sendNext; }
    public long receiveNext() { return receiveBuffer.receiveNext(); }
    public synchronized long remoteWindow() { return remoteWindow; }
    public synchronized int duplicateAcks() { return duplicateAcks; }
    public synchronized long bytesInFlight() { return retransmissions.bytesInFlight(); }
    public synchronized long congestionWindow() { return congestion.congestionWindow(); }
    public synchronized long slowStartThreshold() { return congestion.slowStartThreshold(); }
    public synchronized boolean inFastRecovery() { return congestion.inFastRecovery(); }
    public synchronized long recoveryPoint() { return congestion.recoveryPoint(); }
    public synchronized long rtoNanos() { return retransmissions.rtoNanos(); }
    public int receiveWindow() { return receiveBuffer.advertisedWindow(); }
    public int readableBytes() { return receiveBuffer.readableBytes(); }
    public int outOfOrderBytes() { return receiveBuffer.outOfOrderBytes(); }
    public synchronized int sendMss() { return sendMss; }
    public synchronized int pendingSendBytes() { return pendingSend.length; }
    public synchronized boolean endOfStream() { return endOfStream; }
    public synchronized boolean windowScalingNegotiated() { return windowScaling; }
    public synchronized int localWindowScale() { return localWindowScale; }
    public synchronized int peerWindowScale() { return peerWindowScale; }
    public synchronized boolean timestampsNegotiated() { return timestamps; }
    public synchronized boolean sackPermitted() { return sackPermitted; }
    public synchronized int sackScoreboardBlocks() { return retransmissions.sackBlockCount(); }
    public synchronized long persistDeadline() { return persistDeadline; }

    public synchronized TcpConnectionSnapshot snapshot() {
        return new TcpConnectionSnapshot(id, key, states.state(), sendUnacknowledged, sendNext, receiveNext(),
                congestion.congestionWindow(), congestion.slowStartThreshold(), remoteWindow,
                retransmissions.smoothedRttNanos(), retransmissions.rttVariationNanos(), retransmissions.rtoNanos(),
                totalRetransmissions, totalFastRetransmits, totalRtoEvents, duplicateAcks,
                retransmissions.sackBlockCount(), sackEvents, zeroWindowEvents, totalBytesSent, totalBytesReceived,
                retransmissions.bytesInFlight(), pendingSend.length, receiveBuffer.readableBytes(),
                receiveBuffer.outOfOrderBytes());
    }

    public synchronized void awaitEstablished(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (states.state() == TcpState.SYN_SENT) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { reset(); throw new IllegalStateException("TCP connect timed out"); }
            wait(Math.max(1, remaining / 1_000_000L), (int) (remaining % 1_000_000L));
        }
        if (states.state() != TcpState.ESTABLISHED) throw new IllegalStateException("TCP connect failed: " + states.state());
    }

    public record Config(int receiveCapacity, int localMss, int defaultPeerMss, Duration initialRto,
                         Duration minimumRto, Duration maximumRto, Duration timeWaitDuration,
                         Duration persistInitial, Duration persistMaximum, boolean windowScalingEnabled,
                         int localWindowScale, boolean timestampsEnabled, boolean sackEnabled,
                         int maximumSynTransmissions, int maximumPendingSendBytes,
                         CongestionControlAlgorithm congestionControl) {
        public Config(int receiveCapacity, int localMss, int defaultPeerMss, Duration initialRto,
                      Duration minimumRto, Duration maximumRto, Duration timeWaitDuration) {
            this(receiveCapacity, localMss, defaultPeerMss, initialRto, minimumRto, maximumRto, timeWaitDuration,
                    Duration.ofSeconds(1), Duration.ofSeconds(60), false, 0, false, false, 6);
        }
        public Config(int receiveCapacity, int localMss, int defaultPeerMss, Duration initialRto,
                      Duration minimumRto, Duration maximumRto, Duration timeWaitDuration,
                      Duration persistInitial, Duration persistMaximum, boolean windowScalingEnabled,
                      int localWindowScale, boolean timestampsEnabled, boolean sackEnabled,
                      int maximumSynTransmissions) {
            this(receiveCapacity,localMss,defaultPeerMss,initialRto,minimumRto,maximumRto,timeWaitDuration,
                    persistInitial,persistMaximum,windowScalingEnabled,localWindowScale,timestampsEnabled,
                    sackEnabled,maximumSynTransmissions,DEFAULT_MAX_PENDING_SEND);
        }
        public Config(int receiveCapacity, int localMss, int defaultPeerMss, Duration initialRto,
                      Duration minimumRto, Duration maximumRto, Duration timeWaitDuration,
                      Duration persistInitial, Duration persistMaximum, boolean windowScalingEnabled,
                      int localWindowScale, boolean timestampsEnabled, boolean sackEnabled,
                      int maximumSynTransmissions, int maximumPendingSendBytes) {
            this(receiveCapacity,localMss,defaultPeerMss,initialRto,minimumRto,maximumRto,timeWaitDuration,
                    persistInitial,persistMaximum,windowScalingEnabled,localWindowScale,timestampsEnabled,
                    sackEnabled,maximumSynTransmissions,maximumPendingSendBytes,CongestionControlAlgorithm.RENO);
        }
        public Config {
            if (receiveCapacity < 1 || receiveCapacity > 16 * 1024 * 1024 || localMss < 1 || localMss > 65_535 ||
                    defaultPeerMss < 1 || defaultPeerMss > 65_535) throw new IllegalArgumentException("invalid TCP buffer/MSS config");
            if (localWindowScale < 0 || localWindowScale > 14 || maximumSynTransmissions < 1 || maximumPendingSendBytes < 1)
                throw new IllegalArgumentException("invalid TCP negotiation config");
            if (congestionControl == null) throw new IllegalArgumentException("congestion control required");
            if (timeWaitDuration == null || timeWaitDuration.isNegative() || timeWaitDuration.isZero() ||
                    persistInitial == null || persistInitial.isNegative() || persistInitial.isZero() ||
                    persistMaximum == null || persistMaximum.compareTo(persistInitial) < 0)
                throw new IllegalArgumentException("invalid TCP timer duration");
        }
        public static Config defaults() {
            return new Config(DEFAULT_RECEIVE_WINDOW, DEFAULT_MSS, 536, Duration.ofSeconds(1),
                    Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(60),
                    Duration.ofSeconds(1), Duration.ofSeconds(60), true, 0, true, true, 6);
        }
        public Config withCongestionControl(CongestionControlAlgorithm algorithm) {
            return new Config(receiveCapacity,localMss,defaultPeerMss,initialRto,minimumRto,maximumRto,timeWaitDuration,
                    persistInitial,persistMaximum,windowScalingEnabled,localWindowScale,timestampsEnabled,sackEnabled,
                    maximumSynTransmissions,maximumPendingSendBytes,algorithm);
        }
    }

    public record ProcessingResult(List<TcpSegment> outbound, boolean justEstablished, boolean closed) {
        public ProcessingResult { outbound = List.copyOf(outbound); }
    }
    public record MetricDeltas(long retransmissions, long fastRetransmits, long rtoEvents,
                               long sackEvents,long zeroWindowEvents) {}
}
