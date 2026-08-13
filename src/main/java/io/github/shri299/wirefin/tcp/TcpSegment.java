package io.github.shri299.wirefin.tcp;

import java.util.Arrays;

public record TcpSegment(int sourcePort, int destinationPort, long sequenceNumber,
                         long acknowledgementNumber, int flags, int windowSize,
                         int urgentPointer, byte[] options, byte[] payload) {
    public TcpSegment {
        if (sourcePort < 0 || sourcePort > 0xffff || destinationPort < 0 || destinationPort > 0xffff ||
                sequenceNumber < 0 || sequenceNumber > 0xffff_ffffL ||
                acknowledgementNumber < 0 || acknowledgementNumber > 0xffff_ffffL ||
                flags < 0 || flags > 0x1ff || windowSize < 0 || windowSize > 0xffff ||
                urgentPointer < 0 || urgentPointer > 0xffff)
            throw new IllegalArgumentException("TCP field outside wire range");
        if (options == null || payload == null || (options.length & 3) != 0 || options.length > 40)
            throw new IllegalArgumentException("invalid TCP options/payload");
        options = Arrays.copyOf(options, options.length);
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override public byte[] options() { return Arrays.copyOf(options, options.length); }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    public int headerLength() { return 20 + options.length; }
    public int sequenceSpaceLength() {
        return payload.length + (TcpFlags.has(flags, TcpFlags.SYN) ? 1 : 0) + (TcpFlags.has(flags, TcpFlags.FIN) ? 1 : 0);
    }
    public boolean has(int flag) { return TcpFlags.has(flags, flag); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof TcpSegment that && sourcePort == that.sourcePort && destinationPort == that.destinationPort &&
                sequenceNumber == that.sequenceNumber && acknowledgementNumber == that.acknowledgementNumber &&
                flags == that.flags && windowSize == that.windowSize && urgentPointer == that.urgentPointer &&
                Arrays.equals(options, that.options) && Arrays.equals(payload, that.payload);
    }

    @Override public int hashCode() {
        int result = java.util.Objects.hash(sourcePort, destinationPort, sequenceNumber, acknowledgementNumber,
                flags, windowSize, urgentPointer);
        result = 31 * result + Arrays.hashCode(options);
        return 31 * result + Arrays.hashCode(payload);
    }
}
