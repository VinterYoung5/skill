package com.example.yang.videotranscoding;

import java.nio.ByteBuffer;

/**
 * Created by admin on 2018/1/16.
 */

public class Frame {
    private byte[] yuv;
    private long timeStamp;
    private int flags;
    private int size;

    public Frame(byte[] yuv, long timeStamp, int flags, int size) {
        this.yuv = yuv;
        this.timeStamp = timeStamp;
        this.flags = flags;
        this.size = size;
    }
    public int getsize() { return size;}

    public byte[] getYuv() {
        return yuv;
    }

    public void setYuv(byte[] yuv) {
        this.yuv = yuv;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }
}
