package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ipv6.Ipv6Address;
import java.nio.ByteBuffer;

public final class Ipv6PacketView {
    private final PacketView packet; private final int payloadLength;
    private Ipv6PacketView(PacketView packet,int payloadLength){this.packet=packet;this.payloadLength=payloadLength;}
    public static Ipv6PacketView parse(ByteBuffer memory,int offset,int available){
        PacketView view=new PacketView(memory,offset,available); if(available<40||view.unsignedByte(0)>>>4!=6)throw new IllegalArgumentException("not IPv6");
        int length=view.unsignedShort(4); if(40+length>available)throw new IllegalArgumentException("truncated IPv6 payload"); return new Ipv6PacketView(view,length);
    }
    public int nextHeader(){return packet.unsignedByte(6);}
    public Ipv6Address source(){return address(8);} public Ipv6Address destination(){return address(24);}
    public PacketView payload(){return packet.subview(40,payloadLength);}
    private Ipv6Address address(int offset){byte[] bytes=new byte[16];for(int i=0;i<16;i++)bytes[i]=(byte)packet.unsignedByte(offset+i);return new Ipv6Address(bytes);}
}
