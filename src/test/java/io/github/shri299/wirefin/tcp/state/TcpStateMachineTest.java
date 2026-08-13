package io.github.shri299.wirefin.tcp.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TcpStateMachineTest {
    @Test void passiveHandshakeUsesExplicitTransitions() {
        var states = new TcpStateMachine(TcpState.CLOSED);
        assertEquals(TcpState.LISTEN, states.transition(TcpEvent.PASSIVE_OPEN));
        assertEquals(TcpState.SYN_RECEIVED, states.transition(TcpEvent.RECEIVE_SYN));
        assertEquals(TcpState.ESTABLISHED, states.transition(TcpEvent.RECEIVE_ACK));
        assertThrows(IllegalStateException.class, () -> states.transition(TcpEvent.RECEIVE_SYN));
    }
}
