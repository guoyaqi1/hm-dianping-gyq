package com.hmdp.utils;

/**
 * @Author:guoyaqi
 * @Date: 2025/3/11 0:44
 */
public interface ILock {


    boolean tryLock(long timeoutSec);

    void unlock();
}
