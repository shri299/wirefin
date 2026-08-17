package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ip.*;

public final class UdpDatagramView {
    private final PacketView datagram; private final int length;
    private UdpDatagramView(PacketView datagram,int length){this.datagram=datagram;this.length=length;}
    public static UdpDatagramView parse(PacketView view,IpAddress source,IpAddress destination){
        if(view.length()<8)throw new IllegalArgumentException("truncated UDP");int length=view.unsignedShort(4),checksum=view.unsignedShort(6);
        if(length<8||length>view.length()||source.bitLength()==128&&checksum==0)throw new IllegalArgumentException("invalid UDP header");
        if(checksum!=0&&TransportChecksum.compute(view.memory(),view.offset(),length,source,destination,17)!=0)throw new IllegalArgumentException("invalid UDP checksum");
        return new UdpDatagramView(view,length);
    }
    public int sourcePort(){return datagram.unsignedShort(0);} public int destinationPort(){return datagram.unsignedShort(2);}
    public PacketView payload(){return datagram.subview(8,length-8);}
}
