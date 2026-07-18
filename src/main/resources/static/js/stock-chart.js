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
    {
      id: 'app',
      group: 'app',
      title: '本应用介绍',
      html:
        '<p><b>Quant Stock</b> 是基于 Spring Boot 2.7 + TA4J 的 A 股量化回测单体应用（非前后端分离），页面内嵌于 <code>static/</code>。</p>' +
        '<h4>如何启动</h4><ul>' +
        '<li>项目根目录执行：<code>mvn spring-boot:run</code></li>' +
        '<li>浏览器打开：<code>http://localhost:8080/stock.html</code></li>' +
        '<li>默认连接本地 MySQL（localhost:3306/quant_stock，root/123456），并执行 <code>mapper/schema.sql</code></li>' +
        '<li>空库启动时自动从 classpath JSON 导入日线 + 5 分钟模拟数据</li>' +
        '</ul>' +
        '<h4>行情与回测存储</h4><ul>' +
        '<li>股票：600036 招商银行、000001 平安银行、300059 东方财富</li>' +
        '<li>区间：约 2025-07-17 ~ 2026-07-17</li>' +
        '<li>物理表：<code>market_daily</code>（日线）、<code>market_minute</code>（5分钟）；其它周期运行时聚合</li>' +
        '<li>回测历史/分析：<code>bt_backtest_record</code> / <code>bt_backtest_analysis</code></li>' +
        '<li>种子目录：<code>src/main/resources/data/kline/</code>（仅导入用）</li>' +
        '</ul>' +
        '<h4>页面能做什么</h4><ul>' +
        '<li>进入应用先显示<strong>初始化页</strong>；展开左侧一级菜单进入功能，再点同一菜单可全部收起并回到初始化页</li>' +
        '<li><b>股票池</b>：仅行情展示，可同时开启多只股票信息（标签切换）</li>' +
        '<li><b>单股回测</b>：选股后可见基础K线+信号图（标注买卖点）+权益曲线+成交明细/收益汇总+K线表；历史与分析一一对应，点击历史行在<strong>该行下方</strong>展开决策分析</li>' +
        '<li><b>组合回测</b>：勾选多只共享资金池回测；权益曲线 + 收益看板 + 成交流水 + 分股汇总；点击历史行在该行下方展开对应分析</li>' +
        '<li>回测起止时间默认留空 = 全量 K 线（单股/组合均支持填写）；初始资金默认 <b>100000</b></li>' +
        '<li>侧栏另有股票知识、本应用相关（介绍、交易规则、备忘录）</li>' +
        '<li>页头主题：黑客帝国 / 科技粒子 / 青松·3D星空 / 星空粒子，本地记住选择</li>' +
        '</ul>' +
        '<h4>策略与风控摘要</h4><ul>' +
        '<li>均线金叉死叉；可配置 MA60 / 放量 / ADX / RSI 过滤</li>' +
        '<li>综合成本止损、移动止盈；T+1 <b>分档</b>；金字塔 50/30/20（成交后占档）</li>' +
        '<li>撮合双口径：日K下一根开盘；分钟K≥次日09:45；涨跌停主板10%/创科20%</li>' +
        '<li>单股 / 组合 / 实盘扫描均对齐上述核心规则（实盘为模拟现金账本）</li>' +
        '<li>细则见「本应用相关 → 交易规则」</li>' +
        '</ul>' +
        '<h4>可选能力</h4><ul>' +
        '<li>安全：可选 <code>QUANT_API_KEY</code>；限流 <code>QUANT_RATE_LIMIT</code></li>' +
        '<li>市值股本：<code>quant.float-shares-yi.&lt;code&gt;</code></li>' +
        '<li>扩展：<code>KlineSdkClient</code> / <code>trade-mode=sdk</code></li>' +
        '</ul>' +
        '<p>更完整说明见仓库根目录 <code>README.md</code>。应用变更时 README 与本页须同步更新。</p>'
    },
    {
      id: 'rules',
      group: 'app',
      title: '交易规则',
      html:
        '<p>本页为应用<strong>完整规则说明</strong>（策略买卖、撮合成本、风控、单股/组合/批量扫描/实盘差异、页面默认与落库）。' +
        '默认值来自 <code>application.yml</code> → <code>quant.*</code>；改配置后需重启，并同步更新本页。</p>' +

        '<h4>一、统计口径</h4><ul>' +
        '<li><b>权益</b> = 现金 + 持仓市值（成交已含佣金、印花税、滑点与冲击成本）</li>' +
        '<li><b>峰值回撤</b> = (历史最高权益 − 当前权益) / 历史最高权益</li>' +
        '<li><b>总收益率</b> = (期末权益 − 初始资金) / 初始资金</li>' +
        '<li><b>胜率</b> = 盈利的完整开平回合数 / 全部完整开平回合数</li>' +
        '<li><b>综合成本</b>（单票）= Σ(买入成交额 + 买入佣金) / 股数（不含印花税）</li>' +
        '<li><b>成交汇总 tradeStats</b>：买卖次数/股数/手数/成交额、费用合计、总盈亏(=期末−初始)</li>' +
        '</ul>' +

        '<h4>二、买入何时触发</h4><ul>' +
        '<li><b>金叉首开</b>：上一根 MA5≤MA20，本根 MA5&gt;MA20（收盘确认；有效信号通常需 K 线约≥65根）</li>' +
        '<li><b>必过过滤</b>：RSI14 &lt; 60；ATR14 &gt; 0.001；' +
        '<b>硬过滤</b>：模拟市值 &lt; 50 亿 → <strong>直接跳过、不开仓</strong></li>' +
        '<li><b>可选过滤</b>（默认关）：MA60 上行、放量 &gt; 20日均量×1.2、ADX≥25 且非震荡</li>' +
        '<li><b>金字塔加仓</b>（默认开）：该票浮盈≥1%（相对综合成本）且 MA5&gt;MA20、总仓≤80%；' +
        '<strong>不重验</strong> RSI/ATR；<strong>仅成交成功后</strong>占档（50%→30%→20%）</li>' +
        '<li><b>同日约束（按股票）</b>：该票当日已加仓成交 → 不再受理首开；首开成交当日也不会再挂同日加仓</li>' +
        '<li>该票已有未成交买单时，不再挂新买单</li>' +
        '</ul>' +

        '<h4>三、买入数量怎么算</h4><ul>' +
        '<li>目标满仓股数 = 可用资金 × <b>单只上限30%</b> × ATR调节 × <b>账户仓位系数</b> ÷ 现价，取整到 100 股</li>' +
        '<li><b>ATR调节</b> = <code>baseAtr</code>(<b>0.05</b>) ÷ 当前ATR，再夹到 <b>[0.2, 1.5]</b></li>' +
        '<li><b>账户仓位系数</b>（峰值回撤）：&lt;15%→1.0；≥15%且&lt;25%→0.5；≥25%→0</li>' +
        '<li>总仓：持仓市值 + 拟买入额 ≤ 总权益 × <b>80%</b></li>' +
        '<li>金字塔分批相对「目标满仓」：50% / 30% / 20%</li>' +
        '</ul>' +

        '<h4>四、卖出何时触发</h4><ul>' +
        '<li><b>死叉</b>：挂卖 → 下一有效撮合卖出<strong>该标的全部持仓</strong>；已挂卖不刷新信号日</li>' +
        '<li><b>同日优先级（回测）</b>：先止损/trail；若当日已止损清仓（<code>stoppedOutToday</code>）→ 忽略同日死叉</li>' +
        '<li><b>止损线</b> = max(成本−2×ATR, 成本−权益×2%/股数)，只上移</li>' +
        '<li><b>T+1</b>：仅 <code>openDate &lt; 今日</code> 的老仓可止损/trail</li>' +
        '<li><b>移动止盈</b>：盘后 trail = 持仓最高 − 1.5×ATR（只上移）；次日盘中老仓最低价触及判定</li>' +
        '<li><b>跌停</b>：相对昨收；挂卖连续 3 个交易日失败后按跌停价×0.99 强平</li>' +
        '<li><b>回撤熔断</b>：峰值回撤≥25% → 挂清仓且禁新开；<strong>本轮粘性</strong>（回测结束或进程重启前不自动解除）</li>' +
        '</ul>' +

        '<h4>五、撮合与成本</h4><ul>' +
        '<li><b>日K/周月</b>：收盘信号 → <b>下一根开盘价</b></li>' +
        '<li><b>分钟序列</b>：开仓/死叉在<strong>信号日次日</strong>且 bar≥<b>09:45</b> 的第一根可用 K</li>' +
        '<li><b>静默</b>：09:30–09:45、14:45–15:00 禁止挂新开信号（不会「当日静默窗口内 09:45 成交首开」）</li>' +
        '<li>例外：老仓止损/trail 按触及价<strong>当根</strong>卖</li>' +
        '<li>买单超过信号日 <b>+5 日历日</b>未成交 → 取消</li>' +
        '<li>佣金万三；印花税千一（仅卖）</li>' +
        '<li>分级滑点（近20均量）：≥2000万→0.05%；≥500万→0.2%；否则→0.5%</li>' +
        '<li>冲击：min(0.1×本笔量/20日均量, 2%)；买上浮、卖下浮，与滑点叠加</li>' +
        '</ul>' +

        '<h4>六、开仓过滤与账户风控</h4><ul>' +
        '<li>涨跌停相对昨收（主板±10% / 创科±20%，含 OHLC 封板）</li>' +
        '<li>停牌量≤0 → 禁止开仓；持仓复牌无「首日必卖」专项规则</li>' +
        '<li>低流动性：近20均量 &lt; <code>min-avg-volume20</code>（演示默认 <b>1000</b>）→ 不开</li>' +
        '<li>单日亏≥昨收权益的 3% → 当日禁新开</li>' +
        '<li>连亏 5 笔完整开平 → 当日禁开、<strong>次日自动恢复</strong></li>' +
        '<li>回撤≥15% 仓位×0.5；≥25% 禁开+清仓挂单</li>' +
        '</ul>' +

        '<h4>七、买单/卖单未能成交的常见原因</h4><ul>' +
        '<li>账户熔断禁开 / 单日亏损禁开 / 连亏禁开</li>' +
        '<li>开盘静默 09:30–09:45 禁止新开成交</li>' +
        '<li>开仓过滤未过：涨跌停、停牌、流动性、市值、收盘静默等</li>' +
        '<li>加仓时涨停或停牌</li>' +
        '<li>现金不足，或买入后突破总仓 80%</li>' +
        '<li>仓位系数缩放后不足 1 手（100股）</li>' +
        '<li>卖出遇跌停：累计失败，满 3 个交易日后强平</li>' +
        '<li>买单超过信号日+5 日历日 → 过期取消</li>' +
        '</ul>' +

        '<h4>八、单只回测（页面「单股回测」）</h4><ul>' +
        '<li>默认周期 <b>DAY</b>；起止时间留空 = 该股全量可用 K 线</li>' +
        '<li>初始资金默认 <b>100000</b>（可改）</li>' +
        '<li>决策顺序：①撮合挂单 → ②老仓止损/trail → ③账户风控 → ④收盘挂金叉/金字塔/死叉 → ⑤盘后更新 trail</li>' +
        '<li>结果含：权益曲线、买卖点、成交明细、tradeStats、决策分析事件</li>' +
        '<li>历史与分析一一对应，写入 MySQL：<code>bt_backtest_record</code> / <code>bt_backtest_analysis</code></li>' +
        '</ul>' +

        '<h4>九、组合回测（多股共享资金池）</h4><ul>' +
        '<li>勾选多只成分股；<b>共用一份现金</b>与同一套账户风控（熔断/连亏/单日亏/仓位系数）</li>' +
        '<li>强制用 <b>DAY</b> 行情；按各股交易日并集时间轴推进；某股当日无 K 则跳过该股</li>' +
        '<li>单股 K 线不足约 65 根则不进入组合</li>' +
        '<li>每只股票独立：持仓 lot、金字塔档位、挂单、止损/trail、<code>stoppedOutToday</code></li>' +
        '<li>买卖规则与单股相同；买入手数仍受单只30%与总仓80%约束（相对组合总权益）</li>' +
        '<li>熔断时：对<strong>所有仍有持仓的成分股</strong>挂清仓</li>' +
        '<li>分股表现：按该股已实现盈亏、买卖统计、该股维度回撤等汇总；贡献率≈该股已实现盈亏/初始资金</li>' +
        '<li>历史/分析同样落库（kind=PORTFOLIO）</li>' +
        '</ul>' +

        '<h4>十、批量扫描（单股回测页「扫描」）</h4><ul>' +
        '<li>对配置股票池（默认 600036/000001/300059）线程池并发</li>' +
        '<li>每只：全量 <b>DAY</b> K 线 + 初始资金 100000 跑一遍单股回测引擎</li>' +
        '<li>K 线不足 20 根则跳过该股</li>' +
        '<li>额外计算最新指标与「当前是否金叉可买」信号描述，按总收益率降序展示</li>' +
        '<li>扫描结果为即时列表，不单独写入回测历史表（除非你再点单股回测保存）</li>' +
        '</ul>' +

        '<h4>十一、实盘分钟扫描（定时任务，模拟现金）</h4><ul>' +
        '<li>交易时段约每分钟扫描（工作日 9–11、13–15 点）；模拟初始现金默认 100000</li>' +
        '<li>规则意图对齐回测：金叉/金字塔/死叉、止损/trail、账户风控、09:45 撮合、跌停强平等</li>' +
        '<li><b>已知差异</b>：实盘路径里 <code>stoppedOutToday</code> 未像回测那样在止损后置位，' +
        '极端情况下同日仍可能再挂死叉（与回测「止损优先忽略死叉」不完全一致）</li>' +
        '<li><code>trade-mode=sim</code>：下单即时成交；<code>sdk</code>：SUBMITTED 后由同步推进（桩实现）</li>' +
        '<li>实盘持仓/现金默认在进程内存，不落 <code>trade_positions</code>（表已建，供后续扩展）</li>' +
        '<li>收盘后任务：约 15:30 相关清算；16:00 可触发批量扫描日志等</li>' +
        '</ul>' +

        '<h4>十二、行情与页面产品规则</h4><ul>' +
        '<li>行情真相源：MySQL <code>market_daily</code>（日线）+ <code>market_minute</code>（5分钟）；' +
        '15/30/60分、周、月由运行时聚合；MIN_1 请求降级为 5 分钟序列</li>' +
        '<li>空库启动时从 <code>classpath:data/kline</code> 的 DAY/MIN_5 JSON <b>导入一次</b>，运行时不再读 JSON 做展示</li>' +
        '<li>股票池展示 / K 线图表：走统一 <code>MarketDataService#getKline</code></li>' +
        '<li>回测时间留空 = 全量；填写则截取区间；初始资金默认 100000</li>' +
        '<li>侧栏：一级菜单互斥；回测成功前可隐藏权益/成交等结果面板</li>' +
        '<li>可选：API Key（<code>QUANT_API_KEY</code>）、回测类接口限流（默认 30 次/分钟/IP）</li>' +
        '</ul>' +

        '<h4>十三、单只回测 bar 内决策顺序（摘要）</h4><ol>' +
        '<li>撮合已挂单（日K开盘 / 分钟次日≥09:45）</li>' +
        '<li>仅老仓止损 / 移动止盈</li>' +
        '<li>账户风控快照</li>' +
        '<li>收盘挂金叉 / 金字塔 / 死叉</li>' +
        '<li>盘后更新最高价与 trail</li>' +
        '</ol>' +
        '<p>配置入口：<code>application.yml</code> 的 <code>quant</code> 段。策略代码：' +
        '<code>BackTestEngine</code> / <code>PortfolioBackTestEngine</code> / <code>BatchStockBackTestService</code> / <code>StrategyTask</code>。</p>'
    },
    {
      id: 'memo',
      group: 'app',
      title: '备忘录',
      html:
        '<p>除不同周期 K 线价量外，完整量化系统还需<strong>基本面、事件驱动、另类数据、行情衍生</strong>四大类。本页记录与金叉策略相关的数据待办；应用实质性改动后须对照更新（见项目规则）。</p>' +
        '<div class="memo-todo"><h4 style="margin-top:0">落地优先级（投入产出比）</h4><ol>' +
        '<li><b>复权因子</b>（除权除息）— 未落地 · MA/ATR 不复权会失真</li>' +
        '<li><b>资金流向</b>（主力净流入）— 未落地 · 金叉+流入增强置信度</li>' +
        '<li><b>市值/流动性</b>（流通市值、日均成交额）— 部分：规则有、真数不足（现为股本启发式）</li>' +
        '<li><b>财报</b>（PE、ROE、营收增速）— 未落地 · 建议金叉后再滤 PE/ROE</li>' +
        '<li><b>停牌/ST 状态</b>— 部分：停牌量≤0；缺实时 ST/退市源</li>' +
        '<li><b>新闻舆情</b>— 未落地 · 噪声大、需 NLP</li>' +
        '</ol><p style="margin:0"><b>一句总结</b>：骨架已有；下一步优先<strong>复权因子</strong>与<strong>资金流向</strong>。</p></div>' +
        '<h4>一、基本面数据（与金叉关联最密）</h4>' +
        '<p>决定股票质地，可作买入前二次过滤（在 RSI&lt;60、ATR&gt;0.001 之上）。</p>' +
        '<table><thead><tr><th>子类</th><th>数据项</th><th>策略用法</th></tr></thead><tbody>' +
        '<tr><td>估值</td><td>PE / PB / PS / PEG</td><td>过滤估值过高，避免追高</td></tr>' +
        '<tr><td>盈利</td><td>ROE、毛利率、净利率</td><td>避开业绩暴雷股</td></tr>' +
        '<tr><td>成长</td><td>营收/净利同比增速</td><td>成长股与金叉共振</td></tr>' +
        '<tr><td>财务健康</td><td>资产负债率、现金流、应收</td><td>避开高负债、现金流紧张</td></tr>' +
        '<tr><td>市值</td><td>总市值、流通市值</td><td>已有「市值过低→不开」，需真实阈值</td></tr>' +
        '</tbody></table>' +
        '<p><b>实操建议</b>：金叉后加滤 <code>PE &lt; 行业PE中位数×1.5</code> 且 <code>ROE &gt; 10%</code>。</p>' +
        '<h4>二、行情衍生数据</h4>' +
        '<p>当前仅用 MA5/20/60、RSI14、ATR14；可扩展：</p>' +
        '<table><thead><tr><th>类型</th><th>内容</th><th>价值</th></tr></thead><tbody>' +
        '<tr><td>资金流向</td><td>主力净流入、大单、北向</td><td>金叉+流入=信号增强</td></tr>' +
        '<tr><td>Level2</td><td>十档、逐笔、大单拆分</td><td>辨真假突破</td></tr>' +
        '<tr><td>筹码</td><td>获利盘、套牢盘、集中度</td><td>上方抛压</td></tr>' +
        '<tr><td>换手/流动性</td><td>换手率、日均额、价差</td><td>量化「低流动性→不开」</td></tr>' +
        '<tr><td>波动率</td><td>历史/隐含波动、VIX</td><td>补充 ATR 视角</td></tr>' +
        '</tbody></table>' +
        '<h4>三、事件驱动数据</h4>' +
        '<table><thead><tr><th>事件</th><th>内容</th><th>对金叉影响</th></tr></thead><tbody>' +
        '<tr><td>财报</td><td>季年报、预告、修正</td><td>报前抢跑 / 报后确认</td></tr>' +
        '<tr><td>分红送转</td><td>除权除息、送转比</td><td>需复权，否则 MA/RSI/ATR 失真</td></tr>' +
        '<tr><td>增减持/回购</td><td>股东增减持、回购</td><td>增持+金叉强；减持警惕</td></tr>' +
        '<tr><td>停复牌</td><td>起止日、原因</td><td>已有停牌不开；复牌首日需特殊处理</td></tr>' +
        '<tr><td>ST/退市</td><td>ST/*ST、退市警示</td><td>必须过滤</td></tr>' +
        '</tbody></table>' +
        '<h4>四、另类数据</h4>' +
        '<table><thead><tr><th>类型</th><th>内容</th><th>场景</th></tr></thead><tbody>' +
        '<tr><td>新闻舆情</td><td>财经新闻、公告、政策</td><td>金叉+正面舆情增强</td></tr>' +
        '<tr><td>社交媒体</td><td>股吧/雪球/微博热度</td><td>极端看多或作反向</td></tr>' +
        '<tr><td>分析师</td><td>盈利预测、评级、目标价</td><td>上调+金叉强信号</td></tr>' +
        '<tr><td>宏观</td><td>GDP/CPI/PMI/利率/社融</td><td>熊市金叉成功率更低</td></tr>' +
        '<tr><td>行业</td><td>景气度、产业链价格</td><td>上行期金叉更有价值</td></tr>' +
        '</tbody></table>' +
        '<h4>五、数据频率分层</h4>' +
        '<table><thead><tr><th>频率</th><th>粒度</th><th>用途 / 现状</th></tr></thead><tbody>' +
        '<tr><td>Tick</td><td>每笔成交</td><td>高频 — 未接入</td></tr>' +
        '<tr><td>分钟</td><td>1/5/15/30/60</td><td>已用（含 09:45 规则）</td></tr>' +
        '<tr><td>日</td><td>日K</td><td>已用</td></tr>' +
        '<tr><td>周/月</td><td>周K、月K</td><td>已有数据文件；大趋势过滤可加强</td></tr>' +
        '</tbody></table>' +
        '<p>改数据能力或落地上述待办时，请同步更新本备忘录中的「落地优先级」勾选状态。</p>'
    },
    {
      id: 'ashare',
      group: 'stock',
      title: '中国A股介绍',
      html: '<p>A股是人民币普通股票，在上海证券交易所（沪市）与深圳证券交易所（深市）上市交易，面向境内投资者。</p>' +
        '<h4>常见板块</h4><ul>' +
        '<li><b>主板</b>：沪市以 <code>6</code> 开头（如 600036），深市以 <code>000</code> 开头（如 000001）。</li>' +
        '<li><b>创业板</b>：深市 <code>300</code> 开头（如 300059），成长型企业居多，涨跌幅限制更宽。</li>' +
        '<li><b>科创板</b>：沪市 <code>688</code> 开头，注册制，投资者门槛更高。</li>' +
        '</ul><h4>交易单位</h4><p>买卖通常以 <b>100 股</b>为一手（整数手），不足一手为碎股，规则受限。</p>'
    },
    {
      id: 'session',
      group: 'stock',
      title: '交易时间介绍',
      html: '<p>A股常规交易日为周一至周五（法定节假日休市）。</p>' +
        '<h4>连续竞价时段</h4><ul>' +
        '<li>上午：<code>09:30 – 11:30</code></li>' +
        '<li>下午：<code>13:00 – 15:00</code></li>' +
        '</ul><h4>集合竞价</h4><ul>' +
        '<li>开盘集合竞价：<code>09:15 – 09:25</code></li>' +
        '<li>收盘集合竞价：<code>14:57 – 15:00</code>（沪深略有差异，以交易所规则为准）</li>' +
        '</ul><p>本系统风控中，开盘后 15 分钟与收盘前 15 分钟可配置为「静默不开新仓」，减少噪音行情干扰。</p>'
    },
    {
      id: 'kline',
      group: 'stock',
      title: 'K线介绍',
      html: '<p>K线（蜡烛图）用一根柱子概括一段时间的开高低收：</p><ul>' +
        '<li><b>开盘价 Open</b>：区间第一笔成交价</li>' +
        '<li><b>最高价 High / 最低价 Low</b>：区间极值</li>' +
        '<li><b>收盘价 Close</b>：区间最后一笔成交价</li>' +
        '<li><b>成交量 Volume</b>：区间成交股数合计</li>' +
        '</ul><h4>本系统周期</h4><p>最小粒度是 <b>1 分钟K</b>；5/15/30/60 分钟、日、周、月均由 1 分钟聚合：开=首根开、收=末根收、高=最高、低=最低、量=求和。</p>' +
        '<p>指标与回测应使用<strong>已闭合K线</strong>，未走完的当前K线默认剔除，避免用到未确认价格。</p>'
    },
    {
      id: 'ma',
      group: 'stock',
      title: 'MA均线（MA5 / MA20 / MA60）',
      html: '<p>移动平均线（Moving Average）是一段时间收盘价的算术平均，用来刻画趋势与成本重心。</p><ul>' +
        '<li><code>MA5</code>：近 5 根K线均价，更灵敏，常看作短线成本</li>' +
        '<li><code>MA20</code>：近 20 根，中短线趋势参考</li>' +
        '<li><code>MA60</code>：近 60 根，偏大周期方向过滤</li>' +
        '</ul><h4>金叉 / 死叉</h4><p><b>金叉</b>：短期均线由下向上穿越长期均线，常作买入参考；<b>死叉</b>则相反。</p>' +
        '<p>震荡市中均线会反复交叉产生假信号，因此本系统可叠加大周期趋势、放量、ADX 等过滤。</p>'
    },
    {
      id: 'rsi',
      group: 'stock',
      title: 'RSI相对强弱',
      html: '<p>RSI（Relative Strength Index）衡量一段时间内涨跌力量对比，常用周期 14。</p><ul>' +
        '<li>一般认为 <code>RSI &gt; 70</code> 偏超买，追高风险大</li>' +
        '<li><code>RSI &lt; 30</code> 偏超卖，可能存在反弹机会</li>' +
        '<li>本策略买入时默认要求 RSI 不过高（如 &lt; 60），减少高位接盘</li>' +
        '</ul><p>RSI 在强趋势行情中可能长期处在高位或低位，不宜单独作为开平仓依据。</p>'
    },
    {
      id: 'atr',
      group: 'stock',
      title: 'ATR真实波幅',
      html: '<p>ATR（Average True Range）衡量价格波动幅度，不区分方向，常用 14 周期。</p><ul>' +
        '<li>ATR 大：波动剧烈，止损需更宽，仓位宜更小</li>' +
        '<li>ATR 小：波动温和，止损可收紧</li>' +
        '</ul><h4>本系统用法</h4><ul>' +
        '<li>动态仓位：波动大时降低投入资金</li>' +
        '<li>止损：入场价 − 2×ATR；移动止盈：最高价 − 1.5×ATR</li>' +
        '</ul>'
    },
    {
      id: 'adx',
      group: 'stock',
      title: 'ADX趋势强度',
      html: '<p>ADX（Average Directional Index）衡量趋势<strong>强度</strong>，不告诉涨跌方向。</p><ul>' +
        '<li>经验上 <code>ADX &lt; 20</code> 多为震荡/无趋势，均线策略易假信号</li>' +
        '<li><code>ADX &gt; 25</code> 趋势相对明确，更适合趋势跟踪</li>' +
        '</ul><p>可在 <code>application.yml</code> 中开启 <code>adx-filter-enabled</code>，震荡市直接屏蔽开仓。</p>'
    },
    {
      id: 'boll',
      group: 'stock',
      title: '布林带 BOLL',
      html: '<p>布林带通常由 20 周期中轨（SMA）与上下轨（中轨 ± 2 倍标准差）构成。</p><ul>' +
        '<li>价格贴近上轨：偏强或超买区</li>' +
        '<li>价格贴近下轨：偏弱或超卖区</li>' +
        '<li>带宽扩大：波动升温；收窄：波动下降，可能酝酿突破</li>' +
        '</ul><p>本页K线图会叠加布林带虚线，便于观察震荡区间与突破。</p>'
    },
    {
      id: 'backtest',
      group: 'stock',
      title: '回测要点',
      html: '<p>回测是用历史K线模拟策略买卖，结果不等于实盘收益。</p><h4>本系统关键规则</h4><ul>' +
        '<li>信号在K线收盘确认后，默认按<strong>下一根开盘价</strong>撮合，减少未来函数</li>' +
        '<li>成本含佣金、卖出印花税、分级滑点与冲击成本</li>' +
        '<li>支持 ATR/硬止损、移动止盈、金字塔分批建仓与账户熔断</li>' +
        '</ul><p>回测时间留空表示使用全部可用数据；组合回测可按起止时间截取。</p>'
    }
  ];

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
    $('#knowledgeBody').html(topic.html);
    try { $('#knowledgePanel')[0].scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) {}
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
