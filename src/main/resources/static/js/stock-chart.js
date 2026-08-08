(function () {
  var currentCode = '';
  var singleCode = '';
  var lastBacktestCode = '';
  var singlePeriods = {}; // code -> period
  var currentPeriod = 'DAY';
  var batchCache = [];
  var lastBars = [];
  var lastKlinePayload = null;
  var lastSignalPayload = null;
  var lastSignalMarks = null;
  var lastEquity = null;
  var lastSingleEquity = null;
  var lastSingleKlinePayload = null;
  var poolNames = {};
  /** 全市场标的缓存：[{code,name}]，供行情浏览模糊选股 */
  var universeList = [];
  /** 目标池标的缓存：供个股/组合回测选股（不含全市场） */
  var tradePoolList = [];
  var PICKER_LIMIT = 60;
  /** 行情选股表报价缓存 code -> { lastClose, pctChg } */
  var poolQuoteCache = {};
  var poolSortKey = 'pctChg';
  var poolSortDir = 'desc';
  var poolQuoteReqSeq = 0;
  /** 组合回测已选成分股代码（有序） */
  var portfolioSelected = [];
  var poolTabs = []; // { code, period }
  var activePoolCode = '';
  var lastWorkspaceMode = 'pool';
  var apiKeyRequired = false;
  var baseChart = echarts.init(document.getElementById('baseChart'));
  var singleBaseChart = echarts.init(document.getElementById('singleBaseChart'));
  var signalChart = echarts.init(document.getElementById('signalChart'));
  var singleEquityChart = echarts.init(document.getElementById('singleEquityChart'));
  var equityChart = echarts.init(document.getElementById('equityChart'));
  var acctEquityChart = document.getElementById('acctEquityChart')
    ? echarts.init(document.getElementById('acctEquityChart')) : null;
  var lastAcctEquity = null;

  function storedApiKey() {
    try { return localStorage.getItem('quantApiKey') || ''; } catch (e) { return ''; }
  }

  function ensureApiKeyHeader() {
    if (!apiKeyRequired) return true;
    var key = storedApiKey();
    if (!key) {
      key = window.prompt('服务端已启用 API Key，请输入 X-API-Key（将保存在本机 localStorage）') || '';
      key = key.trim();
      if (!key) {
        toast('未提供 API Key，请求可能被拒绝', 'err');
        return false;
      }
      try { localStorage.setItem('quantApiKey', key); } catch (e) {}
    }
    $.ajaxSetup({
      beforeSend: function (xhr) {
        var k = storedApiKey();
        if (k) xhr.setRequestHeader('X-API-Key', k);
      }
    });
    return true;
  }

  $.getJSON('/api/config').done(function (cfg) {
    apiKeyRequired = !!(cfg && cfg.apiKeyRequired);
    if (apiKeyRequired) ensureApiKeyHeader();
  }).fail(function () {
    toast('加载运行配置失败', 'err');
  });

  function pct(v) {
    if (v == null) return '-';
    return (Number(v) * 100).toFixed(2) + '%';
  }

  /** 小收益率保留更多位数，避免 4.15/10万 显示成 0.00% */
  function pctFine(v) {
    if (v == null) return '-';
    var p = Number(v) * 100;
    if (!isFinite(p)) return '-';
    if (p !== 0 && Math.abs(p) < 0.01) return p.toFixed(4) + '%';
    return p.toFixed(2) + '%';
  }

  function num(v, d) {
    if (v == null) return '-';
    return Number(v).toFixed(d == null ? 2 : d);
  }

  /** 时间展示：去掉 ISO 的 T / 毫秒，统一为 yyyy-MM-dd HH:mm:ss */
  function fmtDateTimeDisplay(v) {
    if (v == null || v === '') return '—';
    var s = String(v).trim();
    if (!s) return '—';
    s = s.replace('T', ' ').replace(/\.\d+/, '');
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(s)) s += ':00';
    return s;
  }

  /** 资金中文简写：100000 → 10万 */
  function formatCapitalCn(v) {
    var n = Number(v);
    if (!isFinite(n) || n < 0) return '-';
    function trimNum(x) {
      if (Math.abs(x - Math.round(x)) < 1e-9) return String(Math.round(x));
      return x.toFixed(2).replace(/\.?0+$/, '');
    }
    if (n >= 100000000) {
      return trimNum(n / 100000000) + '亿';
    }
    if (n >= 10000) {
      return trimNum(n / 10000) + '万';
    }
    return trimNum(n) + '元';
  }

  function bindCapitalHint($input, $hint) {
    function sync() {
      var raw = ($input.val() || '').toString().trim();
      if (raw === '') {
        $hint.text('');
        return;
      }
      $hint.text('(' + formatCapitalCn(raw) + ')');
    }
    $input.on('input change', sync);
    sync();
  }

  function placeThemeToastHost() {
    var $host = $('#toastHost');
    var el = document.querySelector('.theme-field') || document.getElementById('themeSelect');
    if (!el) {
      $host.removeClass('toast-host--theme').css({ top: '', right: '', left: '', bottom: '', transform: '' });
      return;
    }
    var r = el.getBoundingClientRect();
    var gap = 10;
    $host.addClass('toast-host--theme').css({
      top: Math.round(r.bottom + gap) + 'px',
      right: Math.max(12, Math.round(window.innerWidth - r.right)) + 'px',
      left: 'auto',
      bottom: 'auto',
      transform: 'none'
    });
  }

  /**
   * @param {string} msg
   * @param {string} [type] ok|err|info
   * @param {{ place?: 'theme'|'default', duration?: number }} [opts]
   */
  function toast(msg, type, opts) {
    opts = opts || {};
    var $host = $('#toastHost');
    if (opts.place === 'theme') {
      placeThemeToastHost();
    } else {
      $host.removeClass('toast-host--theme').css({ top: '', right: '', left: '', bottom: '', transform: '' });
    }
    var $t = $('<div class="toast"/>').addClass(type || 'info').text(msg);
    $host.append($t);
    var holdMs = opts.duration != null ? opts.duration : (opts.place === 'theme' ? 2200 : 2800);
    setTimeout(function () {
      $t.addClass('out');
      setTimeout(function () {
        $t.remove();
        if (opts.place === 'theme' && !$host.children('.toast').length) {
          $host.removeClass('toast-host--theme').css({ top: '', right: '', left: '', bottom: '', transform: '' });
        }
      }, 250);
    }, holdMs);
  }

  /** 数量角标：0 / 无效时隐藏；.badge-label（如 LOCAL_SIM）只改文案不隐藏 */
  function setCountBadge(target, n) {
    var $els = typeof target === 'string' ? $(target) : $(target);
    if (!$els.length) return;
    $els.each(function () {
      var $el = $(this);
      if ($el.hasClass('badge-label')) {
        $el.text(n == null ? '' : String(n)).removeClass('is-zero').removeAttr('hidden');
        return;
      }
      var num = Number(n);
      if (!isFinite(num) || num < 0) num = 0;
      num = Math.floor(num);
      $el.text(String(num));
      if (num === 0) {
        $el.addClass('is-zero').attr('hidden', true);
      } else {
        $el.removeClass('is-zero').removeAttr('hidden');
      }
    });
  }

  function syncZeroCountBadges() {
    $('.trade-pool-count').not('.badge-label').each(function () {
      var raw = $.trim($(this).text());
      var num = Number(raw);
      if (raw === '' || (isFinite(num) && num === 0)) {
        setCountBadge($(this), isFinite(num) ? num : 0);
      }
    });
  }

  function cssVar(name, fallback) {
    var v = getComputedStyle(document.documentElement).getPropertyValue(name);
    v = (v || '').trim();
    return v || fallback;
  }

  function chartPalette() {
    return {
      muted: cssVar('--muted', '#8fa3b8'),
      accent: cssVar('--accent', '#3d9cf0'),
      accentSoft: cssVar('--accent-soft', 'rgba(61,156,240,0.12)'),
      warn: cssVar('--warn', '#f0a020'),
      buy: cssVar('--buy', '#26a69a'),
      sell: cssVar('--sell', '#ef5350'),
      border: cssVar('--border', '#2a3a4f'),
      split: cssVar('--border', '#243447')
    };
  }

  function renderEquityChart(pf, chart) {
    chart = chart || equityChart;
    var pal = chartPalette();
    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      grid: { left: 50, right: 20, top: 20, bottom: 40 },
      xAxis: { type: 'category', data: pf.equityTimes || [], axisLabel: { color: pal.muted, showMaxLabel: false } },
      yAxis: { scale: true, splitLine: { lineStyle: { color: pal.split } }, axisLabel: { color: pal.muted } },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 16 }],
      series: [{
        type: 'line',
        name: '权益',
        data: pf.equityCurve || [],
        showSymbol: false,
        lineStyle: { color: pal.accent },
        areaStyle: { color: pal.accentSoft }
      }]
    }, true);
  }

  function setSingleResultPanelsVisible(show) {
    $('#singleEquityPanel, #singleTradePanel').prop('hidden', !show);
    if (!show) {
      $('#singleSessionPanel').prop('hidden', true);
    }
    if (show) {
      setTimeout(function () {
        try { singleEquityChart.resize(); } catch (e) {}
      }, 60);
    }
  }

  function renderSessionPanel(bt) {
    var eng = (bt && bt.engine) || '';
    var isSession = eng === 'session' || (bt && bt.sessionEvents && bt.sessionEvents.length);
    if (!isSession) {
      $('#singleSessionPanel').prop('hidden', true);
      return;
    }
    $('#singleSessionPanel').prop('hidden', false);
    var st = bt.sessionBranchStats || {};
    var parts = [];
    parts.push('天数<b>' + (st.sessionDays != null ? st.sessionDays : '-') + '</b>');
    parts.push('撮合<b>' + (st.matchingEnabled === false ? '关' : '开') + '</b>');
    parts.push('成交模式<b>' + (st.fillMode || 'AUTO') + '</b>');
    ['OPEN', 'MID', 'CLOSE'].forEach(function (b) {
      var m = st[b] || {};
      parts.push(b + ' tick<b>' + (m.branchTicks || 0) + '</b>'
        + ' 买/卖<b>' + (m.buys || 0) + '/' + (m.sells || 0) + '</b>'
        + ' 已实现<b>' + num(m.realizedPnl) + '</b>');
    });
    if (bt.degradedBranches && bt.degradedBranches.length) {
      parts.push('降级<b>' + bt.degradedBranches.join(',') + '</b>');
    }
    $('#sessionBranchMetrics').html(parts.join(' · '));
    var $tb = $('#sessionEventBody').empty();
    var rows = bt.sessionEvents || [];
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="5" class="empty-state"/>').text('无会话事件')));
      return;
    }
    var cap = Math.min(rows.length, 300);
    for (var i = 0; i < cap; i++) {
      var e = rows[i] || {};
      var $tr = $('<tr/>');
      $tr.append($('<td/>').text(i + 1));
      $tr.append($('<td/>').text(e.time || '-'));
      $tr.append($('<td/>').text(e.type || '-'));
      $tr.append($('<td/>').text(e.branch || '-'));
      $tr.append($('<td/>').text(e.detail || ''));
      $tb.append($tr);
    }
    if (rows.length > cap) {
      $tb.append($('<tr/>').append($('<td colspan="5" class="empty-state"/>')
        .text('仅展示前 ' + cap + ' 条 / 共 ' + rows.length)));
    }
  }

  function clearSingleEquityChart() {
    lastSingleEquity = null;
    try { singleEquityChart.clear(); } catch (e) {}
    setSingleResultPanelsVisible(false);
  }

  function refreshChartsForTheme() {
    if (lastKlinePayload) {
      baseChart.setOption(buildCandleOption(lastKlinePayload.bars, lastKlinePayload.indicators, null), true);
    }
    if (lastSingleKlinePayload) {
      singleBaseChart.setOption(
        buildCandleOption(lastSingleKlinePayload.bars, lastSingleKlinePayload.indicators, lastSignalMarks),
        true
      );
    }
    if (lastSignalPayload && lastSignalMarks) {
      signalChart.setOption(buildCandleOption(lastSignalPayload.bars, lastSignalPayload.indicators, lastSignalMarks), true);
    } else if (lastSingleKlinePayload) {
      signalChart.setOption(buildCandleOption(lastSingleKlinePayload.bars, lastSingleKlinePayload.indicators, null), true);
    }
    if (lastSingleEquity) {
      renderEquityChart(lastSingleEquity, singleEquityChart);
    }
    if (lastEquity) {
      renderEquityChart(lastEquity, equityChart);
    }
    if (lastAcctEquity && acctEquityChart) {
      renderEquityChart(lastAcctEquity, acctEquityChart);
    }
  }

  function withLoading($btn, promiseLike) {
    $btn.addClass('loading').prop('disabled', true);
    return $.when(promiseLike).always(function () {
      $btn.removeClass('loading').prop('disabled', false);
    });
  }

  var THEME_KEYS = {
    cosmos: 1, forest: 1, night: 1,
    wave: 1, matrix: 1
  };

  function applyTheme(theme) {
    // 已下线主题并入浪花日间；波浪→银河；代码雨/Vanta→夜盘；旧 aurora→浪花
    if (theme === 'interact' || theme === 'finance' || theme === 'day' || theme === 'circuit' ||
        theme === 'rings' || theme === 'worldclock' || theme === 'heatspots' || theme === 'isocandles' ||
        theme === 'orbits') {
      theme = 'cosmos';
    }
    if (theme === 'wave') theme = 'forest';
    if (theme === 'matrix' || theme === 'vanta') theme = 'night';
    if (theme === 'aurora') theme = 'cosmos';
    if (!THEME_KEYS[theme]) theme = 'cosmos';
    document.documentElement.setAttribute('data-theme', theme);
    try { localStorage.setItem('quant-theme', theme); } catch (e) {}
    $('#themeSelect').val(theme);

    if (window.QuantStarfieldBg && typeof window.QuantStarfieldBg.stop === 'function') {
      window.QuantStarfieldBg.stop();
    }

    if (theme === 'forest') {
      if (window.QuantParticleBg && typeof window.QuantParticleBg.stop === 'function') {
        window.QuantParticleBg.stop();
      }
      if (window.QuantStarfieldBg && typeof window.QuantStarfieldBg.start === 'function') {
        window.QuantStarfieldBg.start();
      }
    } else if (window.QuantParticleBg && typeof window.QuantParticleBg.setTheme === 'function') {
      window.QuantParticleBg.setTheme(theme);
    }
    refreshChartsForTheme();
  }

  function initTheme() {
    var theme = 'cosmos';
    try {
      theme = localStorage.getItem('quant-theme') || document.documentElement.getAttribute('data-theme') || 'cosmos';
    } catch (e) {}
    applyTheme(theme);
  }

  function resizeCharts() {
    setTimeout(function () {
      try {
        baseChart.resize();
        singleBaseChart.resize();
        signalChart.resize();
        singleEquityChart.resize();
        equityChart.resize();
        if (acctEquityChart) acctEquityChart.resize();
      } catch (e) {}
    }, 60);
  }

  function loadSummary() {
    $.getJSON('/api/data/summary', function (s) {
      if (s && s.available) {
        var range = (s.start || '') + ' ~ ' + (s.end || '');
        var phStart = (s.start || 'yyyy-MM-dd') + ' 09:30:00';
        var phEnd = (s.end || 'yyyy-MM-dd') + ' 15:00:00';
        $('#dataHint').prop('hidden', false).text(range + ' · MySQL行情');
        $('#singleHint').text(
          '回测时间：默认留空=全部可用K线（当前 ' + range +
          '）。格式 yyyy-MM-dd HH:mm:ss；初始资金默认 100000。'
        );
        $('#backTimeHint').text(
          '回测时间：默认留空=全部可用K线（当前 ' + range +
          '）。格式 yyyy-MM-dd HH:mm:ss，组合回测按区间截取。'
        );
        $('#singleBackStart, #backStart').attr('placeholder', phStart);
        $('#singleBackEnd, #backEnd').attr('placeholder', phEnd);
      } else {
        $('#dataHint').prop('hidden', false).text('运行时合成行情');
        $('#singleHint').text('回测时间默认留空=全部可用K线。格式 yyyy-MM-dd HH:mm:ss。');
        $('#backTimeHint').text('回测时间：默认留空=全部可用K线。格式 yyyy-MM-dd HH:mm:ss。');
      }
    }).fail(function () {
      toast('行情摘要加载失败', 'err');
    });
  }

  function isPortfolioSelected(code) {
    return portfolioSelected.indexOf(code) >= 0;
  }

  function togglePortfolioStock(code) {
    if (!code) return;
    var i = portfolioSelected.indexOf(code);
    if (i >= 0) {
      portfolioSelected.splice(i, 1);
    } else {
      portfolioSelected.push(code);
    }
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  function selectPortfolioTopN(n) {
    portfolioSelected = (tradePoolList || []).slice(0, n || 3).map(function (it) { return it.code; });
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  /** 全选当前目标池 */
  function selectPortfolioAll() {
    portfolioSelected = (tradePoolList || []).map(function (it) { return it.code; }).filter(Boolean);
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  /** 反选：相对目标池取补集 */
  function invertPortfolioSelection() {
    var selected = {};
    (portfolioSelected || []).forEach(function (c) { selected[c] = true; });
    portfolioSelected = (tradePoolList || []).map(function (it) { return it.code; })
      .filter(function (c) { return c && !selected[c]; });
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  function clearPortfolioSelection() {
    portfolioSelected = [];
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  function setPfBatchMenuOpen(open) {
    var $menu = $('#pfBatchMenu');
    var $btn = $('#btnPfPickTop3');
    if (!$menu.length) return;
    $menu.prop('hidden', !open);
    $btn.attr('aria-expanded', open ? 'true' : 'false');
  }

  function syncPortfolioCodes() {
    var allowed = {};
    (tradePoolList || []).forEach(function (it) { if (it && it.code) allowed[it.code] = true; });
    portfolioSelected = portfolioSelected.filter(function (c) { return !!allowed[c]; });
    $('#portfolioCodes').val(portfolioSelected.join(','));
    $('#pfSelectedCountNum').text(String(portfolioSelected.length));
    var $bar = $('#pfChipsBar');
    var $chips = $('#pfChips').empty();
    if (!portfolioSelected.length) {
      $bar.addClass('empty');
      $chips.append($('<span class="pf-chips-empty"/>').text(
        (tradePoolList || []).length
          ? '尚未选择 · 在下方列表点击添加，或用「批量选择」'
          : '目标池为空 · 请先在「目标池」扫描更新'
      ));
    } else {
      $bar.removeClass('empty');
      portfolioSelected.forEach(function (code) {
        var name = poolNames[code] || code;
        $chips.append(
          $('<button type="button" class="pf-chip"/>')
            .attr('data-code', code)
            .attr('title', '移除 ' + code)
            .html(
              '<b>' + escHtml(code) + '</b>'
              + '<span>' + escHtml(name) + '</span>'
              + '<i aria-hidden="true">×</i>'
            )
        );
      });
    }
  }

  function setPortfolioResultPanelsVisible(show) {
    $('#pfEquityPanel, #pfTradePanel, #pfStockPanel').prop('hidden', !show);
    if (!show) {
      $('#pfSessionPanel').prop('hidden', true);
    }
    if (show) {
      setTimeout(function () {
        try { equityChart.resize(); } catch (e) {}
      }, 60);
    }
  }

  function clearPortfolioResult() {
    $('#pfTradeBody').html('<tr><td colspan="10" class="empty-state">执行组合回测后显示买卖明细</td></tr>');
    $('#pfTradeSummary').empty().prop('hidden', true);
    $('#pfStockBody').html('<tr><td colspan="10" class="empty-state">回测后按股票汇总</td></tr>');
    $('#pfMetrics').empty();
    $('#pfSessionMetrics').empty();
    $('#pfSessionEventBody').html('<tr><td colspan="5" class="empty-state">session 组合回测后显示事件</td></tr>');
    $('#pfSessionPanel').prop('hidden', true);
    lastEquity = null;
    try { equityChart.clear(); } catch (e) {}
    setPortfolioResultPanelsVisible(false);
  }

  function renderPortfolioSessionPanel(pf) {
    var eng = (pf && pf.engine) || '';
    var st = (pf && pf.sessionBranchStats) || {};
    var isSession = eng === 'session' || st.mode === 'SHARED_CASH_SESSION'
      || (pf && pf.sessionEvents && pf.sessionEvents.length);
    if (!isSession) {
      $('#pfSessionPanel').prop('hidden', true);
      return;
    }
    $('#pfSessionPanel').prop('hidden', false);
    var parts = [];
    parts.push('模式<b>' + (st.mode || 'SHARED_CASH_SESSION') + '</b>');
    parts.push('腿数<b>' + (st.legs != null ? st.legs : '-') + '</b>');
    parts.push('天数<b>' + (st.sessionDays != null ? st.sessionDays : '-') + '</b>');
    parts.push('撮合<b>' + (st.matchingEnabled === false ? '关' : '开') + '</b>');
    parts.push('成交模式<b>' + (st.fillMode || 'AUTO') + '</b>');
    if (st.halted) {
      parts.push('熔断<b class="pnl-neg">' + (st.haltReason || 'YES') + '</b>');
    }
    if (pf.degradedBranches && pf.degradedBranches.length) {
      parts.push('降级<b>' + pf.degradedBranches.join(',') + '</b>');
    }
    $('#pfSessionMetrics').html(parts.join(' · '));
    var $tb = $('#pfSessionEventBody').empty();
    var rows = pf.sessionEvents || [];
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="5" class="empty-state"/>').text('无会话事件')));
      return;
    }
    var cap = Math.min(rows.length, 300);
    for (var i = 0; i < cap; i++) {
      var e = rows[i] || {};
      var $tr = $('<tr/>');
      $tr.append($('<td/>').text(i + 1));
      $tr.append($('<td/>').text(e.time || '-'));
      $tr.append($('<td/>').text(e.type || '-'));
      $tr.append($('<td/>').text(e.branch || '-'));
      $tr.append($('<td/>').text(e.detail || '-'));
      $tb.append($tr);
    }
  }

  function renderPortfolioTradeTable(pf) {
    var trades = (pf && pf.trades) || [];
    var $tb = $('#pfTradeBody').empty();
    var $sum = $('#pfTradeSummary').empty().prop('hidden', true);
    if (!trades.length) {
      $tb.append($('<tr/>').append($('<td colspan="10" class="empty-state"/>').text('本次组合回测无成交记录')));
      return;
    }

    var posByCode = {};
    var avgByCode = {};
    var realized = 0;
    var feeSum = 0;
    var buyVol = 0;
    var sellVol = 0;
    var buyCount = 0;
    var sellCount = 0;

    trades.forEach(function (t, idx) {
      var code = t.stockCode || '-';
      var side = String(t.side || '').toUpperCase();
      var vol = Number(t.volume || 0);
      var price = Number(t.price || 0);
      var fee = Number(t.fee || 0);
      var amount = t.amount != null ? Number(t.amount) : price * vol;
      var time = t.tradeTime || '-';
      feeSum += fee;
      if (posByCode[code] == null) posByCode[code] = 0;
      if (avgByCode[code] == null) avgByCode[code] = 0;

      var pnlHtml = '<span class="tag-wait">—</span>';
      var sideHtml;
      if (side === 'BUY') {
        sideHtml = '<span class="tag-buy">买入</span>';
        buyVol += vol;
        buyCount++;
        var pos0 = posByCode[code];
        avgByCode[code] = pos0 <= 0 ? price : (avgByCode[code] * pos0 + price * vol) / (pos0 + vol);
        posByCode[code] = pos0 + vol;
        pnlHtml = '<span class="tag-wait">建仓</span>';
      } else {
        sideHtml = '<span class="tag-sell">卖出</span>';
        sellVol += vol;
        sellCount++;
        var gross = (price - avgByCode[code]) * vol;
        var net = gross - fee;
        realized += net;
        posByCode[code] = Math.max(0, posByCode[code] - vol);
        if (posByCode[code] === 0) avgByCode[code] = 0;
        var cls = net >= 0 ? 'pnl-pos' : 'pnl-neg';
        pnlHtml = '<span class="' + cls + '">' + (net >= 0 ? '+' : '') + num(net) + '</span>';
      }

      $tb.append($('<tr/>')
        .append($('<td/>').text(idx + 1))
        .append($('<td/>').text(time))
        .append($('<td/>').text(code))
        .append($('<td/>').html(sideHtml))
        .append($('<td/>').text(num(price)))
        .append($('<td/>').text(vol))
        .append($('<td/>').text(num(amount)))
        .append($('<td/>').text(num(fee)))
        .append($('<td/>').html(pnlHtml))
        .append($('<td/>').text(posByCode[code])));
    });

    var initCap = pf.initCapital != null ? Number(pf.initCapital) : Number($('#pfInitCapital').val() || 0);
    var finalAsset = pf.finalAsset != null ? Number(pf.finalAsset) : initCap;
    var profit = finalAsset - initCap;
    var profitCls = profit >= 0 ? 'pnl-pos' : 'pnl-neg';
    var profitText = (profit >= 0 ? '+' : '') + num(profit);
    var rateText = pctFine(pf.totalRate);
    var realizedCls = realized >= 0 ? 'pnl-pos' : 'pnl-neg';
    var realizedText = (realized >= 0 ? '+' : '') + num(realized);
    var holdCodes = Object.keys(posByCode).filter(function (c) { return posByCode[c] > 0; });

    $sum.prop('hidden', false).html(
      '<div class="result-hero">' +
        '<div class="result-hero-card">' +
          '<div class="label">总盈亏（期末 − 初始）</div>' +
          '<div class="value ' + profitCls + '">' + profitText + '</div>' +
          '<div class="sub">收益率 ' + rateText + '</div>' +
        '</div>' +
        '<div class="result-hero-card">' +
          '<div class="label">期末资产</div>' +
          '<div class="value">' + num(finalAsset) + '</div>' +
          '<div class="sub">约 ' + formatCapitalCn(finalAsset) + '</div>' +
        '</div>' +
      '</div>' +
      '<div class="result-groups">' +
        '<div class="result-group">' +
          '<div class="result-group-title">资金</div>' +
          '<div class="result-kv"><span class="label">初始资金</span><span class="value">' + num(initCap) + '<span class="cn">(' + formatCapitalCn(initCap) + ')</span></span></div>' +
          '<div class="result-kv"><span class="label">期末资产</span><span class="value">' + num(finalAsset) + '</span></div>' +
          '<div class="result-kv"><span class="label">总盈亏</span><span class="value ' + profitCls + '">' + profitText + '</span></div>' +
          '<div class="result-kv"><span class="label">收益率</span><span class="value ' + profitCls + '">' + rateText + '</span></div>' +
        '</div>' +
        '<div class="result-group">' +
          '<div class="result-group-title">风险与胜率</div>' +
          '<div class="result-kv"><span class="label">最大回撤</span><span class="value">' + pct(pf.maxDrawDown) + '</span></div>' +
          '<div class="result-kv"><span class="label">胜率</span><span class="value">' + pct(pf.winRate) + '</span></div>' +
          '<div class="result-kv"><span class="label">完整卖出</span><span class="value">' + sellCount + ' 次</span></div>' +
        '</div>' +
        '<div class="result-group">' +
          '<div class="result-group-title">成交概况</div>' +
          '<div class="result-kv"><span class="label">成交笔数</span><span class="value">' + trades.length + '（买' + buyCount + ' / 卖' + sellCount + '）</span></div>' +
          '<div class="result-kv"><span class="label">买入量 / 卖出量</span><span class="value">' + buyVol + ' / ' + sellVol + ' 股</span></div>' +
          '<div class="result-kv"><span class="label">费用合计</span><span class="value">' + num(feeSum) + '</span></div>' +
          '<div class="result-kv"><span class="label">卖出已实现盈亏</span><span class="value ' + realizedCls + '">' + realizedText + '</span></div>' +
          '<div class="result-kv"><span class="label">期末仍持仓</span><span class="value">' +
            (holdCodes.length ? holdCodes.map(function (c) { return c + ':' + posByCode[c]; }).join(' ') : '已清仓') +
          '</span></div>' +
        '</div>' +
      '</div>' +
      '<p class="result-note">说明：组合共享资金池；已对齐次日开盘撮合、成本模型、开仓过滤、金字塔、止损/trail、账户熔断与分股回撤。细则见「交易规则」。</p>'
    );
  }

  function renderPortfolioStockBreakdown(pf) {
    var $tb = $('#pfStockBody').empty();
    var trades = (pf && pf.trades) || [];
    var byCode = {};
    var winRateByCode = {};
    (pf.stockResults || []).forEach(function (s) {
      if (s && s.stockCode) {
        winRateByCode[s.stockCode] = s.winRate;
      }
    });

    trades.forEach(function (t) {
      var code = t.stockCode || '-';
      if (!byCode[code]) {
        byCode[code] = {
          buyCount: 0, sellCount: 0, buyShares: 0, sellShares: 0,
          buyAmount: 0, sellAmount: 0, fee: 0, realized: 0, avg: 0, pos: 0, trades: 0
        };
      }
      var g = byCode[code];
      var side = String(t.side || '').toUpperCase();
      var vol = Number(t.volume || 0);
      var price = Number(t.price || 0);
      var fee = Number(t.fee || 0);
      var amount = t.amount != null ? Number(t.amount) : price * vol;
      g.fee += fee;
      g.trades++;
      if (side === 'BUY') {
        g.buyCount++;
        g.buyShares += vol;
        g.buyAmount += amount;
        g.avg = g.pos <= 0 ? price : (g.avg * g.pos + price * vol) / (g.pos + vol);
        g.pos += vol;
      } else {
        g.sellCount++;
        g.sellShares += vol;
        g.sellAmount += amount;
        g.realized += (price - g.avg) * vol - fee;
        g.pos = Math.max(0, g.pos - vol);
        if (g.pos === 0) g.avg = 0;
      }
    });

    var codes = Object.keys(byCode).sort();
    if (!codes.length) {
      $tb.append($('<tr/>').append($('<td colspan="10" class="empty-state"/>').text('暂无分股成交')));
      return;
    }
    codes.forEach(function (code) {
      var g = byCode[code];
      var rCls = g.realized >= 0 ? 'pnl-pos' : 'pnl-neg';
      var rText = (g.realized >= 0 ? '+' : '') + num(g.realized);
      $tb.append($('<tr/>')
        .append($('<td/>').text(code))
        .append($('<td/>').text(poolNames[code] || '-'))
        .append($('<td/>').text(g.buyCount + ' / ' + g.sellCount))
        .append($('<td/>').text(Math.floor(g.buyShares / 100) + ' / ' + Math.floor(g.sellShares / 100)))
        .append($('<td/>').text(num(g.buyAmount)))
        .append($('<td/>').text(num(g.sellAmount)))
        .append($('<td/>').text(num(g.fee)))
        .append($('<td/>').html('<span class="' + rCls + '">' + rText + '</span>'))
        .append($('<td/>').text(g.trades))
        .append($('<td/>').text(pct(winRateByCode[code]))));
    });
  }

  function getPoolTab(code) {
    for (var i = 0; i < poolTabs.length; i++) {
      if (poolTabs[i].code === code) return poolTabs[i];
    }
    return null;
  }

  function periodLabel(period) {
    var map = {
      DAY: '日K', WEEK: '周K', MONTH: '月K',
      MIN_60: '60分', MIN_30: '30分', MIN_15: '15分', MIN_5: '5分', MIN_1: '1分'
    };
    return map[period] || period || '日K';
  }

  function renderPoolTabs() {
    var $tabs = $('#poolTabs').empty();
    if (!poolTabs.length) {
      $tabs.append($('<div class="empty-state"/>').text('在上方表格搜索并点击股票加入已选（可同时打开多只）'));
      $('#poolMeta').text('');
      baseChart.clear();
      $('#barTableBody').html('<tr><td colspan="6" class="empty-state">暂无K线数据</td></tr>');
      return;
    }
    poolTabs.forEach(function (tab) {
      var code = tab.code;
      var $tab = $('<div class="stock-tab"/>').attr('data-code', code);
      if (code === activePoolCode) $tab.addClass('active');
      $tab.append(
        $('<span class="stock-tab-label"/>').text(
          code + ' ' + (poolNames[code] || '') + ' · ' + periodLabel(tab.period)
        )
      );
      var $close = $('<button type="button" class="stock-tab-close" title="关闭"/>').text('×');
      $close.on('click', function (e) {
        e.stopPropagation();
        closePoolStock(code);
      });
      $tab.append($close);
      $tab.on('click', function () { focusPoolStock(code); });
      $tabs.append($tab);
    });
  }

  function markPoolListOpen() {
    $('#poolStockResults tr[data-code]').removeClass('active open');
    poolTabs.forEach(function (tab) {
      $('#poolStockResults tr[data-code="' + tab.code + '"]').addClass('open');
    });
    if (activePoolCode) {
      $('#poolStockResults tr[data-code="' + activePoolCode + '"]').addClass('active');
    }
  }

  function normalizeStockQuery(q) {
    return String(q || '').trim().toLowerCase().replace(/\s+/g, '');
  }

  function formatQuotePrice(v) {
    var n = Number(v);
    if (!isFinite(n)) return '—';
    return n.toFixed(2);
  }

  function formatQuotePct(v) {
    var n = Number(v);
    if (!isFinite(n)) return '—';
    var sign = n > 0 ? '+' : '';
    return sign + (n * 100).toFixed(2) + '%';
  }

  function sortPoolMatched(list) {
    var key = poolSortKey || 'pctChg';
    var dir = poolSortDir === 'asc' ? 1 : -1;
    return (list || []).slice().sort(function (a, b) {
      var qa = poolQuoteCache[a.code] || {};
      var qb = poolQuoteCache[b.code] || {};
      var va = key === 'lastClose' ? Number(qa.lastClose) : Number(qa.pctChg);
      var vb = key === 'lastClose' ? Number(qb.lastClose) : Number(qb.pctChg);
      var aOk = isFinite(va);
      var bOk = isFinite(vb);
      if (!aOk && !bOk) return String(a.code).localeCompare(String(b.code));
      if (!aOk) return 1;
      if (!bOk) return -1;
      if (va === vb) return String(a.code).localeCompare(String(b.code));
      return va > vb ? dir : -dir;
    });
  }

  function renderPoolStockTableRows(matched) {
    var $tb = $('#poolStockResults');
    if (!$tb.length) return;
    $tb.empty();
    if (!matched.length) {
      var total = (universeList || []).length;
      var emptyMsg = total ? '无匹配标的' : '暂无股票数据';
      $tb.append($('<tr/>').append($('<td colspan="5" class="empty-state"/>').text(emptyMsg)));
      return;
    }
    matched.forEach(function (it) {
      var code = it.code;
      var q = poolQuoteCache[code] || {};
      var pct = q.pctChg;
      var pctCls = '';
      if (Number(pct) > 0) pctCls = 'pnl-pos';
      else if (Number(pct) < 0) pctCls = 'pnl-neg';
      var status = getPoolTab(code) ? (code === activePoolCode ? '当前' : '已选') : '—';
      var $tr = $('<tr role="button" tabindex="0"/>').attr('data-code', code);
      if (getPoolTab(code)) $tr.addClass('open');
      if (code === activePoolCode) $tr.addClass('active');
      $tr.append(
        $('<td class="stock-picker-code"/>').text(code),
        $('<td class="stock-picker-name"/>').text(it.name || ''),
        $('<td class="num"/>').text(formatQuotePrice(q.lastClose)),
        $('<td class="num"/>').addClass(pctCls).text(formatQuotePct(pct)),
        $('<td class="muted"/>').text(status)
      );
      $tb.append($tr);
    });
  }

  function fillPoolQuotesThenRender(matched) {
    renderPoolStockTableRows(sortPoolMatched(matched));
    var codes = [];
    matched.forEach(function (it) {
      if (it && it.code && poolQuoteCache[it.code] == null) {
        codes.push(it.code);
      }
    });
    if (!codes.length) return;
    var seq = ++poolQuoteReqSeq;
    $.getJSON('/api/stock/quotes', { codes: codes.join(',') })
      .done(function (resp) {
        if (seq !== poolQuoteReqSeq) return;
        var items = (resp && resp.items) || [];
        items.forEach(function (row) {
          if (!row || !row.code) return;
          poolQuoteCache[row.code] = {
            lastClose: row.lastClose,
            pctChg: row.pctChg,
            asOf: row.asOf
          };
        });
        codes.forEach(function (c) {
          if (poolQuoteCache[c] == null) {
            poolQuoteCache[c] = { lastClose: null, pctChg: null };
          }
        });
        var again = filterUniverse($('#poolStockQ').val(), PICKER_LIMIT, 'pool');
        renderPoolStockTableRows(sortPoolMatched(again));
      });
  }

  /** 代码/名称模糊匹配（包含、前缀优先）；mode=single|portfolio 用目标池，其余用全市场。limit<=0 表示不截断 */
  function filterUniverse(q, limit, mode) {
    var query = normalizeStockQuery(q);
    var unlimited = limit == null || limit <= 0;
    limit = unlimited ? 0 : (limit || PICKER_LIMIT);
    var list = (mode === 'single' || mode === 'portfolio')
      ? (tradePoolList || [])
      : (universeList || []);
    if (!query) {
      return unlimited ? list.slice() : list.slice(0, limit);
    }
    var prefix = [];
    var mid = [];
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      var code = String(it.code || '').toLowerCase();
      var name = String(it.name || '').toLowerCase();
      var hit = code.indexOf(query) >= 0 || name.indexOf(query) >= 0;
      if (!hit) continue;
      if (code.indexOf(query) === 0 || name.indexOf(query) === 0) {
        prefix.push(it);
      } else {
        mid.push(it);
      }
      if (!unlimited && prefix.length + mid.length >= limit * 2) break;
    }
    var out = prefix.concat(mid);
    return unlimited ? out : out.slice(0, limit);
  }

  /** 目标池下拉：全量填充，不截断 */
  function fillSinglePoolSelect() {
    var $sel = $('#singlePoolSelect');
    if (!$sel.length) return;
    var list = tradePoolList || [];
    var prev = singleCode || $sel.val() || '';
    $sel.empty();
    if (!list.length) {
      $sel.append($('<option value=""/>').text('目标池为空'));
      return;
    }
    $sel.append($('<option value=""/>').text('请选择目标池股票（共 ' + list.length + '）'));
    list.forEach(function (it) {
      if (!it || !it.code) return;
      var label = it.code + (it.name ? (' · ' + it.name) : '');
      $sel.append($('<option/>').attr('value', it.code).text(label));
    });
    if (prev) {
      $sel.val(prev);
      if ($sel.val() !== prev) $sel.val('');
    }
  }

  function syncSinglePoolSelect(code) {
    var $sel = $('#singlePoolSelect');
    if (!$sel.length) return;
    if (!code) {
      $sel.val('');
      return;
    }
    $sel.val(code);
    if ($sel.val() !== code) {
      // option 尚未填充时忽略，等 fillSinglePoolSelect
    }
  }

  function renderStockPicker(mode) {
    var isPool = mode === 'pool';
    var isPf = mode === 'portfolio';
    var isSingle = !isPool && !isPf;
    var q = isPool ? $('#poolStockQ').val() : (isPf ? $('#pfStockQ').val() : $('#singleStockQ').val());
    var $hint = isPool ? $('#poolStockMatchHint') : (isPf ? $('#pfStockMatchHint') : $('#singleStockMatchHint'));
    // 个股回测：池内全量匹配，不截断；行情/组合仍用 PICKER_LIMIT
    var matched = isSingle
      ? filterUniverse(q, 0, mode)
      : filterUniverse(q, PICKER_LIMIT, mode);
    var total = (mode === 'single' || mode === 'portfolio')
      ? (tradePoolList || []).length
      : (universeList || []).length;
    var query = normalizeStockQuery(q);

    if (isSingle) {
      fillSinglePoolSelect();
      syncSinglePoolSelect(singleCode);
      $hint.text(query
        ? ('匹配 ' + matched.length + ' / 共 ' + total)
        : (total ? ('池内 ' + total + ' 只 · 下拉直选或输入筛选') : '目标池为空'));
      var $singleList = $('#singleStockResults');
      $singleList.empty();
      if (!query) {
        $singleList.prop('hidden', true);
        return;
      }
      if (!matched.length) {
        $singleList.prop('hidden', false)
          .append($('<li class="stock-picker-empty"/>').text(total ? '无匹配标的' : '目标池为空，请先在「目标池」扫描更新'));
        return;
      }
      $singleList.prop('hidden', false);
      matched.forEach(function (it) {
        var code = it.code;
        var $li = $('<li role="button" tabindex="0"/>')
          .attr('data-code', code)
          .html(
            '<span class="stock-picker-code">' + escHtml(code) + '</span>'
            + '<span class="stock-picker-name">' + escHtml(it.name || '') + '</span>'
          );
        if (code === singleCode) $li.addClass('active');
        $singleList.append($li);
      });
      return;
    }

    $hint.text(query
      ? ('匹配 ' + matched.length + (matched.length >= PICKER_LIMIT ? '+' : '') + ' / 共 ' + total)
      : ('展示前 ' + matched.length + ' / 共 ' + total + ' · 输入可筛选'));

    if (isPool) {
      fillPoolQuotesThenRender(matched);
      return;
    }

    var $list = $('#pfStockResults');
    $list.empty();
    if (!matched.length) {
      $list.append($('<li class="stock-picker-empty"/>').text(
        total ? '无匹配标的' : '目标池为空，请先在「目标池」扫描更新'
      ));
      return;
    }
    matched.forEach(function (it) {
      var code = it.code;
      var $li = $('<li role="button" tabindex="0"/>')
        .attr('data-code', code)
        .html(
          '<span class="stock-picker-code">' + escHtml(code) + '</span>'
          + '<span class="stock-picker-name">' + escHtml(it.name || '') + '</span>'
        );
      if (isPortfolioSelected(code)) $li.addClass('selected');
      $list.append($li);
    });
  }

  function refreshUniverseCounts() {
    setCountBadge('#poolUniverseCount', (universeList || []).length);
    setCountBadge('#singleUniverseCount, #pfUniverseCount', (tradePoolList || []).length);
  }

  /** 用目标池 items 刷新回测选股列表 */
  function applyTradePoolForBacktest(items) {
    tradePoolList = [];
    var codes = [];
    (items || []).forEach(function (it) {
      var code = it && it.code;
      if (!code) return;
      var name = it.name || code;
      poolNames[code] = name;
      tradePoolList.push({ code: code, name: name });
      codes.push(code);
    });
    portfolioSelected = portfolioSelected.filter(function (c) {
      return codes.indexOf(c) >= 0;
    });
    if (!portfolioSelected.length && codes.length) {
      portfolioSelected = codes.slice(0, Math.min(3, codes.length));
    }
    if (singleCode && codes.indexOf(singleCode) < 0) {
      if (codes.length) {
        selectSingleStock(codes[0], { silent: true });
      } else {
        singleCode = '';
        $('#stockCode').val('');
        $('#singleSelectedCode').text('未选择');
        $('#singleSelectedName').text('目标池为空，请先扫描更新');
        $('#singleSelectedBar').toggleClass('empty', true);
      }
    } else if (!singleCode && codes.length) {
      selectSingleStock(codes[0], { silent: true });
    }
    refreshUniverseCounts();
    fillSinglePoolSelect();
    syncSinglePoolSelect(singleCode);
    renderStockPicker('single');
    renderStockPicker('portfolio');
    syncPortfolioCodes();
  }

  function openPoolStock(code) {
    if (!code) return;
    if (!getPoolTab(code)) {
      poolTabs.push({ code: code, period: 'DAY' });
    }
    focusPoolStock(code);
  }

  function closePoolStock(code) {
    poolTabs = poolTabs.filter(function (t) { return t.code !== code; });
    if (activePoolCode === code) {
      activePoolCode = poolTabs.length ? poolTabs[poolTabs.length - 1].code : '';
    }
    renderPoolTabs();
    markPoolListOpen();
    if (activePoolCode) {
      focusPoolStock(activePoolCode);
    }
  }

  function focusPoolStock(code) {
    var tab = getPoolTab(code);
    if (!tab) return;
    activePoolCode = code;
    currentCode = code;
    $('#poolPeriod').val(tab.period || 'DAY');
    renderPoolTabs();
    markPoolListOpen();
    loadPoolKline(code);
  }

  function fmtScore(v) {
    if (v == null || v === '') return '—';
    var n = Number(v);
    if (isNaN(n)) return String(v);
    return (n * 100).toFixed(2) + '%';
  }

  /** 目标池综合分（0~100）；兼容旧版小数收益率 */
  function fmtPoolScore(v) {
    if (v == null || v === '') return '—';
    var n = Number(v);
    if (isNaN(n)) return String(v);
    if (Math.abs(n) <= 1.0001) {
      return (n * 100).toFixed(2) + '%';
    }
    return n.toFixed(1) + '分';
  }

  var TP_POOL_COLSPAN = 6;

  function collapseTpPoolAnalysis() {
    var $tb = $('#tpPoolBody');
    $tb.find('tr.tp-pool-row').removeClass('active').removeAttr('data-expanded');
    $tb.find('tr.tp-analysis-row').remove();
  }

  function ensureTpAnalysisRow($tr) {
    var forKey = String($tr.attr('data-code') || '');
    var $next = $tr.next('tr.tp-analysis-row');
    if ($next.length && String($next.attr('data-for-key') || '') === forKey) {
      return $next.find('.tp-analysis-panel');
    }
    $tr.closest('tbody').find('tr.tp-analysis-row').remove();
    var $row = $('<tr class="tp-analysis-row"/>').attr('data-for-key', forKey);
    var $cell = $('<td class="tp-analysis-cell"/>').attr('colspan', TP_POOL_COLSPAN);
    var $panel = $('<div class="tp-analysis-panel knowledge-body"/>');
    $cell.append($panel);
    $row.append($cell);
    $tr.after($row);
    try {
      $row[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    } catch (e) {}
    return $panel;
  }

  function renderTpPoolAnalysis(rec, $panel) {
    $panel.empty();
    var $head = $('<div class="analysis-detail-head"/>');
    $head.append($('<span/>').html('<b>入选分析报告</b> · ' + escHtml(rec.code || '') + ' ' + escHtml(rec.name || '')));
    var $collapse = $('<button type="button" class="secondary analysis-collapse-btn"/>').text('收起');
    $collapse.on('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      collapseTpPoolAnalysis();
    });
    $head.append($collapse);
    $panel.append($head);
    $panel.append($('<p/>').html('<b>结论</b>：' + escHtml(rec.decision || '—')));
    $panel.append($('<p/>').html('<b>摘要</b>：' + escHtml(rec.summary || '—')));
    if (rec.poolReason) {
      $panel.append($('<p class="hint"/>').text('入池备注：' + rec.poolReason));
    }
    var $metrics = $('<div class="metrics tp-analysis-metrics"/>');
    function chip(label, val) {
      return $('<span class="metric"/>').html('<em>' + label + '</em><b>' + escHtml(val == null ? '—' : String(val)) + '</b>');
    }
    $metrics.append(chip('综合分', rec.scoreLabel || rec.scorePct || fmtPoolScore(rec.score)))
      .append(chip('最大回撤', rec.maxDrawDownPct))
      .append(chip('胜率', rec.winRatePct))
      .append(chip('交易次数', rec.trades))
      .append(chip('收盘', rec.lastClose))
      .append(chip('MA5', rec.ma5))
      .append(chip('MA20', rec.ma20))
      .append(chip('RSI', rec.rsi14))
      .append(chip('ATR', rec.atr14))
      .append(chip('金叉可买', rec.canBuyNow ? '是' : '否'))
      .append(chip('入选依据', rec.recommendReason));
    $panel.append($metrics);
    $panel.append($('<p/>').html('<b>信号</b>：' + escHtml(rec.signal || '—')));
    if (rec.scannedAt) {
      $panel.append($('<p class="hint"/>').text('扫描时间：' + rec.scannedAt));
    }
    if (rec.enteredAt) {
      $panel.append($('<p class="hint"/>').text('入池时间：' + rec.enteredAt));
    }
    if (rec.reportCreatedAt) {
      $panel.append($('<p class="hint"/>').text('报告生成时间：' + rec.reportCreatedAt));
    }
    if (rec.reportId != null) {
      $panel.append($('<p class="hint"/>').text('报告ID：' + rec.reportId + (rec.fromDb ? '（已落库）' : '')));
    }
  }

  function showTpPoolAnalysis($tr) {
    var code = String($tr.attr('data-code') || '');
    var reportId = $tr.attr('data-report-id');
    var $panel = ensureTpAnalysisRow($tr);
    if (!code) {
      $panel.html('<p class="hint">无股票代码</p>');
      return;
    }
    $panel.attr('data-open-code', code).html('<p class="hint">加载分析中…</p>');
    var url = reportId
      ? '/api/stock/trade-pool/report/' + encodeURIComponent(reportId)
      : '/api/stock/trade-pool/' + encodeURIComponent(code) + '/analysis';
    $.getJSON(url)
      .done(function (rec) {
        if (!$panel.closest('tbody').length) return;
        if (String($panel.attr('data-open-code') || '') !== code) return;
        if (!$tr.hasClass('active')) return;
        renderTpPoolAnalysis(rec || {}, $panel);
      })
      .fail(function (xhr) {
        if (!$panel.closest('tbody').length) return;
        if (String($panel.attr('data-open-code') || '') !== code) return;
        // reportId 失效时回退按代码取分析
        if (reportId) {
          $.getJSON('/api/stock/trade-pool/' + encodeURIComponent(code) + '/analysis')
            .done(function (rec) {
              if (!$panel.closest('tbody').length) return;
              if (String($panel.attr('data-open-code') || '') !== code) return;
              if (!$tr.hasClass('active')) return;
              renderTpPoolAnalysis(rec || {}, $panel);
            })
            .fail(function (xhr2) {
              var msg2 = (xhr2.responseJSON && xhr2.responseJSON.message) || '加载分析失败';
              $panel.html('<p class="hint">' + escHtml(msg2) + '</p>');
            });
          return;
        }
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载分析失败';
        $panel.html('<p class="hint">' + escHtml(msg) + '</p>');
      });
  }

  function loadTradePoolManage() {
    collapseTpPoolAnalysis();
    $.getJSON('/api/stock/trade-pool').done(function (data) {
      var items = (data && data.items) || [];
      var maxFinal = data && data.maxFinal != null ? data.maxFinal : 30;
      var count = data && data.count != null ? data.count : items.length;
      setCountBadge('#sidePoolCount, #tpPoolBadge', count);
      $('#tpPoolHint').text('目标池 ' + count + ' / 上限 ' + maxFinal);
      applyTradePoolForBacktest(items);
      renderTpFunnel(data && data.lastScan);

      var $tb = $('#tpPoolBody').empty();
      if (!items.length) {
        $tb.html('<tr><td colspan="6" class="empty-state">目标池为空，可点「扫描更新」或在运维中心开启 pool-rebuild</td></tr>');
      } else {
        items.forEach(function (it) {
          poolNames[it.code] = it.name || it.code;
          var reason = it.reason || '';
          $tb.append(
            $('<tr class="tp-pool-row"/>')
              .attr('data-code', it.code)
              .attr('data-report-id', it.reportId != null ? it.reportId : '')
              .css('cursor', 'pointer')
              .html(
                '<td class="tp-col-code"><b>' + escHtml(it.code) + '</b></td>'
                + '<td class="tp-col-name">' + escHtml(it.name || '') + '</td>'
                + '<td class="tp-col-score mono num">' + escHtml(fmtPoolScore(it.score)) + '</td>'
                + '<td class="tp-col-reason"><span class="tp-reason-text" title="' + escHtml(reason || '—') + '">'
                + escHtml(reason || '—') + '</span></td>'
                + '<td class="tp-col-time mono">' + escHtml(it.enteredAt || '—') + '</td>'
                + '<td class="tp-col-op"><button type="button" class="tp-remove" data-code="'
                + escHtml(it.code) + '" title="移出目标池（不停仓、不卖出）">移出</button></td>'
              )
          );
        });
      }
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载失败';
      $('#tpPoolBody').html('<tr><td colspan="6" class="empty-state">' + escHtml(msg) + '</td></tr>');
      renderTpFunnel(null);
      toast(msg, 'err');
    });
  }

  function loadPool() {
    $.getJSON('/api/stock/pool', function (list) {
      var codes = [];
      universeList = [];
      (list || []).forEach(function (item) {
        var code = typeof item === 'string' ? item : item.code;
        var name = typeof item === 'string' ? item : (item.name || item.code);
        if (!code) return;
        poolNames[code] = name;
        universeList.push({ code: code, name: name });
        codes.push(code);
      });
      refreshUniverseCounts();
      renderStockPicker('pool');
      if (codes.length && !activePoolCode) {
        openPoolStock(codes[0]);
      }
    }).fail(function (xhr) {
      var msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || '股票池加载失败';
      toast(msg, 'err');
    }).always(function () {
      // 个股/组合回测选股只读目标池（与行情全市场解耦）
      $.getJSON('/api/stock/trade-pool').done(function (data) {
        var items = (data && data.items) || [];
        var n = data && data.count != null ? data.count : items.length;
        setCountBadge('#sidePoolCount', n || 0);
        applyTradePoolForBacktest(items);
      }).fail(function (xhr) {
        var msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || '目标池加载失败';
        toast(msg, 'err');
        applyTradePoolForBacktest([]);
      });
    });
  }

  function selectSingleStock(code, options) {
    options = options || {};
    if (!code) return;
    var prev = singleCode;
    var sameCode = prev === code;
    singleCode = code;
    currentCode = code;
    $('#stockCode').val(code);
    $('#singleSelectedCode').text(code);
    $('#singleSelectedName').text(poolNames[code] || '');
    $('#singleSelectedBar').toggleClass('empty', false);
    syncSinglePoolSelect(code);

    var period = singlePeriods[code] || 'DAY';
    singlePeriods[code] = period;
    $('#barPeriod').val(period);

    $('#singleStockResults li').removeClass('active');
    var $active = $('#singleStockResults li[data-code="' + code + '"]').addClass('active');
    // 搜索建议选中后收起，避免挡住参数区
    if (!options.keepSuggest) {
      $('#singleStockQ').val('');
      $('#singleStockResults').empty().prop('hidden', true);
      $('#singleStockMatchHint').text(
        (tradePoolList || []).length
          ? ('池内 ' + (tradePoolList || []).length + ' 只 · 下拉直选或输入筛选')
          : '目标池为空'
      );
    } else {
      try {
        if ($active.length && $active[0].scrollIntoView) {
          $active[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
      } catch (e) {}
    }

    // 切换标的时清掉上一只的回测展示，避免串单
    if (prev && prev !== code) {
      $('#btMetrics').html('<span class="hint">已切换至 <b>' + code + '</b>，点击「执行回测」查看结果</span>');
      clearTradeResult();
      clearSingleEquityChart();
      if (lastBacktestCode !== code) {
        lastSignalMarks = null;
        lastSignalPayload = null;
        try { signalChart.clear(); } catch (e2) {}
      }
      if (!options.silent) {
        toast('已选择 ' + code + (poolNames[code] ? ' · ' + poolNames[code] : ''), 'info');
      }
    }
    // 选中后自动加载基础K线，补齐拆分后丢失的行情信息
    if (!options.skipKline) {
      loadSingleKline({ silent: true });
    }
    // 同标的重复进入时跳过历史刷新，避免把已展开的分析冲掉；无行数据时仍加载
    if (options.forceHistory || (!options.skipHistory && (!sameCode || !$('#singleHistoryBody tr.history-row').length))) {
      loadSingleHistory(code);
    }
  }

  function buildCandleOption(bars, indicators, marks) {
    var pal = chartPalette();
    var cats = [];
    var ohlc = [];
    (bars || []).forEach(function (b) {
      cats.push(b.barBegin);
      ohlc.push([+b.open, +b.close, +b.low, +b.high]);
    });
    var ma5 = (indicators && indicators.ma5) || [];
    var ma20 = (indicators && indicators.ma20) || [];
    var up = (indicators && indicators.bollUpper) || [];
    var mid = (indicators && indicators.bollMid) || [];
    var low = (indicators && indicators.bollLower) || [];
    var rsi = (indicators && indicators.rsi14) || [];

    var series = [
      { name: 'K', type: 'candlestick', data: ohlc, xAxisIndex: 0, yAxisIndex: 0,
        itemStyle: { color: pal.sell, color0: pal.buy, borderColor: pal.sell, borderColor0: pal.buy } },
      { name: 'MA5', type: 'line', data: ma5, showSymbol: false, xAxisIndex: 0, yAxisIndex: 0, lineStyle: { width: 1.2, color: pal.warn } },
      { name: 'MA20', type: 'line', data: ma20, showSymbol: false, xAxisIndex: 0, yAxisIndex: 0, lineStyle: { width: 1.2, color: pal.accent } },
      { name: 'BOLL上', type: 'line', data: up, showSymbol: false, xAxisIndex: 0, yAxisIndex: 0, lineStyle: { width: 1, type: 'dashed', color: pal.muted } },
      { name: 'BOLL中', type: 'line', data: mid, showSymbol: false, xAxisIndex: 0, yAxisIndex: 0, lineStyle: { width: 1, type: 'dashed', color: pal.muted } },
      { name: 'BOLL下', type: 'line', data: low, showSymbol: false, xAxisIndex: 0, yAxisIndex: 0, lineStyle: { width: 1, type: 'dashed', color: pal.muted } },
      { name: 'RSI', type: 'line', data: rsi, showSymbol: false, xAxisIndex: 1, yAxisIndex: 1, lineStyle: { width: 1, color: pal.warn } }
    ];

    if (marks) {
      series.push({
        name: '买入',
        type: 'scatter',
        data: (marks.buy || []).map(function (m) { return [m.time, +m.price]; }),
        symbol: 'triangle',
        symbolSize: 12,
        itemStyle: { color: pal.buy },
        xAxisIndex: 0,
        yAxisIndex: 0
      });
      series.push({
        name: '卖出',
        type: 'scatter',
        data: (marks.sell || []).map(function (m) { return [m.time, +m.price]; }),
        symbol: 'triangle',
        symbolRotate: 180,
        symbolSize: 12,
        itemStyle: { color: pal.sell },
        xAxisIndex: 0,
        yAxisIndex: 0
      });
    }

    return {
      backgroundColor: 'transparent',
      animation: false,
      legend: { textStyle: { color: pal.muted }, top: 0 },
      tooltip: { trigger: 'axis' },
      axisPointer: { link: [{ xAxisIndex: [0, 1] }] },
      grid: [
        { left: 50, right: 20, top: 36, height: '58%' },
        { left: 50, right: 20, top: '78%', height: '14%' }
      ],
      xAxis: [
        { type: 'category', data: cats, gridIndex: 0, axisLabel: { show: false }, axisLine: { lineStyle: { color: pal.border } } },
        { type: 'category', data: cats, gridIndex: 1, axisLabel: { color: pal.muted, fontSize: 10 }, axisLine: { lineStyle: { color: pal.border } } }
      ],
      yAxis: [
        { scale: true, gridIndex: 0, splitLine: { lineStyle: { color: pal.split } }, axisLabel: { color: pal.muted } },
        { scale: true, gridIndex: 1, min: 0, max: 100, splitLine: { lineStyle: { color: pal.split } }, axisLabel: { color: pal.muted } }
      ],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0, 1], start: 60, end: 100 },
        { type: 'slider', xAxisIndex: [0, 1], start: 60, end: 100, height: 18, bottom: 4 }
      ],
      series: series
    };
  }

  function renderBarTable(bars, tableBodyId) {
    var $tb = $('#' + (tableBodyId || 'barTableBody')).empty();
    var rows = (bars || []).slice(-40).reverse();
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="6" class="empty-state"/>').text('暂无K线数据')));
      return;
    }
    rows.forEach(function (b) {
      $tb.append($('<tr/>')
        .append($('<td/>').text(b.barBegin))
        .append($('<td/>').text(num(b.open)))
        .append($('<td/>').text(num(b.high)))
        .append($('<td/>').text(num(b.low)))
        .append($('<td/>').text(num(b.close)))
        .append($('<td/>').text(b.volume)));
    });
  }

  function singleRangeParams() {
    var backStart = ($('#singleBackStart').val() || '').trim();
    var backEnd = ($('#singleBackEnd').val() || '').trim();
    var params = {};
    if (backStart) params.start = backStart;
    if (backEnd) params.end = backEnd;
    return params;
  }

  function loadSingleKline(options) {
    options = options || {};
    var code = ($('#stockCode').val() || singleCode || '').trim();
    if (!code) {
      if (!options.silent) toast('请先选择股票', 'err');
      return;
    }
    var period = $('#barPeriod').val() || 'DAY';
    singlePeriods[code] = period;
    var params = $.extend({ code: code, period: period }, singleRangeParams());
    $('#singleKlineMeta').text('加载中...');
    var $btn = $('#btnLoadKline');
    withLoading($btn, $.getJSON('/api/kline', params)
      .done(function (resp) {
        lastBars = resp.bars || [];
        lastSingleKlinePayload = { bars: resp.bars, indicators: resp.indicators, code: code, period: period };
        $('#singleKlineMeta').html(
          '表<b>' + (resp.table || '-') + '</b>' +
          ' 周期<b>' + (resp.period || period) + '</b>' +
          ' 全量<b>' + (resp.total || 0) + '</b>' +
          ' 展示<b>' + (resp.returned || 0) + '</b>根'
        );
        singleBaseChart.setOption(buildCandleOption(resp.bars, resp.indicators, null), true);
        // 尚无回测信号时，信号图先展示同区间行情，避免空白
        if (!lastSignalMarks || lastBacktestCode !== code) {
          signalChart.setOption(buildCandleOption(resp.bars, resp.indicators, null), true);
        }
        renderBarTable(resp.bars, 'singleBarTableBody');
        resizeCharts();
        if (!options.silent) toast('K线已加载', 'ok');
      })
      .fail(function () {
        $('#singleKlineMeta').text('加载失败');
        toast('K线加载失败', 'err');
      }));
  }

  function loadPoolKline(code) {
    if (!code) return;
    var tab = getPoolTab(code);
    var period = (tab && tab.period) || $('#poolPeriod').val() || 'DAY';
    if (tab) {
      tab.period = period;
      $('#poolPeriod').val(period);
    }
    currentPeriod = period;
    $('#poolMeta').text('加载中...');
    withLoading($('#btnPoolRefresh'), $.getJSON('/api/kline', { code: code, period: period })
      .done(function (resp) {
        lastBars = resp.bars || [];
        $('#poolMeta').html(
          '<b>' + code + '</b> ' + (poolNames[code] || '') +
          ' · 表<b>' + (resp.table || '-') + '</b>' +
          ' 周期<b>' + (resp.period || period) + '</b>' +
          ' 全量<b>' + (resp.total || 0) + '</b>' +
          ' 展示<b>' + (resp.returned || 0) + '</b>根'
        );
        lastKlinePayload = { bars: resp.bars, indicators: resp.indicators, code: code, period: period };
        baseChart.setOption(buildCandleOption(resp.bars, resp.indicators, null), true);
        renderBarTable(resp.bars);
        renderPoolTabs();
        resizeCharts();
      })
      .fail(function () {
        $('#poolMeta').text('加载失败');
        toast('K线加载失败', 'err');
      }));
  }

  function renderTradeTable(bt) {
    var trades = (bt && bt.trades) || [];
    var $tb = $('#tradeBody').empty();
    var $sum = $('#tradeSummary').empty().prop('hidden', true);
    if (!trades.length) {
      $tb.append($('<tr/>').append($('<td colspan="9" class="empty-state"/>').text('本次回测无成交记录')));
      return;
    }

    var pos = 0;
    var avgCost = 0;
    var realized = 0;
    var feeSum = 0;
    var buyVol = 0;
    var sellVol = 0;
    var buyCount = 0;
    var sellCount = 0;

    trades.forEach(function (t, idx) {
      var side = String(t.side || '').toUpperCase();
      var vol = Number(t.volume || 0);
      var price = Number(t.price || 0);
      var fee = Number(t.fee || 0);
      var amount = t.amount != null ? Number(t.amount) : price * vol;
      var time = t.tradeTime || '-';
      feeSum += fee;

      var pnlHtml = '<span class="tag-wait">—</span>';
      var sideHtml;
      if (side === 'BUY') {
        sideHtml = '<span class="tag-buy">买入</span>';
        buyVol += vol;
        buyCount++;
        avgCost = pos <= 0 ? price : (avgCost * pos + price * vol) / (pos + vol);
        pos += vol;
        pnlHtml = '<span class="tag-wait">建仓</span>';
      } else {
        sideHtml = '<span class="tag-sell">卖出</span>';
        sellVol += vol;
        sellCount++;
        var gross = (price - avgCost) * vol;
        var net = gross - fee;
        realized += net;
        pos = Math.max(0, pos - vol);
        if (pos === 0) avgCost = 0;
        var cls = net >= 0 ? 'pnl-pos' : 'pnl-neg';
        pnlHtml = '<span class="' + cls + '">' + (net >= 0 ? '+' : '') + num(net) + '</span>';
      }

      $tb.append($('<tr/>')
        .append($('<td/>').text(idx + 1))
        .append($('<td/>').text(time))
        .append($('<td/>').html(sideHtml))
        .append($('<td/>').text(num(price)))
        .append($('<td/>').text(vol))
        .append($('<td/>').text(num(amount)))
        .append($('<td/>').text(num(fee)))
        .append($('<td/>').html(pnlHtml))
        .append($('<td/>').text(pos)));
    });

    var initCap = bt.initCapital != null ? Number(bt.initCapital) : Number($('#initCapital').val() || 0);
    var finalAsset = bt.finalAsset != null ? Number(bt.finalAsset) : initCap;
    var profit = finalAsset - initCap;
    var profitCls = profit >= 0 ? 'pnl-pos' : 'pnl-neg';
    var profitText = (profit >= 0 ? '+' : '') + num(profit);
    var rateText = pctFine(bt.totalRate);
    var realizedCls = realized >= 0 ? 'pnl-pos' : 'pnl-neg';
    var realizedText = (realized >= 0 ? '+' : '') + num(realized);

    $sum.prop('hidden', false).html(
      '<div class="result-hero">' +
        '<div class="result-hero-card">' +
          '<div class="label">总盈亏（期末 − 初始）</div>' +
          '<div class="value ' + profitCls + '">' + profitText + '</div>' +
          '<div class="sub">收益率 ' + rateText + '</div>' +
        '</div>' +
        '<div class="result-hero-card">' +
          '<div class="label">期末资产</div>' +
          '<div class="value">' + num(finalAsset) + '</div>' +
          '<div class="sub">约 ' + formatCapitalCn(finalAsset) + '</div>' +
        '</div>' +
      '</div>' +
      '<div class="result-groups">' +
        '<div class="result-group">' +
          '<div class="result-group-title">资金</div>' +
          '<div class="result-kv"><span class="label">初始资金</span><span class="value">' + num(initCap) + '<span class="cn">(' + formatCapitalCn(initCap) + ')</span></span></div>' +
          '<div class="result-kv"><span class="label">期末资产</span><span class="value">' + num(finalAsset) + '</span></div>' +
          '<div class="result-kv"><span class="label">总盈亏</span><span class="value ' + profitCls + '">' + profitText + '</span></div>' +
          '<div class="result-kv"><span class="label">收益率</span><span class="value ' + profitCls + '">' + rateText + '</span></div>' +
        '</div>' +
        '<div class="result-group">' +
          '<div class="result-group-title">风险与胜率</div>' +
          '<div class="result-kv"><span class="label">最大回撤</span><span class="value">' + pct(bt.maxDrawDown) + '</span></div>' +
          '<div class="result-kv"><span class="label">胜率</span><span class="value">' + pct(bt.winRate) + '</span></div>' +
          '<div class="result-kv"><span class="label">完整回合</span><span class="value">' + (sellCount || 0) + ' 次卖出</span></div>' +
        '</div>' +
        '<div class="result-group">' +
          '<div class="result-group-title">成交概况</div>' +
          '<div class="result-kv"><span class="label">成交笔数</span><span class="value">' + trades.length + '（买' + buyCount + ' / 卖' + sellCount + '）</span></div>' +
          '<div class="result-kv"><span class="label">买入量 / 卖出量</span><span class="value">' + buyVol + ' / ' + sellVol + ' 股</span></div>' +
          '<div class="result-kv"><span class="label">费用合计</span><span class="value">' + num(feeSum) + '</span></div>' +
          '<div class="result-kv"><span class="label">卖出已实现盈亏</span><span class="value ' + realizedCls + '">' + realizedText + '</span></div>' +
          (pos > 0
            ? '<div class="result-kv"><span class="label">期末仍持仓</span><span class="value">' + pos + ' 股</span></div>'
            : '<div class="result-kv"><span class="label">期末持仓</span><span class="value">已清仓</span></div>') +
        '</div>' +
      '</div>' +
      '<p class="result-note">说明：总盈亏按账户期末资产计算；「卖出已实现盈亏」按卖出时相对持仓成本估算（已扣该笔卖出费用），买入行记为建仓不加盈亏。</p>'
    );
  }

  function clearTradeResult() {
    $('#tradeBody').html('<tr><td colspan="9" class="empty-state">执行回测后显示买卖明细</td></tr>');
    $('#tradeSummary').empty().prop('hidden', true);
    setSingleResultPanelsVisible(false);
  }

  function formatRange(start, end) {
    if (!start && !end) return '全量';
    return (start || '…') + ' ~ ' + (end || '…');
  }

  /** 历史成交汇总；缺字段时从 trades 回填 */
  function resolveTradeStats(r) {
    var s = r && r.tradeStats ? r.tradeStats : null;
    if (s && (s.buyCount != null || s.sellCount != null)) {
      return s;
    }
    var buyCount = 0, sellCount = 0, buyShares = 0, sellShares = 0;
    var buyAmount = 0, sellAmount = 0, totalFee = 0;
    (r && r.trades || []).forEach(function (t) {
      var vol = Number(t.volume || 0);
      var amt = Number(t.amount || 0);
      var fee = Number(t.fee || 0);
      totalFee += fee;
      var side = (t.side || '').toUpperCase();
      if (side === 'BUY') {
        buyCount++;
        buyShares += vol;
        buyAmount += amt;
      } else if (side === 'SELL') {
        sellCount++;
        sellShares += vol;
        sellAmount += amt;
      }
    });
    var init = Number(r && r.initCapital || 0);
    var fin = Number(r && r.finalAsset != null ? r.finalAsset : init);
    return {
      buyCount: buyCount,
      sellCount: sellCount,
      buyShares: buyShares,
      sellShares: sellShares,
      buyLots: Math.floor(buyShares / 100),
      sellLots: Math.floor(sellShares / 100),
      buyAmount: buyAmount,
      sellAmount: sellAmount,
      totalFee: totalFee,
      totalPnl: fin - init
    };
  }

  function pnlText(v) {
    var n = Number(v);
    if (isNaN(n)) return '-';
    var t = num(n);
    return n > 0 ? '+' + t : t;
  }

  function isAllSingleHistory() {
    return $('#chkAllSingleHistory').is(':checked');
  }

  function singleHistoryColSpan() {
    return isAllSingleHistory() ? 15 : 14;
  }

  function loadSingleHistory(code) {
    var $tb = $('#singleHistoryBody');
    collapseHistoryAnalysis($tb);
    var all = isAllSingleHistory();
    $('#singleHistoryHead .hist-code-col').prop('hidden', !all);
    if (!all && !code) {
      $tb.html('<tr><td colspan="14" class="empty-state">请先选择股票</td></tr>');
      return;
    }
    var params = all ? {} : { code: code };
    $.getJSON('/api/backtest/history', params)
      .done(function (rows) {
        renderSingleHistory(rows || [], all);
      })
      .fail(function () {
        $tb.html('<tr><td colspan="' + singleHistoryColSpan() + '" class="empty-state">加载历史失败</td></tr>');
        toast('个股回测历史加载失败', 'err');
      });
  }

  var HISTORY_COLSPAN = 14;

  function collapseHistoryAnalysis($tb) {
    if (!$tb || !$tb.length) return;
    $tb.find('tr.history-row').removeClass('active').removeAttr('data-expanded');
    $tb.find('tr.history-analysis-row').remove();
  }

  /** 在记录行正下方插入/复用分析展开行 */
  function ensureInlineAnalysisRow($tr, colSpan) {
    var id = String($tr.attr('data-id') || '');
    var $next = $tr.next('tr.history-analysis-row');
    if ($next.length && String($next.attr('data-for-id') || '') === id) {
      return $next.find('.analysis-detail-panel');
    }
    $tr.closest('tbody').find('tr.history-analysis-row').remove();
    var $row = $('<tr class="history-analysis-row"/>').attr('data-for-id', id);
    var $cell = $('<td class="history-analysis-cell"/>').attr('colspan', colSpan || HISTORY_COLSPAN);
    var $panel = $('<div class="knowledge-body analysis-detail-panel"/>').attr('data-open-id', id);
    $cell.append($panel);
    $row.append($cell);
    $tr.after($row);
    try {
      $row[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    } catch (e) {}
    return $panel;
  }

  /** 回测分析摘要 + 事件列表（字段经 escHtml，避免注入） */
  function appendEscapedAnalysisBody($panel, summary, events) {
    $panel.append($('<p/>').html('<b>摘要</b>：' + escHtml(summary || '-')));
    events = events || [];
    if (!events.length) {
      $panel.append($('<p class="hint"/>').text('无事件明细'));
      return;
    }
    var $ul = $('<ol/>');
    events.forEach(function (ev) {
      var dataStr = '';
      if (ev.data && typeof ev.data === 'object') {
        var parts = [];
        Object.keys(ev.data).forEach(function (k) {
          parts.push(escHtml(k) + '=' + escHtml(String(ev.data[k])));
        });
        dataStr = parts.join('；');
      }
      var codeTxt = ev.stockCode ? ('[' + escHtml(ev.stockCode) + '] ') : '';
      $ul.append($('<li/>').html(
        '<b>' + escHtml(ev.time || '') + '</b> ' + codeTxt +
        '<code>' + escHtml(ev.type || '') + '</code> ' + escHtml(ev.title || '') +
        '<br/>原因：' + escHtml(ev.reason || '-') +
        (dataStr ? ('<br/><span class="hint">数据：' + dataStr + '</span>') : '')
      ));
    });
    $panel.append($ul);
  }

  function renderAnalysisDetail(rec, $panel, $tb) {
    var openId = $panel.attr('data-open-id');
    $panel.empty();
    if (openId) {
      $panel.attr('data-open-id', openId);
    }
    var $head = $('<div class="analysis-detail-head"/>');
    $head.append($('<span/>').html('<b>回测分析</b>'));
    var $collapse = $('<button type="button" class="secondary analysis-collapse-btn"/>').text('收起');
    $collapse.on('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      collapseHistoryAnalysis($tb);
    });
    $head.append($collapse);
    $panel.append($head);

    if (!rec || !rec.id) {
      $panel.append($('<p class="hint"/>').text('未找到与该回测记录对应的分析（旧记录可能无分析，请重新回测）。'));
      return;
    }
    appendEscapedAnalysisBody($panel, rec.summary, rec.events);
  }

  function showHistoryAnalysis(id, apiPath, $tr, $tb, colSpan) {
    id = String(id || '');
    var $panel = ensureInlineAnalysisRow($tr, colSpan);
    if (!id) {
      $panel.html('<p class="hint">该记录无 id，无法关联分析。</p>');
      return;
    }
    $panel.attr('data-open-id', id).html('<p class="hint">加载分析中…</p>');
    $.getJSON(apiPath, { id: id })
      .done(function (rec) {
        if (!$panel.closest('tbody').length) return;
        if (String($panel.attr('data-open-id') || '') !== id) return;
        if (!$tr.hasClass('active')) return;
        renderAnalysisDetail(rec, $panel, $tb);
      })
      .fail(function () {
        if (!$panel.closest('tbody').length) return;
        if (String($panel.attr('data-open-id') || '') !== id) return;
        $panel.attr('data-open-id', id).html('<p class="hint">加载分析失败</p>');
      });
  }

  /** 点击行：在该行下方展开分析；再点同一行收起；点其它行则切换到对应行下方 */
  function bindHistoryTableToggle($tb, apiPath, colSpan) {
    if (!$tb || !$tb.length) return;
    $tb.off('click.historyToggle').on('click.historyToggle', 'tr.history-row', function (e) {
      if ($(e.target).closest('button, a, input, label').length) return;
      var $tr = $(this);
      var id = String($tr.attr('data-id') || '');
      var expanded = $tr.hasClass('active') || $tr.attr('data-expanded') === '1';
      if (expanded) {
        collapseHistoryAnalysis($tb);
        return;
      }
      collapseHistoryAnalysis($tb);
      $tr.addClass('active').attr('data-expanded', '1');
      showHistoryAnalysis(id, apiPath, $tr, $tb, colSpan || HISTORY_COLSPAN);
    });
  }

  function renderSingleHistory(rows, showCode) {
    showCode = !!showCode;
    var colSpan = showCode ? 15 : 14;
    var $tb = $('#singleHistoryBody');
    bindHistoryTableToggle($tb, '/api/backtest/analysis', colSpan);
    $tb.empty();
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="' + colSpan + '" class="empty-state"/>')
        .text(showCode ? '暂无回测记录' : '暂无该股回测记录')));
      return;
    }
    rows.forEach(function (r) {
      var s = resolveTradeStats(r);
      var $tr = $('<tr class="history-row"/>').css('cursor', 'pointer').attr('data-id', r.id || '');
      if (showCode) {
        $tr.append($('<td/>').html('<b>' + escHtml(r.stockCode || '—') + '</b>'));
      }
      $tr.append($('<td/>').text(r.savedAt || '-'))
        .append($('<td/>').text(r.period || '-'))
        .append($('<td/>').text(formatRange(r.backStart, r.backEnd)))
        .append($('<td/>').text(num(r.initCapital)))
        .append($('<td/>').text(num(r.finalAsset)))
        .append($('<td/>').text(pnlText(s.totalPnl)))
        .append($('<td/>').text(pct(r.totalRate)))
        .append($('<td/>').text(pct(r.maxDrawDown)))
        .append($('<td/>').text(pct(r.winRate)))
        .append($('<td/>').text((s.buyCount || 0) + ' / ' + (s.sellCount || 0)))
        .append($('<td/>').text((s.buyLots || 0) + ' / ' + (s.sellLots || 0)))
        .append($('<td/>').text(num(s.buyAmount)))
        .append($('<td/>').text(num(s.sellAmount)))
        .append($('<td/>').text(num(s.totalFee)));
      $tb.append($tr);
    });
  }

  function clearSingleHistory() {
    var code = ($('#stockCode').val() || singleCode || '').trim();
    if (!code) {
      toast('请先选择股票', 'err');
      return;
    }
    if (!window.confirm('确认清除股票 ' + code + ' 的全部个股回测记录及对应分析？')) {
      return;
    }
    withLoading($('#btnClearSingleHistory'), $.ajax({
      url: '/api/backtest/history?code=' + encodeURIComponent(code),
      method: 'DELETE'
    }).done(function (resp) {
      toast('已清除 ' + (resp.removed || 0) + ' 条记录', 'ok');
      loadSingleHistory(code);
    }).fail(function () {
      toast('清除失败', 'err');
    }));
  }

  function loadPortfolioHistory() {
    collapseHistoryAnalysis($('#portfolioHistoryBody'));
    $.getJSON('/api/portfolio/history')
      .done(function (rows) {
        renderPortfolioHistory(rows || []);
      })
      .fail(function () {
        $('#portfolioHistoryBody').html('<tr><td colspan="14" class="empty-state">加载历史失败</td></tr>');
        toast('组合回测历史加载失败', 'err');
      });
  }

  function renderPortfolioHistory(rows) {
    var $tb = $('#portfolioHistoryBody');
    bindHistoryTableToggle($tb, '/api/portfolio/analysis', HISTORY_COLSPAN);
    $tb.empty();
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="14" class="empty-state"/>').text('暂无组合回测记录')));
      return;
    }
    rows.forEach(function (r) {
      var codes = (r.stockCodeList || []).join(',');
      var s = resolveTradeStats(r);
      var $tr = $('<tr class="history-row"/>').css('cursor', 'pointer').attr('data-id', r.id || '');
      $tr.append($('<td/>').text(r.savedAt || '-'))
        .append($('<td/>').text(codes || '-'))
        .append($('<td/>').text(formatRange(r.backStart, r.backEnd)))
        .append($('<td/>').text(num(r.initCapital)))
        .append($('<td/>').text(num(r.finalAsset)))
        .append($('<td/>').text(pnlText(s.totalPnl)))
        .append($('<td/>').text(pct(r.totalRate)))
        .append($('<td/>').text(pct(r.maxDrawDown)))
        .append($('<td/>').text(pct(r.winRate)))
        .append($('<td/>').text((s.buyCount || 0) + ' / ' + (s.sellCount || 0)))
        .append($('<td/>').text((s.buyLots || 0) + ' / ' + (s.sellLots || 0)))
        .append($('<td/>').text(num(s.buyAmount)))
        .append($('<td/>').text(num(s.sellAmount)))
        .append($('<td/>').text(num(s.totalFee)));
      $tb.append($tr);
    });
  }

  function clearPortfolioHistory() {
    if (!window.confirm('确认清除全部组合回测记录及对应分析？此操作不可恢复。')) {
      return;
    }
    withLoading($('#btnClearPortfolioHistory'), $.ajax({
      url: '/api/portfolio/history',
      method: 'DELETE'
    }).done(function (resp) {
      toast('已清除 ' + (resp.removed || 0) + ' 条记录', 'ok');
      loadPortfolioHistory();
    }).fail(function () {
      toast('清除失败', 'err');
    }));
  }

  /** 收集非空临时参数；无有效项返回 null。 */
  function collectRunOverrides(fieldMap) {
    var o = {};
    var n = 0;
    Object.keys(fieldMap || {}).forEach(function (key) {
      var v = ($(fieldMap[key]).val() || '').trim();
      if (v) {
        o[key] = v;
        n++;
      }
    });
    return n ? o : null;
  }

  function runBacktest() {
    var code = ($('#stockCode').val() || singleCode || '').trim();
    if (!code) {
      toast('请先在左侧选择股票', 'err');
      return;
    }
    var capital = $('#initCapital').val();
    var period = $('#barPeriod').val() || 'DAY';
    var backStart = ($('#singleBackStart').val() || '').trim();
    var backEnd = ($('#singleBackEnd').val() || '').trim();
    var strategyId = ($('#singleStrategyId').val() || '').trim();
    var sessionEngine = isSessionStrategyId(strategyId);
    if (sessionEngine) {
      period = 'MIN_1';
    }
    var params = { code: code, initCapital: capital, period: period };
    if (backStart) params.backStart = backStart;
    if (backEnd) params.backEnd = backEnd;
    if (strategyId) params.strategyId = strategyId;
    if (sessionEngine) params.engine = 'session';
    var ov = collectRunOverrides({
      feeRate: '#btOvFeeRate',
      atrStopMultiplier: '#btOvAtrStop',
      maxSinglePosition: '#btOvMaxSingle',
      maxHoldTradingDays: '#btOvMaxHold',
      rsiBuyMax: '#btOvRsiMax'
    });
    if (ov) params.paramOverrides = JSON.stringify(ov);
    withLoading($('#btnBacktest'), $.getJSON('/api/backtest/run', params)
      .done(function (bt) {
        var eng = bt.engine || (sessionEngine ? 'session' : 'classic');
        var deg = (bt.degradedBranches && bt.degradedBranches.length)
          ? bt.degradedBranches.join(',') : '-';
        var sessHint = eng === 'session'
          ? (' 引擎<b>session</b> 降级分支<b>' + deg + '</b>')
          : (' 引擎<b>' + eng + '</b>');
        $('#btMetrics').html(
          '股票<b>' + code + '</b>' +
          ' 期末资产<b>' + num(bt.finalAsset) + '</b>' +
          ' 收益率<b>' + pct(bt.totalRate) + '</b>' +
          ' 最大回撤<b>' + pct(bt.maxDrawDown) + '</b>' +
          ' 交易次数<b>' + (bt.totalTradeNum || 0) + '</b>' +
          ' 胜率<b>' + pct(bt.winRate) + '</b>' +
          sessHint +
          (bt.configFingerprint ? (' 指纹<code style="font-size:11px;">' + bt.configFingerprint + '</code>') : '')
        );
        if (eng === 'session' && bt.analysisSummary) {
          toast('会话回测完成 · ' + bt.analysisSummary, 'ok');
        } else {
          toast('回测完成 · 交易 ' + (bt.totalTradeNum || 0) + ' 笔'
            + (strategyId ? (' · 策略 ' + strategyId) : ''), 'ok');
        }
        lastBacktestCode = code;
        lastSignalMarks = { buy: bt.buyMarks || [], sell: bt.sellMarks || [] };
        lastSingleEquity = {
          equityTimes: bt.equityTimes || [],
          equityCurve: bt.equityCurve || []
        };
        setSingleResultPanelsVisible(true);
        renderEquityChart(lastSingleEquity, singleEquityChart);
        renderTradeTable(bt);
        renderSessionPanel(bt);
        loadSingleHistory(code);
        var klineParams = $.extend({ code: code, period: period }, singleRangeParams());
        $.getJSON('/api/kline', klineParams, function (resp) {
          lastSingleKlinePayload = { bars: resp.bars, indicators: resp.indicators, code: code, period: period };
          lastSignalPayload = { bars: resp.bars, indicators: resp.indicators };
          // 基础K线与信号图均标注买卖点
          singleBaseChart.setOption(buildCandleOption(resp.bars, resp.indicators, lastSignalMarks), true);
          signalChart.setOption(buildCandleOption(resp.bars, resp.indicators, lastSignalMarks), true);
          renderBarTable(resp.bars, 'singleBarTableBody');
          $('#singleKlineMeta').html(
            '表<b>' + (resp.table || '-') + '</b>' +
            ' 周期<b>' + (resp.period || period) + '</b>' +
            ' 全量<b>' + (resp.total || 0) + '</b>' +
            ' 展示<b>' + (resp.returned || 0) + '</b>根'
          );
          resizeCharts();
          try {
            document.getElementById('tradeSummary').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
          } catch (e) {}
        });
      })
      .fail(function (xhr) {
        var msg = '回测失败';
        try {
          if (xhr.responseJSON && xhr.responseJSON.message) {
            msg = xhr.responseJSON.message;
          } else if (xhr.responseText) {
            msg = String(xhr.responseText).substring(0, 220);
          }
        } catch (e) {}
        toast(msg, 'err');
      }));
  }

  function renderBatch(rows) {
    var only = $('#onlyCanBuy').is(':checked');
    var $tb = $('#batchBody').empty();
    var shown = 0;
    (rows || []).forEach(function (r) {
      if (only && !r.canBuyNow) return;
      shown++;
      var $tr = $('<tr/>');
      $tr.append($('<td/>').text(r.stockCode));
      $tr.append($('<td/>').text(num(r.lastClose)));
      $tr.append($('<td/>').text(pct(r.totalRate)));
      $tr.append($('<td/>').text(pct(r.maxDrawDown)));
      $tr.append($('<td/>').text(pct(r.winRate)));
      $tr.append($('<td/>').text(r.totalTradeNum || 0));
      $tr.append($('<td/>').html(r.canBuyNow ? '<span class="tag-buy">是</span>' : '<span class="tag-wait">否</span>'));
      $tr.append($('<td/>').text(num(r.ma5, 3)));
      $tr.append($('<td/>').text(num(r.ma20, 3)));
      $tr.append($('<td/>').text(num(r.rsi14, 2)));
      $tr.append($('<td/>').text(r.signalDesc || ''));
      var $btn = $('<button class="secondary"/>').text('回测').css({ padding: '4px 8px', fontSize: '12px' });
      $btn.on('click', function () {
        selectSingleStock(r.stockCode);
        showMode('single');
        runBacktest();
      });
      $tr.append($('<td/>').append($btn));
      $tb.append($tr);
    });
    if (!shown) {
      $tb.append($('<tr/>').append($('<td colspan="12" class="empty-state"/>').text('暂无扫描结果')));
    }
  }

  function runBatch() {
    focusSinglePanel('batch');
    $('#batchBody').html('<tr><td colspan="12" class="empty-state">扫描中...</td></tr>');
    withLoading($('#btnBatch'), $.getJSON('/api/batch/scanAllStock')
      .done(function (rows) {
        batchCache = rows || [];
        renderBatch(batchCache);
        toast('批量扫描完成 · ' + batchCache.length + ' 只', 'ok');
        focusSinglePanel('batch');
      })
      .fail(function () {
        $('#batchBody').html('<tr><td colspan="12" class="empty-state">扫描失败</td></tr>');
        toast('批量扫描失败', 'err');
      }));
  }

  function runPortfolio() {
    focusPortfolioPanel('workspace');
    syncPortfolioCodes();
    var codes = ($('#portfolioCodes').val() || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    if (!codes.length) {
      toast('请至少选择一只成分股', 'err');
      return;
    }
    var body = {
      backStart: $('#backStart').val() || null,
      backEnd: $('#backEnd').val() || null,
      initCapital: Number($('#pfInitCapital').val() || $('#initCapital').val() || 100000),
      stockCodeList: codes,
      feeRate: 0.0003,
      slipPoint: 0.001
    };
    var pfStrategyId = ($('#pfStrategyId').val() || '').trim();
    if (pfStrategyId) body.strategyId = pfStrategyId;
    var pfSession = isSessionStrategyId(pfStrategyId);
    if (pfSession) body.engine = 'session';
    var pfOv = collectRunOverrides({
      feeRate: '#pfOvFeeRate',
      atrStopMultiplier: '#pfOvAtrStop',
      maxSinglePosition: '#pfOvMaxSingle',
      rsiBuyMax: '#pfOvRsiMax'
    });
    if (pfOv) body.paramOverrides = pfOv;
    clearPortfolioResult();
    withLoading($('#btnPortfolio'), $.ajax({
      url: '/api/portfolio/run',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(body)
    }).done(function (pf) {
        var stats = resolveTradeStats(pf);
        var corr = pf.correlation || {};
        var corrText = corr.avgCorrelation == null ? ''
          : (' 相关均值<b>' + num(corr.avgCorrelation, 2) + '</b>'
            + (corr.warn ? '<span class="pnl-neg">（高相关告警）</span>' : ''));
        var eng = pf.engine || (pfSession ? 'session' : 'classic');
        var st = pf.sessionBranchStats || {};
        var engText = eng === 'session'
          ? (' 引擎<b>session</b>（共享资金池'
            + (st.fillMode ? ' · ' + st.fillMode : '')
            + (st.halted ? ' · 熔断' : '')
            + '）')
          : ' 引擎<b>' + eng + '</b>';
        $('#pfMetrics').html(
          '成分股<b>' + codes.length + '</b>' +
          ' 期末资产<b>' + num(pf.finalAsset) + '</b>' +
          ' 总盈亏<b>' + pnlText(stats.totalPnl) + '</b>' +
          ' 收益率<b>' + pct(pf.totalRate) + '</b>' +
          ' 最大回撤<b>' + pct(pf.maxDrawDown) + '</b>' +
          ' 买/卖<b>' + (stats.buyCount || 0) + '/' + (stats.sellCount || 0) + '</b>' +
          ' 胜率<b>' + pct(pf.winRate) + '</b>' + engText + corrText
        );
        lastEquity = pf;
        setPortfolioResultPanelsVisible(true);
        renderPortfolioSessionPanel(pf);
        renderEquityChart(pf);
        renderPortfolioTradeTable(pf);
        renderPortfolioStockBreakdown(pf);
        toast('组合回测完成 · 成交 ' + ((pf.trades || []).length) + ' 笔'
          + (pfStrategyId ? (' · 策略 ' + pfStrategyId) : ''), 'ok');
        loadPortfolioHistory();
        try {
          document.getElementById('pfTradeSummary').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } catch (e) {}
        resizeCharts();
      }).fail(function () {
        setPortfolioResultPanelsVisible(false);
        toast('组合回测失败', 'err');
      }));
  }

  var knowledgeTopics = [
    { id: 'app', group: 'app', title: '系统概述', src: '/docs/app.html?v=20260808-shell-dash' },
    { id: 'readme', group: 'app', title: '项目 README', src: '/api/docs/readme' },
    { id: 'rules', group: 'app', title: '交易规则', src: '/docs/rules.html?v=20260808-shell-dash' },
    { id: 'memo', group: 'app', title: '能力与待办', src: '/docs/memo.html?v=20260808-plan' },
    { id: 'kuangrui', group: 'kuangrui', title: '宽睿文档梳理', src: '/docs/kuangrui.html?v=20260808-plan' },
    { id: 'ashare', group: 'stock', section: 'base', title: 'A股基础', src: '/docs/ashare.html?v=20260720-nav-rename',
      related: [{ key: 'pool', label: '行情浏览' }, { key: 'single', label: '个股回测' }] },
    { id: 'session', group: 'stock', section: 'base', title: '交易时间', src: '/docs/session.html?v=20260720-nav-rename',
      related: [{ key: 'pool', label: '行情浏览' }, { key: 'schedule', label: '运维中心' }] },
    { id: 'kline', group: 'stock', section: 'base', title: 'K线', src: '/docs/kline.html?v=20260720-nav-rename',
      related: [{ key: 'pool', label: '行情浏览' }, { key: 'single', label: '个股回测' }] },
    { id: 'ma', group: 'stock', section: 'indicator', title: '均线与金叉', src: '/docs/ma.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'strategy', label: '策略总览' }] },
    { id: 'volume', group: 'stock', section: 'indicator', title: '成交量与放量', src: '/docs/volume.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'tradepool', label: '目标池' }] },
    { id: 'rsi', group: 'stock', section: 'indicator', title: 'RSI', src: '/docs/rsi.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'strategy', label: '策略总览' }] },
    { id: 'atr', group: 'stock', section: 'indicator', title: 'ATR', src: '/docs/atr.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'account', label: '账户概览' }] },
    { id: 'adx', group: 'stock', section: 'indicator', title: 'ADX', src: '/docs/adx.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'strategy', label: '策略总览' }] },
    { id: 'boll', group: 'stock', section: 'indicator', title: '布林带', src: '/docs/boll.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }] },
    { id: 'limit', group: 'stock', section: 'mechanism', title: '涨跌停与停牌', src: '/docs/limit.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'tradepool', label: '目标池' }] },
    { id: 'tplus1', group: 'stock', section: 'mechanism', title: 'T+1与整手', src: '/docs/tplus1.html?v=20260720-nav-rename',
      related: [{ key: 'account', label: '账户概览' }, { key: 'single', label: '个股回测' }] },
    { id: 'cost', group: 'stock', section: 'mechanism', title: '交易成本', src: '/docs/cost.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'portfolio', label: '组合回测' }] },
    { id: 'position', group: 'stock', section: 'mechanism', title: '仓位与金字塔', src: '/docs/position.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'account', label: '账户概览' }] },
    { id: 'risk', group: 'stock', section: 'mechanism', title: '账户风控', src: '/docs/risk.html?v=20260720-nav-rename',
      related: [{ key: 'account', label: '账户概览' }, { key: 'strategy', label: '策略总览' }] },
    { id: 'fill', group: 'stock', section: 'mechanism', title: '撮合时机', src: '/docs/fill.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { knowledge: 'rules', label: '交易规则' }] },
    { id: 'metrics', group: 'stock', section: 'eval', title: '夏普·回撤与胜率', src: '/docs/metrics.html?v=20260808-sharpe-metrics',
      related: [{ key: 'strategy', label: '策略总览' }, { key: 'single', label: '个股回测' }] },
    { id: 'backtest', group: 'stock', section: 'eval', title: '回测要点', src: '/docs/backtest.html?v=20260720-nav-rename',
      related: [{ key: 'single', label: '个股回测' }, { key: 'portfolio', label: '组合回测' }, { key: 'strategy', label: '策略总览' }] }
  ];
  var STOCK_KNOWLEDGE_SECTIONS = [
    { key: 'base', label: '基础' },
    { key: 'indicator', label: '指标' },
    { key: 'mechanism', label: '机制' },
    { key: 'eval', label: '评价' }
  ];
  var KNOWLEDGE_LINK_ALIASES = {
    'A股基础': 'ashare', 'A股': 'ashare',
    '交易时间': 'session',
    'K线': 'kline',
    '均线与金叉': 'ma', '金叉死叉': 'ma',
    '成交量与放量': 'volume', '成交量': 'volume', '放量': 'volume',
    'RSI': 'rsi',
    'ATR': 'atr',
    'ADX': 'adx',
    '布林带': 'boll',
    '涨跌停与停牌': 'limit', '涨跌停': 'limit',
    'T+1与整手': 'tplus1', 'T+1': 'tplus1',
    '交易成本': 'cost',
    '仓位与金字塔': 'position', '金字塔': 'position',
    '账户风控': 'risk',
    '撮合时机': 'fill', '撮合静默': 'fill',
    '夏普·回撤与胜率': 'metrics', '夏普': 'metrics',
    '回测要点': 'backtest',
    '交易规则': 'rules',
    '应用说明 → 交易规则': 'rules',
    '系统概述': 'app',
    '能力与待办': 'memo',
    '项目 README': 'readme', 'README': 'readme'
  };
  var knowledgeHtmlCache = {};
  var lastKnowledgeId = '';
  var HOME_SRC = '/docs/home.html?v=20260808-home-recent';
  var homePanelReady = false;
  var pendingHomeLead = null;
  var docsPdfBusy = false;

  function fetchTopicHtml(topic) {
    if (knowledgeHtmlCache[topic.src]) {
      return $.Deferred().resolve(knowledgeHtmlCache[topic.src]).promise();
    }
    return $.get(topic.src).then(function (html) {
      knowledgeHtmlCache[topic.src] = html;
      return html;
    });
  }

  function pad2(n) {
    return (n < 10 ? '0' : '') + n;
  }

  /**
   * 在页面内隔离宿主中渲染一段 HTML（同文档，避免 iframe 截图错乱）。
   * @returns {{ el: HTMLElement, destroy: Function }}
   */
  function mountPdfBlock(innerHtml) {
    var host = document.getElementById('pdfExportHost');
    if (!host) {
      host = document.createElement('div');
      host.id = 'pdfExportHost';
      document.body.appendChild(host);
    }
    host.className = 'pdf-export-host-active';
    host.innerHTML = '';
    var root = document.createElement('div');
    root.className = 'pdf-export-root pdf-export-isolate';
    root.innerHTML = innerHtml;
    host.appendChild(root);
    return {
      el: root,
      destroy: function () {
        try { host.innerHTML = ''; host.className = ''; } catch (e) {}
      }
    };
  }

  /** 创建空 jsPDF（先占一页，内容写完后删掉） */
  function createBlankJsPdf() {
    var holder = document.createElement('div');
    holder.setAttribute('data-pdf-dummy', '1');
    holder.style.cssText = 'position:fixed;left:0;top:0;width:48px;height:24px;padding:4px;background:#fff;color:#fff;z-index:-1;font-size:10px;';
    holder.textContent = '.';
    document.body.appendChild(holder);
    return Promise.resolve(
      html2pdf().set({
        margin: 0,
        image: { type: 'jpeg', quality: 0.2 },
        html2canvas: { scale: 1, backgroundColor: '#ffffff', logging: false },
        jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' }
      }).from(holder).toPdf().get('pdf')
    ).then(function (pdf) {
      try { document.body.removeChild(holder); } catch (e) {}
      return pdf;
    }, function (err) {
      try { document.body.removeChild(holder); } catch (e2) {}
      throw err;
    });
  }

  /**
   * 将一张长 canvas 按 A4 内容区切片，逐页 addImage（每片单独画，避免负偏移叠图）。
   */
  function appendCanvasAsNewPages(pdf, canvas, marginMm) {
    if (!pdf || !canvas || !canvas.width || !canvas.height) return;
    var margin = marginMm == null ? 12 : marginMm;
    var pdfW = pdf.internal.pageSize.getWidth();
    var pdfH = pdf.internal.pageSize.getHeight();
    var contentW = pdfW - margin * 2;
    var contentH = pdfH - margin * 2;
    if (contentW <= 0 || contentH <= 0) return;

    var pxPageH = Math.max(1, Math.floor(canvas.width * contentH / contentW));
    var y = 0;
    while (y < canvas.height) {
      var sliceH = Math.min(pxPageH, canvas.height - y);
      if (sliceH < 2) break;

      var slice = document.createElement('canvas');
      slice.width = canvas.width;
      slice.height = sliceH;
      var ctx = slice.getContext('2d');
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, slice.width, slice.height);
      ctx.drawImage(canvas, 0, y, canvas.width, sliceH, 0, 0, canvas.width, sliceH);

      var data = slice.toDataURL('image/jpeg', 0.95);
      var drawH = contentW * sliceH / canvas.width;
      pdf.addPage();
      pdf.addImage(data, 'JPEG', margin, margin, contentW, drawH);
      y += sliceH;
    }
  }

  function captureElementCanvas(el) {
    return Promise.resolve(
      html2pdf().set({
        html2canvas: {
          scale: 1.25,
          useCORS: true,
          logging: false,
          backgroundColor: '#ffffff',
          scrollX: 0,
          scrollY: 0,
          windowWidth: 800,
          allowTaint: true
        }
      }).from(el).toCanvas().get('canvas')
    );
  }

  /**
   * 按段：挂载 → 截图 → 切片写入 PDF。彻底避开 html2pdf 多段 from/addPage 叠页错乱。
   */
  function exportPdfBlocksSequential(blocks, filename) {
    return createBlankJsPdf().then(function (pdf) {
      var i = 0;
      function step() {
        if (i >= blocks.length) {
          try {
            // 删掉占位首页
            if (typeof pdf.deletePage === 'function' && pdf.internal.getNumberOfPages() > 1) {
              pdf.deletePage(1);
            }
          } catch (e) {}
          pdf.save(filename);
          var host = document.getElementById('pdfExportHost');
          if (host) { host.innerHTML = ''; host.className = ''; }
          return Promise.resolve();
        }
        var html = blocks[i++];
        var mounted = mountPdfBlock(html);
        // 强制布局
        void mounted.el.offsetHeight;
        return captureElementCanvas(mounted.el).then(function (canvas) {
          mounted.destroy();
          appendCanvasAsNewPages(pdf, canvas, 12);
          return step();
        }, function (err) {
          mounted.destroy();
          throw err;
        });
      }
      return step();
    });
  }

  /**
   * 将指定 group（stock|app）下全部知识文档合并导出 PDF。
   * 走服务端 iText（与 zulin/zsw-utils 同路线），不再用浏览器 html2pdf。
   */
  function downloadDocsPdf(group, $btn) {
    if (docsPdfBusy) {
      toast('正在生成 PDF，请稍候…', 'info');
      return;
    }
    if (group !== 'stock' && group !== 'app') {
      toast('无效的文档分组', 'err');
      return;
    }
    var packTitle = group === 'app' ? '应用说明' : '量化知识';
    var filename = 'QuantStock-' + packTitle + '.pdf';
    docsPdfBusy = true;
    var oldText = $btn && $btn.length ? $.trim($btn.text()) : '';
    if ($btn && $btn.length) {
      $btn.addClass('is-loading').prop('disabled', true).text('正在生成 PDF…');
    }
    toast('正在由服务端生成「' + packTitle + '」PDF…', 'info');

    function finishAlways() {
      docsPdfBusy = false;
      if ($btn && $btn.length) {
        $btn.removeClass('is-loading').prop('disabled', false)
          .text(oldText || (group === 'app' ? '下载应用说明全部文档 PDF' : '下载全部量化知识 PDF'));
      }
    }

    var headers = {};
    try {
      var k = localStorage.getItem('quant-api-key');
      if (k) headers['X-API-Key'] = k;
    } catch (e) {}
    fetch('/api/docs/pdf/' + encodeURIComponent(group), {
      method: 'GET',
      credentials: 'same-origin',
      headers: headers
    })
      .then(function (res) {
        if (!res.ok) {
          return res.text().then(function (t) {
            throw new Error(t || ('HTTP ' + res.status));
          });
        }
        return res.blob();
      })
      .then(function (blob) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        setTimeout(function () {
          try { document.body.removeChild(a); } catch (e) {}
          try { URL.revokeObjectURL(url); } catch (e2) {}
        }, 0);
        toast('已下载：' + filename, 'ok');
      })
      .catch(function (err) {
        console.error(err);
        toast('PDF 生成失败：' + (err && err.message ? err.message : '请重试'), 'err');
      })
      .then(finishAlways, finishAlways);
  }

  /** 去掉注释与不适于进 PDF 的控件 */
  function sanitizeDocHtmlForPdf(html) {
    var s = String(html || '').replace(/<!--[\s\S]*?-->/g, '');
    s = s.replace(/<button\b[\s\S]*?<\/button>/gi, '');
    return s.trim();
  }

  /** 按 h4 切段；长文切开以免单次 canvas 过高 */
  function splitHtmlByH4(html) {
    var s = String(html || '').trim();
    if (!s || s.length < 4000) return [s];
    var parts = s.split(/(?=<h4\b)/i).filter(function (p) { return $.trim(p); });
    return parts.length > 1 ? parts : [s];
  }

  /**
   * 首页已改为仪表盘卡片入口，不再同步底部横向按钮（避免三重导航）。
   */
  function syncHomeActionsFromNav() {
    $('#homeActions').remove();
  }

  function setHomeNavActive(on) {
    var $home = $('#btnNavHome');
    if (!$home.length) return;
    $home.toggleClass('open active', !!on);
    if (on) {
      $home.attr('aria-current', 'page');
    } else {
      $home.removeAttr('aria-current');
    }
  }

  function loadHomePanel(done) {
    if (homePanelReady) {
      syncHomeActionsFromNav();
      loadHomeDashboardMetrics();
      renderHomeRecent();
      if (typeof done === 'function') done();
      return;
    }
    $.get(HOME_SRC)
      .done(function (html) {
        $('#homeMount').html(html);
        homePanelReady = true;
        syncHomeActionsFromNav();
        loadHomeDashboardMetrics();
        renderHomeRecent();
        if (pendingHomeLead != null) {
          $('#homeLead').text(pendingHomeLead);
          pendingHomeLead = null;
        }
        if (typeof done === 'function') done();
      })
      .fail(function () {
        $('#homeMount').html('<section class="panel home-panel"><p class="home-lead">工作台加载失败：' + HOME_SRC + '</p></section>');
        if (typeof done === 'function') done();
      });
  }

  function setHomeLead(text) {
    if ($('#homeLead').length) {
      $('#homeLead').text(text);
    } else {
      pendingHomeLead = text;
    }
  }

  function findKnowledgeTopic(id) {
    for (var i = 0; i < knowledgeTopics.length; i++) {
      if (knowledgeTopics[i].id === id) return knowledgeTopics[i];
    }
    return null;
  }

  function knowledgeTopicsInGroup(group) {
    return knowledgeTopics.filter(function (t) { return t.group === group; });
  }

  function siblingKnowledgeTopic(id, delta) {
    var cur = findKnowledgeTopic(id);
    if (!cur || cur.group === 'kuangrui') return null;
    var list = knowledgeTopicsInGroup(cur.group);
    var idx = -1;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === id) { idx = i; break; }
    }
    if (idx < 0) return null;
    return list[idx + delta] || null;
  }

  function knowledgeSectionLabel(topic) {
    if (!topic) return '';
    if (topic.group === 'app') return '应用说明';
    if (topic.group === 'kuangrui') return '宽睿对接';
    for (var i = 0; i < STOCK_KNOWLEDGE_SECTIONS.length; i++) {
      if (STOCK_KNOWLEDGE_SECTIONS[i].key === topic.section) {
        return '量化知识 · ' + STOCK_KNOWLEDGE_SECTIONS[i].label;
      }
    }
    return '量化知识';
  }

  function initKnowledge() {
    var $stock = $('#stockKnowledgeMenu').empty();
    var $app = $('#appRelatedMenu').empty();
    STOCK_KNOWLEDGE_SECTIONS.forEach(function (sec) {
      var items = knowledgeTopics.filter(function (t) {
        return t.group === 'stock' && t.section === sec.key;
      });
      if (!items.length) return;
      $stock.append(
        $('<li class="side-nav-section" aria-hidden="true"/>').text(sec.label)
      );
      items.forEach(function (t) {
        $stock.append(
          $('<li role="button" tabindex="0"/>').text(t.title).attr('data-id', t.id)
        );
      });
    });
    knowledgeTopics.forEach(function (t) {
      if (t.group !== 'app') return;
      $app.append(
        $('<li role="button" tabindex="0"/>').text(t.title).attr('data-id', t.id)
      );
    });
  }

  function setSideNavOpen(bodyId) {
    // 兼容旧会话 / 面包屑：知识菜单改为 side-nav-body 包裹
    if (bodyId === 'stockKnowledgeMenu') bodyId = 'stockKnowledgeBody';
    if (bodyId === 'appRelatedMenu') bodyId = 'appRelatedBody';
    $('.side-nav-toggle').each(function () {
      var id = $(this).attr('data-body');
      // 必须是严格 boolean：jQuery toggleClass(cls, null/undefined) 会变成“切换”而非“关闭”
      var open = !!(bodyId && id === bodyId);
      $(this).attr('aria-expanded', open ? 'true' : 'false').toggleClass('open', open);
      var $panel = $('#' + id);
      if (!$panel.length) {
        return;
      }
      var el = $panel[0];
      if (open) {
        $panel.addClass('open');
        var h = el.scrollHeight || 0;
        $panel.css('max-height', '0');
        void el.offsetHeight;
        $panel.css('max-height', Math.max(h + 8, 48) + 'px');
      } else {
        if ($panel.hasClass('open')) {
          $panel.css('max-height', (el.scrollHeight || 0) + 'px');
          void el.offsetHeight;
        }
        $panel.css('max-height', '0');
        $panel.removeClass('open');
      }
    });
    try {
      if (bodyId) {
        localStorage.setItem(NAV_OPEN_BODY_KEY, bodyId);
      } else {
        localStorage.removeItem(NAV_OPEN_BODY_KEY);
      }
    } catch (e) {}
    if (bodyId) {
      setHomeNavActive(false);
    }
  }

  var SIDEBAR_COLLAPSE_KEY = 'quant-sidebar-collapsed';
  var NAV_OPEN_BODY_KEY = 'quant-nav-open-body';
  var NAV_SESSION_KEY = 'quant-nav-session';
  var NAV_RECENT_KEY = 'quant-nav-recent';
  var sidebarIconCollapsed = false;

  function setSidebarCollapsed(collapsed) {
    sidebarIconCollapsed = !!collapsed;
    $('.layout').toggleClass('sidebar-collapsed', sidebarIconCollapsed);
    var $btn = $('#btnSidebarCollapse');
    if ($btn.length) {
      $btn.attr('aria-expanded', sidebarIconCollapsed ? 'false' : 'true');
      $btn.attr('title', sidebarIconCollapsed ? '展开导航' : '收起导航');
      $btn.attr('aria-label', sidebarIconCollapsed ? '展开导航' : '收起导航');
    }
    try {
      localStorage.setItem(SIDEBAR_COLLAPSE_KEY, sidebarIconCollapsed ? '1' : '0');
    } catch (e) {}
    // 展开后按当前打开项重算高度
    if (!sidebarIconCollapsed) {
      var openId = $('.side-nav-toggle.open').first().attr('data-body');
      if (openId) {
        setSideNavOpen(openId);
      }
    }
  }

  /** 进入工作台/文档二级时自动展开侧栏，避免窄栏藏住二级菜单 */
  function ensureSidebarExpanded() {
    if (sidebarIconCollapsed || $('.layout').hasClass('sidebar-collapsed')) {
      setSidebarCollapsed(false);
    }
  }

  function initSidebarCollapse() {
    var saved = false;
    try {
      saved = localStorage.getItem(SIDEBAR_COLLAPSE_KEY) === '1';
    } catch (e) {}
    if (saved && window.matchMedia && !window.matchMedia('(max-width: 1100px)').matches) {
      setSidebarCollapsed(true);
    } else {
      setSidebarCollapsed(false);
    }
    $('#btnSidebarCollapse').on('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      setSidebarCollapsed(!sidebarIconCollapsed);
    });
  }

  function saveNavSession( partial) {
    var cur = {};
    try {
      cur = JSON.parse(localStorage.getItem(NAV_SESSION_KEY) || '{}') || {};
    } catch (e) {
      cur = {};
    }
    Object.keys(partial || {}).forEach(function (k) {
      cur[k] = partial[k];
    });
    try {
      localStorage.setItem(NAV_SESSION_KEY, JSON.stringify(cur));
    } catch (e) {}
  }

  function pushRecentVisit(item) {
    if (!item || !item.key) return;
    var list = [];
    try {
      list = JSON.parse(localStorage.getItem(NAV_RECENT_KEY) || '[]') || [];
    } catch (e) {
      list = [];
    }
    list = list.filter(function (x) { return x && x.key !== item.key; });
    list.unshift({
      key: item.key,
      label: item.label || item.key,
      mode: item.mode || '',
      panel: item.panel || '',
      at: Date.now()
    });
    if (list.length > 6) list = list.slice(0, 6);
    try {
      localStorage.setItem(NAV_RECENT_KEY, JSON.stringify(list));
    } catch (e) {}
    renderHomeRecent();
  }

  function formatMoney(v) {
    var n = Number(v);
    if (!isFinite(n)) return '—';
    var abs = Math.abs(n);
    var s = abs.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return (n < 0 ? '-' : '') + s;
  }

  function formatPct(v) {
    var n = Number(v);
    if (!isFinite(n)) return '';
    return (n * 100).toFixed(2) + '%';
  }

  function updateBreadcrumb(parts) {
    var $bc = $('#appBreadcrumb').empty();
    if (!$bc.length) return;
    $bc.append($('<button type="button" class="crumb-link"/>').attr('data-crumb', 'home').text('首页'));
    (parts || []).forEach(function (p) {
      $bc.append($('<span class="crumb-sep"/>').text('/'));
      if (p && p.bodyId) {
        $bc.append($('<button type="button" class="crumb-link"/>')
          .attr('data-crumb-body', p.bodyId)
          .text(p.label || ''));
      } else {
        $bc.append($('<span class="crumb-current"/>').text((p && p.label) || ''));
      }
    });
  }

  function breadcrumbForMode(mode, panelLabel) {
    var $btn = $('.side-nav-toggle[data-mode="' + mode + '"]').first();
    if (mode === 'doc') {
      $btn = $('.side-nav-toggle.open').first();
    }
    // 工作台分组名与首页叶子「工作台」撞名，面包屑不再插入分组段；扩展与文档仍保留
    var isDocs = $btn.closest('.nav-group').attr('data-group') === 'docs';
    var title = $.trim($btn.attr('data-intro-title') || $btn.find('.nav-label').text() || mode);
    var bodyId = $btn.attr('data-body') || '';
    var parts = [];
    if (isDocs) {
      parts.push({ label: '扩展与文档' });
    }
    parts.push({ label: title, bodyId: bodyId });
    if (panelLabel) parts.push({ label: panelLabel });
    updateBreadcrumb(parts);
  }

  function loadHomeDashboardMetrics() {
    $('#homeMetricEquity, #homeMetricDayPnl, #homeMetricStrategy, #homeMetricAlerts').text('—')
      .removeClass('is-up is-down is-flat');
    $('#homeMetricEquityHint').text('加载中…').removeClass('home-metric-hint--empty');
    $('#homeMetricDayPnlHint').text('加载中…').removeClass('home-metric-hint--empty');
    $('#homeMetricStrategyHint').text('加载中…').removeClass('home-metric-hint--empty');
    $('#homeMetricAlertsHint').text('加载中…').removeClass('home-metric-hint--empty');

    $.getJSON('/api/account/summary')
      .done(function (d) {
        d = d || {};
        var equityTxt = formatMoney(d.equity);
        $('#homeMetricEquity').text(equityTxt || '—');
        if (!equityTxt || equityTxt === '—') {
          $('#homeMetricEquityHint').text('暂无账户数据').addClass('home-metric-hint--empty');
        } else {
          $('#homeMetricEquityHint').text(d.source === 'LOCAL_SIM' ? '本地模拟账本' : (d.source || '账户'));
        }
        var day = d.dayPnl;
        var pct = formatPct(d.dayPnlPct);
        var dayTxt = formatMoney(day);
        if (dayTxt && dayTxt !== '—' && pct) dayTxt += '（' + pct + '）';
        $('#homeMetricDayPnl').text(dayTxt || '—');
        var prev = Number(d.prevCloseEquity);
        if (!dayTxt || dayTxt === '—') {
          $('#homeMetricDayPnlHint').text('暂无盈亏数据').addClass('home-metric-hint--empty');
          $('#homeMetricDayPnl').removeClass('is-up is-down').addClass('is-flat');
        } else {
          $('#homeMetricDayPnlHint').text(prev > 0 ? '相对昨收权益' : '暂无昨收基准');
          if (Number(day) > 0) $('#homeMetricDayPnl').addClass('is-up').removeClass('is-down is-flat');
          else if (Number(day) < 0) $('#homeMetricDayPnl').addClass('is-down').removeClass('is-up is-flat');
          else $('#homeMetricDayPnl').addClass('is-flat').removeClass('is-up is-down');
        }
      })
      .fail(function () {
        $('#homeMetricEquityHint').text('账户接口暂不可用').addClass('home-metric-hint--empty');
        $('#homeMetricDayPnlHint').text('账户接口暂不可用').addClass('home-metric-hint--empty');
        $('#homeMetricDayPnl').addClass('is-flat');
      });

    $.getJSON('/api/ops/strategies')
      .done(function (d) {
        d = d || {};
        var id = d.activeStrategy || '';
        var label = '';
        var list = d.strategies || [];
        for (var i = 0; i < list.length; i++) {
          if (list[i] && String(list[i].id || list[i].strategyId) === String(id)) {
            label = list[i].label || list[i].displayName || '';
            break;
          }
        }
        if (!label && id) label = id;
        $('#homeMetricStrategy').text(label || '—').attr('title', label || id || '');
        if (!id) {
          $('#homeMetricStrategyHint').text('未设置激活策略').addClass('home-metric-hint--empty');
        } else {
          $('#homeMetricStrategyHint').text(id === label ? '纸面激活' : id);
        }
      })
      .fail(function () {
        $('#homeMetricStrategyHint').text('策略接口暂不可用').addClass('home-metric-hint--empty');
      });

    $.getJSON('/api/ops/data-health/status')
      .done(function (st) {
        st = st || {};
        var r = st.result;
        if (!r && st.hasLastResult === false) {
          $('#homeMetricAlerts').text('0');
          $('#homeMetricAlertsHint').text('尚未执行覆盖检查').addClass('home-metric-hint--empty');
          return;
        }
        if (!r) {
          return $.getJSON('/api/ops/data-health').done(function (res) {
            applyHealthMetric(res);
          }).fail(function () {
            $('#homeMetricAlerts').text('0');
            $('#homeMetricAlertsHint').text('健康接口暂不可用').addClass('home-metric-hint--empty');
          });
        }
        applyHealthMetric(r);
      })
      .fail(function () {
        $.getJSON('/api/ops/data-health')
          .done(applyHealthMetric)
          .fail(function () {
            $('#homeMetricAlerts').text('0');
            $('#homeMetricAlertsHint').text('健康接口暂不可用').addClass('home-metric-hint--empty');
          });
      });
  }

  function applyHealthMetric(res) {
    res = res || {};
    var n = res.warnCount;
    if (n == null) n = (res.items && res.items.length) || 0;
    n = Number(n) || 0;
    $('#homeMetricAlerts').text(String(n));
    if (n === 0) {
      $('#homeMetricAlertsHint').text('暂无待处理').addClass('home-metric-hint--empty');
    } else {
      $('#homeMetricAlertsHint').text(res.asOf ? ('覆盖检查 · ' + String(res.asOf).slice(0, 16)) : (res.hint || '数据健康'));
    }
  }

  var HOME_RECENT_ICONS = {
    pool: '<path d="M4 19V9"/><path d="M10 19V5"/><path d="M16 19v-7"/><path d="M22 19V8"/><path d="M2 19h20"/>',
    single: '<path d="M4 18V10"/><path d="M10 18V6"/><path d="M16 18v-5"/><path d="M3 20h18"/><path d="M18 4l3 3-3 3"/>',
    portfolio: '<circle cx="8" cy="8" r="3.2"/><circle cx="16" cy="9" r="2.6"/><circle cx="12" cy="16.5" r="3"/>',
    tradepool: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="4.5"/>',
    account: '<rect x="3.5" y="7" width="17" height="12" rx="2.2"/><path d="M3.5 12h17"/>',
    schedule: '<circle cx="12" cy="12" r="3.2"/><path d="M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2"/>',
    'schedule-health': '<circle cx="12" cy="12" r="3.2"/><path d="M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2"/>',
    strategy: '<path d="M5 19V8.5L12 4l7 4.5V19"/><path d="M9 19v-6h6v6"/>',
    dbtables: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 9.5h16M9.5 4v16"/>',
    kuangrui: '<rect x="4" y="8.5" width="12" height="11" rx="2"/><path d="M8 8.5V7a2 2 0 0 1 2-2h7.5a2 2 0 0 1 2 2v7.5"/>',
    knowledge: '<path d="M6 4.5h9.5A2.5 2.5 0 0 1 18 7v13.5L12.5 17 7 20.5V7A2.5 2.5 0 0 1 9.5 4.5"/>',
    app: '<path d="M5 19V8.5L12 4l7 4.5V19"/>'
  };

  function homeRecentIconSvg(key) {
    var paths = HOME_RECENT_ICONS[key] || HOME_RECENT_ICONS.pool;
    return '<span class="home-recent-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + paths + '</svg></span>';
  }

  function renderHomeRecent() {
    var $section = $('#homeRecent');
    var $sectionList = $('#homeRecentList');
    var $inline = $('#homeRecentInline');
    var $inlineList = $('#homeRecentInlineList');
    if (!$sectionList.length && !$inlineList.length) return;
    var list = [];
    try {
      list = JSON.parse(localStorage.getItem(NAV_RECENT_KEY) || '[]') || [];
    } catch (e) {
      list = [];
    }
    list = list.slice(0, 5);
    if ($sectionList.length) $sectionList.empty();
    if ($inlineList.length) $inlineList.empty();
    if (!list.length) {
      if ($section.length) $section.prop('hidden', true);
      if ($inline.length) $inline.prop('hidden', true);
      return;
    }
    function appendItems($target) {
      list.forEach(function (it) {
        var key = it.key || '';
        $target.append($('<button type="button" class="home-recent-item"/>')
          .attr('data-recent-key', key)
          .html(homeRecentIconSvg(key) + '<span>' + (it.label || key) + '</span>'));
      });
    }
    // 不足 3 条：收进标题右上角，避免独占一整行；≥3 条用独立模块
    if (list.length < 3 && $inlineList.length) {
      appendItems($inlineList);
      if ($inline.length) $inline.prop('hidden', false);
      if ($section.length) $section.prop('hidden', true);
    } else {
      if ($sectionList.length) appendItems($sectionList);
      if ($section.length) $section.prop('hidden', false);
      if ($inline.length) $inline.prop('hidden', true);
    }
  }

  function enterWorkspaceByKey(key) {
    switch (String(key || '')) {
      case 'pool':
        showMode('pool');
        pushRecentVisit({ key: 'pool', label: '行情浏览', mode: 'pool' });
        break;
      case 'single':
        showMode('single', { panel: 'workspace' });
        pushRecentVisit({ key: 'single', label: '个股回测', mode: 'single', panel: 'workspace' });
        break;
      case 'portfolio':
        showMode('portfolio', { panel: 'workspace' });
        pushRecentVisit({ key: 'portfolio', label: '组合回测', mode: 'portfolio', panel: 'workspace' });
        break;
      case 'tradepool':
        showTradePool('pool');
        pushRecentVisit({ key: 'tradepool', label: '目标池', mode: 'tradepool', panel: 'pool' });
        break;
      case 'account':
        showMode('account', { panel: 'funds' });
        pushRecentVisit({ key: 'account', label: '账户概览', mode: 'account', panel: 'funds' });
        break;
      case 'schedule':
        showSchedulePanel('jobs');
        pushRecentVisit({ key: 'schedule', label: '运维中心', mode: 'schedule', panel: 'jobs' });
        break;
      case 'schedule-health':
      case 'health':
        showSchedulePanel('health');
        pushRecentVisit({ key: 'schedule-health', label: '数据健康', mode: 'schedule', panel: 'health' });
        break;
      case 'strategy':
        showStrategyEval();
        pushRecentVisit({ key: 'strategy', label: '策略管理', mode: 'strategy' });
        break;
      case 'dbtables':
        showMode('dbtables');
        pushRecentVisit({ key: 'dbtables', label: '数据表', mode: 'dbtables' });
        break;
      case 'kuangrui':
        showKuangruiPanel('overview');
        pushRecentVisit({ key: 'kuangrui', label: '宽睿对接', mode: 'kuangrui', panel: 'overview' });
        break;
      case 'knowledge':
        setSideNavOpen('stockKnowledgeBody');
        ensureSidebarExpanded();
        openKnowledge('ashare');
        pushRecentVisit({ key: 'knowledge', label: '量化知识', mode: 'doc', panel: 'ashare' });
        break;
      case 'app':
        setSideNavOpen('appRelatedBody');
        ensureSidebarExpanded();
        openKnowledge('app');
        pushRecentVisit({ key: 'app', label: '应用说明', mode: 'doc', panel: 'app' });
        break;
      default:
        break;
    }
  }

  function buildHeaderHelpMenu() {
    var $box = $('#headerHelpIntros').empty();
    $('.side-nav-toggle').each(function () {
      var $btn = $(this);
      var src = $btn.attr('data-intro');
      var title = $btn.attr('data-intro-title') || $.trim($btn.find('.nav-label').text());
      var bodyId = $btn.attr('data-body');
      if (!src || !title) return;
      $box.append($('<button type="button" class="header-help-item" role="menuitem"/>')
        .attr('data-help-intro', src)
        .attr('data-help-title', title)
        .attr('data-help-body', bodyId || '')
        .text(title));
    });
  }

  function initHeaderHelp() {
    buildHeaderHelpMenu();
    $('#btnHeaderHelp').on('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var open = $('#headerHelpMenu').prop('hidden');
      $('#headerHelpMenu').prop('hidden', !open);
      $(this).attr('aria-expanded', open ? 'true' : 'false');
    });
    $(document).on('click.headerHelp', function (e) {
      if (!$(e.target).closest('#headerHelp').length) {
        $('#headerHelpMenu').prop('hidden', true);
        $('#btnHeaderHelp').attr('aria-expanded', 'false');
      }
    });
    $('#headerHelpMenu').on('click', '[data-help-intro]', function () {
      var src = $(this).attr('data-help-intro');
      var title = $(this).attr('data-help-title') || '功能介绍';
      var bodyId = $(this).attr('data-help-body') || null;
      $('#headerHelpMenu').prop('hidden', true);
      $('#btnHeaderHelp').attr('aria-expanded', 'false');
      showNavIntro({
        bodyId: bodyId,
        title: title,
        src: src + (src.indexOf('?') >= 0 ? '&' : '?') + 'v=20260808-shell-dash'
      });
      updateBreadcrumb([{ label: '帮助' }, { label: title }]);
    });
    $('#headerHelpMenu').on('click', '[data-help-knowledge]', function () {
      var id = $(this).attr('data-help-knowledge');
      $('#headerHelpMenu').prop('hidden', true);
      $('#btnHeaderHelp').attr('aria-expanded', 'false');
      if (id === 'readme') openKnowledge('readme');
      else openKnowledge('app');
    });
  }

  var homeCollapsed = false;

  function setHomeCollapsed(collapsed) {
    homeCollapsed = !!collapsed;
    var onHome = !$('#viewHome').prop('hidden');
    $('#viewHome').toggleClass('home-collapsed', homeCollapsed);
    $('body').toggleClass('home-theme-peek', homeCollapsed && onHome);
    $('#btnExpandHome').prop('hidden', true);
  }

  function hideAllWorkspaceViews() {
    $('#viewHome, #viewNavIntro, #viewPool, #viewSingle, #viewPortfolio, #viewTradePool, #viewTpHistory, #viewDbTable, #viewSchedule, #viewStrategy, #viewDataHealth, #viewSysParams, #viewAcctFunds, #viewAcctPositions, #viewAcctOrders, #viewAcctCashflows, #viewAcctRiskLogs, #viewAcctRiskDash, #viewAcctPaperGap, #viewKuangruiOverview, #viewKuangruiAccount, #viewKuangruiOes, #viewKuangruiMds, #viewKuangruiOrder, #viewKuangruiDocs').prop('hidden', true);
    $('body').removeClass('home-theme-peek');
    $('#btnExpandHome').prop('hidden', true);
  }

  var lastAccountPanel = 'funds';

  function setAccountMenuActive(panel) {
    $('#accountMenu li').removeClass('active');
    if (panel) {
      $('#accountMenu li[data-account-panel="' + panel + '"]').addClass('active');
    }
  }

  var lastTpPanel = 'pool';
  var lastSchedulePanel = 'jobs';
  var lastKuangruiPanel = 'overview';
  var lastSinglePanel = 'workspace';
  var lastPortfolioPanel = 'workspace';
  var krOrderLive = false;
  var krOverviewGen = 0;

  function setSingleMenuActive(panel) {
    $('#singleMenu li').removeClass('active');
    if (panel) {
      $('#singleMenu li[data-single-panel="' + panel + '"]').addClass('active');
    }
  }

  function focusSinglePanel(panel) {
    panel = panel || lastSinglePanel || 'workspace';
    if (panel !== 'workspace' && panel !== 'batch' && panel !== 'history') panel = 'workspace';
    lastSinglePanel = panel;
    setSingleMenuActive(panel);
    if (panel === 'workspace') {
      setTimeout(function () {
        var $sel = $('#singlePoolSelect');
        if ($sel.length && (tradePoolList || []).length) {
          $sel.trigger('focus');
        } else {
          $('#singleStockQ').trigger('focus');
        }
      }, 80);
      // 工作台优先露出参数区底部动作 + K 线核心区，避免只看见顶部标题
      setTimeout(function () {
        var charts = document.getElementById('singlePanelCharts');
        if (charts && typeof charts.scrollIntoView === 'function') {
          try { charts.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); } catch (e2) {}
        }
      }, 100);
      return;
    }
    var id = panel === 'batch' ? 'singlePanelBatch' : 'singlePanelHistory';
    var el = document.getElementById(id);
    if (el && typeof el.scrollIntoView === 'function') {
      setTimeout(function () {
        try { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) {}
      }, 60);
    }
  }

  function setPortfolioMenuActive(panel) {
    $('#portfolioMenu li').removeClass('active');
    if (panel) {
      $('#portfolioMenu li[data-portfolio-panel="' + panel + '"]').addClass('active');
    }
  }

  function focusPortfolioPanel(panel) {
    panel = panel || lastPortfolioPanel || 'workspace';
    if (panel !== 'workspace' && panel !== 'history') panel = 'workspace';
    lastPortfolioPanel = panel;
    setPortfolioMenuActive(panel);
    var id = panel === 'history' ? 'pfPanelHistory' : 'pfPanelWorkspace';
    var el = document.getElementById(id);
    if (el && typeof el.scrollIntoView === 'function') {
      setTimeout(function () {
        try { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) {}
      }, 60);
    }
    if (panel === 'workspace') {
      setTimeout(function () { $('#pfStockQ').trigger('focus'); }, 80);
    } else if (panel === 'history') {
      loadPortfolioHistory();
    }
  }

  function setTradePoolMenuActive(panel) {
    $('#tradepoolMenu li').removeClass('active');
    if (panel) {
      $('#tradepoolMenu li[data-tp-panel="' + panel + '"]').addClass('active');
    }
  }

  function setScheduleMenuActive(panel) {
    $('#scheduleMenu li').removeClass('active');
    if (panel) {
      $('#scheduleMenu li[data-schedule-panel="' + panel + '"]').addClass('active');
    }
  }

  function showTradePool(panel) {
    panel = panel || lastTpPanel || 'pool';
    if (panel !== 'pool' && panel !== 'history') panel = 'pool';
    lastTpPanel = panel;
    lastWorkspaceMode = 'tradepool';
    ensureSidebarExpanded();
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    setSideNavOpen('tradepoolBody');
    setTradePoolMenuActive(panel);
    if (panel === 'history') {
      $('#viewTpHistory').prop('hidden', false);
      loadTpScanHistory();
    } else {
      $('#viewTradePool').prop('hidden', false);
      loadTradePoolManage();
    }
    breadcrumbForMode('tradepool', panel === 'history' ? '扫描历史' : '当前池');
    saveNavSession({ mode: 'tradepool', panel: panel });
    resizeCharts();
  }

  function setKuangruiMenuActive(panel) {
    $('#kuangruiMenu li').removeClass('active');
    if (panel) {
      $('#kuangruiMenu li[data-kuangrui-panel="' + panel + '"]').addClass('active');
    }
  }

  function showKuangruiPanel(panel) {
    panel = panel || lastKuangruiPanel || 'overview';
    if (panel !== 'overview' && panel !== 'account' && panel !== 'oes'
        && panel !== 'mds' && panel !== 'order' && panel !== 'docs') {
      panel = 'overview';
    }
    lastKuangruiPanel = panel;
    lastWorkspaceMode = 'kuangrui';
    ensureSidebarExpanded();
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    setSideNavOpen('kuangruiBody');
    setKuangruiMenuActive(panel);
    if (panel === 'account') {
      $('#viewKuangruiAccount').prop('hidden', false);
      ensureKrCopyControls('krAcc');
      markKrAccCard('current');
      loadKrAccountCurrent();
    } else if (panel === 'oes') {
      $('#viewKuangruiOes').prop('hidden', false);
      ensureKrCopyControls('krOes');
      ensureKrOesCards();
    } else if (panel === 'mds') {
      $('#viewKuangruiMds').prop('hidden', false);
      ensureKrCopyControls('krMds');
      ensureKrMdsCards();
    } else if (panel === 'order') {
      $('#viewKuangruiOrder').prop('hidden', false);
      ensureKrCopyControls('krOrder');
      setKrOrderTab(($('#viewKuangruiOrder .kr-order-tab.is-active').attr('data-kr-order-tab')) || 'place');
      refreshKrOrderGate();
    } else if (panel === 'docs') {
      $('#viewKuangruiDocs').prop('hidden', false);
      loadKrDocs(false);
    } else {
      $('#viewKuangruiOverview').prop('hidden', false);
      loadKrOverview();
    }
    breadcrumbForMode('kuangrui', panel === 'account' ? '账号登录'
      : (panel === 'oes' ? 'OES 只读' : (panel === 'mds' ? 'MDS 行情'
        : (panel === 'order' ? '报撤试单' : (panel === 'docs' ? '宽睿文档梳理' : '接入总览')))));
    saveNavSession({ mode: 'kuangrui', panel: panel });
    resizeCharts();
  }

  var KR_DOCS_SRC = '/docs/kuangrui.html?v=20260808-plan';

  function loadKrDocs(force) {
    var $body = $('#krDocsBody');
    if (!$body.length) return;
    if (!force && $body.attr('data-loaded') === KR_DOCS_SRC) return;
    var gen = String(Date.now());
    $body.attr('data-load-gen', gen).attr('data-loaded', '').html('<p class="hint">加载中…</p>');
    function render(html) {
      if ($body.attr('data-load-gen') !== gen) return;
      $body.attr('data-loaded', KR_DOCS_SRC).html(html || '<p class="hint">暂无内容</p>');
    }
    if (!force && knowledgeHtmlCache[KR_DOCS_SRC]) {
      render(knowledgeHtmlCache[KR_DOCS_SRC]);
      return;
    }
    $.get(KR_DOCS_SRC)
      .done(function (html) {
        knowledgeHtmlCache[KR_DOCS_SRC] = html;
        render(html);
      })
      .fail(function () {
        render('<p class="hint">文档加载失败：' + KR_DOCS_SRC + '</p>');
      });
  }

  function setKrOrderTab(tab) {
    tab = tab || 'place';
    if (tab !== 'place' && tab !== 'cancel' && tab !== 'cash') tab = 'place';
    var $view = $('#viewKuangruiOrder');
    $view.find('.kr-order-tab').removeClass('is-active').attr('aria-selected', 'false');
    $view.find('.kr-order-tab[data-kr-order-tab="' + tab + '"]').addClass('is-active').attr('aria-selected', 'true');
    $view.find('.kr-order-panel').prop('hidden', true);
    var $panel = $view.find('.kr-order-panel[data-kr-order-panel="' + tab + '"]').prop('hidden', false);
    krMarkActiveCard($panel.find('.kr-api-card').first());
  }

  function krPretty(obj) {
    try { return JSON.stringify(obj == null ? null : obj, null, 2); } catch (e) { return String(obj); }
  }

  /** 确保入参/出参旁有复制按钮；结果头不再放「复制出参」（避免重复）。 */
  function ensureKrCopyControls(prefix) {
    var $panel = $('#' + prefix + 'Result');
    if (!$panel.length) return;
    // 去掉结果头上的重复复制按钮（历史 HTML / 旧逻辑可能留下）
    $panel.find('.kr-result-head [data-kr-copy="' + prefix + 'Rsp"]').remove();
    var $headRow = $panel.find('.kr-result-head .kr-result-head-row');
    if ($headRow.length) {
      var $h4 = $headRow.children('h4').first();
      if ($h4.length) {
        $headRow.before($h4);
      }
      if ($headRow.children().length === 0) {
        $headRow.remove();
      }
    }
    [
      { id: prefix + 'Req', label: '入参' },
      { id: prefix + 'Rsp', label: '出参' }
    ].forEach(function (b) {
      var $pre = $('#' + b.id);
      if (!$pre.length) return;
      var $block = $pre.closest('.kr-result-block');
      if (!$block.length) {
        $block = $('<div class="kr-result-block"/>').insertBefore($pre);
        $block.append($pre);
      }
      var $labelRow = $block.children('.kr-result-label-row');
      if (!$labelRow.length) {
        $labelRow = $('<div class="kr-result-label-row"/>').prependTo($block);
        $labelRow.append($('<span class="kr-result-label"/>').text(b.label));
      }
      if (!$labelRow.find('[data-kr-copy="' + b.id + '"]').length) {
        var cls = 'secondary kr-copy-btn kr-copy-btn--sm';
        if (b.id === prefix + 'Rsp') cls += ' kr-copy-btn--rsp';
        $labelRow.append(
          $('<button type="button"/>')
            .attr('class', cls)
            .attr('data-kr-copy', b.id)
            .attr('title', '复制' + b.label)
            .text(b.id === prefix + 'Rsp' ? '复制出参' : '复制')
        );
      }
    });
  }

  function krFillResult(prefix, meta, req, rsp) {
    ensureKrCopyControls(prefix);
    $('#' + prefix + 'ResultMeta').text(meta || '');
    $('#' + prefix + 'Req').text(krPretty(req));
    $('#' + prefix + 'Rsp').text(krPretty(rsp));
    var $panel = $('#' + prefix + 'Result');
    if ($panel.length) {
      $panel.removeClass('is-empty kr-result-flash');
      void $panel[0].offsetWidth;
      $panel.addClass('kr-result-flash is-filled');
    }
  }

  /** 一键复制宽睿点测入参/出参文本（排查用）。 */
  function krCopyByPreId(preId) {
    var el = document.getElementById(preId);
    var text = el ? String(el.textContent || '').trim() : '';
    if (!text || text === '—') {
      toast('暂无可复制内容', 'info');
      return;
    }
    function ok() {
      toast('已复制到剪贴板', 'ok');
    }
    function fail() {
      toast('复制失败，请手动选择文本', 'err');
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(ok).catch(function () {
        // fallback
        try {
          var ta = document.createElement('textarea');
          ta.value = text;
          ta.setAttribute('readonly', '');
          ta.style.position = 'fixed';
          ta.style.left = '-9999px';
          document.body.appendChild(ta);
          ta.select();
          var done = document.execCommand('copy');
          document.body.removeChild(ta);
          if (done) ok();
          else fail();
        } catch (e) {
          fail();
        }
      });
      return;
    }
    try {
      var ta2 = document.createElement('textarea');
      ta2.value = text;
      ta2.setAttribute('readonly', '');
      ta2.style.position = 'fixed';
      ta2.style.left = '-9999px';
      document.body.appendChild(ta2);
      ta2.select();
      var done2 = document.execCommand('copy');
      document.body.removeChild(ta2);
      if (done2) ok();
      else fail();
    } catch (e2) {
      fail();
    }
  }

  function krMarkActiveCard($card) {
    if (!$card || !$card.length) return;
    $card.closest('.kr-bench-apis').find('.kr-api-card').removeClass('is-active');
    $card.addClass('is-active');
  }

  /** 宽睿对接接口介绍（对齐 docs/kuangrui.html 手册/接入摘要） */
  var KR_API_INTROS = {
    'queryCashAsset': {
      title: '查资金',
      sdk: 'queryCashAsset',
      stage: 'M2 只读',
      html:
        '<p>OES 查询通道：查资金资产（可用/可取等）。主路径强类型 Filter + <code>QueryMode.ALL</code> → <code>getQryItems</code>。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/cash</code></li>'
        + '<li>登录后应 <code>sendRptSync</code>；同步失败可<strong>查询降级</strong>仍可查资金</li>'
        + '<li>本应用：运维点测 + <code>position-pnl-sync</code> 纸面对账</li></ul>'
    },
    'queryStkHolding': {
      title: '查持仓',
      sdk: 'queryStkHolding',
      stage: 'M2 只读',
      html:
        '<p>OES 查询通道：证券持仓（数量、成本等）。与查资金同属只读对账能力。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/holdings</code></li>'
        + '<li>查询降级时仍可用；报撤仍要求 <code>rptSynced=true</code></li></ul>'
    },
    'queryOrder': {
      title: '查委托',
      sdk: 'queryOrder',
      stage: 'M2 / M3',
      html:
        '<p>OES 查委托单状态，用于对账与 <code>sync-orders</code> 推进补强。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/orders</code></li>'
        + '<li>M3 报撤 live 时，按柜台状态推进本地 FILLED 等</li></ul>'
    },
    'queryTrade': {
      title: '查成交',
      sdk: 'queryTrade',
      stage: 'M2 / M3',
      html:
        '<p>OES 查成交明细，配合委托回报做纸面/实盘对账。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/trades</code></li></ul>'
    },
    'snapshot': {
      title: '快照',
      sdk: 'snapshot',
      stage: 'M2 只读',
      html:
        '<p>一次拉齐资金/持仓等只读视图，便于联调对照。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/snapshot</code></li>'
        + '<li>依赖 OES 已登录；出参含 live / rptSynced 等门禁字段</li></ul>'
    },
    'reconcile': {
      title: '纸面对账',
      sdk: 'reconcile',
      stage: 'M2 只读',
      html:
        '<p>对比 OES 柜台资金/持仓与本地纸面账户，输出差异摘要。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/reconcile</code></li>'
        + '<li>不改金叉规则；仅辅助核对</li></ul>'
    },
    'order-status': {
      title: '报撤状态',
      sdk: 'order-status',
      stage: 'M3 报撤门禁',
      html:
        '<p><strong>报撤能力状态</strong>，不是某笔委托成交状态。回答：现在能否对柜台发限价报/撤。</p>'
        + '<ul><li>关键字段：<code>orderLive</code>（live ∧ order-enabled）、<code>rptSynced</code>、<code>loggedIn</code></li>'
        + '<li>总闸：<code>quant.kuangrui.oes.order-enabled</code>（默认 false）</li>'
        + '<li>查具体委托请用「查委托」</li></ul>'
    },
    'queryStock': {
      title: '证券产品',
      sdk: 'queryStock',
      stage: 'M4 静态/费率',
      html:
        '<p>OES <code>queryStock</code>：证券产品信息（涨跌停、停牌、股本等产品侧字段）。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/stock?code=</code></li>'
        + '<li>业务总闸 <code>static-enabled</code>；失败回退本地启发式</li>'
        + '<li>与 MDS 静态/状态互补，可覆盖本地涨跌停估算</li></ul>'
    },
    'queryTradingDay': {
      title: '交易日',
      sdk: 'queryTradingDay',
      stage: 'M4 静态/费率',
      html:
        '<p>OES <code>queryTradingDay</code>：柜台交易日，可开关覆盖本地静态节假日日历。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/trading-day</code></li></ul>'
    },
    'queryCommissionRate': {
      title: '佣金',
      sdk: 'queryCommissionRate',
      stage: 'M4 静态/费率',
      html:
        '<p>OES <code>queryCommissionRate</code>：佣金费率，可开关覆盖配置近似费率。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/commission-rate</code></li></ul>'
    },
    'queryClientOverview': {
      title: '客户端总览',
      sdk: 'queryClientOverview',
      stage: 'M5+ 查询增强',
      html:
        '<p>OES <code>queryClientOverview</code>：客户/资金账户/股东账户摘要，补强纸面对账。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/client-overview</code></li>'
        + '<li>只读；不改金叉主路径</li></ul>'
    },
    'queryInvAcct': {
      title: '股东账户',
      sdk: 'queryInvAcct',
      stage: 'M5+ 查询增强',
      html:
        '<p>OES <code>queryInvAcct</code>：股东账户列表（市场、状态、权限等）。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/inv-acct</code></li></ul>'
    },
    'queryCounterCash': {
      title: '主柜资金',
      sdk: 'queryCounterCash',
      stage: 'M5+ 查询增强',
      html:
        '<p>OES <code>queryCounterCash</code>：主柜可用/可取资金（与 OES 内存资金互补）。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/counter-cash?cashAcctId=</code>（账号可选）</li></ul>'
    },
    'queryMaxTradableQty': {
      title: '可买卖量',
      sdk: 'queryMaxTradableQty',
      stage: 'M5+ 查询增强',
      html:
        '<p>OES <code>queryMaxTradableQty</code>：给定证券/方向/限价下的最大可买卖数量。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/max-tradable-qty?code=&amp;side=&amp;price=</code></li>'
        + '<li>可选挂钩下单前校验；当前仅运维点测</li></ul>'
    },
    'queryCashTransferSerial': {
      title: '银证流水',
      sdk: 'queryCashTransferSerial',
      stage: 'M6 银证',
      html:
        '<p>OES <code>queryCashTransferSerial</code>：出入金/银证流水（金额已换算为元）。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/oes/cash-transfer-serial?cashAcctId=</code>（可选）</li>'
        + '<li>只读；与试转配对核对状态</li></ul>'
    },
    'oes.stop': {
      title: '关闭连接',
      sdk: 'stop',
      stage: 'OES 运维',
      html:
        '<p>关闭 OES 客户端连接，释放通道；下次查询会按需重登。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/oes/stop</code></li>'
        + '<li>写操作会二次确认</li></ul>'
    },
    'mds.status': {
      title: 'MDS 状态',
      sdk: 'status',
      stage: 'M1 行情',
      html:
        '<p>MDS 客户端 live / 订阅 / 配置等门禁状态。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/mds/status</code></li>'
        + '<li>真客户端须 <code>-Pkuangrui</code> + <code>mds.enabled</code></li></ul>'
    },
    'qryStockStaticInfo': {
      title: '证券静态',
      sdk: 'qryStockStaticInfo',
      stage: 'M4 静态',
      html:
        '<p>MDS 证券静态（涨跌停、股本等）。对齐 Demo：<code>Filter + QueryMode.ALL</code> → <code>getQryItems</code>；空则兜底 List。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/mds/stock-static?code=</code></li></ul>'
    },
    'qrySecurityStatus': {
      title: '证券状态',
      sdk: 'qrySecurityStatus',
      stage: 'M4 静态',
      html:
        '<p>MDS <code>qrySecurityStatus(Req)</code> 证券实时状态（停复牌等）；可开关回退「量≤0」启发式。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/mds/security-status?code=</code></li></ul>'
    },
    'qryTrdSessionStatus': {
      title: '交易时段',
      sdk: 'qryTrdSessionStatus',
      stage: 'M4 静态',
      html:
        '<p>MDS <code>qryTrdSessionStatus(Req)</code>：沪/深股票各查一次开市/休市。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/mds/session-status</code></li></ul>'
    },
    'static/stock': {
      title: '合并静态',
      sdk: 'static/stock',
      stage: 'M4 静态',
      html:
        '<p>合并 MDS/OES 静态视图，供业务侧一次取涨跌停/停牌等。</p>'
        + '<ul><li>运维：<code>GET /api/ops/kuangrui/static/stock</code></li>'
        + '<li>总闸 <code>quant.kuangrui.static-enabled</code></li></ul>'
    },
    'pull': {
      title: 'Pull 落库',
      sdk: 'pull',
      stage: 'M1 / M5+',
      html:
        '<p>主动 pull L1 快照并写入分钟桶 → <code>market_1min(data_source=MDS)</code>。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/mds/pull</code></li>'
        + '<li>M5+ 优先 <code>qrySnapshotList</code> 批量，失败回退单只 <code>qryMktDataSnapshot</code></li>'
        + '<li>价按元后四位 ÷10000；写操作二次确认</li></ul>'
    },
    'subscribe': {
      title: '订阅 L1',
      sdk: 'subscribe',
      stage: 'M1 MDS L1',
      html:
        '<p>MDS <code>subscribeMarketData</code>：订阅 L1 股票/指数等到分钟桶。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/mds/subscribe</code></li>'
        + '<li>回调勿做重活；L2/UDP 后置</li></ul>'
    },
    'flush': {
      title: 'Flush 分钟桶',
      sdk: 'flush',
      stage: 'M1 MDS L1',
      html:
        '<p>将内存分钟桶刷入库表，便于立刻核对落库结果。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/mds/flush</code></li></ul>'
    },
    'mds.stop': {
      title: '停止订阅',
      sdk: 'stop',
      stage: 'MDS 运维',
      html:
        '<p>停止 MDS 订阅并关闭连接。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/mds/stop</code></li>'
        + '<li>M5 将加强断线清客户端与重连（当前断线行为仍有韧性缺口）</li></ul>'
    },
    'sendOrdReq': {
      title: '限价报单',
      sdk: 'sendOrdReq',
      stage: 'M3 报撤',
      html:
        '<p>OES 限价下单 <code>sendOrdReq</code>（联调试单）。须 <code>orderLive=true</code> 且回报已同步。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/oes/place-test</code></li>'
        + '<li>总闸 <code>oes.order-enabled</code>；业务主路径另需 <code>trade-mode=sdk</code></li>'
        + '<li>页面二次确认；价按「元」入参，SDK 侧按元后四位</li></ul>'
    },
    'sendOrdCancelReq': {
      title: '撤单',
      sdk: 'sendOrdCancelReq',
      stage: 'M3 报撤',
      html:
        '<p>OES 撤单 <code>sendOrdCancelReq</code>。须报撤门禁打开；建议等回报/查询确认（细态闭环仍在 M3/M5 小修）。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/oes/cancel-test</code></li></ul>'
    },
    'sendCashTrsfReq': {
      title: '银证转账',
      sdk: 'sendCashTrsfReq',
      stage: 'M6 银证',
      html:
        '<p>OES 出入金 <code>sendCashTrsfReq</code>（联调试转）。与报撤共用 <code>orderLive</code>；不改本地 sim 账本。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/oes/cash-transfer-test</code></li>'
        + '<li>方向 IN=银行→证券 / OUT=证券→银行；默认类型 BANK（OES↔银行）</li>'
        + '<li>页面二次确认；密码仅发柜台，响应不回显；发出≠柜台最终确认，请查流水</li></ul>'
    },
    'account.login': {
      title: '登录并保存',
      sdk: 'account/login',
      stage: '账号',
      html:
        '<p>先验柜再密文入库（用户名明文、密码 AES-GCM）。库内 active 优先于环境变量。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/account/login</code></li>'
        + '<li>勿把密码写进 yml/Git；数据表白名单不含密钥表</li></ul>'
    },
    'account.logout': {
      title: '清除当前账号',
      sdk: 'account/logout',
      stage: '账号',
      html:
        '<p>清除库内 active 凭据；历史可保留；之后回退环境变量（若有）。</p>'
        + '<ul><li>运维：<code>POST /api/ops/kuangrui/account/logout</code></li></ul>'
    }
  };

  function closeKrApiIntro() {
    $('#krIntroModal').prop('hidden', true);
  }

  function openKrApiIntro(introKey, $from) {
    var info = KR_API_INTROS[introKey];
    if (!info) {
      toast('暂无该接口介绍', 'info');
      return;
    }
    if ($from && $from.length) {
      var $card = $from.closest('.kr-api-card');
      if ($card.length) krMarkActiveCard($card);
    } else {
      var $match = $('.kr-api-card[data-kr-intro="' + introKey + '"]').first();
      if ($match.length) krMarkActiveCard($match);
    }
    var titleHtml = escHtml(info.title || '接口介绍');
    if (info.sdk) {
      titleHtml += ' <span class="kr-intro-sdk"><code>' + escHtml(info.sdk) + '</code></span>';
    }
    $('#krIntroTitle').html(titleHtml);
    var body = '';
    if (info.stage) {
      body += '<span class="kr-intro-stage">' + escHtml(info.stage) + '</span>';
    }
    body += info.html || '<p>暂无摘要</p>';
    $('#krIntroBody').html(body);
    $('#krIntroModal').prop('hidden', false);
  }

  function krIntroKeyForApi(a, channel) {
    if (a.introKey) return a.introKey;
    if (a.sdk === 'stop') return (channel === 'mds' ? 'mds.stop' : 'oes.stop');
    if (a.sdk === 'status' && channel === 'mds') return 'mds.status';
    return a.sdk;
  }

  function appendKrApiCard($box, a, opts) {
    opts = opts || {};
    var channel = opts.channel || 'oes';
    var resultPrefix = opts.resultPrefix;
    var introKey = krIntroKeyForApi(a, channel);
    var $card = $('<div class="kr-api-card"/>').attr('data-kr-intro', introKey);
    var $hd = $('<div class="kr-api-card-head"/>');
    $hd.append($('<h4/>').html(
      '<span class="kr-api-title">' + escHtml(a.title) + '</span> '
      + '<code class="tech-id">' + escHtml(a.sdk) + '</code>'
    ));
    var $actions = $('<div class="kr-api-card-actions"/>');
    var $intro = $('<button type="button" class="kr-api-intro"/>')
      .attr('data-kr-intro', introKey)
      .attr('title', '查看接口说明')
      .text('介绍');
    var $btn = $('<button type="button" class="kr-api-call"/>').text('调用');
    $actions.append($intro).append($btn);
    $hd.append($actions);
    $card.append($hd);
    $card.append($('<p class="hint mono kr-api-path"/>').text(a.method + ' ' + a.path));
    var needParams = !!(a.code || a.tradable || a.cashAcct);
    if (needParams) {
      var $wrap = $('<details class="kr-api-params-wrap"/>');
      $wrap.append($('<summary/>').text('入参（可选展开）'));
      var $tb = $('<div class="toolbar kr-api-params"/>');
      if (a.code) {
        $tb.append($('<label class="field-inline"/>').html(
          '代码 <input type="text" class="kr-code kr-input-sm" value="600036"/>'
        ));
      }
      if (a.tradable) {
        $tb.append($('<label class="field-inline"/>').html(
          '代码 <input type="text" class="kr-code kr-input-sm" value="600036"/>'
        ));
        $tb.append($('<label class="field-inline"/>').html(
          '方向 <select class="kr-side"><option value="BUY">BUY</option><option value="SELL">SELL</option></select>'
        ));
        $tb.append($('<label class="field-inline"/>').html(
          '价格 <input type="text" class="kr-price kr-input-sm" value="10.00"/>'
        ));
      }
      if (a.cashAcct) {
        $tb.append($('<label class="field-inline"/>').html(
          '资金账号 <input type="text" class="kr-cash-acct kr-input-md" placeholder="可选"/>'
        ));
      }
      $wrap.append($tb);
      $card.append($wrap);
    }
    $btn.on('click', function () {
      var data = undefined;
      if (a.code) data = { code: $card.find('.kr-code').val() };
      if (a.tradable) {
        data = {
          code: $card.find('.kr-code').val(),
          side: $card.find('.kr-side').val(),
          price: $card.find('.kr-price').val()
        };
      }
      if (a.cashAcct) {
        var acct = ($card.find('.kr-cash-acct').val() || '').trim();
        data = acct ? { cashAcctId: acct } : {};
      }
      krInvoke({
        method: a.method,
        url: a.path,
        data: data,
        confirm: a.confirm,
        $btn: $btn,
        resultPrefix: resultPrefix,
        label: a.title
      });
    });
    $box.append($card);
  }

  function krInvoke(opts) {
    var method = (opts.method || 'GET').toUpperCase();
    var url = opts.url;
    var data = opts.data;
    var confirmMsg = opts.confirm;
    var $btn = opts.$btn;
    var resultPrefix = opts.resultPrefix;
    var label = opts.label || url;
    if (confirmMsg && !window.confirm(confirmMsg)) {
      return;
    }
    if ($btn && $btn.length) krMarkActiveCard($btn.closest('.kr-api-card'));
    var reqView = opts.reqView || { method: method, url: url };
    if (!opts.reqView && data != null) {
      if (method === 'GET') reqView.query = data;
      else reqView.body = data;
    }
    var t0 = Date.now();
    if ($btn && $btn.length) $btn.prop('disabled', true).addClass('is-loading');
    var $meta = $('#' + resultPrefix + 'ResultMeta');
    if ($meta.length) $meta.text(label + ' · 请求中…');
    var ajax = {
      url: url,
      method: method,
      dataType: 'json'
    };
    if (method === 'GET') {
      ajax.data = data || {};
    } else {
      ajax.contentType = 'application/json';
      ajax.data = JSON.stringify(data == null ? {} : data);
    }
    $.ajax(ajax).done(function (rsp, _text, xhr) {
      var ms = Date.now() - t0;
      var st = xhr && xhr.status != null ? xhr.status : 200;
      krFillResult(resultPrefix, label + ' · HTTP ' + st + ' · ' + ms + 'ms', reqView, rsp);
      if (typeof opts.onDone === 'function') {
        try { opts.onDone(rsp); } catch (e) { /* ignore paint errors */ }
      }
      var ok = rsp && (rsp.ok === true || rsp.live === true || rsp.orderLive === true || rsp.hasCred === true);
      toast(ok ? (label + ' 完成') : (label + ' 已返回'), ok ? 'ok' : 'info');
    }).fail(function (xhr) {
      var ms = Date.now() - t0;
      var body = (xhr && xhr.responseJSON) || { message: (xhr && xhr.responseText) || '请求失败' };
      krFillResult(resultPrefix, label + ' · HTTP ' + (xhr && xhr.status) + ' · ' + ms + 'ms', reqView, body);
      if (typeof opts.onFail === 'function') {
        try { opts.onFail(body); } catch (e) { /* ignore */ }
      }
      toast(label + ' 失败', 'err');
    }).always(function () {
      if ($btn && $btn.length) {
        $btn.removeClass('is-loading');
        if (resultPrefix === 'krOrder') {
          $btn.prop('disabled', !krOrderLive);
        } else {
          $btn.prop('disabled', false);
        }
      }
      if (resultPrefix === 'krOrder') refreshKrOrderGate();
    });
  }

  function loadKrOverview() {
    var gen = ++krOverviewGen;
    $('#krOverviewMeta').text('加载中…');
    $('#btnKrOverviewRefresh').prop('disabled', true).addClass('is-loading');
    var slots = [
      {
        key: 'Account',
        title: '账号凭据',
        sub: '库内 active / env 回退',
        url: '/api/ops/kuangrui/account/status',
        panel: 'account',
        liveKey: 'hasCred'
      },
      {
        key: 'MDS',
        title: 'MDS 行情',
        sub: 'L1 落库 / 静态查询',
        url: '/api/ops/kuangrui/mds/status',
        panel: 'mds',
        liveKey: 'live'
      },
      {
        key: 'OES',
        title: 'OES 交易',
        sub: '只读查询 / 对账',
        url: '/api/ops/kuangrui/oes/status',
        panel: 'oes',
        liveKey: 'live'
      },
      {
        key: 'Order',
        title: '报撤能力',
        sub: '限价报 / 撤试单',
        url: '/api/ops/kuangrui/oes/order-status',
        panel: 'order',
        liveKey: 'orderLive'
      },
      {
        key: 'Static',
        title: '静态 / 费率',
        sub: '涨跌停 · 停牌 · 佣金',
        url: '/api/ops/kuangrui/static/status',
        panel: 'overview',
        liveKey: 'applyEnabled'
      }
    ];
    var $grid = $('#krOverviewCards').empty();
    var $sum = $('#krOverviewSummary').empty().append($('<div class="kr-overview-chips"/>'));
    var $chips = $sum.find('.kr-overview-chips');
    var pending = slots.length;
    var results = {};

    function boolish(v) {
      return v === true || v === 'true' || v === 1 || v === '1';
    }

    function labelOf(k) {
      var map = {
        live: '连接',
        orderLive: '报撤 live',
        orderEnabled: 'order-enabled',
        applyEnabled: '业务覆盖',
        enabled: '开关',
        subscribed: '已订阅',
        loggedIn: '已登录',
        quantKuangruiEnabled: 'kuangrui.enabled',
        quantOesEnabled: 'oes.enabled',
        staticEnabled: 'static-enabled',
        tradeMode: 'trade-mode',
        impl: '实现',
        orderImpl: '报撤实现',
        configDir: '配置目录',
        hasCred: '有凭据',
        hasDbAccount: '库内账号',
        hasEnvFallback: 'env 回退',
        currentUsername: '当前账号',
        activeUsername: '库内 active',
        credSource: '凭据来源',
        lastLoginAt: '最近验柜',
        oesLive: 'OES live',
        probeAvailable: '可验柜',
        hint: '说明',
        orderHint: '报撤说明',
        message: '消息'
      };
      return map[k] || k;
    }

    function fmtVal(v) {
      if (v === true || v === 'true') return { text: '开', kind: 'on' };
      if (v === false || v === 'false') return { text: '关', kind: 'off' };
      if (v == null || v === '') return { text: '—', kind: 'mute' };
      return { text: String(v), kind: 'text' };
    }

    function pickLive(slot, d) {
      if (!d) return null;
      if (slot.liveKey && d[slot.liveKey] != null) return boolish(d[slot.liveKey]);
      if (d.live != null) return boolish(d.live);
      return null;
    }

    function renderCard(slot, state, d, errHttp) {
      var live = state === 'ok' ? pickLive(slot, d) : null;
      var badgeClass = 'kr-badge kr-badge--load';
      var badgeText = '加载中';
      if (state === 'err') {
        badgeClass = 'kr-badge kr-badge--err';
        badgeText = '失败';
      } else if (state === 'ok') {
        if (live === true) {
          badgeClass = 'kr-badge kr-badge--on';
          badgeText = 'LIVE';
        } else {
          badgeClass = 'kr-badge kr-badge--off';
          badgeText = 'OFF';
        }
      }
      var $c = $('<div class="kr-status-card"/>').attr('data-kr-slot', slot.key);
      if (live === true) $c.addClass('is-live');
      else if (state === 'ok') $c.addClass('is-off');
      else if (state === 'err') $c.addClass('is-err');

      var $hd = $('<div class="kr-status-head"/>');
      $hd.append($('<div class="kr-status-titles"/>')
        .append($('<h4/>').text(slot.title))
        .append($('<p class="kr-status-sub"/>').text(slot.sub)));
      $hd.append($('<span/>').attr('class', badgeClass).text(badgeText));
      $c.append($hd);

      if (state === 'load') {
        $c.append($('<p class="kr-status-loading"/>').text('拉取 status…'));
      } else if (state === 'err') {
        $c.append($('<p class="kr-status-err"/>').text('加载失败 HTTP ' + (errHttp || '—')));
      } else {
        var prefer = [
          'live', 'orderLive', 'applyEnabled', 'orderEnabled', 'hasCred', 'hasDbAccount',
          'currentUsername', 'activeUsername', 'credSource', 'lastLoginAt', 'hasEnvFallback',
          'oesLive', 'probeAvailable',
          'subscribed', 'loggedIn',
          'quantKuangruiEnabled', 'quantOesEnabled', 'staticEnabled', 'tradeMode',
          'impl', 'orderImpl', 'configDir'
        ];
        var $rows = $('<div class="kr-kv-rows"/>');
        prefer.forEach(function (k) {
          if (!d || d[k] == null || d[k] === '') return;
          var fv = fmtVal(d[k]);
          var $row = $('<div class="kr-kv-row"/>');
          $row.append($('<span class="kr-kv-k"/>').text(labelOf(k)));
          if (fv.kind === 'on' || fv.kind === 'off') {
            $row.append($('<span class="kr-pill"/>').addClass('kr-pill--' + fv.kind).text(fv.text));
          } else {
            $row.append($('<span class="kr-kv-v mono"/>').text(fv.text));
          }
          $rows.append($row);
        });
        $c.append($rows);

        var hint = (d && (d.hint || d.orderHint || d.message)) || '';
        if (hint) {
          $c.append($('<div class="kr-status-hint"/>').text(hint));
        }

        var $det = $('<details class="kr-status-raw"/>');
        $det.append($('<summary/>').text('原始 JSON'));
        $det.append($('<pre class="kr-json"/>').text(krPretty(d)));
        $c.append($det);
      }

      if (slot.panel && slot.panel !== 'overview') {
        var $go = $('<button type="button" class="secondary kr-status-go"/>')
          .text(slot.key === 'Account' ? '前往账号' : '进入点测')
          .attr('data-kr-jump', slot.panel);
        $c.append($go);
      }
      return $c;
    }

    function paintSummary() {
      $chips.empty();
      var on = 0;
      var anyErr = false;
      var anyLoad = false;
      slots.forEach(function (s) {
        var r = results[s.key];
        var live = r && r.state === 'ok' ? pickLive(s, r.data) : null;
        if (live) on++;
        if (r && r.state === 'err') anyErr = true;
        if (!r || r.state === 'load') anyLoad = true;
        var cls = 'kr-chip';
        var t = s.key + ' · —';
        if (!r || r.state === 'load') {
          cls += ' kr-chip--load';
          t = s.key + ' · …';
        } else if (r.state === 'err') {
          cls += ' kr-chip--err';
          t = s.key + ' · 失败';
        } else if (live) {
          cls += ' kr-chip--on';
          t = s.key + ' · LIVE';
        } else {
          cls += ' kr-chip--off';
          t = s.key + ' · OFF';
        }
        $chips.append($('<span/>').attr('class', cls).text(t));
      });

      var accR = results.Account;
      var hasCred = !!(accR && accR.state === 'ok' && accR.data && boolish(accR.data.hasCred));
      var oesLive = !!(results.OES && results.OES.state === 'ok' && pickLive(slots[2], results.OES.data));
      var mdsLive = !!(results.MDS && results.MDS.state === 'ok' && pickLive(slots[1], results.MDS.data));
      var orderLiveNow = !!(results.Order && results.Order.state === 'ok' && pickLive(slots[3], results.Order.data));

      var verdictCls = 'is-off';
      var verdictTitle = '旁路关闭 / 未就绪';
      var verdictDetail = '当前均为 noop。请确认 -Pkuangrui 与 yml 开关，并准备账号凭据。';
      var ctas = [];
      if (anyLoad && on === 0 && !anyErr) {
        verdictCls = 'is-off';
        verdictTitle = '接入结论 · 加载中';
        verdictDetail = '正在拉取各通道 status…';
      } else if (orderLiveNow) {
        verdictCls = 'is-live';
        verdictTitle = '可报撤';
        verdictDetail = 'orderLive 已开；试单仍会二次确认。建议先只读对账再试报撤。';
        ctas = [
          { panel: 'order', label: '去报撤试单', primary: true },
          { panel: 'oes', label: 'OES 只读', primary: false }
        ];
      } else if (oesLive || mdsLive) {
        verdictCls = 'is-read';
        verdictTitle = '可联调（只读）';
        verdictDetail = '已 live ' + on + '/' + slots.length + ' 项。可点测 OES/MDS；报撤仍须打开 order-enabled。';
        ctas = [
          { panel: oesLive ? 'oes' : 'mds', label: oesLive ? 'OES 只读' : 'MDS 行情', primary: true },
          { panel: 'docs', label: '宽睿文档', primary: false }
        ];
      } else if (hasCred) {
        verdictCls = 'is-warn';
        verdictTitle = '有账号 · 通道未 live';
        verdictDetail = '凭据已就绪，但 MDS/OES 仍旁路。检查 -Pkuangrui 与 enabled 开关。';
        ctas = [
          { panel: 'oes', label: 'OES 点测', primary: true },
          { panel: 'docs', label: '宽睿文档', primary: false }
        ];
      } else if (anyErr) {
        verdictCls = 'is-err';
        verdictTitle = '状态拉取异常';
        verdictDetail = '部分 status 失败；请确认服务已启动且宽睿运维接口可达。';
        ctas = [
          { panel: 'docs', label: '宽睿文档', primary: false },
          { panel: 'account', label: '账号登录', primary: false }
        ];
      } else {
        ctas = [
          { panel: 'account', label: '账号登录', primary: true },
          { panel: 'docs', label: '宽睿文档', primary: false }
        ];
      }

      var $verdict = $('#krOverviewVerdict')
        .removeClass('is-live is-read is-warn is-err is-off')
        .addClass(verdictCls);
      $('#krOverviewVerdictTitle').text(verdictTitle);
      $('#krOverviewVerdictDetail').text(verdictDetail);
      var $cta = $('#krOverviewCta').empty();
      ctas.forEach(function (c) {
        $cta.append(
          $('<button type="button"/>')
            .addClass(c.primary ? '' : 'secondary')
            .attr('data-kr-jump', c.panel)
            .text(c.label)
        );
      });
      if (!$verdict.length) {
        /* 兼容旧 DOM */
      }

      var tip = on === 0
        ? '卡片「进入」可跳转点测；建议：文档 → 账号 → 只读 → 报撤。'
        : ('已 live ' + on + '/' + slots.length + ' 项；点下方卡片进入点测。');
      $sum.find('.kr-overview-tip').remove();
      $sum.append($('<p class="kr-overview-tip"/>').text(tip));
    }

    slots.forEach(function (slot) {
      var $ph = renderCard(slot, 'load', null, null);
      $grid.append($ph);
      results[slot.key] = { state: 'load' };
      $.getJSON(slot.url).done(function (d) {
        if (gen !== krOverviewGen) return;
        if (slot.key === 'Order') krOrderLive = !!(d && d.orderLive);
        results[slot.key] = { state: 'ok', data: d };
        $ph.replaceWith(renderCard(slot, 'ok', d, null));
      }).fail(function (xhr) {
        if (gen !== krOverviewGen) return;
        results[slot.key] = { state: 'err' };
        $ph.replaceWith(renderCard(slot, 'err', null, xhr && xhr.status));
      }).always(function () {
        if (gen !== krOverviewGen) return;
        pending--;
        paintSummary();
        if (pending <= 0) {
          $('#krOverviewMeta').text('已刷新 ' + new Date().toLocaleTimeString());
          $('#btnKrOverviewRefresh').prop('disabled', false).removeClass('is-loading');
        }
      });
    });
    paintSummary();
  }

  function markKrAccCard(api) {
    var $cards = $('#krAccCards .kr-api-card');
    $cards.removeClass('is-active');
    if (api) {
      $cards.filter('[data-kr-acc-api="' + api + '"]').addClass('is-active');
    }
  }

  function paintKrAccCurrent(d) {
    var user = (d && (d.currentUsername || d.activeUsername)) || '';
    var src = (d && d.credSource) || 'none';
    var has = !!(d && d.hasCred);
    $('#krAccCurrentUser').text(user || '（无）');
    var $src = $('#krAccCurrentSrc').removeClass('kr-pill--on kr-pill--off kr-pill--mute');
    if (src === 'db') {
      $src.addClass('kr-pill--on').text('来源 库内 active');
    } else if (src === 'env') {
      $src.addClass('kr-pill--on').text('来源 环境变量');
    } else {
      $src.addClass('kr-pill--mute').text('来源 无');
    }
    var meta = has
      ? ('生效账号 ' + user + (d.lastLoginAt ? ' · 最近验柜 ' + d.lastLoginAt : ''))
      : ((d && d.message) || '当前无可用账号');
    $('#krAccCurrentMeta').text(meta);
    $('#krAccCurrentBox').toggleClass('is-empty', !has).toggleClass('is-ready', has);
    if (has && user && !($('#krAccUser').val() || '').trim()) {
      $('#krAccUser').val(user);
    }
  }

  /** 进入账号页时静默拉取当前生效账号（只更新上方展示条，不占结果区）。 */
  function loadKrAccountCurrent() {
    $('#krAccCurrentMeta').text('正在查询当前账号…');
    $.getJSON('/api/ops/kuangrui/account/current')
      .done(function (rsp) {
        paintKrAccCurrent(rsp || {});
      })
      .fail(function (xhr) {
        paintKrAccCurrent({
          hasCred: false,
          credSource: 'none',
          message: (xhr && xhr.responseJSON && xhr.responseJSON.message)
            || ('查询失败 HTTP ' + (xhr && xhr.status))
        });
      });
  }

  function ensureKrOesCards() {
    var $box = $('#krOesCards');
    if ($box.data('ready')) return;
    $box.data('ready', true);
    var apis = [
      { title: '查资金', sdk: 'queryCashAsset', method: 'GET', path: '/api/ops/kuangrui/oes/cash' },
      { title: '查持仓', sdk: 'queryStkHolding', method: 'GET', path: '/api/ops/kuangrui/oes/holdings' },
      { title: '查委托', sdk: 'queryOrder', method: 'GET', path: '/api/ops/kuangrui/oes/orders' },
      { title: '查成交', sdk: 'queryTrade', method: 'GET', path: '/api/ops/kuangrui/oes/trades' },
      { title: '快照', sdk: 'snapshot', method: 'GET', path: '/api/ops/kuangrui/oes/snapshot' },
      { title: '纸面对账', sdk: 'reconcile', method: 'GET', path: '/api/ops/kuangrui/oes/reconcile' },
      { title: '报撤状态', sdk: 'order-status', method: 'GET', path: '/api/ops/kuangrui/oes/order-status' },
      { title: '证券产品', sdk: 'queryStock', method: 'GET', path: '/api/ops/kuangrui/oes/stock', code: true },
      { title: '交易日', sdk: 'queryTradingDay', method: 'GET', path: '/api/ops/kuangrui/oes/trading-day' },
      { title: '佣金', sdk: 'queryCommissionRate', method: 'GET', path: '/api/ops/kuangrui/oes/commission-rate' },
      { title: '客户端总览', sdk: 'queryClientOverview', method: 'GET', path: '/api/ops/kuangrui/oes/client-overview' },
      { title: '股东账户', sdk: 'queryInvAcct', method: 'GET', path: '/api/ops/kuangrui/oes/inv-acct' },
      { title: '主柜资金', sdk: 'queryCounterCash', method: 'GET', path: '/api/ops/kuangrui/oes/counter-cash', cashAcct: true },
      { title: '可买卖量', sdk: 'queryMaxTradableQty', method: 'GET', path: '/api/ops/kuangrui/oes/max-tradable-qty', tradable: true },
      { title: '银证流水', sdk: 'queryCashTransferSerial', method: 'GET', path: '/api/ops/kuangrui/oes/cash-transfer-serial', cashAcct: true },
      { title: '关闭连接', sdk: 'stop', method: 'POST', path: '/api/ops/kuangrui/oes/stop', confirm: '确认关闭 OES 客户端连接？' }
    ];
    apis.forEach(function (a) {
      appendKrApiCard($box, a, { channel: 'oes', resultPrefix: 'krOes' });
    });
  }

  function ensureKrMdsCards() {
    var $box = $('#krMdsCards');
    if ($box.data('ready')) return;
    $box.data('ready', true);
    var apis = [
      { title: 'MDS 状态', sdk: 'status', method: 'GET', path: '/api/ops/kuangrui/mds/status' },
      { title: '证券静态', sdk: 'qryStockStaticInfo', method: 'GET', path: '/api/ops/kuangrui/mds/stock-static', code: true },
      { title: '证券状态', sdk: 'qrySecurityStatus', method: 'GET', path: '/api/ops/kuangrui/mds/security-status', code: true },
      { title: '交易时段', sdk: 'qryTrdSessionStatus', method: 'GET', path: '/api/ops/kuangrui/mds/session-status' },
      { title: '合并静态', sdk: 'static/stock', method: 'GET', path: '/api/ops/kuangrui/static/stock', code: true },
      { title: 'Pull 落库', sdk: 'pull', method: 'POST', path: '/api/ops/kuangrui/mds/pull', confirm: '确认执行 MDS pull 并落库？' },
      { title: '订阅 L1', sdk: 'subscribe', method: 'POST', path: '/api/ops/kuangrui/mds/subscribe', confirm: '确认订阅 MDS L1？' },
      { title: 'Flush 分钟桶', sdk: 'flush', method: 'POST', path: '/api/ops/kuangrui/mds/flush', confirm: '确认 flush 分钟桶？' },
      { title: '停止订阅', sdk: 'stop', method: 'POST', path: '/api/ops/kuangrui/mds/stop', confirm: '确认停止 MDS 订阅并关闭连接？' }
    ];
    apis.forEach(function (a) {
      appendKrApiCard($box, a, { channel: 'mds', resultPrefix: 'krMds' });
    });
  }

  function refreshKrOrderGate() {
    $.getJSON('/api/ops/kuangrui/oes/order-status').done(function (d) {
      krOrderLive = !!(d && d.orderLive);
      var hint = (d && (d.hint || d.orderHint || d.message)) || '';
      $('#krOrderGateBanner').toggleClass('is-on', krOrderLive).toggleClass('is-off', !krOrderLive);
      $('#krOrderGateLabel').text(krOrderLive ? '报撤闸 · LIVE' : '报撤闸 · OFF');
      $('#krOrderGateHint').html(
        krOrderLive
          ? 'orderLive=<b>true</b>，可试单（仍会弹框确认）。高危操作请核对代码/数量/金额。'
          : 'orderLive=<b>false</b>，试单按钮禁用。' + (hint ? ' ' + escHtml(hint) : '')
          + ' 开关：<code>quant.kuangrui.oes.order-enabled</code>（yml，默认 false）。'
      );
      $('#btnKrPlace, #btnKrCancel, #btnKrCashTrsf').prop('disabled', !krOrderLive);
    }).fail(function () {
      krOrderLive = false;
      $('#btnKrPlace, #btnKrCancel, #btnKrCashTrsf').prop('disabled', true);
      $('#krOrderGateBanner').removeClass('is-on').addClass('is-off');
      $('#krOrderGateLabel').text('报撤闸 · 未知');
      $('#krOrderGateHint').text('无法读取 order-status');
    });
  }

  function showSchedulePanel(panel) {
    panel = panel || lastSchedulePanel || 'jobs';
    if (panel !== 'jobs' && panel !== 'health' && panel !== 'params') panel = 'jobs';
    lastSchedulePanel = panel;
    lastWorkspaceMode = 'schedule';
    ensureSidebarExpanded();
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    setSideNavOpen('scheduleBody');
    setScheduleMenuActive(panel);
    if (panel === 'health') {
      $('#viewDataHealth').prop('hidden', false);
      loadDataHealth();
    } else if (panel === 'params') {
      $('#viewSysParams').prop('hidden', false);
      loadSysParams();
    } else {
      $('#viewSchedule').prop('hidden', false);
      loadScheduleJobs();
    }
    breadcrumbForMode('schedule', panel === 'health' ? '数据健康' : (panel === 'params' ? '运行参数' : '任务管理'));
    saveNavSession({ mode: 'schedule', panel: panel });
    resizeCharts();
  }

  var strategyEvalState = {
    strategies: [],
    selectedId: '',
    kind: 'ALL',
    sort: 'time_desc',
    historyRows: [],
    enabled: true,
    unknownCount: 0,
    seedPollTimer: null,
    seedHintShown: {},
    autoSeedStarted: {}
  };
  var STRATEGY_HIST_COLSPAN = 7;

  function strategyGradeTagClass(grade) {
    var g = String(grade || '').toUpperCase();
    if (g === 'S' || g === 'A') return 'strategy-tag strategy-tag--grade-a';
    if (g === 'B') return 'strategy-tag strategy-tag--grade-b';
    if (g === 'C') return 'strategy-tag strategy-tag--grade-c';
    if (g === 'D' || g === 'E') return 'strategy-tag strategy-tag--grade-d';
    return 'strategy-tag strategy-tag--muted';
  }

  function setStrategyMenuActive(panel) {
    $('#strategyMenu li').removeClass('active');
    if (panel) {
      $('#strategyMenu li[data-strategy-panel="' + panel + '"]').addClass('active');
    }
  }

  function showStrategyEval() {
    lastWorkspaceMode = 'strategy';
    ensureSidebarExpanded();
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    setSideNavOpen('strategyBody');
    setStrategyMenuActive('eval');
    $('#viewStrategy').prop('hidden', false);
    loadStrategyOverview();
    pollStrategySeedStatus(false);
    breadcrumbForMode('strategy', '策略总览');
    saveNavSession({ mode: 'strategy', panel: 'eval' });
    resizeCharts();
  }

  function renderStrategyList(strategies, selectedId) {
    var $list = $('#strategyList').empty();
    if (!strategies || !strategies.length) {
      $list.append($('<div class="hint"/>').text('暂无注册策略'));
      return;
    }
    strategies.forEach(function (s) {
      var id = s.strategyId || '';
      var label = s.displayName || id || '—';
      var $item = $('<div class="strategy-list-item" role="button" tabindex="0"/>')
        .attr('data-strategy-id', id)
        .attr('title', id || '');
      if (id && id === selectedId) $item.addClass('active');
      var $top = $('<div class="strategy-list-top"/>');
      $top.append($('<span class="strategy-list-name"/>').text(label));
      var $badges = $('<span class="strategy-list-badges"/>');
      if (s.active) {
        $badges.append($('<span class="strategy-tag strategy-tag--active"/>').text('激活'));
      }
      if (s.score != null && s.score !== '') {
        $badges.append($('<span class="strategy-tag strategy-tag--score"/>')
          .text(String(s.score)));
        if (s.grade) {
          $badges.append($('<span/>').attr('class', strategyGradeTagClass(s.grade)).text(s.grade));
        }
      } else {
        $badges.append($('<span class="strategy-tag strategy-tag--muted"/>').text('未评'));
      }
      $top.append($badges);
      $item.append($top);
      $item.append($('<div class="strategy-list-id tech-id"/>').text(id || '—'));
      $list.append($item);
    });
  }

  function setStrategyIntroCollapsed(collapsed) {
    var $wrap = $('#strategyIntro');
    var $btn = $('#btnStrategyIntroToggle');
    var $body = $('#strategyIntroBody');
    if (!$wrap.length) return;
    $btn.attr('aria-expanded', collapsed ? 'false' : 'true');
    $btn.find('.strategy-intro-chevron').text(collapsed ? '▸' : '▾');
    $body.prop('hidden', !!collapsed);
  }

  function renderStrategyIntro(s) {
    var $wrap = $('#strategyIntro');
    if (!$wrap.length) return;
    if (!s) {
      $wrap.prop('hidden', true);
      $('#strategyIntroBody').empty();
      setStrategyIntroCollapsed(true);
      return;
    }
    $wrap.prop('hidden', false);
    var title = (s.displayName || s.strategyId || '策略') + ' · 详细介绍';
    $('#strategyIntroTitle').text(title);
    var text = s.detailIntro || s.summary || '暂无详细介绍';
    $('#strategyIntroBody').empty().append($('<p class="strategy-intro-text"/>').text(text));
    setStrategyIntroCollapsed(true);
  }

  function renderStrategyUnknownBanner() {
    var $hint = $('#strategyUnknownHint');
    if (!$hint.length) return;
    var n = Number(strategyEvalState.unknownCount || 0);
    if (n > 0) {
      $hint.prop('hidden', false)
        .text('另有 ' + n + ' 条回测 strategy_id 仍为空；启动会自动补全，亦可 POST /api/ops/backtest/backfill-strategy-id');
    } else {
      $hint.prop('hidden', true).text('');
    }
  }

  function renderStrategyCards(s) {
    var $cards = $('#strategyEvalCards').empty();
    renderStrategyUnknownBanner();
    renderStrategySeedBar(s);
    if (!s) {
      $cards.append($('<div class="hint"/>').text('请选择左侧策略'));
      return;
    }
    function metricCard(label, value, sub, extraClass) {
      var $c = $('<div class="strategy-metric-card"/>');
      if (extraClass) $c.addClass(extraClass);
      $c.append($('<div class="label"/>').text(label));
      $c.append($('<div class="value"/>').text(value == null || value === '' ? '—' : String(value)));
      if (sub) $c.append($('<div class="sub"/>').text(sub));
      return $c;
    }

    var scoreText = s.score != null && s.score !== ''
      ? (String(s.score) + ' / ' + (s.scoreMax != null ? s.scoreMax : 100))
      : '—';
    var $hero = $('<div class="strategy-score-hero"/>');
    var $heroMain = $('<div class="strategy-score-hero-main"/>');
    $heroMain.append($('<div class="label"/>').text('综合评分'));
    $heroMain.append($('<div class="value"/>').text(scoreText));
    var $tags = $('<div class="strategy-score-hero-tags"/>');
    if (s.active) {
      $tags.append($('<span class="strategy-tag strategy-tag--active"/>').text('激活'));
    }
    if (s.grade) {
      $tags.append($('<span/>').attr('class', strategyGradeTagClass(s.grade)).text('等级 ' + s.grade));
    } else if (s.score == null) {
      $tags.append($('<span class="strategy-tag strategy-tag--muted"/>').text('暂无回测，无法评分'));
    }
    $heroMain.append($tags);
    $hero.append($heroMain);
    $hero.append($('<p class="hint strategy-score-hero-hint"/>').text(
      '满分 100：收益 30 + 回撤 25 + 胜率 20 + 盈利占比 15 + 样本 10；夏普仅展示不计入'
    ));
    $cards.append($hero);

    var comps = s.scoreComponents || [];
    if (comps.length) {
      $cards.append($('<h5 class="strategy-block-title"/>').text('评分分项'));
      var $scoreGrid = $('<div class="strategy-metric-grid"/>');
      comps.forEach(function (c) {
        var pts = (c.points != null ? c.points : '—') + ' / ' + (c.max != null ? c.max : '—');
        $scoreGrid.append(metricCard(c.label || c.key || '分项', pts, c.detail || null, 'strategy-metric-card--score'));
      });
      $cards.append($scoreGrid);
    }

    $cards.append($('<h5 class="strategy-block-title"/>').text('聚合指标'));
    var $metricGrid = $('<div class="strategy-metric-grid"/>');
    $metricGrid.append(metricCard('运行次数', s.runCount != null ? s.runCount : 0,
      strategyEvalState.enabled === false ? '数据库未启用，聚合为 0' : null));
    $metricGrid.append(metricCard('平均收益率', pct(s.avgTotalRate)));
    $metricGrid.append(metricCard('中位收益率', pct(s.medianTotalRate)));
    $metricGrid.append(metricCard('平均回撤', pct(s.avgMaxDrawdown)));
    $metricGrid.append(metricCard('平均胜率', pct(s.avgWinRate)));
    var sharpeSub = '年化·RF=0·不计入评分';
    if (s.sharpeCount != null && s.runCount != null && Number(s.sharpeCount) < Number(s.runCount)) {
      sharpeSub += '（有夏普 ' + s.sharpeCount + '/' + s.runCount + '）';
    }
    $metricGrid.append(metricCard('平均夏普',
      s.avgSharpe != null && s.avgSharpe !== '' ? num(s.avgSharpe, 2) : '—',
      sharpeSub));
    $metricGrid.append(metricCard('盈利占比', pct(s.positiveRatio)));
    $metricGrid.append(metricCard('最近收益', pct(s.lastTotalRate),
      s.lastSavedAt ? ('最近：' + fmtDateTimeDisplay(s.lastSavedAt)) : '暂无回测'));
    $cards.append($metricGrid);
  }

  function renderStrategySeedBar(s) {
    var $bar = $('#strategySeedBar');
    if (!$bar.length) return;
    if (!s) {
      $bar.prop('hidden', true);
      return;
    }
    var runCount = Number(s.runCount || 0);
    $bar.prop('hidden', false);
    var $btn = $('#btnStrategySeed');
    var $hint = $('#strategySeedHint');
    if (runCount <= 0) {
      $btn.text('用目标池补回测').prop('disabled', false);
      $hint.text('无回测时会自动用目标池补种（逐只单股 + 全池组合）；也可手动再点');
    } else {
      $btn.text('强制再补目标池回测').prop('disabled', false);
      $hint.text('已有 ' + runCount + ' 条回测；强制将再追加目标池单股+组合');
    }
  }

  function applyStrategySeedStatus(st) {
    st = st || {};
    var $prog = $('#strategySeedProgress');
    if (!$prog.length) return;
    var running = !!st.running;
    var hasResult = st.ok === true || st.ok === false;
    if (!running && !hasResult && st.idle) {
      $prog.prop('hidden', true);
      return;
    }
    if (!running && !hasResult) {
      return;
    }
    $prog.prop('hidden', false);
    var phase = st.phaseLabel || st.phase || '—';
    if (st.currentCode) phase += ' · ' + st.currentCode;
    $('#strategySeedPhase').text(phase);
    var pctVal = st.progressPercent != null ? Number(st.progressPercent) : 0;
    if (isNaN(pctVal)) pctVal = 0;
    $('#strategySeedPct').text(pctVal.toFixed(1) + '%');
    $('#strategySeedBarFill').css('width', Math.max(0, Math.min(100, pctVal)) + '%');
    $('#strategySeedSummary').text(st.summary || st.message || '');
    $('#btnStrategySeed').prop('disabled', running);
  }

  function stopStrategySeedPoll() {
    if (strategyEvalState.seedPollTimer) {
      clearInterval(strategyEvalState.seedPollTimer);
      strategyEvalState.seedPollTimer = null;
    }
  }

  function pollStrategySeedStatus(refreshOverviewOnDone) {
    $.getJSON('/api/strategy/seed-status')
      .done(function (st) {
        applyStrategySeedStatus(st);
        if (st && st.running) {
          if (!strategyEvalState.seedPollTimer) {
            strategyEvalState.seedPollTimer = setInterval(function () {
              pollStrategySeedStatus(true);
            }, 1500);
          }
          return;
        }
        stopStrategySeedPoll();
        if (refreshOverviewOnDone && st && (st.ok === true || st.ok === false)) {
          if (st.ok) {
            toast(st.summary || '目标池补回测完成', 'ok');
            loadStrategyOverview();
          } else {
            toast(st.message || st.summary || '目标池补回测失败', 'err');
            renderStrategySeedBar(findStrategyById(strategyEvalState.selectedId));
          }
        }
      })
      .fail(function () {
        /* 忽略瞬时失败，继续轮询 */
      });
  }

  function extractAjaxError(xhr, fallback) {
    var msg = fallback || '请求失败';
    if (!xhr) return msg;
    if (xhr.responseJSON) {
      return xhr.responseJSON.message || xhr.responseJSON.error || xhr.responseJSON.reason || msg;
    }
    if (xhr.responseText) {
      try {
        var j = JSON.parse(xhr.responseText);
        return j.message || j.error || j.reason || msg;
      } catch (e) {
        return String(xhr.responseText).slice(0, 200) || msg;
      }
    }
    return msg;
  }

  /**
   * @param force 是否强制（已有回测时）
   * @param opts.auto 自动触发：无确认框；仅 runCount==0
   */
  function startStrategyPoolSeed(force, opts) {
    opts = opts || {};
    var id = String(strategyEvalState.selectedId || '').trim();
    if (!id) {
      toast('请先选择策略', 'err');
      return;
    }
    var s = findStrategyById(id);
    var runCount = s ? Number(s.runCount || 0) : 0;
    var useForce = !!force || runCount > 0;
    if (opts.auto && runCount > 0) {
      return;
    }
    if (!opts.auto) {
      if (useForce && runCount > 0) {
        if (!window.confirm('策略已有 ' + runCount + ' 条回测，确认再追加目标池单股+组合回测？')) {
          return;
        }
      } else if (runCount <= 0) {
        if (!window.confirm('将对目标池全部股票各跑一次单股回测，再跑一次全池组合回测。可能较久，确认开始？')) {
          return;
        }
      }
    }
    var $btn = $('#btnStrategySeed');
    withLoading($btn, $.ajax({
      url: '/api/strategy/' + encodeURIComponent(id) + '/seed-pool-backtest',
      method: 'POST',
      data: { force: useForce }
    }).done(function (data) {
      data = data || {};
      toast(data.message || (opts.auto ? '无回测，已自动开始目标池补种' : '已开始补回测'), 'ok');
      applyStrategySeedStatus(data.status || { running: true, phaseLabel: '已受理', progressPercent: 0 });
      stopStrategySeedPoll();
      strategyEvalState.seedPollTimer = setInterval(function () {
        pollStrategySeedStatus(true);
      }, 1500);
      pollStrategySeedStatus(true);
    }).fail(function (xhr) {
      toast(extractAjaxError(xhr, '启动补回测失败'), 'err');
      if (opts.auto) {
        /* 允许用户手动再点 */
        delete strategyEvalState.autoSeedStarted[id];
      }
    }));
  }

  function maybeAutoSeedStrategy(s) {
    if (!s || strategyEvalState.enabled === false) return;
    var id = String(s.strategyId || '');
    if (!id || Number(s.runCount || 0) > 0) return;
    if (strategyEvalState.autoSeedStarted[id]) return;
    strategyEvalState.autoSeedStarted[id] = true;
    startStrategyPoolSeed(false, { auto: true });
  }

  function findStrategyById(id) {
    var list = strategyEvalState.strategies || [];
    for (var i = 0; i < list.length; i++) {
      if (String(list[i].strategyId || '') === String(id || '')) return list[i];
    }
    return null;
  }

  function strategyTargetText(r) {
    if (!r) return '—';
    if (String(r.kind || '').toUpperCase() === 'PORTFOLIO') {
      var codes = r.stockCodes;
      if (Array.isArray(codes) && codes.length) {
        return codes.length <= 3 ? codes.join(',') : (codes.slice(0, 3).join(',') + '…+' + (codes.length - 3));
      }
      return '组合';
    }
    return r.stockCode || '—';
  }

  function sortStrategyHistoryRows(rows, sortKey) {
    var list = (rows || []).slice();
    var key = sortKey || strategyEvalState.sort || 'time_desc';
    list.sort(function (a, b) {
      if (key === 'rate_desc' || key === 'rate_asc') {
        var ra = Number(a && a.totalRate);
        var rb = Number(b && b.totalRate);
        if (!isFinite(ra)) ra = key === 'rate_desc' ? -Infinity : Infinity;
        if (!isFinite(rb)) rb = key === 'rate_desc' ? -Infinity : Infinity;
        return key === 'rate_desc' ? (rb - ra) : (ra - rb);
      }
      var ta = a && a.savedAt ? Date.parse(String(a.savedAt).replace(' ', 'T')) : 0;
      var tb = b && b.savedAt ? Date.parse(String(b.savedAt).replace(' ', 'T')) : 0;
      if (isNaN(ta)) ta = 0;
      if (isNaN(tb)) tb = 0;
      return key === 'time_asc' ? (ta - tb) : (tb - ta);
    });
    return list;
  }

  function renderStrategyHistory(rows) {
    var $tb = $('#strategyHistoryBody').empty();
    collapseHistoryAnalysis($tb);
    strategyEvalState.historyRows = rows || [];
    var sorted = sortStrategyHistoryRows(strategyEvalState.historyRows, strategyEvalState.sort);
    $('#strategyHistMeta').text('共 ' + sorted.length + ' 条');
    if (!sorted.length) {
      $tb.append($('<tr/>').append(
        $('<td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state"/>')
          .text('该策略暂无回测记录')
      ));
      return;
    }
    sorted.forEach(function (r) {
      var kind = String(r.kind || '').toUpperCase();
      var kindLabel = kind === 'PORTFOLIO' ? '组合' : (kind === 'SINGLE' ? '单股' : (r.kind || '—'));
      var kindClass = kind === 'PORTFOLIO'
        ? 'strategy-tag strategy-tag--kind-pf'
        : (kind === 'SINGLE' ? 'strategy-tag strategy-tag--kind-single' : 'strategy-tag strategy-tag--muted');
      var $tr = $('<tr class="history-row"/>')
        .css('cursor', 'pointer')
        .attr('data-id', r.id || '')
        .attr('title', '点击展开详情（含区间、资金、买卖次数）');
      $tr.append($('<td class="mono"/>').text(fmtDateTimeDisplay(r.savedAt)))
        .append($('<td/>').html('<span class="' + kindClass + '">' + escHtml(kindLabel) + '</span>'))
        .append($('<td/>').html('<b>' + escHtml(strategyTargetText(r)) + '</b>'))
        .append($('<td class="num"/>').html(
          '<span class="' + pnlClass(r.totalRate) + '">' + escHtml(pct(r.totalRate)) + '</span>'
        ))
        .append($('<td class="num"/>').text(pct(r.maxDrawdown != null ? r.maxDrawdown : r.maxDrawDown)))
        .append($('<td class="num"/>').text(pct(r.winRate)))
        .append($('<td class="num"/>').text(r.sharpe != null && r.sharpe !== '' ? num(r.sharpe, 2) : '—'));
      $tb.append($tr);
    });
  }

  function loadStrategyHistory(strategyId) {
    var id = String(strategyId || strategyEvalState.selectedId || '').trim();
    var $tb = $('#strategyHistoryBody');
    if (!id) {
      strategyEvalState.historyRows = [];
      $tb.html('<tr><td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state">请选择左侧策略</td></tr>');
      $('#strategyHistMeta').text('');
      return;
    }
    collapseHistoryAnalysis($tb);
    $tb.html('<tr><td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state">加载中…</td></tr>');
    var kind = strategyEvalState.kind || 'ALL';
    $.getJSON('/api/strategy/' + encodeURIComponent(id) + '/history', { kind: kind })
      .done(function (rows) {
        if (String(strategyEvalState.selectedId || '') !== id) return;
        renderStrategyHistory(rows || []);
      })
      .fail(function (xhr) {
        if (String(strategyEvalState.selectedId || '') !== id) return;
        strategyEvalState.historyRows = [];
        var msg = (xhr && xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error))
          || '策略历史加载失败';
        $tb.html('<tr><td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state">'
          + escHtml(msg) + '</td></tr>');
        $('#strategyHistMeta').text('');
        toast(msg, 'err');
      });
  }

  function selectStrategy(strategyId) {
    var id = String(strategyId || '').trim();
    if (!id) return;
    strategyEvalState.selectedId = id;
    renderStrategyList(strategyEvalState.strategies, id);
    var s = findStrategyById(id);
    renderStrategyIntro(s);
    renderStrategyCards(s);
    loadStrategyHistory(id);
    maybeAutoSeedStrategy(s);
  }

  function loadStrategyOverview() {
    $('#strategyList').html('<div class="hint">加载策略…</div>');
    $('#strategyEvalCards').empty();
    $('#strategyUnknownHint').prop('hidden', true).text('');
    $('#strategyHistoryBody').html(
      '<tr><td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state">加载中…</td></tr>'
    );
    $.getJSON('/api/strategy/overview')
      .done(function (data) {
        data = data || {};
        strategyEvalState.enabled = data.enabled !== false;
        strategyEvalState.unknownCount = Number(data.unknownCount || 0);
        strategyEvalState.strategies = data.strategies || [];
        var preferred = strategyEvalState.selectedId;
        var pick = null;
        var list = strategyEvalState.strategies;
        if (preferred) {
          pick = findStrategyById(preferred);
        }
        if (!pick) {
          for (var i = 0; i < list.length; i++) {
            if (list[i].active) { pick = list[i]; break; }
          }
        }
        if (!pick && list.length) pick = list[0];
        renderStrategyList(list, pick ? pick.strategyId : '');
        if (pick) {
          selectStrategy(pick.strategyId);
        } else {
          strategyEvalState.selectedId = '';
          renderStrategyCards(null);
          renderStrategyIntro(null);
          $('#strategyHistoryBody').html(
            '<tr><td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state">暂无注册策略</td></tr>'
          );
          $('#strategyHistMeta').text('');
        }
      })
      .fail(function (xhr) {
        var msg = (xhr && xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error))
          || '策略概览加载失败';
        $('#strategyList').html('<div class="hint">' + escHtml(msg) + '</div>');
        $('#strategyEvalCards').empty();
        $('#strategyHistoryBody').html(
          '<tr><td colspan="' + STRATEGY_HIST_COLSPAN + '" class="empty-state">'
          + escHtml(msg) + '</td></tr>'
        );
        toast(msg, 'err');
      });
  }

  function renderStrategyDetailPanel(detail, $panel, $tb) {
    var openId = $panel.attr('data-open-id');
    $panel.empty();
    if (openId) $panel.attr('data-open-id', openId);

    var $head = $('<div class="analysis-detail-head"/>');
    $head.append($('<span/>').html('<b>回测详情</b>'));
    var $collapse = $('<button type="button" class="secondary analysis-collapse-btn"/>').text('收起');
    $collapse.on('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      collapseHistoryAnalysis($tb);
    });
    $head.append($collapse);
    $panel.append($head);

    if (!detail || !detail.id) {
      $panel.append($('<p class="hint"/>').text('未找到该回测记录详情。'));
      return;
    }

    var s = resolveTradeStats(detail);
    $panel.append($('<p/>').html(
      '<b>区间 / 资金</b>：' + escHtml(formatRange(detail.backStart, detail.backEnd))
      + ' · 初始 ' + escHtml(num(detail.initCapital))
      + ' · 期末 ' + escHtml(num(detail.finalAsset))
    ));
    $panel.append($('<p/>').html(
      '<b>成交统计</b>：买/卖 ' + (s.buyCount || 0) + '/' + (s.sellCount || 0)
      + ' 次 · 手 ' + (s.buyLots || 0) + '/' + (s.sellLots || 0)
      + ' · 买入额 ' + num(s.buyAmount)
      + ' · 卖出额 ' + num(s.sellAmount)
      + ' · 费用 ' + num(s.totalFee)
      + ' · 盈亏 ' + pnlText(s.totalPnl)
    ));
    if (detail.configFingerprint) {
      $panel.append($('<p class="hint"/>').text('指纹：' + detail.configFingerprint));
    }

    var analysis = detail.analysis;
    if (!analysis) {
      $panel.append($('<p class="hint"/>').text('未找到与该回测记录对应的分析（旧记录可能无分析，请重新回测）。'));
      return;
    }
    appendEscapedAnalysisBody($panel, analysis.summary, analysis.events);
  }

  function showStrategyHistoryDetail($tr) {
    var $tb = $('#strategyHistoryBody');
    var id = String($tr.attr('data-id') || '');
    var $panel = ensureInlineAnalysisRow($tr, STRATEGY_HIST_COLSPAN);
    if (!id) {
      $panel.html('<p class="hint">该记录无 id，无法加载详情。</p>');
      return;
    }
    $panel.attr('data-open-id', id).html('<p class="hint">加载详情中…</p>');
    $.getJSON('/api/strategy/history/' + encodeURIComponent(id))
      .done(function (detail) {
        if (!$panel.closest('tbody').length) return;
        if (String($panel.attr('data-open-id') || '') !== id) return;
        if (!$tr.hasClass('active')) return;
        renderStrategyDetailPanel(detail, $panel, $tb);
      })
      .fail(function (xhr) {
        if (!$panel.closest('tbody').length) return;
        if (String($panel.attr('data-open-id') || '') !== id) return;
        var msg = (xhr && xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error))
          || '加载详情失败';
        $panel.attr('data-open-id', id).html('<p class="hint">' + escHtml(msg) + '</p>');
        toast(msg, 'err');
      });
  }

  function showAccountPanel(panel) {
    panel = panel || lastAccountPanel || 'funds';
    if (panel !== 'funds' && panel !== 'positions' && panel !== 'orders'
        && panel !== 'cashflows' && panel !== 'risklogs' && panel !== 'riskdash'
        && panel !== 'papergap') {
      panel = 'funds';
    }
    lastAccountPanel = panel;
    lastWorkspaceMode = 'account';
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    setSideNavOpen('accountBody');
    setAccountMenuActive(panel);
    if (panel === 'positions') {
      $('#viewAcctPositions').prop('hidden', false);
      loadAccountOverview();
    } else if (panel === 'orders') {
      $('#viewAcctOrders').prop('hidden', false);
      loadAccountOverview();
    } else if (panel === 'cashflows') {
      $('#viewAcctCashflows').prop('hidden', false);
      loadAccountCashflows();
      loadAccountOverview();
    } else if (panel === 'risklogs') {
      $('#viewAcctRiskLogs').prop('hidden', false);
      loadAccountRiskLogs();
      loadAccountOverview();
    } else if (panel === 'riskdash') {
      $('#viewAcctRiskDash').prop('hidden', false);
      loadAccountRiskDash();
    } else if (panel === 'papergap') {
      $('#viewAcctPaperGap').prop('hidden', false);
      loadAccountPaperGap();
    } else {
      $('#viewAcctFunds').prop('hidden', false);
      loadAccountOverview();
    }
    var acctLabels = {
      funds: '资金权益', positions: '当前持仓', orders: '委托记录', cashflows: '权益日结',
      risklogs: '风控事件', riskdash: '风控日报', papergap: '纸面对账'
    };
    breadcrumbForMode('account', acctLabels[panel] || panel);
    saveNavSession({ mode: 'account', panel: panel });
    resizeCharts();
  }

  function riskRuleLabel(t) {
    var map = {
      DRAWDOWN_HALT: '峰值回撤熔断',
      DRAWDOWN_DURATION_HALT: '回撤持续期熔断',
      STRATEGY_RETIRED: '策略退役',
      DAILY_LOSS: '单日亏损禁开',
      CONSECUTIVE_LOSS: '连亏禁开'
    };
    return map[t] || t || '—';
  }

  function renderAccountCashflows(data) {
    data = data || {};
    var rows = data.items || [];
    setCountBadge('#acctCfBadge, #sideCfCount', data.count != null ? data.count : rows.length);
    if (data.hint) $('#acctCfHint').text(data.hint);
    $('#acctCfMeta').text(rows.length ? ('共 ' + rows.length + ' 个交易日') : '');
    lastAcctEquity = {
      equityTimes: data.equityTimes || [],
      equityCurve: data.equityCurve || []
    };
    if (acctEquityChart) {
      if (lastAcctEquity.equityTimes.length) {
        renderEquityChart(lastAcctEquity, acctEquityChart);
      } else {
        try { acctEquityChart.clear(); } catch (e) {}
      }
    }
    var $tb = $('#acctCfBody').empty();
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="9" class="empty-state"/>').text('暂无日结（请先跑收盘清算）')));
      return;
    }
    rows.forEach(function (it) {
      $tb.append(
        '<tr>'
        + '<td class="mono">' + escHtml(it.tradeDate || '—') + '</td>'
        + '<td class="mono">' + escHtml(num(it.cash)) + '</td>'
        + '<td class="mono">' + escHtml(num(it.marketValue)) + '</td>'
        + '<td class="mono"><b>' + escHtml(num(it.totalEquity)) + '</b></td>'
        + '<td class="mono">' + escHtml(num(it.peakEquity)) + '</td>'
        + '<td class="mono ' + pnlClass(it.dailyPnl) + '">' + escHtml(num(it.dailyPnl)) + '</td>'
        + '<td class="mono ' + pnlClass(it.dailyPnlRate) + '">' + escHtml(pctFine(it.dailyPnlRate)) + '</td>'
        + '<td class="mono">' + escHtml(pct(it.drawdownRate)) + '</td>'
        + '<td class="mono">' + escHtml(String(it.consecutiveLossCount == null ? 0 : it.consecutiveLossCount)) + '</td>'
        + '</tr>'
      );
    });
  }

  function renderAccountRiskLogs(data) {
    data = data || {};
    var rows = data.items || [];
    setCountBadge('#acctRiskBadge, #sideRiskCount', data.count != null ? data.count : rows.length);
    if (data.hint) $('#acctRiskHint').text(data.hint);
    $('#acctRiskMeta').text(rows.length ? ('共 ' + rows.length + ' 条') : '');
    var $tb = $('#acctRiskBody').empty();
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="6" class="empty-state"/>').text('暂无风控事件')));
      return;
    }
    rows.forEach(function (it) {
      $tb.append(
        '<tr>'
        + '<td class="mono">' + escHtml(it.createdAt || '—') + '</td>'
        + '<td class="mono">' + escHtml(it.logDate || '—') + '</td>'
        + '<td><b>' + escHtml(it.symbol || '—') + '</b></td>'
        + '<td>' + escHtml(riskRuleLabel(it.ruleType)) + '</td>'
        + '<td class="mono">' + escHtml(it.triggerValue == null ? '—' : pct(it.triggerValue)) + '</td>'
        + '<td>' + escHtml(it.actionTaken || '—') + '</td>'
        + '</tr>'
      );
    });
  }

  function loadAccountCashflows() {
    $.getJSON('/api/account/cashflows', { limit: 120 })
      .done(function (data) {
        renderAccountCashflows(data);
        setTimeout(function () {
          try { if (acctEquityChart) acctEquityChart.resize(); } catch (e) {}
        }, 60);
      })
      .fail(function (xhr) {
        var msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || '权益日结加载失败';
        $('#acctCfHint').text(msg);
        toast(msg, 'err');
      });
  }

  function loadAccountRiskLogs() {
    $.getJSON('/api/account/risk-logs', { limit: 100 })
      .done(renderAccountRiskLogs)
      .fail(function (xhr) {
        var msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || '风控事件加载失败';
        $('#acctRiskHint').text(msg);
        toast(msg, 'err');
      });
  }

  function gapDimLabel(d) {
    var map = {
      FLICKER: '闪烁', COST: '成本', SELECTION: '选股',
      FILL_ASSUMPTION: '撮合', MODE: '模式'
    };
    return map[d] || d || '—';
  }

  function dashText() {
    var parts = [];
    for (var i = 0; i < arguments.length; i++) {
      if (arguments[i] != null && arguments[i] !== '') parts.push(String(arguments[i]));
    }
    return parts.length ? parts.join(' · ') : '—';
  }

  function loadAccountRiskDash() {
    var urls = [
      '/api/account/alerts?limit=20',
      '/api/account/turnover',
      '/api/account/ic-decay',
      '/api/account/signal-drift',
      '/api/account/structural-break',
      '/api/account/stress',
      '/api/account/partial-fill',
      '/api/account/slippage-residual',
      '/api/account/order-protect',
      '/api/account/execution-cap',
      '/api/account/short-policy',
      '/api/account/correlation'
    ];
    var keys = [
      'alerts', 'turnover', 'icDecay', 'signalDrift', 'structuralBreak',
      'stress', 'partialFill', 'slippage', 'orderProtect', 'executionCap', 'shortPolicy', 'correlation'
    ];
    var reqs = urls.map(function (u) {
      var dfd = $.Deferred();
      $.getJSON(u)
        .done(function (d) { dfd.resolve(d || {}); })
        .fail(function () { dfd.resolve({ _error: true }); });
      return dfd.promise();
    });
    $.when.apply($, reqs)
      .done(function () {
        var bag = {};
        var args = arguments;
        // 单请求时 $.when 直接返回该对象，多请求时为参数列表
        if (keys.length === 1) {
          bag[keys[0]] = args[0];
        } else {
          for (var i = 0; i < keys.length; i++) {
            bag[keys[i]] = args[i];
          }
        }
        var alertN = (bag.alerts && bag.alerts.recent) ? bag.alerts.recent.length
          : (bag.alerts && bag.alerts.count != null ? bag.alerts.count : 0);
        $('#acctDashAlerts').text(String(alertN));
        $('#acctDashTurnover').text(dashText(
          bag.turnover && bag.turnover.softHit ? '软顶' : null,
          bag.turnover && bag.turnover.hardHit ? '硬顶' : null,
          bag.turnover && bag.turnover.scaleMultiplier != null ? ('×' + bag.turnover.scaleMultiplier) : null,
          bag.turnover && bag.turnover.enabled === false ? '关' : null
        ));
        $('#acctDashIc').text(dashText(
          bag.icDecay && bag.icDecay.decayActive ? '衰减中' : '正常',
          bag.icDecay && bag.icDecay.ir != null ? ('IR=' + bag.icDecay.ir) : null,
          bag.icDecay && bag.icDecay.scaleMultiplier != null ? ('×' + bag.icDecay.scaleMultiplier) : null
        ));
        $('#acctDashDrift').text(dashText(
          bag.signalDrift && bag.signalDrift.killArmed ? 'Kill' : '监控中',
          bag.signalDrift && bag.signalDrift.icSource ? bag.signalDrift.icSource : null,
          bag.signalDrift && bag.signalDrift.rollingIc != null ? ('IC=' + bag.signalDrift.rollingIc) : null
        ));
        $('#acctDashBreak').text(dashText(
          bag.structuralBreak && bag.structuralBreak.active ? '触发' : '未触发',
          bag.structuralBreak && bag.structuralBreak.score != null ? ('score=' + bag.structuralBreak.score) : null
        ));
        $('#acctDashStress').text(dashText(
          bag.stress && bag.stress.activeScenario ? bag.stress.activeScenario : null,
          bag.stress && bag.stress.scaleMultiplier != null ? ('×' + bag.stress.scaleMultiplier) : null,
          bag.stress && bag.stress.enabled === false ? '关' : '就绪'
        ));
        $('#acctDashPartial').text(dashText(
          bag.partialFill && bag.partialFill.fillRatio != null ? ('ratio=' + bag.partialFill.fillRatio) : null,
          bag.partialFill && bag.partialFill.partialCount != null ? ('部成' + bag.partialFill.partialCount) : null,
          bag.partialFill && bag.partialFill.hint ? null : null
        ));
        $('#acctDashSlip').text(dashText(
          bag.slippage && bag.slippage.avgAbsAdverseBps != null ? (bag.slippage.avgAbsAdverseBps + 'bp') : null,
          bag.slippage && bag.slippage.sampleCount != null ? ('n=' + bag.slippage.sampleCount) : null
        ));
        $('#acctDashProtect').text(dashText(
          bag.orderProtect && bag.orderProtect.enabled ? '开' : '关',
          bag.orderProtect && bag.orderProtect.fiveLevelBook
        ));
        $('#acctDashExec').text(dashText(
          bag.executionCap && bag.executionCap.effectiveMaxParticipationAdv != null
            ? ('ADV=' + bag.executionCap.effectiveMaxParticipationAdv) : null,
          bag.executionCap && bag.executionCap.twapSlicer
        ));
        $('#acctDashShort').text(dashText(
          bag.shortPolicy && bag.shortPolicy.mode,
          bag.shortPolicy && bag.shortPolicy.allowShort === false ? '禁空' : null
        ));
        $('#acctDashCorr').text(dashText(
          bag.correlation && bag.correlation.warn ? '告警' : '正常',
          bag.correlation && bag.correlation.maxCorrelation != null
            ? ('max=' + bag.correlation.maxCorrelation) : null,
          bag.correlation && bag.correlation.avgCorrelation != null
            ? ('avg=' + bag.correlation.avgCorrelation) : null,
          bag.correlation && bag.correlation.pairCount != null ? ('对=' + bag.correlation.pairCount) : null
        ));
        var okN = 0;
        keys.forEach(function (k) { if (bag[k] && !bag[k]._error) okN++; });
        setCountBadge('#acctDashBadge, #sideDashCount', okN);
        $('#acctDashMeta').text('已加载 ' + okN + '/' + keys.length + ' · ' + new Date().toLocaleString());
        $('#acctDashHint').text('聚合只读监控；外部五档/TWAP/真柜台仍不可用。');
        try {
          $('#acctDashRaw').text(JSON.stringify(bag, null, 2));
        } catch (e) {
          $('#acctDashRaw').text(String(e));
        }
      })
      .fail(function () {
        $('#acctDashHint').text('风控日报加载失败');
        toast('风控日报加载失败', 'err');
      });
  }

  function loadAccountPaperGap() {
    $.getJSON('/api/account/paper-live-gap')
      .done(function (data) {
        data = data || {};
        var sum = data.summary || {};
        var gaps = data.gaps || [];
        var costs = data.costRows || [];
        $('#acctGapPass').text(data.gatePass ? '通过' : '告警');
        $('#acctGapMode').text(data.tradeMode || '—');
        $('#acctGapFp').text(data.configFingerprint || '—');
        $('#acctGapSameDay').text(sum.sameDayFillVsNextBar == null ? '—' : String(sum.sameDayFillVsNextBar));
        $('#acctGapFeeSum').text(sum.feeResidualSum == null ? '—' : num(sum.feeResidualSum));
        $('#acctGapPartial').text(sum.partialCount == null ? '—' : String(sum.partialCount));
        $('#acctGapAdverseBps').text(sum.avgAbsAdverseBps == null ? '—' : String(sum.avgAbsAdverseBps));
        setCountBadge('#acctGapBadge, #sideGapCount', gaps.length);
        $('#acctGapMeta').text(data.asOf ? ('更新：' + data.asOf) : '');
        if (data.gateHint) $('#acctGapHint').text(data.gateHint);
        var $g = $('#acctGapBody').empty();
        if (!gaps.length) {
          $g.html('<tr><td colspan="6" class="empty-state">暂无差异条目</td></tr>');
        } else {
          gaps.forEach(function (it) {
            $g.append(
              '<tr>'
              + '<td>' + escHtml(gapDimLabel(it.dimension)) + '</td>'
              + '<td>' + escHtml(it.severity || '—') + '</td>'
              + '<td><b>' + escHtml(it.code || '—') + '</b></td>'
              + '<td>' + escHtml(it.title || '—') + '</td>'
              + '<td>' + escHtml(it.detail || '—') + '</td>'
              + '<td class="mono">' + escHtml(it.orderId || '—') + '</td>'
              + '</tr>'
            );
          });
        }
        var $c = $('#acctGapCostBody').empty();
        if (!costs.length) {
          $c.html('<tr><td colspan="9" class="empty-state">暂无成交对照</td></tr>');
        } else {
          costs.forEach(function (it) {
            $c.append(
              '<tr>'
              + '<td class="mono">' + escHtml(it.orderId || '—') + '</td>'
              + '<td><b>' + escHtml(it.code || '—') + '</b></td>'
              + '<td>' + escHtml(sideLabel(it.side)) + '</td>'
              + '<td class="mono">' + escHtml(num(it.orderPrice)) + '</td>'
              + '<td class="mono">' + escHtml(num(it.filledPrice)) + '</td>'
              + '<td class="mono">' + escHtml(num(it.priceResidualBps, 2)) + '</td>'
              + '<td class="mono">' + escHtml(num(it.actualFee)) + '</td>'
              + '<td class="mono">' + escHtml(num(it.modelFee)) + '</td>'
              + '<td class="mono ' + pnlClass(it.feeResidual) + '">' + escHtml(num(it.feeResidual)) + '</td>'
              + '</tr>'
            );
          });
        }
      })
      .fail(function (xhr) {
        var msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || '纸面对账加载失败';
        $('#acctGapHint').text(msg);
        toast(msg, 'err');
      });
  }

  function pnlClass(v) {
    var n = Number(v);
    if (!isFinite(n) || n === 0) return '';
    return n > 0 ? 'pnl-pos' : 'pnl-neg';
  }

  function sideLabel(side) {
    if (side === 'BUY') return '买';
    if (side === 'SELL') return '卖';
    return side || '—';
  }

  function orderStatusLabel(st) {
    var map = {
      PENDING: '待报', SUBMITTED: '已报', PARTIAL: '部成',
      FILLED: '已成', CANCELLED: '已撤', REJECTED: '拒单'
    };
    return map[st] || st || '—';
  }

  function renderAccountFunds(data) {
    data = data || {};
    setCountBadge('#acctModeBadge', data.source || data.mode || 'LOCAL_SIM');
    if (data.hint) $('#acctFundsHint').text(data.hint);
    $('#acctFundsAsOf').text(data.asOf ? ('更新：' + data.asOf) : '');
    $('#acctEquity').text(num(data.equity));
    $('#acctCash').text(num(data.cash));
    $('#acctPosMv').text('持仓市值 ' + num(data.positionMv));
    $('#acctTotalReturn').attr('class', 'sub ' + pnlClass(data.totalReturn))
      .text('累计收益 ' + pctFine(data.totalReturn));
    $('#acctDayPnl').attr('class', 'value ' + pnlClass(data.dayPnl)).text(num(data.dayPnl));
    $('#acctDayPnlPct').attr('class', 'value ' + pnlClass(data.dayPnlPct)).text(pctFine(data.dayPnlPct));
    $('#acctInit').text(formatCapitalCn(data.initCapital) + ' / ' + num(data.initCapital));
    $('#acctDrawdown').text(pct(data.drawdown));
    $('#acctPeak').text(num(data.peakEquity));
    $('#acctPrevClose').text(num(data.prevCloseEquity));
    var haltReasonMap = { DEPTH: '深度', DURATION: '持续期' };
    $('#acctHalted').text(data.halted ? '是（禁开）' : '否');
    $('#acctHaltReason').text(data.halted
      ? (haltReasonMap[data.haltReason] || data.haltReason || '—') : '—');
    var uw = data.underwaterTradingDays;
    var uwNeed = data.drawdownDurationHaltDays;
    $('#acctUnderwater').text(uw == null ? '—'
      : (String(uw) + (uwNeed ? (' / 熔断阈 ' + uwNeed) : '')));
    $('#acctAllowOpen').text(data.allowNewOpen ? '是' : '否');
    $('#acctPosScale').text(data.positionScale == null ? '—' : num(data.positionScale, 2) + '×');
    $('#acctLossStreak').text(data.consecutiveLosses == null ? '—' : String(data.consecutiveLosses));
    var ret = data.retirement || {};
    $('#acctRetired').text(data.strategyRetired
      ? ('是 · 剩余冷却 ' + (ret.remainingCooldownDays == null ? '—' : ret.remainingCooldownDays) + ' 日')
      : '否');
    $('#acctRetireHint').text(ret.hint || '');
  }

  function pendingLabel(it) {
    var parts = [];
    if (it.pendingBuy) parts.push('待买' + (it.pendingBuyVol ? it.pendingBuyVol : ''));
    if (it.pendingSell) parts.push('待卖');
    return parts.length ? parts.join('/') : '—';
  }

  function renderAccountPositions(items) {
    items = items || [];
    setCountBadge('#acctPosBadge, #sidePosCount', items.length);
    var $body = $('#acctPosBody').empty();
    if (!items.length) {
      $body.html('<tr><td colspan="11" class="empty-state">暂无持仓</td></tr>');
      $('#acctPosHint').text('无持仓');
      return;
    }
    items.forEach(function (it) {
      var code = it.code || '';
      var $tr = $('<tr class="acct-pos-row"/>')
        .attr('data-code', code)
        .css('cursor', 'pointer')
        .html(
          '<td><b>' + escHtml(code) + '</b></td>'
          + '<td>' + escHtml(it.name || '') + '</td>'
          + '<td class="mono">' + escHtml(String(it.volume == null ? '—' : it.volume))
          + (it.ledgerDesync ? ' <span class="tag-wait" title="网关与批次数量不一致">分歧</span>' : '')
          + '</td>'
          + '<td class="mono">' + escHtml(String(it.sellableShares == null ? '—' : it.sellableShares)) + '</td>'
          + '<td class="mono">' + escHtml(num(it.avgCost)) + '</td>'
          + '<td class="mono">' + escHtml(num(it.lastPrice)) + '</td>'
          + '<td class="mono">' + escHtml(num(it.marketValue)) + '</td>'
          + '<td class="mono ' + pnlClass(it.unrealizedPnlPct) + '">' + escHtml(pctFine(it.unrealizedPnlPct)) + '</td>'
          + '<td class="mono">' + escHtml(num(it.stopPrice)) + '</td>'
          + '<td>' + escHtml(pendingLabel(it)) + '</td>'
          + '<td class="mono">' + escHtml(String(it.pyramidStage == null ? 0 : it.pyramidStage)) + '</td>'
        );
      $body.append($tr);
      var lots = it.lots || [];
      var lotHtml = '<div class="hint" style="margin:0 0 6px;">持仓批次（点行切换）· 最高价 '
        + escHtml(num(it.highestSinceEntry)) + ' · 买入日 ' + escHtml(it.lastBuyDate || '—') + '</div>';
      if (!lots.length) {
        lotHtml += '<p class="hint" style="margin:0;">无批次明细</p>';
      } else {
        lotHtml += '<table class="tp-table"><thead><tr><th>开仓日</th><th>股数</th><th>成本</th><th>可卖</th></tr></thead><tbody>';
        lots.forEach(function (lot) {
          lotHtml += '<tr><td class="mono">' + escHtml(lot.openDate || '—') + '</td>'
            + '<td class="mono">' + escHtml(String(lot.shares)) + '</td>'
            + '<td class="mono">' + escHtml(num(lot.cost)) + '</td>'
            + '<td>' + (lot.sellable ? '是' : '否(T+1)') + '</td></tr>';
        });
        lotHtml += '</tbody></table>';
      }
      $body.append(
        $('<tr class="acct-pos-lots" hidden/>').attr('data-code', code)
          .append($('<td colspan="11"/>').html(lotHtml))
      );
    });
    var desyncN = 0;
    items.forEach(function (it) { if (it.ledgerDesync) desyncN++; });
    $('#acctPosHint').text('共 ' + items.length + ' 只 · 数量以批次为准'
      + (desyncN ? (' · ' + desyncN + ' 只网关分歧') : '')
      + ' · 点击行展开批次');
  }

  function orderTypeLabel(t) {
    var map = {
      1: '首开/买入', 2: '加仓30', 3: '加仓20',
      4: '死叉/卖出', 5: '止损', 6: '止盈', 7: '熔断'
    };
    if (t == null || t === '') return '—';
    return map[t] || map[String(t)] || ('类型' + t);
  }

  function renderAccountOrders(items) {
    items = items || [];
    setCountBadge('#acctOrderBadge, #sideOrderCount', items.length);
    var $body = $('#acctOrderBody').empty();
    if (!items.length) {
      $body.html('<tr><td colspan="14" class="empty-state">暂无委托</td></tr>');
      $('#acctOrderHint').text('无委托');
      return;
    }
    var rows = items.slice();
    if (rows.length && rows[0].source !== 'DB') {
      rows = rows.slice().reverse();
    }
    rows.forEach(function (it) {
      var filled = it.filledVolume != null ? it.filledVolume : (it.status === 'FILLED' ? it.volume : '—');
      var st = String(it.status || '').toUpperCase();
      var oid = it.orderId || '';
      var open = st === 'SUBMITTED' || st === 'PARTIAL' || st === '2' || st === '3';
      var ops = '—';
      if (open && oid) {
        ops = '<button type="button" class="secondary btn-order-cancel" data-id="' + escHtml(oid) + '">撤</button> '
          + '<button type="button" class="secondary btn-order-partial" data-id="' + escHtml(oid) + '">部成</button> '
          + '<button type="button" class="secondary btn-order-replace" data-id="' + escHtml(oid)
          + '" data-price="' + escHtml(String(it.price == null ? '' : it.price)) + '">改价</button>';
      }
      $body.append(
        '<tr>'
        + '<td class="mono">' + escHtml(it.orderId || it.clientOrderId || '—') + '</td>'
        + '<td><b>' + escHtml(it.code) + '</b></td>'
        + '<td>' + escHtml(sideLabel(it.side)) + '</td>'
        + '<td>' + escHtml(orderTypeLabel(it.orderType)) + '</td>'
        + '<td class="mono">' + escHtml(num(it.price)) + '</td>'
        + '<td class="mono">' + escHtml(String(it.volume == null ? '—' : it.volume)) + '</td>'
        + '<td class="mono">' + escHtml(String(filled)) + '</td>'
        + '<td class="mono">' + escHtml(num(it.amount)) + '</td>'
        + '<td class="mono">' + escHtml(it.fee == null ? '—' : num(it.fee)) + '</td>'
        + '<td>' + escHtml(orderStatusLabel(it.status)) + '</td>'
        + '<td class="mono">' + escHtml(it.signalDate || '—') + '</td>'
        + '<td class="mono">' + escHtml(it.executionDate || '—') + '</td>'
        + '<td>' + escHtml(it.source || '—') + '</td>'
        + '<td class="acct-order-ops">' + ops + '</td>'
        + '</tr>'
      );
    });
    var src = rows[0] && rows[0].source === 'DB' ? '库表' : '内存';
    $('#acctOrderHint').text('共 ' + rows.length + ' 笔 · 来源 ' + src);
  }

  function postOrderAction(url, data) {
    return $.ajax({
      url: url,
      method: 'POST',
      data: data || {}
    });
  }

  function loadAccountOverview() {
    $.getJSON('/api/account')
      .done(function (data) {
        renderAccountFunds(data);
        renderAccountPositions(data.positions || []);
        renderAccountOrders(data.orders || []);
        if (data.positionCount != null) setCountBadge('#sidePosCount', data.positionCount);
        if (data.orderCount != null) setCountBadge('#sideOrderCount', data.orderCount);
      })
      .fail(function (xhr) {
        var msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || '账户概览加载失败';
        $('#acctFundsHint').text(msg);
        $('#acctPosHint').text(msg);
        $('#acctOrderHint').text(msg);
        toast(msg, 'err');
      });
  }

  var navIntroCache = {};

  /** 一级菜单专属介绍页（非全局初始化页） */
  function showNavIntro(options) {
    options = options || {};
    var bodyId = options.bodyId;
    var title = options.title || '功能介绍';
    var src = options.src;
    if (!src) return;

    $('body').removeClass('mode-doc home-theme-peek');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    var $intro = $('#viewNavIntro');
    $intro.prop('hidden', false);
    // 重播入场动画
    $intro.removeClass('nav-intro-anim');
    void $intro[0].offsetWidth;
    $intro.addClass('nav-intro-anim');
    setSideNavOpen(bodyId || null);
    $('#navIntroTitle').text(title);
    $('#navIntroBody').html('<p class="nav-intro-loading">加载介绍中…</p>');

    function render(html) {
      $('#navIntroBody').html(html || '<p>暂无介绍</p>');
    }
    if (navIntroCache[src]) {
      render(navIntroCache[src]);
      return;
    }
    $.get(src)
      .done(function (html) {
        navIntroCache[src] = html;
        if ($('#navIntroTitle').text() !== title) return;
        render(html);
      })
      .fail(function () {
        if ($('#navIntroTitle').text() !== title) return;
        render('<p>介绍页加载失败：' + src + '</p>');
      });
  }

  /** 首页仪表盘 */
  function showHome(options) {
    options = options || {};
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    $('#viewHome').prop('hidden', false);
    if (options.collapseNav !== false) {
      setSideNavOpen(null);
    }
    setHomeNavActive(true);
    setHomeCollapsed(false);
    updateBreadcrumb([{ label: '工作台' }]);
    saveNavSession({ mode: 'home', panel: '' });
    loadHomePanel(function () {
      loadHomeDashboardMetrics();
      renderHomeRecent();
    });
  }

  /**
   * @param {string} mode pool|single|portfolio|tradepool|dbtables|schedule|strategy|account
   * @param {{expandNav?: boolean, panel?: string, table?: string}} [options]
   */
  function showMode(mode, options) {
    options = options || {};
    var expandNav = options.expandNav !== false;
    lastWorkspaceMode = mode || 'pool';
    setHomeNavActive(false);
    if (expandNav) {
      ensureSidebarExpanded();
    }
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();

    if (lastWorkspaceMode === 'single') {
      $('#viewSingle').prop('hidden', false);
      if (expandNav) setSideNavOpen('singleBody');
      if (singleCode) {
        selectSingleStock(singleCode, { silent: true });
      }
      renderStockPicker('single');
      focusSinglePanel(options.panel || lastSinglePanel || 'workspace');
      var sp = options.panel || lastSinglePanel || 'workspace';
      breadcrumbForMode('single', sp === 'batch' ? '批量扫描' : (sp === 'history' ? '回测历史' : '回测工作台'));
      saveNavSession({ mode: 'single', panel: sp });
    } else if (lastWorkspaceMode === 'portfolio') {
      $('#viewPortfolio').prop('hidden', false);
      if (expandNav) setSideNavOpen('portfolioBody');
      renderStockPicker('portfolio');
      syncPortfolioCodes();
      loadPortfolioHistory();
      focusPortfolioPanel(options.panel || lastPortfolioPanel || 'workspace');
      var pp = options.panel || lastPortfolioPanel || 'workspace';
      breadcrumbForMode('portfolio', pp === 'history' ? '回测历史' : '回测工作台');
      saveNavSession({ mode: 'portfolio', panel: pp });
    } else if (lastWorkspaceMode === 'tradepool') {
      showTradePool(options.panel || lastTpPanel || 'pool');
      return;
    } else if (lastWorkspaceMode === 'account') {
      showAccountPanel(options.panel || lastAccountPanel || 'funds');
      return;
    } else if (lastWorkspaceMode === 'dbtables') {
      if (expandNav) setSideNavOpen('dbtablesBody');
      showDbTable(options.table || dbTableState.name || '');
      return;
    } else if (lastWorkspaceMode === 'schedule') {
      showSchedulePanel(options.panel || lastSchedulePanel || 'jobs');
      return;
    } else if (lastWorkspaceMode === 'kuangrui') {
      showKuangruiPanel(options.panel || lastKuangruiPanel || 'overview');
      return;
    } else if (lastWorkspaceMode === 'strategy') {
      showStrategyEval();
      return;
    } else {
      lastWorkspaceMode = 'pool';
      $('#viewPool').prop('hidden', false);
      if (expandNav) setSideNavOpen('poolBody');
      $('#poolMenu li').removeClass('active');
      $('#poolMenu li[data-pool-panel="workspace"]').addClass('active');
      renderStockPicker('pool');
      setTimeout(function () { $('#poolStockQ').trigger('focus'); }, 80);
      breadcrumbForMode('pool', '行情工作台');
      saveNavSession({ mode: 'pool', panel: 'workspace' });
    }
    resizeCharts();
  }

  var dbTableState = { name: '', page: 1, size: 20, pages: 0, total: 0 };

  /** 字节 → 可读（B / KB / MB / GB） */
  function formatBytes(n) {
    if (n == null || n === '' || isNaN(Number(n))) return '—';
    n = Number(n);
    if (n < 0) n = 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(n < 10 * 1024 ? 1 : 0) + ' KB';
    if (n < 1024 * 1024 * 1024) {
      var mb = n / (1024 * 1024);
      return mb.toFixed(mb < 10 ? 2 : 1) + ' MB';
    }
    return (n / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  function formatDiskBreakdown(data) {
    data = data || {};
    if (data.totalBytes == null && data.dataBytes == null) return '';
    var total = formatBytes(data.totalBytes != null ? data.totalBytes
      : (Number(data.dataBytes || 0) + Number(data.indexBytes || 0)));
    var detail = '数据 ' + formatBytes(data.dataBytes) + ' / 索引 ' + formatBytes(data.indexBytes);
    if (data.freeBytes != null && Number(data.freeBytes) > 0) {
      detail += ' / 可回收 ' + formatBytes(data.freeBytes);
    }
    return { total: total, detail: detail };
  }

  function loadDbTablesMenu() {
    var $menu = $('#dbtablesMenu').empty();
    $.getJSON('/api/db/tables').done(function (data) {
      var tables = (data && data.tables) || [];
      if (!tables.length) {
        $menu.append($('<li class="hint"/>').text('暂无表白名单'));
        return;
      }
      tables.forEach(function (t) {
        var countTxt = t.rowCount != null ? String(t.rowCount) : '—';
        var sizeTxt = t.totalBytes != null ? formatBytes(t.totalBytes) : '';
        var tipParts = [];
        if (t.rowCount != null) tipParts.push(t.rowCount + ' 行');
        var disk = formatDiskBreakdown(t);
        if (disk) tipParts.push('磁盘约 ' + disk.total + '（' + disk.detail + '）');
        if (t.exists === false) tipParts.push('表可能尚未创建');
        var sizeHtml = sizeTxt
          ? ' <span class="db-tbl-size" title="磁盘约占用（information_schema）">' + escHtml(sizeTxt) + '</span>'
          : '';
        var $li = $('<li role="button" tabindex="0"/>')
          .attr('data-table', t.name)
          .attr('title', tipParts.join(' · ') || t.name)
          .html(
            '<span class="db-tbl-title">' + escHtml(t.title || t.name)
            + ' <span class="trade-pool-count" title="行数">' + escHtml(countTxt) + '</span></span>'
            + '<span class="db-tbl-name">' + escHtml(t.name) + sizeHtml + '</span>'
          );
        if (t.exists === false) {
          $li.css('opacity', '0.55');
        }
        $menu.append($li);
      });
      if (dbTableState.name) {
        $('#dbtablesMenu li[data-table="' + dbTableState.name + '"]').addClass('active');
      }
    }).fail(function () {
      $menu.append($('<li class="hint"/>').text('加载表列表失败'));
    });
  }

  function showDbTable(tableName) {
    lastWorkspaceMode = 'dbtables';
    $('body').removeClass('mode-doc');
    $('#knowledgePanel').prop('hidden', true);
    $('.side-nav-menu li').removeClass('active');
    hideAllWorkspaceViews();
    setSideNavOpen('dbtablesBody');
    $('#viewDbTable').prop('hidden', false);

    if (!tableName) {
      $('#dbTableTitle').text('数据表');
      $('#dbTableHint').text('请从左侧选择一张表');
      $('#dbTableSummary').text('');
      clearDbTableMeta();
      $('#dbTableHead').html('<tr><th>请选择左侧表</th></tr>');
      $('#dbTableBody').html('<tr><td class="empty-state">暂无数据</td></tr>');
      updateDbPager(0, 1, 20, 0);
      breadcrumbForMode('dbtables');
      saveNavSession({ mode: 'dbtables', panel: '', table: '' });
      resizeCharts();
      return;
    }

    dbTableState.name = tableName;
    dbTableState.page = 1;
    dbTableState.size = parseInt($('#dbTablePageSize').val(), 10) || 20;
    $('#dbtablesMenu li[data-table="' + tableName + '"]').addClass('active');
    breadcrumbForMode('dbtables', tableName);
    saveNavSession({ mode: 'dbtables', panel: '', table: tableName });
    loadDbTablePage();
    resizeCharts();
  }

  function loadDbTablePage() {
    var name = dbTableState.name;
    if (!name) return;
    var page = dbTableState.page || 1;
    var size = dbTableState.size || 20;
    $('#dbTableBody').html('<tr><td class="empty-state" colspan="99">加载中…</td></tr>');
    $.getJSON('/api/db/tables/' + encodeURIComponent(name), { page: page, size: size })
      .done(function (data) {
        dbTableState.page = data.page || page;
        dbTableState.size = data.size || size;
        dbTableState.pages = data.pages || 0;
        dbTableState.total = data.total || 0;
        $('#dbTableTitle').text((data.title || name) + ' · ' + name);
        $('#dbTableHint').text('只读分页浏览 · 表结构见下方字段中文说明');
        renderDbTableMeta(data);
        var disk = formatDiskBreakdown(data);
        var summary = '共 ' + dbTableState.total + ' 行';
        if (disk) {
          summary += ' · 磁盘约 ' + disk.total + '（' + disk.detail + '）';
        }
        summary += ' · 第 ' + dbTableState.page + ' / ' + Math.max(1, dbTableState.pages) + ' 页';
        $('#dbTableSummary').text(summary)
          .attr('title', disk
            ? '磁盘占用来自 information_schema；InnoDB 约为已分配空间，与行数无严格正比'
            : '');
        var cols = normalizeDbColumns(data.columns || []);
        if (!cols.length) {
          $('#dbTableHead').html('<tr><th>—</th></tr>');
          $('#dbTableBody').html('<tr><td class="empty-state">表无列或为空</td></tr>');
        } else {
          var head = '<tr>' + cols.map(function (c) {
            var tip = c.comment ? (c.name + ' · ' + c.comment) : c.name;
            return '<th class="db-col-th" title="' + escHtml(tip) + '">'
              + '<span class="db-col-label">' + escHtml(c.label || c.comment || c.name) + '</span>'
              + '<span class="db-col-name">' + escHtml(c.name) + '</span>'
              + '</th>';
          }).join('') + '</tr>';
          $('#dbTableHead').html(head);
          var rows = data.rows || [];
          if (!rows.length) {
            $('#dbTableBody').html('<tr><td class="empty-state" colspan="' + cols.length + '">本页无数据</td></tr>');
          } else {
            var html = rows.map(function (row) {
              return '<tr>' + cols.map(function (c) {
                var v = row[c.name];
                if (v == null) return '<td class="db-cell-null">NULL</td>';
                return '<td title="' + escHtml(String(v)) + '">' + escHtml(String(v)) + '</td>';
              }).join('') + '</tr>';
            }).join('');
            $('#dbTableBody').html(html);
          }
        }
        updateDbPager(dbTableState.total, dbTableState.page, dbTableState.size, dbTableState.pages);
        $('#dbPageJump').val(dbTableState.page);
        $('#dbTablePageSize').val(String(dbTableState.size));
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载失败';
        $('#dbTableTitle').text(name);
        $('#dbTableHint').text(msg);
        clearDbTableMeta();
        $('#dbTableHead').html('<tr><th>错误</th></tr>');
        $('#dbTableBody').html('<tr><td class="empty-state">' + escHtml(msg) + '</td></tr>');
        updateDbPager(0, 1, dbTableState.size, 0);
      });
  }

  function setDbMetaExpanded(open) {
    open = !!open;
    $('#dbTableMeta').toggleClass('is-open', open);
    $('#dbMetaDetail').prop('hidden', !open);
    $('#btnDbMetaToggle').attr('aria-expanded', open ? 'true' : 'false');
  }

  function renderDbTableMeta(data) {
    data = data || {};
    var module = data.module || '—';
    var purpose = data.purpose || '—';
    $('#dbMetaModule').text(module);
    $('#dbMetaPurpose').text(purpose);
    $('#dbMetaSource').text(data.source || '—');
    $('#dbMetaUsage').text(data.usage || '—');
    $('#dbMetaOrder').text(data.orderBy || '—');
    var disk = formatDiskBreakdown(data);
    $('#dbMetaDisk').text(disk ? (disk.total + '（' + disk.detail + '）') : '—');
    // 一行简略：模块 · 功能说明（有磁盘时附总占用）
    var summaryLine = module + ' · ' + purpose;
    if (disk) summaryLine += ' · ' + disk.total;
    $('#dbMetaSummary').text(summaryLine);
    $('#dbTableMeta').prop('hidden', false);
    setDbMetaExpanded(false);
  }

  function clearDbTableMeta() {
    $('#dbTableMeta').prop('hidden', true);
    setDbMetaExpanded(false);
    $('#dbMetaSummary').text('—');
    $('#dbMetaModule, #dbMetaPurpose, #dbMetaSource, #dbMetaUsage, #dbMetaOrder, #dbMetaDisk').text('—');
  }

  function updateDbPager(total, page, size, pages) {
    pages = pages || 0;
    $('#dbPageInfo').text('第 ' + page + ' / ' + Math.max(1, pages) + ' 页（共 ' + total + ' 行）');
    $('#btnDbPrev').prop('disabled', page <= 1);
    $('#btnDbNext').prop('disabled', pages <= 0 || page >= pages);
  }

  /** 兼容 columns: string[] 或 {name,comment,label}[] */
  function normalizeDbColumns(raw) {
    if (!raw || !raw.length) return [];
    return raw.map(function (c) {
      if (typeof c === 'string') {
        return { name: c, comment: '', label: c };
      }
      var name = c.name || c.column || '';
      var comment = c.comment || '';
      var label = c.label || comment || name;
      return { name: name, comment: comment, label: label };
    });
  }

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  var scheduleJobsByCode = {};
  var SCHEDULE_COLSPAN = 6;
  /** Spring 6 段 cron / 固定间隔常用预设 */
  var SCHEDULE_CRON_PRESETS = [
    { label: '工作日 09:30', cron: '0 30 9 * * MON-FRI' },
    { label: '工作日 15:00', cron: '0 0 15 * * MON-FRI' },
    { label: '工作日 16:00', cron: '0 0 16 * * MON-FRI' },
    { label: '工作日 17:00', cron: '0 0 17 * * MON-FRI' },
    { label: '每小时整点', cron: '0 0 * * * *' },
    { label: '每 5 分钟', cron: '0 */5 * * * *' },
    { label: '固定间隔 1 分钟', fixedMs: 60000 },
    { label: '固定间隔 5 分钟', fixedMs: 300000 }
  ];
  var scheduleRunPollTimer = null;
  var scheduleProgressModalMinimized = false;
  var scheduleRunTickTimer = null;
  var scheduleRunPollCode = '';
  var scheduleRunSeenFinishedKey = '';
  var scheduleRunStartedAtMs = 0;
  var scheduleRunLastPayload = null;
  var scheduleRunIdleStreak = 0;
  var scheduleRunPollOkAt = 0;
  var scheduleRunPollFail = 0;
  /** 目标池页发起的 pool-rebuild：完成后刷新列表/漏斗 */
  var pendingTradePoolScanOpts = null;

  function formatElapsedSec(sec) {
    sec = Math.max(0, Number(sec) || 0);
    if (sec < 60) return sec + ' 秒';
    var m = Math.floor(sec / 60);
    var s = sec % 60;
    if (m < 60) return m + ' 分 ' + s + ' 秒';
    var h = Math.floor(m / 60);
    return h + ' 小时 ' + (m % 60) + ' 分';
  }

  function formatClock(ms) {
    var d = new Date(ms || Date.now());
    var p = function (n) { return n < 10 ? '0' + n : String(n); };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  function stopScheduleRunPoll() {
    if (scheduleRunPollTimer) {
      clearInterval(scheduleRunPollTimer);
      scheduleRunPollTimer = null;
    }
    if (scheduleRunTickTimer) {
      clearInterval(scheduleRunTickTimer);
      scheduleRunTickTimer = null;
    }
  }

  function applyScheduleRunButtons(runningCode) {
    $('#scheduleJobBody .sch-run').each(function () {
      var code = $(this).closest('tr').attr('data-code');
      var busy = !!(runningCode && code === runningCode);
      $(this).prop('disabled', busy).text(busy ? '执行中…' : '执行一次');
    });
    var tpBusy = !!(runningCode && (runningCode === 'pool-rebuild' || runningCode === 'after-market-batch-scan'));
    $('#btnTpRebuild, #btnTpHistRebuild').each(function () {
      var $b = $(this);
      if (!$b.data('tpScanIdleText')) {
        $b.data('tpScanIdleText', $.trim($b.text()) || '扫描更新');
      }
      $b.prop('disabled', tpBusy).text(tpBusy ? '扫描中…' : $b.data('tpScanIdleText'));
    });
  }

  function isTdxProgressJob(jobCode) {
    return jobCode === 'day-collect' || jobCode === 'pool-minute-backfill';
  }

  function applyPhaseLabels(jobCode) {
    var $phases = $('#scheduleRunPhases');
    if (!$phases.length) return;
    if (isTdxProgressJob(jobCode)) {
      $phases.find('[data-phase="sync"]').text('① 同步列表');
      $phases.find('[data-phase="fetch"]').text(jobCode === 'day-collect' ? '② 拉取日线' : '② 拉取分钟');
    } else {
      $phases.find('[data-phase="sync"]').text('① 排队/受理');
      $phases.find('[data-phase="fetch"]').text('② 执行中');
    }
    $phases.find('[data-phase="done"]').text('③ 完成');
  }

  function setScheduleRunPhases(phase, running, failed, jobCode) {
    var $phases = $('#scheduleRunPhases');
    if (!$phases.length) return;
    applyPhaseLabels(jobCode || scheduleRunPollCode);
    var show = running || phase === 'sync' || phase === 'fetch' || phase === 'done' || phase === 'error'
      || phase === 'starting' || phase === 'queued' || phase === 'running';
    if (show) $phases.show(); else $phases.hide();
    var map = {
      starting: 'sync',
      queued: 'sync',
      idle: 'sync',
      running: 'fetch',
      sync: 'sync',
      fetch: 'fetch',
      done: 'done',
      error: 'done'
    };
    var active = map[phase] || (running ? 'fetch' : 'done');
    var order = ['sync', 'fetch', 'done'];
    var activeIdx = order.indexOf(active);
    $phases.find('.schedule-run-phase').each(function () {
      var p = $(this).attr('data-phase');
      var idx = order.indexOf(p);
      $(this).removeClass('is-active is-done is-error');
      if (p === 'done') {
        $(this).text(failed ? '③ 失败' : '③ 完成');
      }
      if (failed && p === 'done') {
        $(this).addClass('is-error');
      } else if (idx < activeIdx || (!running && phase === 'done' && idx <= activeIdx)) {
        $(this).addClass('is-done');
      } else if (idx === activeIdx) {
        $(this).addClass(failed && p === 'done' ? 'is-error' : 'is-active');
      }
    });
  }

  function friendlyScheduleSummary(mr, tdx, running) {
    var tdxLive = tdx && (tdx.running || (running && tdx.summary && isTdxProgressJob(mr && mr.jobCode)));
    if (tdxLive && tdx.summary) return tdx.summary;
    if (mr && mr.summary) return mr.summary;
    if (tdx && tdx.summary) return tdx.summary;
    if (mr && mr.message && mr.message !== '后台执行中…' && mr.message !== '执行完成') {
      return mr.message;
    }
    if (running) return '任务执行中，请稍候…';
    if (tdx && tdx.lastFinished && tdx.lastFinished.summaryFriendly) {
      return tdx.lastFinished.summaryFriendly;
    }
    return (mr && mr.message) || '';
  }

  function clientElapsedSec(mr, tdx) {
    if (scheduleRunStartedAtMs > 0) {
      return Math.floor((Date.now() - scheduleRunStartedAtMs) / 1000);
    }
    if (mr && mr.elapsedSec != null) return Number(mr.elapsedSec) || 0;
    if (tdx && tdx.elapsedMs != null) return Math.floor(Number(tdx.elapsedMs) / 1000);
    return 0;
  }

  function showScheduleProgressModal(visible) {
    var $m = $('#scheduleProgressModal');
    if (!$m.length) return;
    if (visible && scheduleProgressModalMinimized) {
      $m.prop('hidden', true);
      return;
    }
    $m.prop('hidden', !visible);
  }

  function setScheduleProgressModalPhases(phase, running, failed, jobCode) {
    var $phases = $('#scheduleProgressPhases');
    if (!$phases.length) return;
    applyPhaseLabels(jobCode || scheduleRunPollCode);
    // 同步弹框阶段文案
    if (isTdxProgressJob(jobCode || scheduleRunPollCode)) {
      $phases.find('[data-phase="sync"]').text('① 同步列表');
      $phases.find('[data-phase="fetch"]').text(
        (jobCode || scheduleRunPollCode) === 'day-collect' ? '② 拉取日线' : '② 拉取分钟');
    } else {
      $phases.find('[data-phase="sync"]').text('① 排队/受理');
      $phases.find('[data-phase="fetch"]').text('② 执行中');
    }
    var map = {
      starting: 'sync',
      queued: 'sync',
      idle: 'sync',
      running: 'fetch',
      sync: 'sync',
      fetch: 'fetch',
      summarizing: 'fetch',
      done: 'done',
      error: 'done'
    };
    var active = map[phase] || (running ? 'fetch' : 'done');
    var order = ['sync', 'fetch', 'done'];
    var activeIdx = order.indexOf(active);
    $phases.find('.ops-progress-phase').each(function () {
      var p = $(this).attr('data-phase');
      var idx = order.indexOf(p);
      $(this).removeClass('is-active is-done is-error');
      if (p === 'done') $(this).text(failed ? '③ 失败' : '③ 完成');
      if (failed && p === 'done') {
        $(this).addClass('is-error');
      } else if (idx < activeIdx || (!running && phase === 'done' && idx <= activeIdx)) {
        $(this).addClass('is-done');
      } else if (idx === activeIdx) {
        $(this).addClass('is-active');
      }
    });
  }

  function syncScheduleProgressModal(view) {
    view = view || {};
    var $m = $('#scheduleProgressModal');
    if (!$m.length) return;
    showScheduleProgressModal(true);
    setScheduleProgressModalPhases(view.phase, view.running, view.failed, view.jobCode);
    $('#scheduleProgressTitle').text(view.title || '任务执行中');
    $('#scheduleProgressPhase').text(view.phaseLabel || view.phase || '—');
    $('#scheduleProgressDetail').text(view.detail || '准备中…');
    $('#scheduleProgressSummary').text(view.summary || '');
    $('#scheduleProgressMeta').text(view.meta || '');
    var $fill = $('#scheduleProgressFill');
    if (view.indeterminate) {
      $fill.addClass('is-indeterminate').css('width', '36%');
      $('#scheduleProgressPct').text(view.pctText || '进行中');
    } else {
      $fill.removeClass('is-indeterminate').css('width', (view.pct != null ? view.pct : 0) + '%');
      $('#scheduleProgressPct').text(view.pctText || ((view.pct != null ? view.pct : 0) + '%'));
    }
    // 执行中可收起到页内；结束后显示关闭
    $('#btnScheduleProgressMinimize').prop('hidden', !view.running);
    $('#btnScheduleProgressClose').prop('hidden', !!view.running);
    var $hint = $('#scheduleRunInlineHint');
    if ($hint.length) {
      $hint.text(view.running
        ? '页内进度（与弹框同步；可点弹框「收起到页内」后继续在此查看）'
        : '页内进度（任务已结束，可关闭弹框后仍保留在此）');
    }
  }

  function renderScheduleRunBanner(payload, opts) {
    opts = opts || {};
    var $banner = $('#scheduleRunBanner');
    if (!$banner.length) return;
    if (payload) scheduleRunLastPayload = payload;
    else payload = scheduleRunLastPayload || {};
    var mr = (payload && payload.manualRun) || {};
    var tdx = (payload && payload.tdxScript) || {};
    var polling = !!scheduleRunPollTimer || !!opts.forceRunning;
    var running = !!mr.running || !!tdx.running || !!opts.forceRunning || (!!polling && !!opts.keepAlive);
    var jobCode = mr.jobCode || scheduleRunPollCode || '';
    var jobName = mr.jobName || jobCode || '任务';
    var $fill = $('#scheduleRunBarFill');
    // 仅当 TDX 脚本真正在跑或已进入有效阶段时，才用脚本进度（避免 idle 盖住任务态）
    var tdxActive = !!tdx.running || (tdx.phase && tdx.phase !== 'idle');
    var tdxJob = isTdxProgressJob(jobCode) && tdxActive;
    var phase = (tdxJob && tdx.phase)
      || mr.phase
      || (running ? 'running' : (mr.ok === false ? 'error' : 'done'));
    var phaseLabel = (tdxJob && tdx.phaseLabel) || mr.phaseLabel || '';

    // 轮询进行中即使短暂拿不到 running，也不要把横幅藏掉
    if (!running && !polling && !mr.finishedAt && !tdx.lastFinished) {
      $banner.prop('hidden', true).removeClass('is-done-ok is-done-err is-live');
      applyScheduleRunButtons('');
      return;
    }

    $banner.prop('hidden', false);
    try {
      if ($banner[0] && $banner[0].scrollIntoView) {
        $banner[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
    } catch (eScroll) {}

    var tagLabel = tdx.tagLabel || (tdx.lastFinished && tdx.lastFinished.tagLabel) || '';
    var titleName = jobName || tagLabel || jobCode;
    var stillRunning = !!mr.running || !!tdx.running || !!opts.forceRunning;
    // 统一进度：TDX 脚本 或 manualRun 上报的 i/n
    var mrHasPct = !tdxJob && mr.progressTotal != null && Number(mr.progressTotal) > 0
      && mr.progressPct != null;
    var unifiedPct = tdxJob && tdx.progressPct != null
      ? Number(tdx.progressPct)
      : (mrHasPct ? Number(mr.progressPct) : null);
    var unifiedIndex = tdxJob ? tdx.progressIndex : mr.progressIndex;
    var unifiedTotal = tdxJob ? tdx.progressTotal : mr.progressTotal;

    if (stillRunning || (polling && !mr.finishedAt && mr.ok == null)) {
      $banner.removeClass('is-done-ok is-done-err').addClass('is-live');
      var liveTitle = '执行中 · ' + titleName + (phaseLabel ? (' · ' + phaseLabel) : '');
      $('#scheduleRunTitle').text(liveTitle);
      var meta = [];
      meta.push('已用时 ' + formatElapsedSec(clientElapsedSec(mr, tdx)));
      if (unifiedIndex != null && unifiedTotal != null) {
        meta.push('进度 ' + unifiedIndex + ' / ' + unifiedTotal + ' 只');
      }
      if (tdxJob && tdx.etaSec != null && tdx.etaSec > 0) {
        meta.push('预计剩余 ' + formatElapsedSec(tdx.etaSec));
      } else if (tdxJob && tdx.etaSec === 0 && tdx.progressPct != null && tdx.progressPct >= 99) {
        meta.push('即将完成');
      }
      if (scheduleRunPollOkAt) {
        meta.push('刷新于 ' + formatClock(scheduleRunPollOkAt));
      } else {
        meta.push('实时跟踪中');
      }
      if (scheduleRunPollFail > 0) {
        meta.push('刷新失败 ' + scheduleRunPollFail + ' 次');
      }
      var metaText = meta.join(' · ');
      $('#scheduleRunMeta').text(metaText);
      setScheduleRunPhases(phase, true, false, jobCode);
      var summary = friendlyScheduleSummary(mr, tdx, true);
      if (!summary || summary === '后台执行中…') {
        summary = stillRunning
          ? (isTdxProgressJob(jobCode)
            ? '正在同步列表 / 拉取行情，进度会持续更新…'
            : (mrHasPct
              ? '长任务执行中，进度会持续更新…'
              : '任务执行中，进度将持续更新…'))
          : '正在连接任务状态…';
      }
      $('#scheduleRunSummary').text(summary);
      var indeterminate = unifiedPct == null;
      var pctVal = unifiedPct != null ? Math.max(2, Number(unifiedPct)) : 0;
      var pctText = indeterminate
        ? (phase === 'sync' || phase === 'starting' ? '启动中' : '进行中')
        : (pctVal + '%');
      if (!indeterminate) {
        $fill.removeClass('is-indeterminate').css('width', pctVal + '%');
      } else {
        $fill.addClass('is-indeterminate').css('width', '36%');
      }
      $('#scheduleRunPct').text(pctText);
      var rawLine = '';
      if (tdxJob && tdx.summary) rawLine = tdx.summary;
      else if (tdxJob && tdx.lastLine) rawLine = tdx.lastLine;
      else rawLine = mr.detail || mr.summary || mr.message || '';
      // 摘要里已含代码时不再拼「当前 xxx」，避免重复与乱码叠字
      var curSym = tdxJob ? tdx.currentSymbol : mr.currentSymbol;
      if (curSym && rawLine.indexOf(String(curSym)) < 0) {
        rawLine = (rawLine ? rawLine + ' · ' : '') + '当前 ' + curSym;
      }
      $('#scheduleRunDetail').text(rawLine || '等待任务输出…');
      try { $('#scheduleRunLogWrap').prop('open', true); } catch (eOpen) {}
      applyScheduleRunButtons(jobCode || scheduleRunPollCode);
      syncScheduleProgressModal({
        visible: true,
        running: true,
        failed: false,
        jobCode: jobCode,
        title: liveTitle,
        phase: phase,
        phaseLabel: phaseLabel || '执行中',
        detail: rawLine || summary,
        summary: summary,
        meta: metaText,
        indeterminate: indeterminate,
        pct: pctVal,
        pctText: pctText
      });
      return;
    }

    var ok = mr.ok;
    if (ok == null && tdx.lastFinished && isTdxProgressJob(jobCode)) ok = !!tdx.lastFinished.ok;
    $banner.removeClass('is-live')
      .toggleClass('is-done-ok', ok === true)
      .toggleClass('is-done-err', ok === false);
    var doneTitle = (ok === false ? '执行失败' : '执行完成') + ' · ' + titleName;
    $('#scheduleRunTitle').text(doneTitle);
    var doneMeta = [];
    doneMeta.push('总耗时 ' + formatElapsedSec(clientElapsedSec(mr, tdx) || mr.elapsedSec || 0));
    if (tdx.lastFinished && tdx.lastFinished.progressTotal != null && isTdxProgressJob(jobCode)) {
      doneMeta.push('标的 ' + (tdx.lastFinished.progressTotal) + ' 只');
    } else if (mr.progressTotal != null) {
      doneMeta.push('标的 ' + mr.progressTotal + ' 只');
    }
    if (mr.finishedAt) doneMeta.push(String(mr.finishedAt).replace('T', ' ').slice(0, 19));
    var doneMetaText = doneMeta.join(' · ');
    $('#scheduleRunMeta').text(doneMetaText);
    setScheduleRunPhases(ok === false ? 'error' : 'done', false, ok === false, jobCode);
    var doneSummary = friendlyScheduleSummary(mr, tdx, false);
    if (!doneSummary) {
      doneSummary = ok === false
        ? ((mr.message) || '任务失败，请查看进度详情或服务端日志')
        : '任务已完成';
    }
    $('#scheduleRunSummary').text(doneSummary);
    $fill.removeClass('is-indeterminate').css('width', '100%');
    $('#scheduleRunPct').text(ok === false ? '失败' : '100%');
    var rawDone = (tdx.lastFinished && tdx.lastFinished.lastLine) || tdx.lastLine
      || mr.detail || mr.summary || mr.message || '';
    $('#scheduleRunDetail').text(rawDone || '—');
    if (ok === false) {
      try { $('#scheduleRunLogWrap').prop('open', true); } catch (e) {}
    }
    applyScheduleRunButtons('');
    syncScheduleProgressModal({
      visible: true,
      running: false,
      failed: ok === false,
      jobCode: jobCode,
      title: doneTitle,
      phase: ok === false ? 'error' : 'done',
      phaseLabel: ok === false ? '失败' : '已完成',
      detail: rawDone || doneSummary,
      summary: doneSummary,
      meta: doneMetaText,
      indeterminate: false,
      pct: 100,
      pctText: ok === false ? '失败' : '100%'
    });
  }

  function startScheduleRunPoll(jobCode) {
    scheduleRunPollCode = jobCode || scheduleRunPollCode || '';
    if (!scheduleRunStartedAtMs) scheduleRunStartedAtMs = Date.now();
    scheduleRunIdleStreak = 0;
    scheduleRunPollFail = 0;
    stopScheduleRunPoll();

    var tickUi = function () {
      // 本地心跳：即使接口短暂失败/脚本暂无新日志，已用时也在跳
      if (scheduleRunLastPayload || scheduleRunPollTimer) {
        renderScheduleRunBanner(null, { keepAlive: true });
      }
    };

    var tick = function () {
      $.ajax({
        url: '/api/schedule/run-status',
        method: 'GET',
        dataType: 'json',
        cache: false,
        data: { _: Date.now() }
      }).done(function (data) {
        scheduleRunPollOkAt = Date.now();
        scheduleRunPollFail = 0;
        if (data && data.manualRun && data.manualRun.startedAt && !scheduleRunStartedAtMs) {
          // 尽量对齐服务端开始时间
          try {
            var t = Date.parse(String(data.manualRun.startedAt).replace(' ', 'T'));
            if (!isNaN(t)) scheduleRunStartedAtMs = t;
          } catch (e) {}
        }
        renderScheduleRunBanner(data);
        var mr = (data && data.manualRun) || {};
        var tdx = (data && data.tdxScript) || {};
        var running = !!mr.running || !!tdx.running;
        if (running) {
          scheduleRunIdleStreak = 0;
          return;
        }
        // 连续两次确认已结束，避免启动瞬间误判停表
        scheduleRunIdleStreak += 1;
        if (scheduleRunIdleStreak < 2 && !mr.finishedAt) {
          renderScheduleRunBanner(data, { keepAlive: true });
          return;
        }
        stopScheduleRunPoll();
        var finishKey = (mr.jobCode || '') + '|' + (mr.finishedAt || '') + '|' + String(mr.ok);
        if (mr.finishedAt && finishKey !== scheduleRunSeenFinishedKey) {
          scheduleRunSeenFinishedKey = finishKey;
          var name = mr.jobName || mr.jobCode || '任务';
          var detail = (tdx.lastFinished && (tdx.lastFinished.summaryFriendly || tdx.lastFinished.summary))
            || mr.message || '';
          if (mr.ok === false) {
            toast(name + '失败：' + (mr.message || detail || '未知错误'), 'err', { duration: 7000 });
          } else if (mr.ok === true) {
            toast(name + '已完成' + (detail ? ' · ' + detail : ''), 'ok', { duration: 5500 });
          }
          scheduleRunStartedAtMs = 0;
          loadScheduleJobs();
          // 目标池「扫描更新」走 pool-rebuild：完成后刷新池/历史/漏斗
          if (mr.ok === true && (mr.jobCode === 'pool-rebuild' || mr.jobCode === 'after-market-batch-scan')) {
            pendingTradePoolScanOpts = null;
            loadTradePoolManage();
            loadTpScanHistory();
          } else if (mr.ok === false) {
            pendingTradePoolScanOpts = null;
          }
        } else {
          renderScheduleRunBanner(data);
        }
      }).fail(function () {
        scheduleRunPollFail += 1;
        renderScheduleRunBanner(scheduleRunLastPayload, { keepAlive: true });
      });
    };

    tick();
    scheduleRunPollTimer = setInterval(tick, 1000);
    scheduleRunTickTimer = setInterval(tickUi, 1000);
  }

  function updateScheduleMasterBadge(masterOn, registeredCount) {
    var $badge = $('#scheduleMasterBadge');
    var on = !!masterOn;
    $badge.toggleClass('is-on', on).toggleClass('is-off', !on);
    $('#scheduleMasterLabel').text(on ? '运行中' : '已关闭');
    $('#scheduleMasterHint').text(on
      ? ('已注册 ' + (registeredCount || 0) + ' 个触发器 · quant.schedule.enabled=true')
      : 'quant.schedule.enabled=false（改 yml 后需重启）');
  }

  function loadScheduleJobs() {
    var $body = $('#scheduleJobBody');
    $body.html('<tr><td colspan="6" class="empty-state">加载中…</td></tr>');
    $.getJSON('/api/schedule').done(function (data) {
      var masterOn = !!data.enabled;
      updateScheduleMasterBadge(masterOn, data.registeredCount);
      var baseHint = data.hint || '';
      $('#scheduleHint').text(baseHint + (baseHint ? ' · ' : '')
        + '行内启停/执行；点行或「配置」展开改触发与备注；长任务执行一次显示进度');
      var jobs = data.jobs || [];
      scheduleJobsByCode = {};
      if (!jobs.length) {
        $body.html('<tr><td colspan="6" class="empty-state">暂无任务（需 quant.db-enabled=true）</td></tr>');
        return;
      }
      var rows = jobs.map(function (j) {
        scheduleJobsByCode[j.jobCode] = j;
        var impl = j.implemented ? '' : ' <span class="schedule-badge schedule-badge--todo">未实现</span>';
        var eff = j.effective
          ? '<span class="schedule-ok">调度中</span>'
          : (j.enabled ? '<span class="schedule-warn">未生效</span>' : '<span class="schedule-off">关闭</span>');
        var remark = j.remark || '';
        return '<tr class="sch-job-row" data-code="' + escHtml(j.jobCode) + '" title="点击空白处展开配置与说明">'
          + '<td class="sch-col-en"><label class="schedule-switch"><input type="checkbox" class="sch-enabled" '
          + (j.enabled ? 'checked' : '') + '/><span></span></label></td>'
          + '<td class="sch-col-name"><div class="schedule-name">' + escHtml(j.jobName) + impl + '</div>'
          + '<div class="schedule-code tech-id">' + escHtml(j.jobCode) + '</div></td>'
          + '<td class="sch-col-eff">' + eff + '</td>'
          + '<td class="sch-col-run mono">' + escHtml(j.lastRunAt || '—') + '</td>'
          + '<td class="sch-col-remark"><span class="sch-remark-text" title="' + escHtml(remark || '—') + '">'
          + escHtml(remark || '—') + '</span></td>'
          + '<td class="sch-col-op schedule-actions">'
          + '<button type="button" class="secondary sch-config">配置</button> '
          + '<button type="button" class="sch-run">执行一次</button>'
          + '</td></tr>';
      });
      $body.html(rows.join(''));
      var mr = data.manualRun || {};
      var tdx = data.tdxScript || {};
      if (mr.running || tdx.running) {
        if (mr.startedAt) {
          try {
            var t0 = Date.parse(String(mr.startedAt).replace(' ', 'T'));
            if (!isNaN(t0)) scheduleRunStartedAtMs = t0;
          } catch (e0) {}
        }
        if (!scheduleRunStartedAtMs) scheduleRunStartedAtMs = Date.now();
        renderScheduleRunBanner({ manualRun: mr, tdxScript: tdx }, { forceRunning: true });
        startScheduleRunPoll(mr.jobCode || '');
      } else if (mr.finishedAt) {
        renderScheduleRunBanner({ manualRun: mr, tdxScript: tdx });
        applyScheduleRunButtons('');
      } else {
        applyScheduleRunButtons('');
      }
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || xhr.statusText || '加载失败';
      $body.html('<tr><td colspan="6" class="empty-state">' + escHtml(msg) + '</td></tr>');
      updateScheduleMasterBadge(false, 0);
      $('#scheduleMasterHint').text(msg);
      toast(msg, 'err');
    });
  }

  function collapseScheduleJobDetail() {
    var $tb = $('#scheduleJobBody');
    $tb.find('tr.sch-job-row').removeClass('active').removeAttr('data-expanded');
    $tb.find('tr.sch-detail-row').remove();
  }

  function ensureScheduleDetailRow($tr) {
    var code = String($tr.attr('data-code') || '');
    var $next = $tr.next('tr.sch-detail-row');
    if ($next.length && String($next.attr('data-for-code') || '') === code) {
      return $next.find('.sch-detail-panel');
    }
    $tr.closest('tbody').find('tr.sch-detail-row').remove();
    var $row = $('<tr class="sch-detail-row"/>').attr('data-for-code', code);
    var $cell = $('<td class="sch-detail-cell"/>').attr('colspan', SCHEDULE_COLSPAN);
    var $panel = $('<div class="sch-detail-panel knowledge-body"/>');
    $cell.append($panel);
    $row.append($cell);
    $tr.after($row);
    try {
      $row[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    } catch (e) {}
    return $panel;
  }

  function syncSchTriggerHint($form) {
    if (!$form || !$form.length) return;
    var type = ($form.find('.sch-type').val() || 'CRON').toUpperCase();
    var $hint = $form.find('.sch-cron-hint');
    var $input = $form.find('.sch-trigger');
    if (type === 'FIXED_RATE') {
      $hint.text('固定间隔：填写毫秒，至少 1000（如 60000=1 分钟）');
      $input.attr('placeholder', '间隔毫秒，如 60000');
    } else {
      $hint.text('Cron 格式：秒 分 时 日 月 周（Spring 6 段，如 0 0 16 * * MON-FRI）');
      $input.attr('placeholder', '0 0 16 * * MON-FRI');
    }
  }

  function renderScheduleJobDetail(job, $panel) {
    job = job || {};
    var d = job.detail || {};
    var isFixed = (job.triggerType || '').toUpperCase() === 'FIXED_RATE';
    var triggerVal = isFixed
      ? (job.intervalMs != null ? String(job.intervalMs) : '')
      : (job.cronExpr || '');
    $panel.empty();
    var $head = $('<div class="analysis-detail-head"/>');
    $head.append($('<span/>').html(
      '<b>任务配置</b> · ' + escHtml(job.jobName || '') + ' <code class="tech-id">' + escHtml(job.jobCode || '') + '</code>'
      + (job.implemented ? '' : ' <span class="schedule-badge schedule-badge--todo">未实现</span>')
    ));
    var $collapse = $('<button type="button" class="secondary analysis-collapse-btn"/>').text('收起');
    $collapse.on('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      collapseScheduleJobDetail();
    });
    $head.append($collapse);
    $panel.append($head);

    var presetOpts = '<option value="">常用周期预设…</option>';
    SCHEDULE_CRON_PRESETS.forEach(function (p, idx) {
      presetOpts += '<option value="' + idx + '">' + escHtml(p.label) + '</option>';
    });

    var $form = $('<div class="sch-config-form"/>').html(
      '<div class="sch-config-grid">'
      + '<label class="field">触发类型'
      + '<select class="sch-type">'
      + '<option value="CRON"' + (!isFixed ? ' selected' : '') + '>CRON</option>'
      + '<option value="FIXED_RATE"' + (isFixed ? ' selected' : '') + '>FIXED_RATE</option>'
      + '</select></label>'
      + '<label class="field">常用预设'
      + '<select class="sch-cron-preset">' + presetOpts + '</select></label>'
      + '<label class="field field--wide">Cron / 间隔(ms)'
      + '<input class="sch-trigger" type="text" value="' + escHtml(triggerVal) + '"/>'
      + '</label>'
      + '<label class="field field--wide">备注'
      + '<input class="sch-remark" type="text" value="' + escHtml(job.remark || '') + '" placeholder="可选说明"/>'
      + '</label>'
      + '</div>'
      + '<p class="hint sch-cron-hint"></p>'
      + '<div class="sch-config-actions">'
      + '<button type="button" class="primary sch-save">保存配置</button>'
      + '<span class="field-hint">保存后立即重载调度</span>'
      + '</div>'
    );
    $panel.append($form);
    syncSchTriggerHint($form);

    function row(tag, text) {
      $panel.append(
        $('<p class="sch-detail-line"/>').html(
          '<span class="db-meta-tag">' + escHtml(tag) + '</span> '
          + '<span>' + escHtml(text == null || text === '' ? '—' : String(text)) + '</span>'
        )
      );
    }
    $panel.append($('<h5 class="sch-detail-sec-title"/>').text('任务说明'));
    row('功能', d.purpose);
    row('范围', d.scope);
    row('触发', d.triggerHint);
    row('落库', d.writes);
    row('说明', d.notes);
    var trig = isFixed
      ? ('FIXED_RATE · ' + (job.intervalMs != null ? job.intervalMs + ' ms' : '—'))
      : ('CRON · ' + (job.cronExpr || '—'));
    row('当前生效', (job.enabled ? '启用' : '关闭') + ' · ' + trig
      + ' · 最近执行 ' + (job.lastRunAt || '—'));
  }

  function showScheduleJobDetail($tr) {
    var code = String($tr.attr('data-code') || '');
    var job = scheduleJobsByCode[code];
    var $panel = ensureScheduleDetailRow($tr);
    if (!job) {
      $panel.html('<p class="hint">未找到任务详情，请刷新列表</p>');
      return;
    }
    renderScheduleJobDetail(job, $panel);
  }

  function scheduleJobRowFromEl($el) {
    var $detail = $el.closest('tr.sch-detail-row');
    if ($detail.length) {
      return $detail.prev('tr.sch-job-row');
    }
    return $el.closest('tr.sch-job-row');
  }

  function schedulePayloadFromRow($tr) {
    var $form = $tr.next('tr.sch-detail-row').find('.sch-config-form');
    if (!$form.length) {
      toast('请先点「配置」展开后再保存', 'info');
      return null;
    }
    var type = ($form.find('.sch-type').val() || 'CRON').toUpperCase();
    var trigger = $.trim($form.find('.sch-trigger').val() || '');
    var body = {
      enabled: $tr.find('.sch-enabled').prop('checked'),
      triggerType: type,
      remark: $form.find('.sch-remark').val() || ''
    };
    if (type === 'FIXED_RATE') {
      var ms = parseInt(trigger, 10);
      if (!ms || ms < 1000) {
        toast('FIXED_RATE 间隔至少 1000ms', 'err');
        return null;
      }
      body.intervalMs = ms;
      body.cronExpr = '';
    } else {
      if (!trigger) {
        toast('请填写 cron 表达式', 'err');
        return null;
      }
      body.cronExpr = trigger;
    }
    return body;
  }

  function showDocMode(menuBodyId) {
    ensureSidebarExpanded();
    setHomeNavActive(false);
    $('body').addClass('mode-doc');
    hideAllWorkspaceViews();
    $('#knowledgePanel').prop('hidden', false);
    setSideNavOpen(menuBodyId || null);
  }

  function updateKnowledgeChrome(topic) {
    lastKnowledgeId = topic ? topic.id : '';
    var group = topic ? topic.group : 'stock';
    var pdfGroup = group === 'app' ? 'app' : 'stock';
    $('#knowledgeEyebrow').text(knowledgeSectionLabel(topic));
    $('#knowledgeTitle').text(topic ? topic.title : '说明');
    var $pdf = $('#btnKnowledgePdf');
    if (group === 'kuangrui') {
      $pdf.prop('hidden', true);
    } else {
      $pdf.prop('hidden', false)
        .attr('data-download-docs', pdfGroup)
        .text(pdfGroup === 'app' ? '下载应用说明 PDF' : '下载量化知识 PDF');
    }
    var prev = topic ? siblingKnowledgeTopic(topic.id, -1) : null;
    var next = topic ? siblingKnowledgeTopic(topic.id, 1) : null;
    var list = topic ? knowledgeTopicsInGroup(topic.group) : [];
    var idx = -1;
    if (topic) {
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === topic.id) { idx = i; break; }
      }
    }
    $('#btnKnowledgePrev, #btnKnowledgePrevFoot').prop('disabled', !prev);
    $('#btnKnowledgeNext, #btnKnowledgeNextFoot').prop('disabled', !next);
    if (list.length && idx >= 0) {
      $('#knowledgePager').prop('hidden', false);
      $('#knowledgePagerMeta').text((idx + 1) + ' / ' + list.length
        + (prev ? ' · 上：' + prev.title : '')
        + (next ? ' · 下：' + next.title : ''));
    } else {
      $('#knowledgePager').prop('hidden', true);
      $('#knowledgePagerMeta').text('');
    }
    $('#knowledgeToc').prop('hidden', true).empty();
  }

  function wrapKnowledgeSysUsage($root) {
    $root.find('h4').each(function () {
      var $h = $(this);
      if ($h.closest('.kb-sys-block').length) return;
      if ($.trim($h.text()) !== '本系统用法') return;
      var $block = $('<div class="kb-sys-block"/>');
      $h.addClass('kb-sys-title').before($block);
      $block.append($h);
      var $n = $block.next();
      while ($n.length && !$n.is('h4') && !$n.is('.knowledge-related') && !$n.is('.kb-sys-block')) {
        var $move = $n;
        $n = $n.next();
        $block.append($move);
      }
    });
  }

  function linkifyKnowledgeRefs($root) {
    var keys = Object.keys(KNOWLEDGE_LINK_ALIASES).sort(function (a, b) {
      return b.length - a.length;
    });
    if (!keys.length) return;
    var re = new RegExp('「(' + keys.map(function (k) {
      return k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }).join('|') + ')」', 'g');
    $root.find('p, li').each(function () {
      var el = this;
      if ($(el).closest('button, a, code, pre, .kb-link, .knowledge-toc').length) return;
      var html = el.innerHTML;
      if (!html || html.indexOf('「') < 0) return;
      var next = html.replace(re, function (m, name) {
        var kid = KNOWLEDGE_LINK_ALIASES[name];
        if (!kid || kid === lastKnowledgeId) return m;
        return '<button type="button" class="kb-link" data-knowledge="' + kid + '">' + m + '</button>';
      });
      if (next !== html) el.innerHTML = next;
    });
  }

  function buildKnowledgeToc($root) {
    var $toc = $('#knowledgeToc').empty().prop('hidden', true);
    var $heads = $root.find('h4').filter(function () {
      return !$(this).closest('.knowledge-related').length;
    });
    if ($heads.length < 3) return;
    $toc.append($('<span class="knowledge-toc-label"/>').text('本文目录'));
    $heads.each(function (i) {
      var $h = $(this);
      var tid = 'kb-sec-' + i;
      $h.attr('id', tid);
      $toc.append(
        $('<a href="#' + tid + '"/>')
          .text($.trim($h.text()) || ('节 ' + (i + 1)))
          .on('click', function (e) {
            e.preventDefault();
            var el = document.getElementById(tid);
            if (el) {
              try { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (err) {}
            }
          })
      );
    });
    $toc.prop('hidden', false);
  }

  function appendKnowledgeRelated(topic, $root) {
    $root.find('.knowledge-related').remove();
    if (!topic || topic.group !== 'stock' || !topic.related || !topic.related.length) return;
    var $box = $('<div class="knowledge-related"/>');
    $box.append($('<p class="knowledge-related-title"/>').text('相关功能'));
    var $chips = $('<div class="knowledge-related-chips"/>');
    topic.related.forEach(function (r) {
      if (r.knowledge) {
        $chips.append(
          $('<button type="button" class="secondary"/>')
            .attr('data-knowledge', r.knowledge)
            .text(r.label || r.knowledge)
        );
      } else if (r.key) {
        $chips.append(
          $('<button type="button" class="secondary"/>')
            .attr('data-enter', r.key)
            .text(r.label || r.key)
        );
      }
    });
    $box.append($chips);
    $root.append($box);
  }

  function enhanceKnowledgeArticle(topic, $root) {
    wrapKnowledgeSysUsage($root);
    linkifyKnowledgeRefs($root);
    buildKnowledgeToc($root);
    appendKnowledgeRelated(topic, $root);
    if (topic && topic.id === 'readme') {
      enhanceReadmeMermaid($root);
    }
  }

  function paintKnowledgeEmpty(msg, topic) {
    var $empty = $('<div class="knowledge-empty"/>');
    $empty.append($('<p class="hint"/>').text(msg || '文档加载失败'));
    var $tb = $('<div class="toolbar"/>');
    if (topic) {
      $tb.append(
        $('<button type="button"/>').text('重试').on('click', function () {
          openKnowledge(topic.id, { force: true });
        })
      );
    }
    $tb.append(
      $('<button type="button" class="secondary"/>').text('回首页').on('click', function () {
        showHome();
      })
    );
    $empty.append($tb);
    $('#knowledgeBody').empty().append($empty);
    $('#knowledgeToc').prop('hidden', true).empty();
  }

  function openKnowledge(id, opts) {
    opts = opts || {};
    // 宽睿文档梳理改为 workspace 内嵌，避免切 mode-doc 丢联调台
    if (id === 'kuangrui') {
      showKuangruiPanel('docs');
      return;
    }
    var topic = findKnowledgeTopic(id);
    if (!topic) return;
    var menuId = topic.group === 'app' ? 'appRelatedBody' : 'stockKnowledgeBody';
    showDocMode(menuId);
    $('.side-nav-menu li').removeClass('active');
    var $active = $('.side-nav-menu li[data-id="' + id + '"]').addClass('active');
    if ($active.length) {
      try { $active[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' }); } catch (e0) {}
    }
    updateKnowledgeChrome(topic);
    $('#knowledgeBody').html('<p class="hint">加载中…</p>');
    try {
      var bodyEl = document.getElementById('knowledgeBody');
      if (bodyEl) bodyEl.scrollTop = 0;
      $('#knowledgePanel')[0].scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (e) {}
    var groupLabel = topic.group === 'app' ? '应用说明' : '量化知识';
    updateBreadcrumb([
      { label: '扩展与文档' },
      { label: groupLabel, bodyId: menuId },
      { label: topic.title }
    ]);
    saveNavSession({ mode: 'doc', panel: id, menuBodyId: menuId });
    pushRecentVisit({
      key: topic.group === 'app' ? 'app' : 'knowledge',
      label: topic.title,
      mode: 'doc',
      panel: id
    });

    function render(html) {
      if ($('#knowledgeTitle').text() !== topic.title) return;
      var $root = $('#knowledgeBody');
      $root.html(html || '<p class="hint">暂无内容</p>');
      enhanceKnowledgeArticle(topic, $root);
      try { $root[0].scrollTop = 0; } catch (e1) {}
    }

    // README 实时读盘，不做本地 HTML 缓存
    if (topic.id === 'readme') {
      $.ajax({ url: topic.src, dataType: 'html', cache: false })
        .done(function (html) { render(html); })
        .fail(function () {
          if ($('#knowledgeTitle').text() !== topic.title) return;
          paintKnowledgeEmpty('README 加载失败：请确认从项目根目录启动，且 GET /api/docs/readme 可用。', topic);
        });
      return;
    }
    if (opts.force) {
      try { delete knowledgeHtmlCache[topic.src]; } catch (e2) {}
    }
    if (!opts.force && knowledgeHtmlCache[topic.src]) {
      render(knowledgeHtmlCache[topic.src]);
      return;
    }
    $.get(topic.src)
      .done(function (html) {
        knowledgeHtmlCache[topic.src] = html;
        render(html);
      })
      .fail(function () {
        if ($('#knowledgeTitle').text() !== topic.title) return;
        paintKnowledgeEmpty('文档加载失败，请检查网络或稍后重试。', topic);
      });
  }

  /** 将 README 中 ```mermaid 代码块交给 Mermaid 渲染（CDN，失败则保留源码） */
  function enhanceReadmeMermaid($root) {
    var $codes = $root.find('pre > code.language-mermaid');
    if (!$codes.length) return;
    function run() {
      if (!window.mermaid) return;
      try {
        window.mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'loose' });
      } catch (e0) {}
      $codes.each(function () {
        var src = $(this).text();
        var $div = $('<div class="mermaid readme-mermaid"></div>').text(src);
        $(this).closest('pre').replaceWith($div);
      });
      try {
        window.mermaid.run({ nodes: $root.find('.readme-mermaid').toArray() });
      } catch (e1) {
        console.error('mermaid render failed', e1);
      }
    }
    if (window.mermaid) {
      run();
      return;
    }
    if (window.__quantMermaidLoading) {
      window.__quantMermaidLoading.push(run);
      return;
    }
    window.__quantMermaidLoading = [run];
    var s = document.createElement('script');
    s.src = 'https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js';
    s.onload = function () {
      var q = window.__quantMermaidLoading || [];
      window.__quantMermaidLoading = null;
      for (var i = 0; i < q.length; i++) { try { q[i](); } catch (e) {} }
    };
    s.onerror = function () {
      window.__quantMermaidLoading = null;
      console.error('mermaid CDN 加载失败，架构图将显示为源码');
    };
    document.head.appendChild(s);
  }

  $('.side-nav-toggle').on('click', function () {
    var $btn = $(this);
    if ($btn.is('#btnNavHome') || $btn.attr('data-mode') === 'home') {
      showHome();
      return;
    }
    var bodyId = $btn.attr('data-body');
    if (!bodyId) return;
    var wasOpen = $btn.attr('aria-expanded') === 'true';
    // 标准后台：一级仅展开/收起，不换页、不跳介绍
    if (wasOpen) {
      setSideNavOpen(null);
    } else {
      setSideNavOpen(bodyId);
    }
  });

  $('#viewNavIntro').on('click', '[data-enter-mode]', function () {
    var mode = $(this).attr('data-enter-mode');
    if (!mode) return;
    if (mode === 'account') {
      showMode('account', { panel: $(this).attr('data-account-panel') || 'funds' });
      return;
    }
    if (mode === 'tradepool') {
      showTradePool($(this).attr('data-tp-panel') || 'pool');
      return;
    }
    if (mode === 'schedule') {
      showSchedulePanel($(this).attr('data-schedule-panel') || 'jobs');
      return;
    }
    if (mode === 'kuangrui') {
      showKuangruiPanel($(this).attr('data-kuangrui-panel') || 'overview');
      return;
    }
    if (mode === 'strategy') {
      showStrategyEval();
      return;
    }
    if (mode === 'single') {
      showMode('single', { panel: $(this).attr('data-single-panel') || 'workspace' });
      return;
    }
    if (mode === 'portfolio') {
      showMode('portfolio', { panel: $(this).attr('data-portfolio-panel') || 'workspace' });
      return;
    }
    if (mode === 'knowledge') {
      openKnowledge($(this).attr('data-knowledge-id') || 'ashare');
      return;
    }
    if (mode === 'app') {
      openKnowledge($(this).attr('data-knowledge-id') || 'app');
      return;
    }
    showMode(mode);
  });

  $('#tradepoolMenu').on('click', 'li', function () {
    showTradePool($(this).attr('data-tp-panel') || 'pool');
  });

  $('#tradepoolMenu').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#scheduleMenu').on('click', 'li', function () {
    showSchedulePanel($(this).attr('data-schedule-panel') || 'jobs');
  });

  $('#scheduleMenu').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#kuangruiMenu').on('click', 'li[data-kuangrui-panel]', function () {
    showKuangruiPanel($(this).attr('data-kuangrui-panel') || 'overview');
  });
  $('#kuangruiMenu').on('keydown', 'li[data-kuangrui-panel]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });
  $('#btnKrOverviewRefresh').on('click', function () { loadKrOverview(); });
  $(document).on('click', '[data-kr-jump]', function (e) {
    e.preventDefault();
    showKuangruiPanel($(this).attr('data-kr-jump') || 'overview');
  });
  $('#viewKuangruiOrder').on('click', '.kr-order-tab', function () {
    setKrOrderTab($(this).attr('data-kr-order-tab') || 'place');
  });
  $('#btnKrDocsReload').on('click', function () { loadKrDocs(true); });

  $(document).on('click', '.kr-api-intro', function (e) {
    e.preventDefault();
    e.stopPropagation();
    openKrApiIntro($(this).attr('data-kr-intro'), $(this));
  });
  $('#btnKrIntroClose, #btnKrIntroCloseX').on('click', function () {
    closeKrApiIntro();
  });
  $('#krIntroModal').on('click', function (e) {
    if (e.target === this) closeKrApiIntro();
  });
  $('#btnKrIntroDocs').on('click', function () {
    closeKrApiIntro();
    showKuangruiPanel('docs');
  });
  $(document).on('keydown.krkrIntro', function (e) {
    if (e.key === 'Escape' && !$('#krIntroModal').prop('hidden')) {
      closeKrApiIntro();
    }
  });
  // 宽睿结果区复制（委托到 document，避免视图切换/动态补按钮后失效）
  $(document).on('click', '[data-kr-copy]', function (e) {
    e.preventDefault();
    e.stopPropagation();
    krCopyByPreId($(this).attr('data-kr-copy'));
  });
  // 进入宽睿各页时预挂复制控件
  ['krOes', 'krMds', 'krOrder', 'krAcc'].forEach(function (p) {
    ensureKrCopyControls(p);
  });
  $('#btnKrAccLogin').on('click', function () {
    markKrAccCard('login');
    var user = ($('#krAccUser').val() || '').trim();
    var pass = $('#krAccPass').val() || '';
    if (!user || !pass) {
      toast('请填写用户名和密码', 'err');
      return;
    }
    var body = { username: user, password: pass };
    var reqView = { method: 'POST', url: '/api/ops/kuangrui/account/login', body: { username: user, password: '***' } };
    var $btn = $('#btnKrAccLogin');
    var t0 = Date.now();
    $btn.prop('disabled', true).addClass('is-loading');
    $('#krAccResultMeta').text('登录并保存 · 请求中…');
    $.ajax({
      url: '/api/ops/kuangrui/account/login',
      method: 'POST',
      contentType: 'application/json',
      dataType: 'json',
      data: JSON.stringify(body)
    }).done(function (rsp, _t, xhr) {
      var ms = Date.now() - t0;
      krFillResult('krAcc', '登录并保存 · HTTP ' + (xhr && xhr.status) + ' · ' + ms + 'ms', reqView, rsp);
      paintKrAccCurrent(rsp);
      if (rsp && rsp.ok) {
        $('#krAccPass').val('');
        toast('登录成功，账号已保存', 'ok');
      } else {
        toast((rsp && rsp.message) || '登录失败', 'err');
      }
    }).fail(function (xhr) {
      var ms = Date.now() - t0;
      var bodyRsp = (xhr && xhr.responseJSON) || { message: (xhr && xhr.responseText) || '请求失败' };
      krFillResult('krAcc', '登录并保存 · HTTP ' + (xhr && xhr.status) + ' · ' + ms + 'ms', reqView, bodyRsp);
      toast('登录失败', 'err');
    }).always(function () {
      $btn.prop('disabled', false).removeClass('is-loading');
    });
  });
  $('#btnKrAccLogout').on('click', function () {
    if (!window.confirm('确认清除库内当前 active 账号？历史行保留；将回退环境变量（若有）。')) return;
    markKrAccCard('logout');
    krInvoke({
      method: 'POST',
      url: '/api/ops/kuangrui/account/logout',
      data: {},
      $btn: $('#btnKrAccLogout'),
      resultPrefix: 'krAcc',
      label: '清除当前账号',
      onDone: function (rsp) {
        paintKrAccCurrent(rsp);
      }
    });
  });
  $('#btnKrPlace').on('click', function () {
    var body = {
      code: $('#krPlaceCode').val(),
      side: $('#krPlaceSide').val(),
      price: Number($('#krPlacePrice').val()),
      qty: Number($('#krPlaceQty').val()),
      clientOrderId: $('#krPlaceCid').val() || undefined
    };
    var msg = '确认限价试单？\n' + body.side + ' ' + body.code + ' @' + body.price + ' x' + body.qty
      + '\n（须 order-enabled；将调用柜台）';
    krInvoke({
      method: 'POST',
      url: '/api/ops/kuangrui/oes/place-test',
      data: body,
      confirm: msg,
      $btn: $('#btnKrPlace'),
      resultPrefix: 'krOrder',
      label: '报单试单'
    });
  });
  $('#btnKrCancel').on('click', function () {
    var body = {
      origClSeqNo: Number($('#krCancelSeq').val()),
      code: $('#krCancelCode').val()
    };
    var msg = '确认撤单试单？\norigClSeqNo=' + body.origClSeqNo + ' code=' + body.code;
    krInvoke({
      method: 'POST',
      url: '/api/ops/kuangrui/oes/cancel-test',
      data: body,
      confirm: msg,
      $btn: $('#btnKrCancel'),
      resultPrefix: 'krOrder',
      label: '撤单试单'
    });
  });
  $('#btnKrCashTrsf').on('click', function () {
    var body = {
      direct: $('#krCashDirect').val(),
      amount: Number($('#krCashAmt').val()),
      cashAcctId: ($('#krCashAcct').val() || '').trim() || undefined,
      trsfType: $('#krCashTrsfType').val(),
      trdPasswd: $('#krCashTrdPwd').val() || undefined,
      trsfPasswd: $('#krCashTrsfPwd').val() || undefined
    };
    var msg = '确认银证试转？\n' + body.direct + ' amount=' + body.amount
      + ' type=' + body.trsfType
      + (body.cashAcctId ? (' acct=' + body.cashAcctId) : '')
      + '\n（须 order-enabled；将调用柜台；不改本地 sim 账本）';
    var reqView = {
      method: 'POST',
      url: '/api/ops/kuangrui/oes/cash-transfer-test',
      body: {
        direct: body.direct,
        amount: body.amount,
        cashAcctId: body.cashAcctId,
        trsfType: body.trsfType,
        trdPasswd: body.trdPasswd ? '***' : undefined,
        trsfPasswd: body.trsfPasswd ? '***' : undefined
      }
    };
    krInvoke({
      method: 'POST',
      url: '/api/ops/kuangrui/oes/cash-transfer-test',
      data: body,
      confirm: msg,
      $btn: $('#btnKrCashTrsf'),
      resultPrefix: 'krOrder',
      label: '银证试转',
      reqView: reqView,
      onDone: function () {
        $('#krCashTrdPwd').val('');
        $('#krCashTrsfPwd').val('');
      }
    });
  });

  $('#strategyMenu').on('click', 'li[data-strategy-panel]', function () {
    showStrategyEval();
  });

  $('#strategyMenu').on('keydown', 'li[data-strategy-panel]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#strategyList').on('click', '.strategy-list-item', function () {
    selectStrategy($(this).attr('data-strategy-id'));
  });

  $('#strategyList').on('keydown', '.strategy-list-item', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#btnStrategyIntroToggle').on('click', function () {
    var expanded = $(this).attr('aria-expanded') === 'true';
    setStrategyIntroCollapsed(expanded);
  });

  $('#btnStrategySeed').on('click', function () {
    startStrategyPoolSeed(false);
  });

  $('#viewStrategy').on('click', '.strategy-kind-btn', function () {
    var kind = String($(this).attr('data-kind') || 'ALL').toUpperCase();
    if (kind !== 'SINGLE' && kind !== 'PORTFOLIO') kind = 'ALL';
    strategyEvalState.kind = kind;
    $('#viewStrategy .strategy-kind-btn').removeClass('active');
    $(this).addClass('active');
    if (strategyEvalState.selectedId) {
      loadStrategyHistory(strategyEvalState.selectedId);
    }
  });

  $('#strategyHistSort').on('change', function () {
    strategyEvalState.sort = String($(this).val() || 'time_desc');
    if (strategyEvalState.historyRows && strategyEvalState.historyRows.length) {
      renderStrategyHistory(strategyEvalState.historyRows);
    }
  });

  $('#strategyHistoryBody').on('click', 'tr.history-row', function (e) {
    if ($(e.target).closest('button, a, input, label').length) return;
    var $tb = $('#strategyHistoryBody');
    var $tr = $(this);
    var expanded = $tr.hasClass('active') || $tr.attr('data-expanded') === '1';
    if (expanded) {
      collapseHistoryAnalysis($tb);
      return;
    }
    collapseHistoryAnalysis($tb);
    $tr.addClass('active').attr('data-expanded', '1');
    showStrategyHistoryDetail($tr);
  });

  $('#acctPosBody').on('click', 'tr.acct-pos-row', function () {
    var code = $(this).attr('data-code');
    var $lot = $('#acctPosBody tr.acct-pos-lots[data-code="' + code + '"]');
    var open = !$lot.prop('hidden');
    $('#acctPosBody tr.acct-pos-lots').prop('hidden', true);
    $('#acctPosBody tr.acct-pos-row').removeClass('active');
    if (!open) {
      $lot.prop('hidden', false);
      $(this).addClass('active');
    }
  });

  function loadTpScanHistory() {
    $('#tpHistDetail').prop('hidden', true);
    $.getJSON('/api/stock/trade-pool/batches', { limit: 30 })
      .done(function (data) {
        if (data.hint) $('#tpHistHint').text(data.hint);
        var items = (data && data.items) || [];
        $('#tpHistMeta').text('共 ' + items.length + ' 个批次');
        var $tb = $('#tpHistBody').empty();
        if (!items.length) {
          $tb.html('<tr><td colspan="6" class="empty-state">暂无扫描批次（请先扫描更新）</td></tr>');
          return;
        }
        items.forEach(function (it) {
          $tb.append(
            $('<tr/>').html(
              '<td class="mono"><b>' + escHtml(it.batchId || '—') + '</b></td>'
              + '<td class="mono">' + escHtml(it.createdAt || '—') + '</td>'
              + '<td class="mono">' + escHtml(String(it.reportCount == null ? '—' : it.reportCount)) + '</td>'
              + '<td class="mono">' + escHtml(fmtPoolScore(it.maxScore)) + '</td>'
              + '<td class="mono">' + escHtml(fmtPoolScore(it.avgScore)) + '</td>'
              + '<td><button type="button" class="secondary tp-hist-open" data-batch="'
              + escHtml(it.batchId || '') + '">明细</button></td>'
            )
          );
        });
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载扫描历史失败';
        $('#tpHistBody').html('<tr><td colspan="6" class="empty-state">' + escHtml(msg) + '</td></tr>');
      });
  }

  function loadTpBatchDetail(batchId) {
    if (!batchId) return;
    $('#tpHistDetail').prop('hidden', false);
    $('#tpHistDetailId').text(batchId);
    $('#tpHistDetailBody').html('<tr><td colspan="5" class="empty-state">加载中…</td></tr>');
    $.getJSON('/api/stock/trade-pool/batches/' + encodeURIComponent(batchId))
      .done(function (data) {
        var items = (data && data.items) || [];
        var $tb = $('#tpHistDetailBody').empty();
        if (!items.length) {
          $tb.html('<tr><td colspan="5" class="empty-state">该批次无报告</td></tr>');
          return;
        }
        items.forEach(function (it) {
          $tb.append(
            '<tr>'
            + '<td><b>' + escHtml(it.code) + '</b></td>'
            + '<td>' + escHtml(it.name || '') + '</td>'
            + '<td class="mono">' + escHtml(fmtPoolScore(it.score)) + '</td>'
            + '<td>' + escHtml(it.reason || '') + '</td>'
            + '<td><button type="button" class="secondary tp-hist-report" data-id="'
            + escHtml(String(it.reportId || '')) + '">查看</button></td>'
            + '</tr>'
          );
        });
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载批次明细失败';
        $('#tpHistDetailBody').html('<tr><td colspan="5" class="empty-state">' + escHtml(msg) + '</td></tr>');
      });
  }

  function renderReconcile(data) {
    data = data || {};
    $('#reconcileBlock').text(data.blockNewOpen ? '是' : '否');
    var n = data.divergeCodeCount != null ? data.divergeCodeCount
      : (data.divergeCodes != null ? data.divergeCodes
        : (data.divergences ? data.divergences.length : null));
    $('#reconcileDiverge').text(n == null ? '—' : String(n));
    var at = data.lastRunAt || data.asOf;
    $('#reconcileAt').text(at ? fmtDateTimeDisplay(at) : '—');
    if (data.hint) $('#reconcileHint').text(data.hint);
  }

  function loadDataReconcile() {
    $.getJSON('/api/ops/data-reconcile')
      .done(renderReconcile)
      .fail(function () {
        $('#reconcileHint').text('分钟自洽结果加载失败');
      });
  }

  var healthCheckPollTimer = null;

  function stopHealthCheckPoll() {
    if (healthCheckPollTimer) {
      clearInterval(healthCheckPollTimer);
      healthCheckPollTimer = null;
    }
  }

  function setHealthProgressPhases(phase, running, failed) {
    var $phases = $('#healthProgressPhases');
    if (!$phases.length) return;
    var map = {
      starting: 'loading',
      loading: 'loading',
      checking: 'checking',
      summarizing: 'checking',
      done: 'done',
      error: 'done'
    };
    var active = map[phase] || (running ? 'checking' : 'done');
    var order = ['loading', 'checking', 'done'];
    var activeIdx = order.indexOf(active);
    $phases.find('.ops-progress-phase').each(function () {
      var p = $(this).attr('data-phase');
      var idx = order.indexOf(p);
      $(this).removeClass('is-active is-done is-error');
      if (p === 'done') {
        $(this).text(failed ? '③ 失败' : '③ 完成');
      } else if (p === 'loading') {
        $(this).text('① 加载标的');
      } else if (p === 'checking') {
        $(this).text('② 逐只检查');
      }
      if (failed && p === 'done') {
        $(this).addClass('is-error');
      } else if (idx < activeIdx || (!running && phase === 'done' && idx <= activeIdx)) {
        $(this).addClass('is-done');
      } else if (idx === activeIdx) {
        $(this).addClass('is-active');
      }
    });
  }

  function showHealthProgressModal(visible) {
    var $m = $('#healthProgressModal');
    if (!$m.length) return;
    $m.prop('hidden', !visible);
  }

  function applyHealthProgressStatus(st) {
    st = st || {};
    var running = !!st.running;
    var failed = st.ok === false || st.phase === 'error';
    var done = !running && (st.ok === true || st.ok === false || st.phase === 'done' || st.phase === 'error');
    setHealthProgressPhases(st.phase || (running ? 'checking' : 'done'), running, failed);
    $('#healthProgressTitle').text(running ? '覆盖检查进行中' : (failed ? '覆盖检查失败' : '覆盖检查完成'));
    $('#healthProgressPhase').text(st.phaseLabel || st.phase || '—');
    var pctVal = st.progressPercent != null ? Number(st.progressPercent) : 0;
    if (isNaN(pctVal)) pctVal = 0;
    if (st.phase === 'loading' || st.phase === 'starting') {
      $('#healthProgressFill').addClass('is-indeterminate').css('width', '36%');
      $('#healthProgressPct').text('…');
    } else {
      $('#healthProgressFill').removeClass('is-indeterminate')
        .css('width', Math.max(0, Math.min(100, pctVal)) + '%');
      $('#healthProgressPct').text(pctVal.toFixed(1) + '%');
    }
    var detail = st.detail || st.summary || st.message || '';
    if (st.currentCode && running && detail.indexOf(st.currentCode) < 0) {
      detail = (detail ? detail + ' · ' : '') + st.currentCode;
    }
    $('#healthProgressDetail').text(detail || '准备中…');
    var counts = '';
    if (st.total != null && st.total > 0) {
      counts = '进度 ' + (st.currentIndex != null ? st.currentIndex : 0) + '/' + st.total;
      if (st.okSoFar != null || st.warnSoFar != null) {
        counts += ' · 正常 ' + (st.okSoFar || 0) + ' / 待处置 ' + (st.warnSoFar || 0);
        if (st.specialSoFar != null) counts += ' / 特殊 ' + (st.specialSoFar || 0);
      }
      if (st.poolSize != null) counts += ' · 目标池 ' + st.poolSize;
    }
    $('#healthProgressSummary').text(counts || (st.summary || ''));
    $('#btnHealthProgressClose').prop('hidden', !done);
    $('#btnHealthRefresh').prop('disabled', running);
  }

  function renderHealthSpecial(data) {
    data = data || {};
    if (data.specialHint) {
      $('#healthSpecialHint').text(data.specialHint);
    }
    var items = data.specialItems || [];
    items = items.filter(function (it) { return it && it.severity === 'special'; });
    var $tb = $('#healthSpecialBody').empty();
    if (!items.length) {
      var emptyMsg = (data.specialCount === 0 && (data.okCount > 0 || data.warnCount >= 0))
        ? '无特殊项'
        : '刷新覆盖检查后展示特殊项（北交所/退市·PT/停牌）';
      $tb.html('<tr><td colspan="6" class="empty-state">' + emptyMsg + '</td></tr>');
      return;
    }
    items.sort(function (a, b) {
      var ka = String(a.emptyDailyKind || '') + String(a.code || '');
      var kb = String(b.emptyDailyKind || '') + String(b.code || '');
      return ka < kb ? -1 : (ka > kb ? 1 : 0);
    });
    items.forEach(function (it) {
      $tb.append(
        '<tr>'
        + '<td><b>' + escHtml(it.code) + '</b>'
        + (it.name ? (' <span class="muted">' + escHtml(it.name) + '</span>') : '')
        + '</td>'
        + '<td><span class="tag-wait">特殊</span></td>'
        + '<td class="mono">' + escHtml(String(it.dailyCount == null ? '—' : it.dailyCount)) + '</td>'
        + '<td class="mono">' + escHtml(it.maxDaily || '—') + '</td>'
        + '<td class="mono">' + escHtml(String(it.lastDailyVolume == null ? '—' : it.lastDailyVolume)) + '</td>'
        + '<td>' + escHtml(it.issueText || '—') + '</td>'
        + '</tr>'
      );
    });
  }

  function renderMdsTdxSample(data) {
    data = data || {};
    $('#mdsTdxSampled').text(String(data.sampled == null ? '—' : data.sampled));
    $('#mdsTdxBoth').text(String(data.bothPresent == null ? '—' : data.bothPresent));
    $('#mdsTdxCloseWarn').attr('class', 'value ' + (data.closeDiffWarnCount > 0 ? 'pnl-neg' : ''))
      .text(String(data.closeDiffWarnCount == null ? '—' : data.closeDiffWarnCount));
    $('#mdsTdxMaxBp').text(String(data.maxCloseDiffBp == null ? '—' : data.maxCloseDiffBp));
    if (data.hint) {
      $('#mdsTdxHint').text(data.hint);
    }
    var items = data.items || [];
    var $tb = $('#mdsTdxBody').empty();
    if (!items.length) {
      $tb.html('<tr><td colspan="8" class="empty-state">无抽样结果（库中可能尚无 MDS/TDX 分钟）</td></tr>');
      return;
    }
    items.forEach(function (it) {
      var bad = !it.ok;
      $tb.append(
        '<tr>'
        + '<td><b>' + escHtml(it.code) + '</b></td>'
        + '<td class="mono">' + escHtml(String(it.tdxCount == null ? '—' : it.tdxCount)) + '</td>'
        + '<td class="mono">' + escHtml(it.tdxMaxTime ? fmtDateTimeDisplay(it.tdxMaxTime) : '—') + '</td>'
        + '<td class="mono">' + escHtml(String(it.mdsCount == null ? '—' : it.mdsCount)) + '</td>'
        + '<td class="mono">' + escHtml(it.mdsMaxTime ? fmtDateTimeDisplay(it.mdsMaxTime) : '—') + '</td>'
        + '<td class="mono">' + escHtml(String(it.overlapCount == null ? '—' : it.overlapCount)) + '</td>'
        + '<td class="mono' + (it.closeDiffWarn ? ' pnl-neg' : '') + '">'
        + escHtml(String(it.maxCloseDiffBp == null ? '—' : it.maxCloseDiffBp)) + '</td>'
        + '<td>' + (bad ? escHtml(it.issueText || '—') : '<span class="muted">一致</span>') + '</td>'
        + '</tr>'
      );
    });
  }

  function renderHealthResult(data) {
    data = data || {};
    if (data.hint) $('#healthHint').text(data.hint);
    $('#healthUniverse').text(String(data.universeSize == null ? '—' : data.universeSize));
    $('#healthPool').text(String(data.poolSize == null ? '—' : data.poolSize));
    $('#healthOk').text(String(data.okCount == null ? '—' : data.okCount));
    $('#healthWarn').attr('class', 'value ' + (data.warnCount > 0 ? 'pnl-neg' : ''))
      .text(String(data.warnCount == null ? '—' : data.warnCount));
    $('#healthSpecial').attr('class', 'value ' + (data.specialCount > 0 ? '' : ''))
      .text(String(data.specialCount == null ? '—' : data.specialCount));
    setCountBadge('#healthBadge', data.warnCount == null ? 0 : data.warnCount);
    $('#healthMeta').text(data.asOf
      ? ('检查时间：' + fmtDateTimeDisplay(data.asOf) + ' · 待处置告警 / 特殊项分表')
      : '');
    var bd = data.breakdown || {};
    if (bd.emptyDaily != null || bd.specialSuspended != null) {
      var parts = [];
      if (bd.specialBj || bd.emptyDailyBj) parts.push('北交所特殊 ' + (bd.specialBj || bd.emptyDailyBj));
      if (bd.specialDelisted || bd.emptyDailyLikelyDelisted) {
        parts.push('疑似退市 ' + (bd.specialDelisted || bd.emptyDailyLikelyDelisted));
      }
      if (bd.specialSuspended) parts.push('停牌特殊 ' + bd.specialSuspended);
      if (bd.emptyDailyOther) parts.push('其它空 ' + bd.emptyDailyOther);
      if (bd.minuteWarn) parts.push('分钟告警 ' + bd.minuteWarn);
      if (parts.length) {
        $('#healthMeta').text(($('#healthMeta').text() || '') + ' · ' + parts.join(' / '));
      }
    }
    renderHealthSpecial(data);
    var items = data.items || [];
    // 后端已 warn_only；前端再滤一层，避免旧结果夹带正常/特殊行
    items = items.filter(function (it) {
      return it && !it.ok && it.severity !== 'special' && it.actionNeeded !== false;
    });
    var $tb = $('#healthBody').empty();
    if (!items.length) {
      var emptyMsg = (data.warnCount === 0 && data.okCount > 0)
        ? '无待处置告警'
        : '无待处置告警或尚未执行覆盖检查';
      $tb.html('<tr><td colspan="7" class="empty-state">' + emptyMsg + '</td></tr>');
      return;
    }
    items.sort(function (a, b) {
      var ka = String(a.emptyDailyKind || '') + String(a.code || '');
      var kb = String(b.emptyDailyKind || '') + String(b.code || '');
      return ka < kb ? -1 : (ka > kb ? 1 : 0);
    });
    items.forEach(function (it) {
      $tb.append(
        '<tr>'
        + '<td><b>' + escHtml(it.code) + '</b>'
        + (it.name ? (' <span class="muted">' + escHtml(it.name) + '</span>') : '')
        + '</td>'
        + '<td><span class="tag-wait">待处置</span></td>'
        + '<td class="mono">' + escHtml(String(it.dailyCount == null ? '—' : it.dailyCount)) + '</td>'
        + '<td class="mono">' + escHtml(it.maxDaily || '—') + '</td>'
        + '<td class="mono">' + escHtml(String(it.minuteCount == null ? '—' : it.minuteCount)) + '</td>'
        + '<td class="mono">' + escHtml(it.maxMinute ? fmtDateTimeDisplay(it.maxMinute) : '—') + '</td>'
        + '<td>' + escHtml(it.issueText || '—') + '</td>'
        + '</tr>'
      );
    });
  }

  function pollHealthCheckStatus(opts) {
    opts = opts || {};
    $.getJSON('/api/ops/data-health/status')
      .done(function (st) {
        st = st || {};
        if (st.running) {
          showHealthProgressModal(true);
          applyHealthProgressStatus(st);
          if (!healthCheckPollTimer) {
            healthCheckPollTimer = setInterval(function () {
              pollHealthCheckStatus({ fromPoll: true });
            }, 800);
          }
          return;
        }
        stopHealthCheckPoll();
        if (opts.fromPoll || opts.forceModal) {
          applyHealthProgressStatus(st);
          showHealthProgressModal(true);
          if (st.result) {
            renderHealthResult(st.result);
          }
          if (st.ok === true) {
            toast(st.summary || '覆盖检查完成', 'ok');
          } else if (st.ok === false) {
            toast(st.message || st.summary || '覆盖检查失败', 'err');
          }
        } else if (st.hasLastResult && st.result) {
          renderHealthResult(st.result);
        }
      })
      .fail(function () {
        /* 瞬时失败忽略 */
      });
  }

  function startHealthCoverageCheck() {
    var $btn = $('#btnHealthRefresh');
    if ($btn.prop('disabled')) return;
    $('#healthBody').html('<tr><td colspan="7" class="empty-state">覆盖检查进行中…</td></tr>');
    loadDataReconcile();
    showHealthProgressModal(true);
    applyHealthProgressStatus({
      running: true,
      phase: 'starting',
      phaseLabel: '已受理',
      detail: '正在启动覆盖检查：先加载股票列表，再逐只核对日线/分钟…',
      progressPercent: 0
    });
    $('#btnHealthProgressClose').prop('hidden', true);
    withLoading($btn, $.ajax({
      url: '/api/ops/data-health/run',
      method: 'POST'
    }).done(function (data) {
      data = data || {};
      applyHealthProgressStatus(data.status || {
        running: true,
        phase: 'loading',
        phaseLabel: '加载标的',
        detail: data.message || '已开始'
      });
      stopHealthCheckPoll();
      healthCheckPollTimer = setInterval(function () {
        pollHealthCheckStatus({ fromPoll: true });
      }, 800);
      pollHealthCheckStatus({ fromPoll: true });
    }).fail(function (xhr) {
      showHealthProgressModal(true);
      applyHealthProgressStatus({
        running: false,
        ok: false,
        phase: 'error',
        phaseLabel: '失败',
        detail: extractAjaxError(xhr, '启动覆盖检查失败'),
        progressPercent: 0
      });
      toast(extractAjaxError(xhr, '启动覆盖检查失败'), 'err');
    }));
  }

  /** 进入数据健康页：展示上次结果；若任务仍在跑则打开进度框。 */
  function loadDataHealth() {
    loadDataReconcile();
    $.getJSON('/api/ops/data-health/status')
      .done(function (st) {
        st = st || {};
        if (st.running) {
          showHealthProgressModal(true);
          applyHealthProgressStatus(st);
          stopHealthCheckPoll();
          healthCheckPollTimer = setInterval(function () {
            pollHealthCheckStatus({ fromPoll: true });
          }, 800);
          return;
        }
        if (st.hasLastResult && st.result) {
          renderHealthResult(st.result);
        } else {
          $.getJSON('/api/ops/data-health')
            .done(function (data) {
              if (data && (data.items || []).length) {
                renderHealthResult(data);
              } else {
                $('#healthBody').html(
                  '<tr><td colspan="7" class="empty-state">点击「刷新覆盖检查」开始（全市场日线 + 目标池分钟）</td></tr>'
                );
                if (data && data.hint) $('#healthHint').text(data.hint);
              }
            });
        }
      })
      .fail(function () {
        $('#healthBody').html(
          '<tr><td colspan="7" class="empty-state">点击「刷新覆盖检查」</td></tr>'
        );
      });
  }

  function loadOpsStrategies() {
    $.getJSON('/api/ops/strategies')
      .done(function (data) {
        if (data.hint) $('#opsStrategyHint').text(data.hint);
        var active = data.activeStrategy || '';
        var list = data.strategies || [];
        var $tb = $('#opsStrategyBody').empty();
        if (!list.length) {
          $tb.html('<tr><td colspan="5" class="empty-state">无已注册策略</td></tr>');
          return;
        }
        list.forEach(function (s) {
          var id = s.id || '';
          var isActive = id === active;
          var btn = isActive
            ? '<span class="muted">—</span>'
            : ('<button type="button" class="secondary ops-strategy-activate" data-id="'
              + escHtml(id) + '">激活</button>');
          $tb.append(
            '<tr>'
            + '<td>' + (isActive ? '<span class="tag-buy">激活</span>' : '—') + '</td>'
            + '<td class="mono"><b>' + escHtml(id) + '</b></td>'
            + '<td>' + escHtml(s.label || id) + '</td>'
            + '<td class="muted">' + escHtml(s.summary || s.fingerprintId || '') + '</td>'
            + '<td>' + btn + '</td>'
            + '</tr>'
          );
        });
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载策略列表失败';
        $('#opsStrategyBody').html('<tr><td colspan="5" class="empty-state">' + escHtml(msg) + '</td></tr>');
      });
  }

  function activateOpsStrategy(strategyId) {
    if (!strategyId) return;
    var ok = window.confirm(
      '确认将纸面激活策略切换为「' + strategyId + '」？\n'
      + '仅影响纸面扫描/扫池，不影响回测下拉「仅本次」。'
    );
    if (!ok) return;
    $.ajax({
      url: '/api/ops/active-strategy',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ strategyId: strategyId, confirm: true })
    }).done(function (data) {
      if (data && data.ok) {
        toast(data.message || ('已切换为 ' + strategyId), 'ok');
        loadOpsStrategies();
        loadSysParams();
      } else {
        toast((data && data.message) || '切换失败', 'err');
      }
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || '切换失败';
      toast(msg, 'err');
    });
  }

  var paramsSparseVersion = null;

  function renderParamsValue(it) {
    var key = it.key || '';
    var val = it.effectiveValue != null ? String(it.effectiveValue)
      : (it.value == null ? '' : String(it.value));
    if (!it.writable) {
      return $('<span class="value mono"/>').text(val === '' ? '—' : val);
    }
    var $wrap = $('<span class="params-edit-wrap"/>');
    if (it.type === 'bool') {
      var checked = /^(true|1|yes|on)$/i.test(val);
      $wrap.append($('<label class="params-bool"/>')
        .append($('<input type="checkbox" class="params-edit"/>')
          .attr('data-key', key)
          .attr('data-type', 'bool')
          .prop('checked', checked)));
    } else {
      $wrap.append($('<input type="text" class="params-edit mono"/>')
        .attr('data-key', key)
        .attr('data-type', it.type || 'decimal')
        .val(val));
    }
    if (it.overridden) {
      $wrap.append($('<span class="params-override-tag"/>').text('覆盖'));
      $wrap.append($('<button type="button" class="secondary params-clear-override"/>')
        .attr('data-key', key)
        .text('清除'));
    } else {
      $wrap.append($('<span class="params-writable-tag"/>').text('可写'));
    }
    return $wrap;
  }

  function collectWritableParams() {
    var updates = {};
    $('#paramsGroups .params-edit').each(function () {
      var $el = $(this);
      var key = String($el.data('key') || '');
      if (!key) return;
      if ($el.is(':checkbox')) {
        updates[key] = $el.is(':checked') ? 'true' : 'false';
      } else {
        updates[key] = String($el.val() == null ? '' : $el.val()).trim();
      }
    });
    return updates;
  }

  function currentParamsStrategyId() {
    return String($('#paramsStrategySelect').val() || '').trim();
  }

  function saveSysParams() {
    var updates = collectWritableParams();
    var keys = Object.keys(updates);
    if (!keys.length) {
      toast('没有可写参数可保存', 'info');
      return;
    }
    var ok = window.confirm(
      '确认保存 ' + keys.length + ' 项到全局 quant.prop.*？\n'
      + '不影响各策略稀疏包；配置指纹会更新。'
    );
    if (!ok) return;
    var $btn = $('#btnParamsSave');
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    $.ajax({
      url: '/api/ops/params',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ updates: updates, confirm: true })
    }).done(function (data) {
      if (data && data.ok) {
        toast(data.message || '全局已保存', 'ok');
        loadSysParams();
      } else {
        toast((data && data.message) || '保存失败', 'err');
      }
    }).fail(function (xhr) {
      toast((xhr.responseJSON && xhr.responseJSON.message) || '保存失败', 'err');
    }).always(function () {
      $btn.prop('disabled', false).removeClass('is-loading');
    });
  }

  function saveStrategyParams() {
    var sid = currentParamsStrategyId();
    if (!sid) {
      toast('请先选择策略', 'info');
      return;
    }
    var updates = collectWritableParams();
    var keys = Object.keys(updates);
    if (!keys.length) {
      toast('没有可写参数可写入策略包', 'info');
      return;
    }
    var ok = window.confirm(
      '确认将当前表单 ' + keys.length + ' 项写入策略包「' + sid + '」？\n'
      + '稀疏覆盖叠在全局之上；仅影响该策略纸面/回测。'
    );
    if (!ok) return;
    var $btn = $('#btnStrategyParamsSave');
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    var body = { strategyId: sid, updates: updates, confirm: true };
    if (paramsSparseVersion != null) body.version = paramsSparseVersion;
    $.ajax({
      url: '/api/ops/strategy-params',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(body)
    }).done(function (data) {
      if (data && data.ok) {
        toast(data.message || '策略包已保存', 'ok');
        if (data.view) renderSysParamsView(data.view);
        else loadSysParams();
      } else {
        toast((data && data.message) || '策略包保存失败', 'err');
        if (data && data.view) renderSysParamsView(data.view);
      }
    }).fail(function (xhr) {
      toast((xhr.responseJSON && xhr.responseJSON.message) || '策略包保存失败', 'err');
    }).always(function () {
      $btn.prop('disabled', false).removeClass('is-loading');
    });
  }

  function clearStrategyOverride(key) {
    var sid = currentParamsStrategyId();
    if (!sid || !key) return;
    if (!window.confirm('清除策略「' + sid + '」对 ' + key + ' 的覆盖，恢复继承全局？')) return;
    var body = { strategyId: sid, clearKeys: [key], confirm: true };
    if (paramsSparseVersion != null) body.version = paramsSparseVersion;
    $.ajax({
      url: '/api/ops/strategy-params',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(body)
    }).done(function (data) {
      if (data && data.ok) {
        toast('已清除覆盖', 'ok');
        if (data.view) renderSysParamsView(data.view);
        else loadSysParams();
      } else {
        toast((data && data.message) || '清除失败', 'err');
      }
    }).fail(function () { toast('清除失败', 'err'); });
  }

  function renderSysParamsView(data) {
    if (!data) return;
    if (data.hint) $('#paramsHint').text(data.hint);
    paramsSparseVersion = data.sparseVersion == null ? null : data.sparseVersion;
    var fp = data.configFingerprint || '';
    var sid = data.strategyId || '';
    $('#paramsFpHint').text(fp ? ('生效指纹 ' + fp + (sid ? ' · ' + sid : '')) : '');
    var $sel = $('#paramsStrategySelect');
    if ($sel.length && (data.strategies || []).length) {
      var cur = sid || $sel.val();
      $sel.empty();
      (data.strategies || []).forEach(function (s) {
        var id = s.id || '';
        var lab = (s.label || id) + (s.hasSparse ? ' ·有包' : '');
        $sel.append($('<option/>').val(id).text(lab));
      });
      if (cur) $sel.val(cur);
    }
    var $g = $('#paramsGroups').empty();
    (data.groups || []).forEach(function (grp) {
      var $sec = $('<div class="result-group"/>');
      $sec.append($('<div class="result-group-title"/>').text(grp.title || ''));
      (grp.items || []).forEach(function (it) {
        var label = it.label || it.key || '';
        var key = it.key || '';
        var $lab = $('<span class="label params-kv-label"/>');
        $lab.append($('<span class="params-kv-cn"/>').text(label));
        if (key && key !== label) {
          $lab.append($('<span class="params-kv-key mono"/>').text(key));
        }
        if (it.globalValue != null && it.writable) {
          $lab.append($('<span class="params-kv-note"/>').text('全局 ' + it.globalValue));
        }
        if (it.note) {
          $lab.append($('<span class="params-kv-note"/>').attr('title', it.note).text(it.note));
        }
        $sec.append(
          $('<div class="result-kv params-kv"/>')
            .append($lab)
            .append(renderParamsValue(it))
        );
      });
      $g.append($sec);
    });
    var cfgs = data.systemConfig || [];
    var $tb = $('#paramsCfgBody').empty();
    if (!cfgs.length) {
      $tb.html('<tr><td colspan="5" class="empty-state">无 system_config 或未启用数据库</td></tr>');
      return;
    }
    cfgs.forEach(function (c) {
      var label = c.label || c.description || c.key || '';
      var note = (c.description && c.description !== label) ? c.description : '';
      $tb.append(
        '<tr>'
        + '<td>' + escHtml(label) + '</td>'
        + '<td class="mono">' + escHtml(c.key) + '</td>'
        + '<td class="mono">' + escHtml(c.value == null ? '—' : String(c.value)) + '</td>'
        + '<td class="muted">' + escHtml(note) + '</td>'
        + '<td class="mono">' + escHtml(c.updatedAt || '—') + '</td>'
        + '</tr>'
      );
    });
  }

  function loadSysParams() {
    loadOpsStrategies();
    var sid = currentParamsStrategyId();
    var url = '/api/ops/params' + (sid ? ('?strategyId=' + encodeURIComponent(sid)) : '');
    $.getJSON(url)
      .done(function (data) {
        renderSysParamsView(data);
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载运行参数失败';
        $('#paramsHint').text(msg);
        toast(msg, 'err');
      });
  }

  $('#btnTpHistRefresh').on('click', function () {
    loadTpScanHistory();
    toast('已刷新扫描历史列表（未扫描）', 'info');
  });
  $('#btnHealthRefresh').on('click', startHealthCoverageCheck);
  $('#btnHealthProgressClose').on('click', function () {
    showHealthProgressModal(false);
  });
  $('#btnMdsTdxSample').on('click', function () {
    var $btn = $(this);
    if ($btn.prop('disabled')) return;
    $('#mdsTdxBody').html('<tr><td colspan="8" class="empty-state">抽样对账进行中…</td></tr>');
    withLoading($btn, $.ajax({
      url: '/api/ops/data-health/mds-tdx-sample',
      method: 'POST',
      data: { limit: 20 }
    }).done(function (data) {
      renderMdsTdxSample(data || {});
      var n = (data && data.sampled != null) ? data.sampled : 0;
      var w = (data && data.closeDiffWarnCount != null) ? data.closeDiffWarnCount : 0;
      toast('MDS/TDX 抽样完成：' + n + ' 只，收盘偏差告警 ' + w, w > 0 ? 'err' : 'ok');
    }).fail(function (xhr) {
      toast(extractAjaxError(xhr, 'MDS/TDX 抽样对账失败'), 'err');
      $('#mdsTdxBody').html('<tr><td colspan="8" class="empty-state">抽样对账失败</td></tr>');
    }));
  });
  $('#btnReconcileRun').on('click', function () {
    var $btn = $(this);
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    postOrderAction('/api/ops/data-reconcile/run')
      .done(function (data) {
        renderReconcile(data);
        toast('行情自洽检查已执行', 'ok');
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '分钟自洽检查失败';
        toast(msg, 'err');
      })
      .always(function () {
        $btn.prop('disabled', false).removeClass('is-loading');
      });
  });
  $('#btnParamsRefresh').on('click', loadSysParams);
  $('#btnParamsSave').on('click', saveSysParams);
  $('#btnStrategyParamsSave').on('click', saveStrategyParams);
  $('#paramsStrategySelect').on('change', loadSysParams);
  $(document).on('click', '.params-clear-override', function () {
    clearStrategyOverride(String($(this).data('key') || ''));
  });
  $(document).on('click', '.ops-strategy-activate', function () {
    activateOpsStrategy(String($(this).data('id') || ''));
  });

  $('#acctOrderBody').on('click', '.btn-order-cancel', function () {
    var id = $(this).attr('data-id');
    if (!id) return;
    var $btn = $(this);
    $btn.prop('disabled', true).addClass('is-loading');
    postOrderAction('/api/account/orders/' + encodeURIComponent(id) + '/cancel')
      .done(function (r) {
        toast((r && r.message) || '已撤单', r && r.ok === false ? 'err' : 'ok');
        loadAccountOverview();
      })
      .fail(function () { toast('撤单失败', 'err'); })
      .always(function () { $btn.prop('disabled', false).removeClass('is-loading'); });
  });
  $('#acctOrderBody').on('click', '.btn-order-partial', function () {
    var id = $(this).attr('data-id');
    if (!id) return;
    var qtyStr = window.prompt('部成数量（100 股整数倍）', '100');
    if (qtyStr == null) return;
    var qty = parseInt(qtyStr, 10);
    if (!(qty >= 100) || qty % 100 !== 0) {
      toast('数量须为 100 整数倍', 'err');
      return;
    }
    var $btn = $(this);
    $btn.prop('disabled', true).addClass('is-loading');
    postOrderAction('/api/account/orders/' + encodeURIComponent(id) + '/partial-fill', { qty: qty })
      .done(function (r) {
        toast((r && r.message) || '已部成', r && r.ok === false ? 'err' : 'ok');
        loadAccountOverview();
      })
      .fail(function () { toast('部成失败', 'err'); })
      .always(function () { $btn.prop('disabled', false).removeClass('is-loading'); });
  });
  $('#acctOrderBody').on('click', '.btn-order-replace', function () {
    var id = $(this).attr('data-id');
    var oldPx = $(this).attr('data-price') || '';
    if (!id) return;
    var pxStr = window.prompt('新价格（改价=撤补队尾）', oldPx);
    if (pxStr == null) return;
    var price = parseFloat(pxStr);
    if (!(price > 0)) {
      toast('价格非法', 'err');
      return;
    }
    var $btn = $(this);
    $btn.prop('disabled', true).addClass('is-loading');
    postOrderAction('/api/account/orders/' + encodeURIComponent(id) + '/replace', { price: price })
      .done(function (r) {
        toast((r && r.message) || '已改价撤补', r && r.ok === false ? 'err' : 'ok');
        loadAccountOverview();
      })
      .fail(function () { toast('改价失败', 'err'); })
      .always(function () { $btn.prop('disabled', false).removeClass('is-loading'); });
  });

  $('#tpHistBody').on('click', '.tp-hist-open', function (e) {
    e.preventDefault();
    loadTpBatchDetail($(this).attr('data-batch'));
  });

  $('#tpHistDetailBody').on('click', '.tp-hist-report', function (e) {
    e.preventDefault();
    var id = $(this).attr('data-id');
    if (!id) return;
    $.getJSON('/api/stock/trade-pool/report/' + encodeURIComponent(id))
      .done(function (rec) {
        var summary = (rec && (rec.summary || rec.recommendReason || rec.signal)) || '已加载报告';
        toast('报告 #' + id + '：' + String(summary).slice(0, 80), 'ok');
      })
      .fail(function () { toast('报告加载失败', 'err'); });
  });

  $('#accountMenu').on('click', 'li', function () {
    showAccountPanel($(this).attr('data-account-panel'));
  });

  $('#accountMenu').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#btnAcctRefreshFunds, #btnAcctRefreshPos, #btnAcctRefreshOrders').on('click', function () {
    loadAccountOverview();
  });

  $('#btnAcctRetire').on('click', function () {
    var $btn = $(this);
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    $.post('/api/account/retirement/retire', { reason: 'MANUAL', note: '页面手动退役' })
      .done(function (r) {
        toast(r && r.hint ? r.hint : '已退役', r && r.retired ? 'ok' : 'err');
        loadAccountOverview();
      })
      .fail(function () { toast('退役失败', 'err'); })
      .always(function () { $btn.prop('disabled', false).removeClass('is-loading'); });
  });

  $('#btnAcctResume').on('click', function () {
    var $btn = $(this);
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    $.post('/api/account/retirement/resume', { force: false })
      .done(function (r) {
        toast((r && r.message) || '操作完成', r && r.ok ? 'ok' : 'err');
        loadAccountOverview();
      })
      .fail(function () { toast('恢复失败', 'err'); })
      .always(function () { $btn.prop('disabled', false).removeClass('is-loading'); });
  });

  $('#btnAcctResumeForce').on('click', function () {
    var $btn = $(this);
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    // 双人复核：先武装令牌，再请第二人确认码
    $.post('/api/account/retirement/resume', { force: true })
      .done(function (r) {
        if (r && r.ok) {
          toast((r && r.message) || '已强制恢复', 'ok');
          loadAccountOverview();
          return;
        }
        var token = r && r.forceConfirmToken ? String(r.forceConfirmToken) : '';
        var code = window.prompt(
          (r && r.message ? r.message + '\n\n' : '') + '请第二人输入复核码（confirmCode）：',
          token
        );
        if (!code) {
          toast('已取消强制恢复', 'err');
          return;
        }
        $.post('/api/account/retirement/resume', { force: true, confirmCode: code })
          .done(function (r2) {
            toast((r2 && r2.message) || '操作完成', r2 && r2.ok ? 'ok' : 'err');
            loadAccountOverview();
          })
          .fail(function () { toast('强制恢复失败', 'err'); });
      })
      .fail(function () { toast('强制恢复失败', 'err'); })
      .always(function () { $btn.prop('disabled', false).removeClass('is-loading'); });
  });

  $('#btnAcctRefreshCf').on('click', function () {
    loadAccountCashflows();
  });

  $('#btnAcctRefreshRisk').on('click', function () {
    loadAccountRiskLogs();
  });

  $('#btnAcctRefreshGap').on('click', function () {
    loadAccountPaperGap();
  });

  $('#btnAcctRefreshDash').on('click', function () {
    var $btn = $(this);
    if ($btn.prop('disabled')) return;
    $btn.prop('disabled', true).addClass('is-loading');
    loadAccountRiskDash();
    setTimeout(function () {
      $btn.prop('disabled', false).removeClass('is-loading');
    }, 600);
  });

  $('#dbtablesMenu').on('click', 'li[data-table]', function () {
    showDbTable($(this).attr('data-table'));
  });

  $('#dbtablesMenu').on('keydown', 'li[data-table]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#btnDbMetaToggle').on('click', function () {
    setDbMetaExpanded(!$('#dbTableMeta').hasClass('is-open'));
  });

  $('#btnDbTableRefresh').on('click', function () {
    if (dbTableState.name) {
      loadDbTablePage();
      loadDbTablesMenu();
    } else {
      loadDbTablesMenu();
      toast('已刷新表列表', 'ok');
    }
  });

  $('#dbTablePageSize').on('change', function () {
    dbTableState.size = parseInt($(this).val(), 10) || 20;
    dbTableState.page = 1;
    if (dbTableState.name) loadDbTablePage();
  });

  $('#btnDbPrev').on('click', function () {
    if (dbTableState.page > 1) {
      dbTableState.page -= 1;
      loadDbTablePage();
    }
  });

  $('#btnDbNext').on('click', function () {
    if (dbTableState.pages && dbTableState.page < dbTableState.pages) {
      dbTableState.page += 1;
      loadDbTablePage();
    }
  });

  $('#btnDbJump').on('click', function () {
    var p = parseInt($('#dbPageJump').val(), 10);
    if (!p || p < 1) p = 1;
    if (dbTableState.pages && p > dbTableState.pages) p = dbTableState.pages;
    dbTableState.page = p;
    if (dbTableState.name) loadDbTablePage();
  });

  $('#btnTpRefresh').on('click', function () {
    loadTradePoolManage();
    toast('已刷新当前池列表（未扫描）', 'info');
  });

  $('#btnTpFunnelCta').on('click', function () {
    $('#btnTpRebuild').trigger('click');
  });

  function hasTpFunnelData(res) {
    if (!res || typeof res !== 'object') return false;
    return res.universe != null
      || res.afterCoarse != null
      || res.afterScan != null
      || res.scanned != null
      || res.afterLiquidity != null
      || res.selected != null
      || !!res.batchId
      || !!res.reportFileName;
  }

  function renderTpFunnel(res) {
    var $funnel = $('#tpFunnel');
    var $empty = $('#tpFunnelEmpty');
    var $data = $('#tpFunnelData');
    if (!$funnel.length) return;
    $funnel.prop('hidden', false);

    if (!hasTpFunnelData(res)) {
      $empty.prop('hidden', false);
      $data.prop('hidden', true);
      $('#tpFunnelUniverse, #tpFunnelCoarse, #tpFunnelScan, #tpFunnelLiq, #tpFunnelSelected').text('—');
      $('#tpFunnelMeta').text('—');
      $('#tpReportLink').prop('hidden', true).hide().attr('href', '#');
      return;
    }

    res = res || {};
    $empty.prop('hidden', true);
    $data.prop('hidden', false);
    $('#tpFunnelUniverse').text(String(res.universe != null ? res.universe : '—'));
    $('#tpFunnelCoarse').text(String(res.afterCoarse != null ? res.afterCoarse : '—'));
    $('#tpFunnelScan').text(String(res.afterScan != null ? res.afterScan : (res.scanned != null ? res.scanned : '—')));
    $('#tpFunnelLiq').text(String(res.afterLiquidity != null ? res.afterLiquidity : (res.scanned != null ? res.scanned : '—')));
    $('#tpFunnelSelected').text(String(res.selected != null ? res.selected : '—'));
    var meta = '下限 ' + (res.scoreMin != null ? res.scoreMin : '—')
      + ' · 上限 ' + (res.tradePoolMax != null ? res.tradePoolMax : '—');
    if (res.batchId) meta += ' · ' + res.batchId;
    $('#tpFunnelMeta').text(meta);
    if (res.reportFileName) {
      $('#tpReportLink')
        .attr('href', '/api/stock/trade-pool/reports/' + encodeURIComponent(res.reportFileName))
        .prop('hidden', false)
        .show();
    } else {
      $('#tpReportLink').prop('hidden', true).hide().attr('href', '#');
    }
  }

  /**
   * 手动触发目标池扫描：与运维「pool-rebuild / 全市场入池扫描」同一异步任务 + 进度弹框。
   * （不再同步 POST /analyze，否则无进度且易超时。）
   * @param {JQuery} $btn
   * @param {{refreshHistory?: boolean, showFunnel?: boolean}} [opts]
   */
  function runTradePoolScan($btn, opts) {
    opts = opts || {};
    var code = 'pool-rebuild';
    var jobName = (scheduleJobsByCode[code] && scheduleJobsByCode[code].jobName) || '全市场入池扫描';
    var asyncStarted = false;
    if ($btn && $btn.length) {
      if (!$btn.data('tpScanIdleText')) {
        $btn.data('tpScanIdleText', $.trim($btn.text()) || '扫描更新');
      }
      $btn.prop('disabled', true).text('提交中…');
    }
    pendingTradePoolScanOpts = {
      refreshHistory: !!opts.refreshHistory,
      showFunnel: opts.showFunnel !== false
    };
    var startSummary = '已受理「' + jobName + '」，正在启动全市场入池扫描…';
    scheduleProgressModalMinimized = false;
    renderScheduleRunBanner({
      manualRun: {
        jobCode: code,
        jobName: jobName,
        running: true,
        progressKind: 'generic',
        phase: 'starting',
        phaseLabel: '已受理',
        summary: startSummary,
        message: startSummary,
        elapsedSec: 0
      },
      tdxScript: { running: false }
    }, { forceRunning: true });
    toast('正在提交「' + jobName + '」…', 'info');
    $.post('/api/schedule/jobs/' + encodeURIComponent(code) + '/run').done(function (res) {
      if (res && res.async) {
        asyncStarted = true;
        scheduleRunStartedAtMs = Date.now();
        scheduleRunSeenFinishedKey = '';
        toast((res.message) || ('已开始「' + jobName + '」，请看进度弹框'), 'info', { duration: 6500 });
        var summary2 = (res.manualRun && res.manualRun.summary) || startSummary;
        renderScheduleRunBanner({
          manualRun: Object.assign({
            jobCode: code,
            jobName: jobName,
            running: true,
            progressKind: 'generic',
            phase: 'starting',
            phaseLabel: '已受理',
            elapsedSec: 0
          }, res.manualRun || {}, {
            summary: summary2,
            message: res.message || summary2,
            running: true
          }),
          tdxScript: { running: false }
        }, { forceRunning: true });
        applyScheduleRunButtons(code);
        startScheduleRunPoll(code);
        return;
      }
      // 兜底：同步完成（旧后端）
      showScheduleProgressModal(false);
      pendingTradePoolScanOpts = null;
      if (opts.showFunnel !== false) {
        renderTpFunnel(res || {});
      }
      toast((res && res.message) ? res.message : ('已执行 ' + jobName), 'ok');
      loadTradePoolManage();
      if (opts.refreshHistory) {
        loadTpScanHistory();
      }
    }).fail(function (xhr) {
      pendingTradePoolScanOpts = null;
      var msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error))
        || '扫描提交失败';
      renderScheduleRunBanner({
        manualRun: {
          jobCode: code,
          jobName: jobName,
          running: false,
          ok: false,
          phase: 'error',
          phaseLabel: '失败',
          finishedAt: new Date().toISOString().slice(0, 19).replace(' ', 'T').replace('T', ' '),
          summary: msg,
          message: msg
        },
        tdxScript: { running: false }
      });
      toast(msg, 'err', { duration: 5000 });
    }).always(function () {
      if (!asyncStarted && $btn && $btn.length) {
        $btn.prop('disabled', false).text($btn.data('tpScanIdleText') || '扫描更新');
      }
    });
  }

  $('#btnTpRebuild').on('click', function () {
    runTradePoolScan($(this), { showFunnel: true, refreshHistory: false });
  });

  $('#btnTpHistRebuild').on('click', function () {
    runTradePoolScan($(this), { showFunnel: false, refreshHistory: true });
  });

  $('#chkAllSingleHistory').on('change', function () {
    var code = ($('#stockCode').val() || singleCode || '').trim();
    loadSingleHistory(code);
  });

  $('#tpPoolBody').on('click', 'tr.tp-pool-row', function (e) {
    if ($(e.target).closest('input, button, a, label').length) return;
    var $tr = $(this);
    var expanded = $tr.hasClass('active') || $tr.attr('data-expanded') === '1';
    if (expanded) {
      collapseTpPoolAnalysis();
      return;
    }
    collapseTpPoolAnalysis();
    $tr.addClass('active').attr('data-expanded', '1');
    showTpPoolAnalysis($tr);
  });

  $('#tpPoolBody').on('click', '.tp-remove', function (e) {
    e.preventDefault();
    e.stopPropagation();
    var code = $(this).attr('data-code');
    if (!code) return;
    var name = poolNames[code] || '';
    var tip = '确认将 ' + code + (name ? ('（' + name + '）') : '') + ' 移出目标池？\n\n'
      + '移出 ≠ 卖出：不停仓、不卖出持仓；实盘扫描将不再盯该标的。';
    if (!window.confirm(tip)) {
      return;
    }
    $.post('/api/stock/trade-pool/' + encodeURIComponent(code) + '/remove').done(function () {
      toast('已移出目标池 ' + code + '（未卖出）', 'ok');
      loadTradePoolManage();
    }).fail(function (xhr) {
      toast((xhr.responseJSON && xhr.responseJSON.message) || '移出失败', 'err');
    });
  });

  $('#btnScheduleRefresh').on('click', function () {
    loadScheduleJobs();
  });

  $('#btnScheduleReload').on('click', function () {
    $.post('/api/schedule/reload').done(function () {
      toast('已重载调度', 'ok');
      loadScheduleJobs();
    }).fail(function (xhr) {
      toast((xhr.responseJSON && xhr.responseJSON.message) || '重载失败', 'err');
    });
  });

  function toggleScheduleJobDetail($tr) {
    if (!$tr || !$tr.length) return;
    var expanded = $tr.hasClass('active') || $tr.attr('data-expanded') === '1';
    if (expanded) {
      collapseScheduleJobDetail();
      return;
    }
    collapseScheduleJobDetail();
    $tr.addClass('active').attr('data-expanded', '1');
    showScheduleJobDetail($tr);
  }

  $('#scheduleJobBody').on('click', 'tr.sch-job-row', function (e) {
    if ($(e.target).closest('input, select, button, a, label, textarea').length) return;
    toggleScheduleJobDetail($(this));
  });

  $('#scheduleJobBody').on('click', '.sch-config', function (e) {
    e.preventDefault();
    e.stopPropagation();
    toggleScheduleJobDetail($(this).closest('tr.sch-job-row'));
  });

  $('#scheduleJobBody').on('change', '.sch-enabled', function () {
    var $tr = $(this).closest('tr.sch-job-row');
    var code = $tr.attr('data-code');
    var enabled = $(this).prop('checked');
    $.ajax({
      url: '/api/schedule/jobs/' + encodeURIComponent(code) + '/toggle?enabled=' + enabled,
      method: 'POST'
    }).done(function () {
      toast((enabled ? '已启用 ' : '已停用 ') + code, 'ok');
      loadScheduleJobs();
    }).fail(function (xhr) {
      toast((xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || '切换失败', 'err');
      loadScheduleJobs();
    });
  });

  $('#scheduleJobBody').on('change', '.sch-type', function () {
    syncSchTriggerHint($(this).closest('.sch-config-form'));
  });

  $('#scheduleJobBody').on('change', '.sch-cron-preset', function () {
    var $form = $(this).closest('.sch-config-form');
    var idx = parseInt($(this).val(), 10);
    if (!isFinite(idx) || !SCHEDULE_CRON_PRESETS[idx]) return;
    var p = SCHEDULE_CRON_PRESETS[idx];
    if (p.fixedMs) {
      $form.find('.sch-type').val('FIXED_RATE');
      $form.find('.sch-trigger').val(String(p.fixedMs));
    } else {
      $form.find('.sch-type').val('CRON');
      $form.find('.sch-trigger').val(p.cron || '');
    }
    syncSchTriggerHint($form);
    $(this).val('');
  });

  $('#scheduleJobBody').on('click', '.sch-save', function () {
    var $tr = scheduleJobRowFromEl($(this));
    var code = $tr.attr('data-code');
    var body = schedulePayloadFromRow($tr);
    if (!body) return;
    $.ajax({
      url: '/api/schedule/jobs/' + encodeURIComponent(code),
      method: 'PUT',
      contentType: 'application/json',
      data: JSON.stringify(body)
    }).done(function () {
      toast('已保存 ' + code, 'ok');
      loadScheduleJobs();
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || '保存失败';
      toast(msg, 'err');
    });
  });

  $('#scheduleJobBody').on('click', '.sch-run', function () {
    var code = $(this).closest('tr.sch-job-row').attr('data-code');
    var job = scheduleJobsByCode[code] || {};
    var $btn = $(this);
    var asyncStarted = false;
    $btn.prop('disabled', true).text('提交中…');
    var startSummary = isTdxProgressJob(code)
      ? '即将同步全市场股票列表，再逐只拉取日线/分钟…'
      : ('已受理「' + (job.jobName || code) + '」，正在启动…');
    // 先弹进度框，避免只看到 toast、看不到进度
    scheduleProgressModalMinimized = false;
    renderScheduleRunBanner({
      manualRun: {
        jobCode: code,
        jobName: job.jobName || code,
        running: true,
        progressKind: isTdxProgressJob(code) ? 'tdx' : 'generic',
        phase: 'starting',
        phaseLabel: '已受理',
        summary: startSummary,
        message: startSummary,
        elapsedSec: 0
      },
      tdxScript: isTdxProgressJob(code) ? {
        running: false,
        phase: 'starting',
        phaseLabel: '启动中',
        summary: startSummary,
        lastLine: '正在提交任务…'
      } : { running: false }
    }, { forceRunning: true });
    toast('正在提交「' + (job.jobName || code) + '」…', 'info');
    $.post('/api/schedule/jobs/' + encodeURIComponent(code) + '/run').done(function (res) {
      if (res && res.async) {
        asyncStarted = true;
        scheduleRunStartedAtMs = Date.now();
        scheduleRunSeenFinishedKey = '';
        toast((res.message) || ('已开始「' + (job.jobName || code) + '」，请看进度弹框'), 'info', { duration: 6500 });
        var summary2 = (res.manualRun && res.manualRun.summary) || startSummary;
        renderScheduleRunBanner({
          manualRun: Object.assign({
            jobCode: code,
            jobName: job.jobName || code,
            running: true,
            progressKind: isTdxProgressJob(code) ? 'tdx' : 'generic',
            phase: 'starting',
            phaseLabel: '已受理',
            elapsedSec: 0
          }, res.manualRun || {}, {
            summary: summary2,
            message: res.message || summary2,
            running: true
          }),
          tdxScript: isTdxProgressJob(code) ? Object.assign({
            phase: 'starting',
            phaseLabel: '启动中',
            lastLine: ''
          }, (res.tdxScript || {}), {
            summary: summary2
          }) : { running: false }
        }, { forceRunning: true });
        startScheduleRunPoll(code);
        return;
      }
      // 兜底：若后端仍返回同步完成
      showScheduleProgressModal(false);
      toast((res && res.message) ? res.message : ('已执行 ' + code), 'ok');
      loadScheduleJobs();
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error))
        || '执行失败';
      renderScheduleRunBanner({
        manualRun: {
          jobCode: code,
          jobName: job.jobName || code,
          running: false,
          ok: false,
          phase: 'error',
          phaseLabel: '失败',
          finishedAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
          summary: msg,
          message: msg
        },
        tdxScript: { running: false }
      });
      toast(msg, 'err', { duration: 5000 });
      loadScheduleJobs();
    }).always(function () {
      if (!asyncStarted) {
        $btn.prop('disabled', false).text('执行一次');
      }
    });
  });

  $('#btnScheduleProgressMinimize').on('click', function () {
    scheduleProgressModalMinimized = true;
    showScheduleProgressModal(false);
    var $banner = $('#scheduleRunBanner');
    if ($banner.length) {
      $banner.prop('hidden', false);
      try {
        if ($banner[0].scrollIntoView) {
          $banner[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
      } catch (e) {}
    }
    toast('已收起到任务管理页内进度条，任务仍在后台继续', 'info', { duration: 4000 });
  });

  $('#btnScheduleProgressClose').on('click', function () {
    scheduleProgressModalMinimized = false;
    showScheduleProgressModal(false);
  });

  $('#viewNavIntro, #knowledgePanel').on('click', '[data-download-docs], [data-docs-pdf]', function () {
    var group = $(this).attr('data-download-docs') || $(this).attr('data-docs-pdf');
    if (group === 'stock' || group === 'app') {
      downloadDocsPdf(group, $(this));
    }
  });

  $('#viewHome').on('click', '[data-enter]', function () {
    enterWorkspaceByKey($(this).attr('data-enter'));
  });

  $('#viewHome').on('click', '[data-metric-go]', function () {
    enterWorkspaceByKey($(this).attr('data-metric-go'));
  });

  $('#viewHome').on('keydown', '[data-metric-go]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      enterWorkspaceByKey($(this).attr('data-metric-go'));
    }
  });

  $('#viewHome').on('click', '[data-recent-key]', function () {
    enterWorkspaceByKey($(this).attr('data-recent-key'));
  });

  $('#viewHome').on('click', '[data-open-nav]', function () {
    var bodyId = $(this).attr('data-open-nav');
    var mode = $(this).attr('data-mode') || '';
    if (mode === 'doc') {
      if (bodyId === 'stockKnowledgeMenu' || bodyId === 'stockKnowledgeBody') {
        enterWorkspaceByKey('knowledge');
      } else {
        enterWorkspaceByKey('app');
      }
      return;
    }
    var map = {
      poolBody: 'pool', singleBody: 'single', portfolioBody: 'portfolio', tradepoolBody: 'tradepool',
      accountBody: 'account', scheduleBody: 'schedule', strategyBody: 'strategy',
      dbtablesBody: 'dbtables', kuangruiBody: 'kuangrui'
    };
    enterWorkspaceByKey(map[bodyId] || mode);
  });

  $('#appBreadcrumb').on('click', '[data-crumb="home"]', function () {
    showHome();
  });
  $('#appBreadcrumb').on('click', '[data-crumb-body]', function () {
    var bodyId = $(this).attr('data-crumb-body');
    if (bodyId) setSideNavOpen(bodyId);
  });

  $('#btnBrandHome').on('click', function () {
    showHome();
    toast('已回到首页工作台', 'ok');
  });

  $('#stockKnowledgeMenu, #appRelatedMenu').on('click', 'li', function () {
    if ($(this).hasClass('side-nav-section')) return;
    var kid = $(this).data('id');
    if (kid) openKnowledge(kid);
  });
  $('#stockKnowledgeMenu, #appRelatedMenu').on('keydown', 'li[data-id]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  function goKnowledgeSibling(delta) {
    if (!lastKnowledgeId) return;
    var sib = siblingKnowledgeTopic(lastKnowledgeId, delta);
    if (sib) openKnowledge(sib.id);
  }

  $('#btnKnowledgePrev, #btnKnowledgePrevFoot').on('click', function () { goKnowledgeSibling(-1); });
  $('#btnKnowledgeNext, #btnKnowledgeNextFoot').on('click', function () { goKnowledgeSibling(1); });

  $('#knowledgeClose').on('click', function () {
    showHome();
  });

  $(document).on('click', '#knowledgePanel [data-knowledge], #knowledgeBody [data-knowledge]', function (e) {
    e.preventDefault();
    var kid = $(this).attr('data-knowledge');
    if (kid) openKnowledge(kid);
  });

  $(document).on('click', '#knowledgePanel [data-enter]', function (e) {
    e.preventDefault();
    var key = $(this).attr('data-enter');
    if (key) enterWorkspaceByKey(key);
  });

  $(document).on('keydown.knowledgePager', function (e) {
    if ($('#knowledgePanel').prop('hidden')) return;
    if ($(e.target).closest('input, textarea, select, [contenteditable="true"]').length) return;
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      goKnowledgeSibling(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      goKnowledgeSibling(1);
    }
  });

  $('#poolMenu').on('click', 'li', function () {
    showMode('pool');
    pushRecentVisit({ key: 'pool', label: '行情浏览', mode: 'pool' });
  });
  $('#poolMenu').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#btnEnterSingle').on('click', function () {
    showMode('single', { panel: 'workspace' });
  });

  $('#singleMenu').on('click', 'li', function () {
    showMode('single', { panel: $(this).attr('data-single-panel') || 'workspace' });
  });

  $('#singleMenu').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#btnEnterPortfolio').on('click', function () {
    showMode('portfolio', { panel: 'workspace' });
  });

  $('#portfolioMenu').on('click', 'li', function () {
    showMode('portfolio', { panel: $(this).attr('data-portfolio-panel') || 'workspace' });
  });

  $('#portfolioMenu').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  var poolSearchTimer = null;
  var singleSearchTimer = null;
  var pfSearchTimer = null;

  $('#poolStockQ').on('input', function () {
    clearTimeout(poolSearchTimer);
    poolSearchTimer = setTimeout(function () { renderStockPicker('pool'); }, 120);
  });

  $('#singleStockQ').on('input', function () {
    clearTimeout(singleSearchTimer);
    singleSearchTimer = setTimeout(function () { renderStockPicker('single'); }, 120);
  });
  $('#singleStockQ').on('focus', function () {
    if (normalizeStockQuery($(this).val())) {
      renderStockPicker('single');
    }
  });
  $(document).on('click', function (e) {
    if (!$(e.target).closest('#singleStockPicker').length) {
      $('#singleStockResults').prop('hidden', true);
    }
  });

  $('#singlePoolSelect').on('change', function () {
    var code = $(this).val();
    if (!code) return;
    showMode('single');
    selectSingleStock(code);
  });

  $('#pfStockQ').on('input', function () {
    clearTimeout(pfSearchTimer);
    pfSearchTimer = setTimeout(function () { renderStockPicker('portfolio'); }, 120);
  });

  $('#poolStockResults').on('click', 'tr[data-code]', function () {
    var code = $(this).attr('data-code');
    showMode('pool');
    openPoolStock(code);
    renderStockPicker('pool');
  });

  $('#singleStockResults').on('click', 'li[data-code]', function () {
    var code = $(this).attr('data-code');
    showMode('single');
    selectSingleStock(code);
  });

  $('#pfStockResults').on('click', 'li[data-code]', function () {
    togglePortfolioStock($(this).attr('data-code'));
  });

  $('#poolStockResults').on('keydown', 'tr[data-code]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#singleStockResults, #pfStockResults').on('keydown', 'li[data-code]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#viewPool').on('click', 'th.pool-sort', function () {
    var key = $(this).attr('data-pool-sort') || 'pctChg';
    if (poolSortKey === key) {
      poolSortDir = poolSortDir === 'desc' ? 'asc' : 'desc';
    } else {
      poolSortKey = key;
      poolSortDir = 'desc';
    }
    $('#viewPool th.pool-sort').removeClass('is-asc is-desc');
    $(this).addClass(poolSortDir === 'asc' ? 'is-asc' : 'is-desc');
    renderStockPicker('pool');
  });

  $('#pfChips').on('click', '.pf-chip', function () {
    togglePortfolioStock($(this).attr('data-code'));
  });

  $('#btnPfPickTop3').on('click', function (e) {
    e.preventDefault();
    e.stopPropagation();
    setPfBatchMenuOpen($('#pfBatchMenu').prop('hidden'));
  });

  $('#pfBatchMenu').on('click', '[data-pf-batch]', function (e) {
    e.preventDefault();
    e.stopPropagation();
    var act = $(this).attr('data-pf-batch');
    if (act === 'all') {
      selectPortfolioAll();
      toast('已全选目标池 ' + portfolioSelected.length + ' 只', 'ok');
    } else if (act === 'clear') {
      clearPortfolioSelection();
      toast('已清空已选', 'info');
    } else if (act === 'invert') {
      invertPortfolioSelection();
      toast('已反选 · 当前 ' + portfolioSelected.length + ' 只', 'ok');
    } else if (act === 'top3') {
      selectPortfolioTopN(3);
    }
    setPfBatchMenuOpen(false);
  });

  $(document).on('click.pfBatch', function (e) {
    if (!$(e.target).closest('.pf-batch').length) {
      setPfBatchMenuOpen(false);
    }
  });

  $('#barPeriod').on('change', function () {
    if (singleCode) {
      singlePeriods[singleCode] = $(this).val() || 'DAY';
      loadSingleKline({ silent: true });
    }
  });
  $('#singleBackStart, #singleBackEnd').on('change', function () {
    if (singleCode) loadSingleKline({ silent: true });
  });

  $('#btnPoolRefresh').on('click', function () {
    if (activePoolCode) loadPoolKline(activePoolCode);
  });
  $('#poolPeriod').on('change', function () {
    if (!activePoolCode) return;
    var tab = getPoolTab(activePoolCode);
    if (tab) {
      tab.period = $(this).val() || 'DAY';
    }
    loadPoolKline(activePoolCode);
  });
  $('#btnLoadKline').on('click', function () { loadSingleKline(); });
  $('#btnBacktest').on('click', runBacktest);
  $('#btnBatch').on('click', runBatch);
  $('#btnPortfolio').on('click', runPortfolio);
  $('#btnClearSingleHistory').on('click', clearSingleHistory);
  $('#btnClearPortfolioHistory').on('click', clearPortfolioHistory);
  $('#onlyCanBuy').on('change', function () { renderBatch(batchCache); });
  $(window).on('resize', resizeCharts);

  $('#themeSelect').on('change', function () {
    var val = $(this).val();
    var label = ($(this).find('option:selected').text() || val || '').replace(/\s+/g, ' ').trim();
    applyTheme(val);
    toast('已切换为「' + label + '」', 'ok', { place: 'theme' });
  });

  $(window).on('resize', function () {
    if ($('#toastHost').hasClass('toast-host--theme')) {
      placeThemeToastHost();
    }
  });

  var sessionStrategyIds = {};

  function isSessionStrategyId(id) {
    if (!id) return false;
    return !!sessionStrategyIds[String(id).toLowerCase()];
  }

  function fillStrategySelect($sel, data) {
    if (!$sel || !$sel.length) return;
    var list = (data && data.strategies) || [];
    var active = (data && data.activeStrategy) || '';
    $sel.empty();
    if (!list.length) {
      $sel.append($('<option/>').val('').text('无可用策略'));
      return;
    }
    list.forEach(function (s) {
      var id = s.id || '';
      var label = s.label || id;
      if (id && id === active) {
        label = label + ' · 配置默认';
      }
      var $opt = $('<option/>').val(id).text(label);
      if (s.summary) {
        $opt.attr('title', s.summary);
      }
      if (s.session) {
        $opt.attr('data-session', '1');
      }
      $sel.append($opt);
    });
    if (active) {
      $sel.val(active);
    }
  }

  function loadStrategyOptions() {
    return $.getJSON('/api/config/strategies')
      .done(function (data) {
        sessionStrategyIds = {};
        var list = (data && data.strategies) || [];
        list.forEach(function (s) {
          if (s && s.session && s.id) {
            sessionStrategyIds[String(s.id).toLowerCase()] = true;
          }
        });
        fillStrategySelect($('#singleStrategyId'), data);
        fillStrategySelect($('#pfStrategyId'), data);
      })
      .fail(function () {
        sessionStrategyIds = {};
        $('#singleStrategyId, #pfStrategyId').empty()
          .append($('<option/>').val('maCross').text('均线金叉（maCross）'));
      });
  }

  function restoreNavSession() {
    var sess = null;
    try {
      sess = JSON.parse(localStorage.getItem(NAV_SESSION_KEY) || 'null');
    } catch (e) {
      sess = null;
    }
    if (!sess || !sess.mode || sess.mode === 'home') {
      return false;
    }
    try {
      if (sess.mode === 'single') showMode('single', { panel: sess.panel || 'workspace' });
      else if (sess.mode === 'portfolio') showMode('portfolio', { panel: sess.panel || 'workspace' });
      else if (sess.mode === 'tradepool') showTradePool(sess.panel || 'pool');
      else if (sess.mode === 'account') showMode('account', { panel: sess.panel || 'funds' });
      else if (sess.mode === 'schedule') showSchedulePanel(sess.panel || 'jobs');
      else if (sess.mode === 'strategy') showStrategyEval();
      else if (sess.mode === 'dbtables') showMode('dbtables', { table: sess.table || '' });
      else if (sess.mode === 'kuangrui') showKuangruiPanel(sess.panel || 'overview');
      else if (sess.mode === 'pool') showMode('pool');
      else if (sess.mode === 'doc' && sess.panel) openKnowledge(sess.panel);
      else return false;
      return true;
    } catch (e2) {
      return false;
    }
  }

  initKnowledge();
  initSidebarCollapse();
  syncZeroCountBadges();
  initHeaderHelp();
  initTheme();
  loadSummary();
  loadPool();
  loadDbTablesMenu();
  loadStrategyOptions();
  if (!restoreNavSession()) {
    showHome();
  }
  bindCapitalHint($('#initCapital'), $('#initCapitalHint'));
  bindCapitalHint($('#pfInitCapital'), $('#pfInitCapitalHint'));
})();
