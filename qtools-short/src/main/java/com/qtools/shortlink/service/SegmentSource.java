package com.qtools.shortlink.service;

public interface SegmentSource {
    /**
     * 申请一段，返回该段的结束值（含）；实现里就是INCRBY（SEGMENT）
     */
    long nextSegmentEnd();
    /**
     * 一段多少个号
     */
    long segmentSize();
}
