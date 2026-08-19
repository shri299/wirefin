package io.github.shri299.wirefin.trace;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.tcp.connection.*;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class JsonLineProtocolTracerTest {
    @Test void writesStructuredControlBlockState() {
        StringWriter output = new StringWriter();
        var key = new TcpConnectionKey(Ipv4Address.parse("10.0.0.2"),8080,Ipv4Address.parse("10.0.0.1"),50000);
        var snapshot = new TcpConnectionSnapshot(42,key,TcpState.ESTABLISHED,100,200,300,400,500,600,
                700,80,900,2,1,1,3,1,4,5,1000,2000,50,10,20,30);
        try (var tracer = new JsonLineProtocolTracer(output)) {
            tracer.onSegment(new ProtocolTracer.TcpSegmentTrace(123,PacketCapture.Direction.RX,16,10,20,30,snapshot));
        }
        String json=output.toString();
        assertTrue(json.contains("\"connection_id\":42"));assertTrue(json.contains("\"direction\":\"RX\""));
        assertTrue(json.contains("\"cwnd\":400"));assertTrue(json.contains("\"rto_nanos\":900"));
    }
}
