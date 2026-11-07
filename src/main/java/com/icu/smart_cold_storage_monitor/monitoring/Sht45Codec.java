package com.icu.smart_cold_storage_monitor.monitoring;

import java.util.HexFormat;

public final class Sht45Codec {
    private Sht45Codec() {}
    public static double[] decode(String hex) {
        byte[] b = HexFormat.of().parseHex(hex.replace(" ", ""));
        if (b.length != 6 || crc(b,0) != Byte.toUnsignedInt(b[2]) || crc(b,3) != Byte.toUnsignedInt(b[5]))
            throw new IllegalArgumentException("Invalid SHT45 frame or CRC.");
        int t = Byte.toUnsignedInt(b[0])*256+Byte.toUnsignedInt(b[1]);
        int h = Byte.toUnsignedInt(b[3])*256+Byte.toUnsignedInt(b[4]);
        return new double[] {-45+175.0*t/65535, Math.clamp(-6+125.0*h/65535,0,100)};
    }
    static double decodeChannel(byte[] frame, boolean temperature) {
        int offset = temperature ? 0 : 3;
        if (frame.length != 6 || crc(frame, offset) != Byte.toUnsignedInt(frame[offset + 2])) {
            throw new IllegalArgumentException("Invalid SHT45 channel CRC.");
        }
        int value = Byte.toUnsignedInt(frame[offset]) * 256 + Byte.toUnsignedInt(frame[offset + 1]);
        return temperature ? -45 + 175.0 * value / 65535 : Math.clamp(-6 + 125.0 * value / 65535, 0, 100);
    }
    private static int crc(byte[] b,int offset) {
        int c=255;
        for (int i=offset;i<offset+2;i++) {
            c ^= Byte.toUnsignedInt(b[i]);
            for(int k=0;k<8;k++) c = ((c&128)!=0 ? (c<<1)^0x31 : c<<1)&255;
        }
        return c;
    }
}
