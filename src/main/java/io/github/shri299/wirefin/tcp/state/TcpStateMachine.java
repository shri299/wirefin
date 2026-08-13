package io.github.shri299.wirefin.tcp.state;

import java.util.EnumMap;
import java.util.Map;

/** Explicit subset of the RFC 9293 state diagram used by Wirefin. */
public final class TcpStateMachine {
    private static final Map<TcpState, Map<TcpEvent, TcpState>> TRANSITIONS = build();
    private TcpState state;

    public TcpStateMachine(TcpState initial) { state = initial; }
    public TcpState state() { return state; }

    public synchronized TcpState transition(TcpEvent event) {
        TcpState next = TRANSITIONS.getOrDefault(state, Map.of()).get(event);
        if (next == null) throw new IllegalStateException("invalid TCP transition: " + state + " + " + event);
        state = next;
        return next;
    }

    public synchronized void reset() { state = TcpState.CLOSED; }

    private static Map<TcpState, Map<TcpEvent, TcpState>> build() {
        Map<TcpState, Map<TcpEvent, TcpState>> map = new EnumMap<>(TcpState.class);
        put(map, TcpState.CLOSED, TcpEvent.PASSIVE_OPEN, TcpState.LISTEN);
        put(map, TcpState.CLOSED, TcpEvent.ACTIVE_OPEN, TcpState.SYN_SENT);
        put(map, TcpState.LISTEN, TcpEvent.RECEIVE_SYN, TcpState.SYN_RECEIVED);
        put(map, TcpState.SYN_SENT, TcpEvent.RECEIVE_SYN_ACK, TcpState.ESTABLISHED);
        put(map, TcpState.SYN_RECEIVED, TcpEvent.RECEIVE_ACK, TcpState.ESTABLISHED);
        put(map, TcpState.ESTABLISHED, TcpEvent.APP_CLOSE, TcpState.FIN_WAIT_1);
        put(map, TcpState.ESTABLISHED, TcpEvent.RECEIVE_FIN, TcpState.CLOSE_WAIT);
        put(map, TcpState.FIN_WAIT_1, TcpEvent.RECEIVE_ACK_OF_FIN, TcpState.FIN_WAIT_2);
        put(map, TcpState.FIN_WAIT_1, TcpEvent.RECEIVE_FIN, TcpState.CLOSING);
        put(map, TcpState.FIN_WAIT_1, TcpEvent.RECEIVE_FIN_ACK, TcpState.TIME_WAIT);
        put(map, TcpState.FIN_WAIT_2, TcpEvent.RECEIVE_FIN, TcpState.TIME_WAIT);
        put(map, TcpState.CLOSE_WAIT, TcpEvent.APP_CLOSE, TcpState.LAST_ACK);
        put(map, TcpState.CLOSING, TcpEvent.RECEIVE_ACK_OF_FIN, TcpState.TIME_WAIT);
        put(map, TcpState.LAST_ACK, TcpEvent.RECEIVE_ACK_OF_FIN, TcpState.CLOSED);
        put(map, TcpState.TIME_WAIT, TcpEvent.TIMEOUT, TcpState.CLOSED);
        for (TcpState state : TcpState.values()) put(map, state, TcpEvent.RESET, TcpState.CLOSED);
        return map;
    }

    private static void put(Map<TcpState, Map<TcpEvent, TcpState>> map, TcpState from, TcpEvent event, TcpState to) {
        map.computeIfAbsent(from, ignored -> new EnumMap<>(TcpEvent.class)).put(event, to);
    }
}
