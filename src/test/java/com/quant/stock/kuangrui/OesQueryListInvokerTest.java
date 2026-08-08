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

    /** 模拟 0.19.x：queryCashAsset(Filter, QueryMode)，mode 为 null 会 NPE。 */
    public enum QueryMode {
        CACHE, DEFAULT, ALL
    }

    public static class ClientQueryMode {
        public final AtomicInteger nullModeHits = new AtomicInteger();

        public List<Item> queryCashAsset(FilterA f, QueryMode mode) {
            if (mode == null) {
                nullModeHits.incrementAndGet();
                throw new NullPointerException("Cannot invoke \"QueryMode.ordinal()\" because \"mode\" is null");
            }
            if (mode == QueryMode.ALL || mode == QueryMode.DEFAULT) {
                return Collections.singletonList(new Item("cash-" + mode.name()));
            }
            return Collections.emptyList();
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
    void missingMethod_failsWithDetail() {
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                new ClientList(),
                new String[]{"noSuchQuery"},
                new String[]{FilterA.class.getName()});
        assertFalse(r.ok);
        assertTrue(r.detail.contains("未找到") || r.detail.contains("查询未返回"));
    }

    @Test
    void queryModeEnum_avoidsNullAndPrefersAll() {
        ClientQueryMode c = new ClientQueryMode();
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                c,
                new String[]{"queryCashAsset"},
                new String[]{FilterA.class.getName()});
        assertTrue(r.ok, r.detail);
        assertEquals(0, c.nullModeHits.get(), "不得向 QueryMode 传 null");
        assertFalse(r.list.isEmpty());
        assertTrue(r.methodUsed != null && (r.methodUsed.contains("ALL") || r.methodUsed.contains("DEFAULT")),
                r.methodUsed);
    }

    public static class ClientSingleEnum {
        public final AtomicInteger nullHits = new AtomicInteger();

        public List<Item> queryTradingDay(QueryMode mode) {
            if (mode == null) {
                nullHits.incrementAndGet();
                throw new NullPointerException("mode is null");
            }
            if (mode == QueryMode.ALL) {
                return Collections.singletonList(new Item("day"));
            }
            return Collections.emptyList();
        }
    }

    @Test
    void singleArgQueryMode_neverPassesNull() {
        ClientSingleEnum c = new ClientSingleEnum();
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(
                c,
                new String[]{"queryTradingDay"},
                new String[]{});
        assertTrue(r.ok, r.detail);
        assertEquals(0, c.nullHits.get());
        assertEquals(1, r.list.size());
    }
}
