package com.quant.stock.kuangrui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回报同步反射适配（不依赖 quant360 jar）。
 */
class OesRptSyncInvokerTest {

    public static class ClientLong {
        long seen = -1;

        public int sendRptSync(long seq) {
            seen = seq;
            return 0;
        }
    }

    public static class ClientInt {
        int seen = -1;

        public void sendRptSync(int seq) {
            seen = seq;
        }
    }

    public static class ClientTriple {
        byte env;
        boolean all;
        long seq;

        public int sendRptSync(byte envId, boolean subscribeAll, long lastRptSeq) {
            this.env = envId;
            this.all = subscribeAll;
            this.seq = lastRptSeq;
            return 0;
        }
    }

    public static class ClientInitThenSend {
        long inited = -1;
        boolean sent;

        public void initRptSync(long seq) {
            inited = seq;
        }

        public void sendRptSync() {
            sent = true;
        }
    }

    public static class ClientReq {
        public static class Req {
            public long lastRptSeqNum;

            public void setLastRptSeqNum(long v) {
                lastRptSeqNum = v;
            }
        }

        Req last;

        public void sendRptSync(Req req) {
            last = req;
        }
    }

    public static class ClientFailCode {
        public int sendRptSync(long seq) {
            return -1;
        }
    }

    public static class ClientThrows {
        public void sendRptSync(long seq) {
            throw new IllegalStateException("rpt channel down");
        }
    }

    @Test
    void invoke_longSignature() {
        ClientLong c = new ClientLong();
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(c, 42L);
        assertTrue(r.ok);
        assertEquals(42L, c.seen);
        assertNotNull(r.methodUsed);
    }

    @Test
    void invoke_intSignature() {
        ClientInt c = new ClientInt();
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(c, 7L);
        assertTrue(r.ok);
        assertEquals(7, c.seen);
    }

    @Test
    void invoke_tripleSignature() {
        ClientTriple c = new ClientTriple();
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(c, 99L);
        assertTrue(r.ok);
        assertTrue(c.all);
        assertEquals(99L, c.seq);
    }

    @Test
    void invoke_initThenSend() {
        ClientInitThenSend c = new ClientInitThenSend();
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(c, 5L);
        assertTrue(r.ok);
        assertEquals(5L, c.inited);
        assertTrue(c.sent);
    }

    @Test
    void invoke_requestObject() {
        ClientReq c = new ClientReq();
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(c, 123L);
        assertTrue(r.ok);
        assertNotNull(c.last);
        assertEquals(123L, c.last.lastRptSeqNum);
    }

    @Test
    void invoke_negativeCode_failsWithDetail() {
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(new ClientFailCode(), 1L);
        assertFalse(r.ok);
        assertTrue(r.detail.contains("返回 -1") || r.detail.contains("回报同步失败"));
    }

    @Test
    void invoke_throws_surfacesCause() {
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(new ClientThrows(), 1L);
        assertFalse(r.ok);
        assertTrue(r.detail.contains("rpt channel down"), r.detail);
    }

    @Test
    void buildArgs_and_formatSig() throws Exception {
        Method m = ClientTriple.class.getMethod("sendRptSync", byte.class, boolean.class, long.class);
        Object[] args = OesRptSyncInvoker.buildArgs(m.getParameterTypes(), 8L);
        assertNotNull(args);
        assertEquals(3, args.length);
        assertEquals("sendRptSync(byte,boolean,long)", OesRptSyncInvoker.formatSig(m));
        assertTrue(OesRptSyncInvoker.isSyncMethodName("sendReportSynchronization"));
    }
}
