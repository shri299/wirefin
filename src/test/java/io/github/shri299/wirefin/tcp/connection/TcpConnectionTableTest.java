package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.tcp.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TcpConnectionTableTest {
    @Test void rejectsConnectionsBeyondItsFixedCapacityAndRecoversAfterRemoval() {
        TcpConnectionTable table = new TcpConnectionTable(2);
        TcpConnection first=connection(50_000),second=connection(50_001),third=connection(50_002);
        table.add(first);table.add(second);
        assertEquals(2,table.size());assertEquals(2,table.capacity());
        assertThrows(TcpConnectionTable.CapacityExceededException.class,()->table.add(third));
        table.remove(first.key());table.add(third);assertEquals(2,table.size());
    }

    private static TcpConnection connection(int port) {
        Ipv4Address local=Ipv4Address.parse("10.0.0.2"),remote=Ipv4Address.parse("10.0.0.1");
        var key=new TcpConnectionKey(local,8080,remote,port);
        var syn=new TcpSegment(port,8080,1,0,TcpFlags.SYN,1024,0,new byte[0],new byte[0]);
        return TcpConnection.passiveOpen(key,100,syn);
    }
}
