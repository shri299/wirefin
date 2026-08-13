package io.github.shri299.wirefin.tcp.state;

public enum TcpState {
    CLOSED, LISTEN, SYN_SENT, SYN_RECEIVED, ESTABLISHED,
    FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, CLOSING, LAST_ACK, TIME_WAIT
}
