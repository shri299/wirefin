package io.github.shri299.wirefin.tcp;

import java.util.ArrayList;
import java.util.List;

public final class TcpFlags {
    public static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08,
            ACK = 0x10, URG = 0x20, ECE = 0x40, CWR = 0x80;
    private TcpFlags() {}

    public static boolean has(int flags, int flag) { return (flags & flag) != 0; }

    public static String describe(int flags) {
        List<String> names = new ArrayList<>();
        if (has(flags, FIN)) names.add("FIN");
        if (has(flags, SYN)) names.add("SYN");
        if (has(flags, RST)) names.add("RST");
        if (has(flags, PSH)) names.add("PSH");
        if (has(flags, ACK)) names.add("ACK");
        if (has(flags, URG)) names.add("URG");
        if (has(flags, ECE)) names.add("ECE");
        if (has(flags, CWR)) names.add("CWR");
        return names.isEmpty() ? "NONE" : String.join("|", names);
    }
}
