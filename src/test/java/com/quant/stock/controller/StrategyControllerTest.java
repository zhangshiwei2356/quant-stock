package com.quant.stock.controller;

import com.quant.stock.strategy.StrategyEvalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略评估 Controller：未知策略 / 未知记录映射为 404。
 */
class StrategyControllerTest {

    private StrategyEvalService evalService;
    private StrategyController controller;

    @BeforeEach
    void setUp() {
        evalService = mock(StrategyEvalService.class);
        controller = new StrategyController(evalService);
    }

    @Test
    void history_unknownStrategy_returns404() {
        when(evalService.history(eq("noSuch"), any()))
                .thenThrow(new NoSuchElementException("未知策略: noSuch"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.history("noSuch", "ALL"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("未知策略: noSuch", ex.getReason());
    }

    @Test
    void detail_unknownRecord_returns404() {
        when(evalService.detail("missing"))
                .thenThrow(new NoSuchElementException("未知回测记录: missing"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.detail("missing"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("未知回测记录: missing", ex.getReason());
    }

    @Test
    void history_knownStrategy_passesThrough() {
        when(evalService.history("maCross", "ALL")).thenReturn(Collections.emptyList());
        assertEquals(0, controller.history("maCross", "ALL").size());
    }

    @Test
    void detail_knownRecord_passesThrough() {
        Map<String, Object> detail = new HashMap<String, Object>();
        detail.put("id", "abc");
        when(evalService.detail("abc")).thenReturn(detail);
        assertEquals("abc", controller.detail("abc").get("id"));
    }
}
