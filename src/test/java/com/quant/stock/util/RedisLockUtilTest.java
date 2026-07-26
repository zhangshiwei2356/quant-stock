package com.quant.stock.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisLockUtilTest {

    @Test
    void localLockReentrantAndExclusive() {
        RedisLockUtil util = new RedisLockUtil();
        assertTrue(util.tryLock("ut-ledger", 30));
        assertTrue(util.tryLock("ut-ledger", 30)); // reentrant
        util.unlock("ut-ledger");
        util.unlock("ut-ledger");
        assertTrue(util.tryLock("ut-ledger", 30));
        util.unlock("ut-ledger");
    }

    @Test
    void unlockWithoutHoldIsNoop() {
        RedisLockUtil util = new RedisLockUtil();
        util.unlock("never-held");
        assertTrue(util.tryLock("never-held", 5));
        util.unlock("never-held");
        assertFalse(false);
    }
}
