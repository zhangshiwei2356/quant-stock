package com.quant.stock.kuangrui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OesQueryListInvokerTest {

    public static class FilterA {
    }

    public static class Item {
        public final String id;

        Item(String id) {
            this.id = id;
        }
    }

    public interface ItemCb {
        int onItem(Item item);
    }

    public static class ClientList {
        public List<Item> queryCashAsset(FilterA f) {
            return Arrays.asList(new Item("a"), new Item("b"));
        }
    }

    public static class ClientNullThenOk {
        public List<Item> queryCashAsset(FilterA f) {
            return null;
        }

        public List<Item> queryCashAsset() {
            return Collections.singletonList(new Item("x"));
        }
    }

    public static class ClientCallback {
        public int queryCashAsset(FilterA f, ItemCb cb) {
            cb.onItem(new Item("c1"));
            cb.onItem(new Item("c2"));
            return 2;
        }
    }

    public static class ClientArray {
        public Item[] queryStkHolding(FilterA f) {
            return new Item[]{new Item("h1")};
        }
    }

    /** 对齐宽睿 0.19 Demo：queryXxx(Filter, QueryMode) → Rsp.getQryItems() */
    public enum QueryMode {
        ALL, PAGE
    }

    public static class CashRsp {
        private final List<Item> qryItems;

        CashRsp(List<Item> qryItems) {
            this.qryItems = qryItems;
        }

        public List<Item> getQryItems() {
            return qryItems;
        }
    }

    public static class ClientQueryModeRsp {
        public CashRsp queryCashAsset(FilterA f, QueryMode mode) {
            if (mode == null) {
                throw new NullPointerException("mode is null");
            }
            if (mode != QueryMode.ALL) {
                return new CashRsp(Collections.<Item>emptyList());
            }
            return new CashRsp(Arrays.asList(new Item("cash1"), new Item("cash2")));
        }
    }

    @Test
    void listReturn_withFilter() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientList(),
                new String[]{"queryCashAsset"},
                new String[]{FilterA.class.getName()});
        assertTrue(r.ok);
        assertEquals(2, r.list.size());
    }

    @Test
    void fallsBackToNoArg_whenFilterReturnsNull() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientNullThenOk(),
                new String[]{"queryCashAsset"},
                new String[]{FilterA.class.getName()});
        assertTrue(r.ok, r.detail);
        assertEquals(1, r.list.size());
        assertEquals("x", ((Item) r.list.get(0)).id);
    }

    @Test
    void callbackCollectsItems() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientCallback(),
                new String[]{"queryCashAsset"},
                new String[]{FilterA.class.getName()});
        assertTrue(r.ok, r.detail);
        assertEquals(2, r.list.size());
    }

    @Test
    void arrayReturn() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientArray(),
                new String[]{"queryStkHolding"},
                new String[]{FilterA.class.getName()});
        assertTrue(r.ok);
        assertEquals(1, r.list.size());
    }

    @Test
    void queryModeAll_unwrapsQryItems() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientQueryModeRsp(),
                new String[]{"queryCashAsset"},
                new String[]{FilterA.class.getName()});
        assertTrue(r.ok, r.detail);
        assertEquals(2, r.list.size());
        assertEquals("cash1", ((Item) r.list.get(0)).id);
        assertTrue(r.methodUsed != null && r.methodUsed.contains("ALL"));
        assertTrue(r.methodUsed.contains("getQryItems"));
    }

    @Test
    void missingMethod_failsWithDetail() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientList(),
                new String[]{"noSuchQuery"},
                new String[]{FilterA.class.getName()});
        assertFalse(r.ok);
        assertTrue(r.detail.contains("未找到") || r.detail.contains("查询未返回"));
    }
}
