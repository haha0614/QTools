package com.qtools.shortlink.service;

public interface IShortCodeService {
    /** 取下一个短码（6 位起步，唯一） */
    String nextCode();
}
