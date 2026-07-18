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
  var poolTabs = []; // { code, period }
  var activePoolCode = '';
  var lastWorkspaceMode = 'pool';
  var apiKeyRequired = false;
  var baseChart = echarts.init(document.getElementById('baseChart'));
  var singleBaseChart = echarts.init(document.getElementById('singleBaseChart'));
  var signalChart = echarts.init(document.getElementById('signalChart'));
  var singleEquityChart = echarts.init(document.getElementById('singleEquityChart'));
  var equityChart = echarts.init(document.getElementById('equityChart'));

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
   * @param {{ place?: 'theme'|'default' }} [opts]
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
    setTimeout(function () {
      $t.addClass('out');
      setTimeout(function () {
        $t.remove();
        if (opts.place === 'theme' && !$host.children('.toast').length) {
          $host.removeClass('toast-host--theme').css({ top: '', right: '', left: '', bottom: '', transform: '' });
        }
      }, 250);
    }, opts.place === 'theme' ? 2200 : 2800);
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
    if (show) {
      setTimeout(function () {
        try { singleEquityChart.resize(); } catch (e) {}
      }, 60);
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
  }

  function withLoading($btn, promiseLike) {
    $btn.addClass('loading').prop('disabled', true);
    return $.when(promiseLike).always(function () {
      $btn.removeClass('loading').prop('disabled', false);
    });
  }

  var THEME_KEYS = {
    day: 1, forest: 1, night: 1, cosmos: 1,
    interact: 1, wave: 1, matrix: 1
  };

  function applyTheme(theme) {
    // 旧主题并入：交互粒子→日间，波浪→青松，代码雨/极光/Vanta→夜盘，金融科技→日间
    if (theme === 'interact' || theme === 'finance') theme = 'day';
    if (theme === 'wave') theme = 'forest';
    if (theme === 'matrix' || theme === 'aurora' || theme === 'vanta') theme = 'night';
    if (!THEME_KEYS[theme]) theme = 'night';
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
    var theme = 'night';
    try {
      theme = localStorage.getItem('quant-theme') || document.documentElement.getAttribute('data-theme') || 'night';
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
      } catch (e) {}
    }, 60);
  }

  function loadSummary() {
    $.getJSON('/api/data/summary', function (s) {
      if (s && s.available) {
        var range = (s.start || '') + ' ~ ' + (s.end || '');
        var phStart = (s.start || 'yyyy-MM-dd') + ' 09:30:00';
        var phEnd = (s.end || 'yyyy-MM-dd') + ' 15:00:00';
        $('#dataHint').text(range + ' · MySQL行情');
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
        $('#dataHint').text('运行时合成行情');
        $('#singleHint').text('回测时间默认留空=全部可用K线。格式 yyyy-MM-dd HH:mm:ss。');
        $('#backTimeHint').text('回测时间：默认留空=全部可用K线。格式 yyyy-MM-dd HH:mm:ss。');
      }
    });
  }

  function syncPortfolioCodes() {
    var codes = [];
    var names = [];
    $('#portfolioStockList input:checked').each(function () {
      var code = $(this).val();
      codes.push(code);
      names.push((poolNames[code] || code));
    });
    $('#portfolioCodes').val(codes.join(','));
    var $bar = $('#pfSelectedBar');
    if (!codes.length) {
      $bar.addClass('empty');
      $('#pfSelectedCount').text('未勾选股票');
      $('#pfSelectedNames').text('请从左侧勾选组合成分股');
    } else {
      $bar.removeClass('empty');
      $('#pfSelectedCount').text(codes.length + ' 只 · ' + codes.join(' / '));
      $('#pfSelectedNames').text(names.join('、'));
    }
  }

  function setPortfolioResultPanelsVisible(show) {
    $('#pfEquityPanel, #pfTradePanel, #pfStockPanel').prop('hidden', !show);
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
    lastEquity = null;
    try { equityChart.clear(); } catch (e) {}
    setPortfolioResultPanelsVisible(false);
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
      $tabs.append($('<div class="empty-state"/>').text('从左侧股票池点击股票开启信息（可同时打开多只）'));
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
    $('#stockList li').removeClass('active open');
    poolTabs.forEach(function (tab) {
      $('#stockList li[data-code="' + tab.code + '"]').addClass('open');
    });
    if (activePoolCode) {
      $('#stockList li[data-code="' + activePoolCode + '"]').addClass('active');
    }
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

  function loadPool() {
    $.getJSON('/api/stock/pool', function (list) {
      var $pool = $('#stockList').empty();
      var $single = $('#singleStockList').empty();
      var $pf = $('#portfolioStockList').empty();
      var codes = [];
      (list || []).forEach(function (item, i) {
        var code = typeof item === 'string' ? item : item.code;
        var name = typeof item === 'string' ? item : (item.name || item.code);
        poolNames[code] = name;
        codes.push(code);

        $pool.append(
          $('<li/>').html('<b>' + code + '</b><br/><span class="stock-name">' + name + '</span>')
            .attr('data-code', code)
        );
        $single.append(
          $('<li class="sub-menu-item"/>')
            .attr('data-code', code)
            .attr('role', 'button')
            .attr('tabindex', '0')
            .html(
              '<div class="sub-menu-main">' +
                '<div class="sub-menu-title"><b>' + code + '</b><span class="sub-menu-badge">当前</span></div>' +
                '<span class="stock-name">' + name + '</span>' +
              '</div>' +
              '<button type="button" class="sub-menu-action" data-action="run" title="选择并回测">回测</button>'
            )
        );
        var $label = $('<label class="check-item"/>');
        var $cb = $('<input type="checkbox"/>').val(code);
        if (i < 3) $cb.prop('checked', true);
        $label.append($cb).append($('<span/>').html('<b>' + code + '</b> ' + name));
        $pf.append($('<li/>').append($label));
      });
      syncPortfolioCodes();
      if (codes.length) {
        openPoolStock(codes[0]);
        selectSingleStock(codes[0], { silent: true });
      }
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

    var period = singlePeriods[code] || 'DAY';
    singlePeriods[code] = period;
    $('#barPeriod').val(period);

    $('#singleStockList li').removeClass('active');
    var $active = $('#singleStockList li[data-code="' + code + '"]').addClass('active');
    try {
      if ($active.length && $active[0].scrollIntoView) {
        $active[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    } catch (e) {}

    // 切换标的时清掉上一只的回测展示，避免串单
    if (prev && prev !== code) {
      $('#btMetrics').html('<span class="hint">已切换至 <b>' + code + '</b>，点击「执行回测」或左侧「回测」查看结果</span>');
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

  function loadSingleHistory(code) {
    var $tb = $('#singleHistoryBody');
    collapseHistoryAnalysis($tb);
    if (!code) {
      $tb.html('<tr><td colspan="14" class="empty-state">请先选择股票</td></tr>');
      return;
    }
    $.getJSON('/api/backtest/history', { code: code })
      .done(function (rows) {
        renderSingleHistory(rows || []);
      })
      .fail(function () {
        $tb.html('<tr><td colspan="14" class="empty-state">加载历史失败</td></tr>');
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
    var events = rec.events || [];
    $panel.append($('<p/>').html('<b>摘要</b>：' + (rec.summary || '-')));
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
          parts.push(k + '=' + ev.data[k]);
        });
        dataStr = parts.join('；');
      }
      var codeTxt = ev.stockCode ? ('[' + ev.stockCode + '] ') : '';
      $ul.append($('<li/>').html(
        '<b>' + (ev.time || '') + '</b> ' + codeTxt +
        '<code>' + (ev.type || '') + '</code> ' + (ev.title || '') +
        '<br/>原因：' + (ev.reason || '-') +
        (dataStr ? ('<br/><span class="hint">数据：' + dataStr + '</span>') : '')
      ));
    });
    $panel.append($ul);
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

  function renderSingleHistory(rows) {
    var $tb = $('#singleHistoryBody');
    bindHistoryTableToggle($tb, '/api/backtest/analysis', HISTORY_COLSPAN);
    $tb.empty();
    if (!rows.length) {
      $tb.append($('<tr/>').append($('<td colspan="14" class="empty-state"/>').text('暂无该股回测记录')));
      return;
    }
    rows.forEach(function (r) {
      var s = resolveTradeStats(r);
      var $tr = $('<tr class="history-row"/>').css('cursor', 'pointer').attr('data-id', r.id || '');
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
    if (!window.confirm('确认清除股票 ' + code + ' 的全部单股回测记录及对应分析？')) {
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
    var params = { code: code, initCapital: capital, period: period };
    if (backStart) params.backStart = backStart;
    if (backEnd) params.backEnd = backEnd;
    withLoading($('#btnBacktest'), $.getJSON('/api/backtest/run', params)
      .done(function (bt) {
        $('#btMetrics').html(
          '股票<b>' + code + '</b>' +
          ' 期末资产<b>' + num(bt.finalAsset) + '</b>' +
          ' 收益率<b>' + pct(bt.totalRate) + '</b>' +
          ' 最大回撤<b>' + pct(bt.maxDrawDown) + '</b>' +
          ' 交易次数<b>' + (bt.totalTradeNum || 0) + '</b>' +
          ' 胜率<b>' + pct(bt.winRate) + '</b>'
        );
        toast('回测完成 · 交易 ' + (bt.totalTradeNum || 0) + ' 笔', 'ok');
        lastBacktestCode = code;
        lastSignalMarks = { buy: bt.buyMarks || [], sell: bt.sellMarks || [] };
        lastSingleEquity = {
          equityTimes: bt.equityTimes || [],
          equityCurve: bt.equityCurve || []
        };
        setSingleResultPanelsVisible(true);
        renderEquityChart(lastSingleEquity, singleEquityChart);
        renderTradeTable(bt);
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
      .fail(function () { toast('回测失败', 'err'); }));
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
    $('#batchBody').html('<tr><td colspan="12" class="empty-state">扫描中...</td></tr>');
    withLoading($('#btnBatch'), $.getJSON('/api/batch/scanAllStock')
      .done(function (rows) {
        batchCache = rows || [];
        renderBatch(batchCache);
        toast('批量扫描完成 · ' + batchCache.length + ' 只', 'ok');
      })
      .fail(function () {
        $('#batchBody').html('<tr><td colspan="12" class="empty-state">扫描失败</td></tr>');
        toast('批量扫描失败', 'err');
      }));
  }

  function runPortfolio() {
    syncPortfolioCodes();
    var codes = ($('#portfolioCodes').val() || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    if (!codes.length) {
      toast('请至少勾选一只股票', 'err');
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
    clearPortfolioResult();
    withLoading($('#btnPortfolio'), $.ajax({
      url: '/api/portfolio/run',
      method: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(body)
    }).done(function (pf) {
        var stats = resolveTradeStats(pf);
        $('#pfMetrics').html(
          '成分股<b>' + codes.length + '</b>' +
          ' 期末资产<b>' + num(pf.finalAsset) + '</b>' +
          ' 总盈亏<b>' + pnlText(stats.totalPnl) + '</b>' +
          ' 收益率<b>' + pct(pf.totalRate) + '</b>' +
          ' 最大回撤<b>' + pct(pf.maxDrawDown) + '</b>' +
          ' 买/卖<b>' + (stats.buyCount || 0) + '/' + (stats.sellCount || 0) + '</b>' +
          ' 胜率<b>' + pct(pf.winRate) + '</b>'
        );
        lastEquity = pf;
        setPortfolioResultPanelsVisible(true);
        renderEquityChart(pf);
        renderPortfolioTradeTable(pf);
        renderPortfolioStockBreakdown(pf);
        toast('组合回测完成 · 成交 ' + ((pf.trades || []).length) + ' 笔', 'ok');
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
    { id: 'app', group: 'app', title: '本应用介绍', src: '/docs/app.html' },
    { id: 'rules', group: 'app', title: '交易规则', src: '/docs/rules.html?v=20260719-full' },
    { id: 'memo', group: 'app', title: '备忘录', src: '/docs/memo.html' },
    { id: 'ashare', group: 'stock', title: '中国A股介绍', src: '/docs/ashare.html' },
    { id: 'session', group: 'stock', title: '交易时间介绍', src: '/docs/session.html' },
    { id: 'kline', group: 'stock', title: 'K线介绍', src: '/docs/kline.html' },
    { id: 'ma', group: 'stock', title: 'MA均线（MA5 / MA20 / MA60）', src: '/docs/ma.html' },
    { id: 'rsi', group: 'stock', title: 'RSI相对强弱', src: '/docs/rsi.html' },
    { id: 'atr', group: 'stock', title: 'ATR真实波幅', src: '/docs/atr.html' },
    { id: 'adx', group: 'stock', title: 'ADX趋势强度', src: '/docs/adx.html' },
    { id: 'boll', group: 'stock', title: '布林带 BOLL', src: '/docs/boll.html' },
    { id: 'backtest', group: 'stock', title: '回测要点', src: '/docs/backtest.html' }
  ];
  var knowledgeHtmlCache = {};

  function initKnowledge() {
    var $stock = $('#stockKnowledgeMenu').empty();
    var $app = $('#appRelatedMenu').empty();
    knowledgeTopics.forEach(function (t) {
      var $li = $('<li/>').text(t.title).attr('data-id', t.id);
      if (t.group === 'app') {
        $app.append($li);
      } else {
        $stock.append($li);
      }
    });
  }

  function setSideNavOpen(bodyId) {
    $('.side-nav-toggle').each(function () {
      var id = $(this).attr('data-body');
      // 必须是严格 boolean：jQuery toggleClass(cls, null/undefined) 会变成“切换”而非“关闭”
      var open = !!(bodyId && id === bodyId);
      $(this).attr('aria-expanded', open ? 'true' : 'false').toggleClass('open', open);
      $('#' + id).toggleClass('open', open);
    });
  }

  var homeCollapsed = false;

  function setHomeCollapsed(collapsed) {
    homeCollapsed = !!collapsed;
    var onHome = !$('#viewHome').prop('hidden');
    $('#viewHome').toggleClass('home-collapsed', homeCollapsed);
    $('body').toggleClass('home-theme-peek', homeCollapsed && onHome);
    $('#btnExpandHome').prop('hidden', !(homeCollapsed && onHome));
  }

  function hideAllWorkspaceViews() {
    $('#viewHome, #viewPool, #viewSingle, #viewPortfolio').prop('hidden', true);
    $('body').removeClass('home-theme-peek');
    $('#btnExpandHome').prop('hidden', true);
  }

  /** 初始化页：无一级菜单展开时展示 */
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
    var lead = options.lead || '左侧尚未展开菜单。点击下方入口，或展开左侧一级菜单进入对应功能。';
    $('#homeLead').text(lead);
    // 进入欢迎页默认展开；若显式要求保持收起则沿用
    setHomeCollapsed(options.keepCollapsed ? homeCollapsed : false);
  }

  /**
   * @param {string} mode pool|single|portfolio
   * @param {{expandNav?: boolean}} [options] expandNav 默认 true；为 false 时只切主区、不强制展开一级菜单
   */
  function showMode(mode, options) {
    options = options || {};
    var expandNav = options.expandNav !== false;
    lastWorkspaceMode = mode || 'pool';
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
    } else if (lastWorkspaceMode === 'portfolio') {
      $('#viewPortfolio').prop('hidden', false);
      if (expandNav) setSideNavOpen('portfolioBody');
      syncPortfolioCodes();
      loadPortfolioHistory();
    } else {
      lastWorkspaceMode = 'pool';
      $('#viewPool').prop('hidden', false);
      if (expandNav) setSideNavOpen('poolBody');
    }
    resizeCharts();
  }

  function showDocMode(menuBodyId) {
    $('body').addClass('mode-doc');
    hideAllWorkspaceViews();
    $('#knowledgePanel').prop('hidden', false);
    setSideNavOpen(menuBodyId || null);
  }

  function openKnowledge(id) {
    var topic = null;
    for (var i = 0; i < knowledgeTopics.length; i++) {
      if (knowledgeTopics[i].id === id) { topic = knowledgeTopics[i]; break; }
    }
    if (!topic) return;
    var menuId = topic.group === 'app' ? 'appRelatedMenu' : 'stockKnowledgeMenu';
    showDocMode(menuId);
    $('.side-nav-menu li').removeClass('active');
    $('.side-nav-menu li[data-id="' + id + '"]').addClass('active');
    $('#knowledgeTitle').text(topic.title);
    $('#knowledgeBody').html('<p>加载中…</p>');
    try { $('#knowledgePanel')[0].scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) {}

    function render(html) {
      $('#knowledgeBody').html(html || '<p>暂无内容</p>');
    }
    if (knowledgeHtmlCache[topic.src]) {
      render(knowledgeHtmlCache[topic.src]);
      return;
    }
    $.get(topic.src)
      .done(function (html) {
        knowledgeHtmlCache[topic.src] = html;
        // 若用户已点开其它条目，勿覆盖
        if ($('#knowledgeTitle').text() !== topic.title) return;
        render(html);
      })
      .fail(function () {
        if ($('#knowledgeTitle').text() !== topic.title) return;
        render('<p>文档加载失败：' + topic.src + '</p>');
      });
  }

  $('.side-nav-toggle').on('click', function () {
    var $btn = $(this);
    var bodyId = $btn.attr('data-body');
    var mode = $btn.attr('data-mode');
    var wasOpen = $btn.attr('aria-expanded') === 'true';

    // 再次点击已展开的一级菜单：全部收起，右侧回到初始化页
    if (wasOpen) {
      showHome();
      return;
    }

    if (mode === 'doc') {
      setSideNavOpen(bodyId);
      $('body').addClass('mode-doc');
      hideAllWorkspaceViews();
      $('#knowledgePanel').prop('hidden', true);
      $('.side-nav-menu li').removeClass('active');
      $('#viewHome').prop('hidden', false);
      $('#homeLead').text('已展开说明菜单，请在左侧点选条目阅读；或再点同一菜单收起并回到本页。');
      setHomeCollapsed(false);
    } else {
      showMode(mode); // 切入对应工作台并展开该一级菜单
    }
  });

  $('.home-actions').on('click', '[data-open-nav]', function () {
    var bodyId = $(this).attr('data-open-nav');
    var $btn = $('.side-nav-toggle[data-body="' + bodyId + '"]');
    if (!$btn.length) return;
    if ($btn.attr('aria-expanded') === 'true') return;
    $btn.trigger('click');
  });

  $('#btnCollapseHome').on('click', function () {
    setHomeCollapsed(true);
    toast('欢迎页已收起，可切换主题欣赏背景', 'info', { place: 'theme' });
  });

  $('#btnExpandHome').on('click', function () {
    setHomeCollapsed(false);
    toast('已展开欢迎页', 'ok');
  });

  $('.side-nav-menu').on('click', 'li', function () {
    openKnowledge($(this).data('id'));
  });

  $('#knowledgeClose').on('click', function () {
    showMode(lastWorkspaceMode || 'pool');
  });

  $('#stockList').on('click', 'li', function () {
    showMode('pool');
    openPoolStock($(this).data('code'));
  });

  $('#singleStockList').on('click', 'li', function (e) {
    var code = $(this).data('code');
    var $btn = $(e.target).closest('[data-action="run"]');
    showMode('single');
    selectSingleStock(code);
    if ($btn.length) {
      runBacktest();
    }
  });

  $('#singleStockList').on('keydown', 'li', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
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

  $('#portfolioStockList').on('change', 'input[type=checkbox]', function () {
    syncPortfolioCodes();
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

  initKnowledge();
  initTheme();
  loadSummary();
  loadPool();
  showHome();
  bindCapitalHint($('#initCapital'), $('#initCapitalHint'));
  bindCapitalHint($('#pfInitCapital'), $('#pfInitCapitalHint'));
})();
