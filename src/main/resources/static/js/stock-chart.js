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
  /** 全市场标的缓存：[{code,name}]，供工作台模糊选股 */
  var universeList = [];
  var PICKER_LIMIT = 60;
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
    day: 1, forest: 1, night: 1, cosmos: 1,
    interact: 1, wave: 1, matrix: 1
  };

  function applyTheme(theme) {
    // 旧主题并入：交互粒子→日间，波浪→青松，代码雨/极光/Vanta→夜盘，金融科技→日间
    if (theme === 'interact' || theme === 'finance') theme = 'day';
    if (theme === 'wave') theme = 'forest';
    if (theme === 'matrix' || theme === 'aurora' || theme === 'vanta') theme = 'night';
    if (!THEME_KEYS[theme]) theme = 'day';
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
    var theme = 'day';
    try {
      theme = localStorage.getItem('quant-theme') || document.documentElement.getAttribute('data-theme') || 'day';
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
    portfolioSelected = universeList.slice(0, n || 3).map(function (it) { return it.code; });
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  function clearPortfolioSelection() {
    portfolioSelected = [];
    syncPortfolioCodes();
    renderStockPicker('portfolio');
  }

  function syncPortfolioCodes() {
    portfolioSelected = portfolioSelected.filter(function (c) { return !!poolNames[c]; });
    $('#portfolioCodes').val(portfolioSelected.join(','));
    $('#pfSelectedCountNum').text(String(portfolioSelected.length));
    var $bar = $('#pfChipsBar');
    var $chips = $('#pfChips').empty();
    if (!portfolioSelected.length) {
      $bar.addClass('empty');
      $chips.append($('<span class="pf-chips-empty"/>').text('尚未选择 · 在上方列表点击添加'));
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
      $tabs.append($('<div class="empty-state"/>').text('在上方搜索并点击股票开启信息（可同时打开多只）'));
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
    $('#poolStockResults li').removeClass('active open');
    poolTabs.forEach(function (tab) {
      $('#poolStockResults li[data-code="' + tab.code + '"]').addClass('open');
    });
    if (activePoolCode) {
      $('#poolStockResults li[data-code="' + activePoolCode + '"]').addClass('active');
    }
  }

  function normalizeStockQuery(q) {
    return String(q || '').trim().toLowerCase().replace(/\s+/g, '');
  }

  /** 代码/名称模糊匹配（包含、前缀优先） */
  function filterUniverse(q, limit) {
    limit = limit || PICKER_LIMIT;
    var query = normalizeStockQuery(q);
    var list = universeList || [];
    if (!query) {
      return list.slice(0, limit);
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
      if (prefix.length + mid.length >= limit * 2) break;
    }
    return prefix.concat(mid).slice(0, limit);
  }

  function renderStockPicker(mode) {
    var isPool = mode === 'pool';
    var isPf = mode === 'portfolio';
    var q = isPool ? $('#poolStockQ').val() : (isPf ? $('#pfStockQ').val() : $('#singleStockQ').val());
    var $list = isPool ? $('#poolStockResults') : (isPf ? $('#pfStockResults') : $('#singleStockResults'));
    var $hint = isPool ? $('#poolStockMatchHint') : (isPf ? $('#pfStockMatchHint') : $('#singleStockMatchHint'));
    var matched = filterUniverse(q, PICKER_LIMIT);
    var total = universeList.length;
    var query = normalizeStockQuery(q);
    $hint.text(query
      ? ('匹配 ' + matched.length + (matched.length >= PICKER_LIMIT ? '+' : '') + ' / 共 ' + total)
      : ('展示前 ' + matched.length + ' / 共 ' + total + ' · 输入可筛选'));
    $list.empty();
    if (!matched.length) {
      $list.append($('<li class="stock-picker-empty"/>').text(total ? '无匹配标的' : '暂无股票数据'));
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
      if (isPool) {
        if (getPoolTab(code)) $li.addClass('open');
        if (code === activePoolCode) $li.addClass('active');
      } else if (isPf) {
        if (isPortfolioSelected(code)) $li.addClass('selected');
      } else if (code === singleCode) {
        $li.addClass('active');
      }
      $list.append($li);
    });
  }

  function refreshUniverseCounts() {
    var n = universeList.length;
    $('#poolUniverseCount, #singleUniverseCount, #pfUniverseCount').text(String(n));
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
      $('#sidePoolCount, #tpPoolBadge').text(String(count));
      $('#tpPoolHint').text('目标池 ' + count + ' / 上限 ' + maxFinal);

      var $tb = $('#tpPoolBody').empty();
      if (!items.length) {
        $tb.html('<tr><td colspan="6" class="empty-state">目标池为空，可点「扫描更新」或在运维中心开启 pool-rebuild</td></tr>');
      } else {
        items.forEach(function (it) {
          poolNames[it.code] = it.name || it.code;
          $tb.append(
            $('<tr class="tp-pool-row"/>')
              .attr('data-code', it.code)
              .attr('data-report-id', it.reportId != null ? it.reportId : '')
              .css('cursor', 'pointer')
              .html(
                '<td><b>' + escHtml(it.code) + '</b></td>'
                + '<td>' + escHtml(it.name || '') + '</td>'
                + '<td class="mono">' + escHtml(fmtPoolScore(it.score)) + '</td>'
                + '<td>' + escHtml(it.reason || '') + '</td>'
                + '<td class="mono">' + escHtml(it.enteredAt || '—') + '</td>'
                + '<td><button type="button" class="secondary tp-remove" data-code="' + escHtml(it.code) + '">移出</button></td>'
              )
          );
        });
      }
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载失败';
      $('#tpPoolBody').html('<tr><td colspan="6" class="empty-state">' + escHtml(msg) + '</td></tr>');
    });
  }

  function loadPool() {
    $.getJSON('/api/stock/pool', function (list) {
      var codes = [];
      universeList = [];
      poolNames = {};
      (list || []).forEach(function (item) {
        var code = typeof item === 'string' ? item : item.code;
        var name = typeof item === 'string' ? item : (item.name || item.code);
        if (!code) return;
        poolNames[code] = name;
        universeList.push({ code: code, name: name });
        codes.push(code);
      });
      portfolioSelected = portfolioSelected.filter(function (c) { return !!poolNames[c]; });
      if (!portfolioSelected.length) {
        portfolioSelected = codes.slice(0, 3);
      }
      refreshUniverseCounts();
      renderStockPicker('pool');
      renderStockPicker('single');
      renderStockPicker('portfolio');
      syncPortfolioCodes();
      if (codes.length) {
        openPoolStock(codes[0]);
        selectSingleStock(codes[0], { silent: true });
      }
      // 侧栏目标池计数
      $.getJSON('/api/stock/trade-pool').done(function (data) {
        var n = data && data.count != null ? data.count : ((data && data.items) || []).length;
        $('#sidePoolCount').text(String(n || 0));
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

    var period = singlePeriods[code] || 'DAY';
    singlePeriods[code] = period;
    $('#barPeriod').val(period);

    $('#singleStockResults li').removeClass('active');
    var $active = $('#singleStockResults li[data-code="' + code + '"]').addClass('active');
    try {
      if ($active.length && $active[0].scrollIntoView) {
        $active[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    } catch (e) {}

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
    { id: 'app', group: 'app', title: '系统概述', src: '/docs/app.html?v=20260720-nav-rename' },
    { id: 'rules', group: 'app', title: '交易规则', src: '/docs/rules.html?v=20260720-nav-rename' },
    { id: 'memo', group: 'app', title: '待办清单', src: '/docs/memo.html?v=20260720-nav-rename' },
    { id: 'ashare', group: 'stock', title: 'A股基础', src: '/docs/ashare.html?v=20260720-nav-rename' },
    { id: 'session', group: 'stock', title: '交易时间', src: '/docs/session.html?v=20260720-nav-rename' },
    { id: 'kline', group: 'stock', title: 'K线', src: '/docs/kline.html?v=20260720-nav-rename' },
    { id: 'ma', group: 'stock', title: '均线与金叉', src: '/docs/ma.html?v=20260720-nav-rename' },
    { id: 'volume', group: 'stock', title: '成交量与放量', src: '/docs/volume.html?v=20260720-nav-rename' },
    { id: 'rsi', group: 'stock', title: 'RSI', src: '/docs/rsi.html?v=20260720-nav-rename' },
    { id: 'atr', group: 'stock', title: 'ATR', src: '/docs/atr.html?v=20260720-nav-rename' },
    { id: 'adx', group: 'stock', title: 'ADX', src: '/docs/adx.html?v=20260720-nav-rename' },
    { id: 'boll', group: 'stock', title: '布林带', src: '/docs/boll.html?v=20260720-nav-rename' },
    { id: 'limit', group: 'stock', title: '涨跌停与停牌', src: '/docs/limit.html?v=20260720-nav-rename' },
    { id: 'tplus1', group: 'stock', title: 'T+1与整手', src: '/docs/tplus1.html?v=20260720-nav-rename' },
    { id: 'cost', group: 'stock', title: '交易成本', src: '/docs/cost.html?v=20260720-nav-rename' },
    { id: 'position', group: 'stock', title: '仓位与金字塔', src: '/docs/position.html?v=20260720-nav-rename' },
    { id: 'risk', group: 'stock', title: '账户风控', src: '/docs/risk.html?v=20260720-nav-rename' },
    { id: 'fill', group: 'stock', title: '撮合时机', src: '/docs/fill.html?v=20260720-nav-rename' },
    { id: 'metrics', group: 'stock', title: '权益回撤与胜率', src: '/docs/metrics.html?v=20260720-nav-rename' },
    { id: 'backtest', group: 'stock', title: '回测要点', src: '/docs/backtest.html?v=20260720-nav-rename' }
  ];
  var knowledgeHtmlCache = {};
  var HOME_SRC = '/docs/home.html?v=20260720-nav-rename';
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

  function loadHomePanel(done) {
    if (homePanelReady) {
      if (typeof done === 'function') done();
      return;
    }
    $.get(HOME_SRC)
      .done(function (html) {
        $('#homeMount').html(html);
        homePanelReady = true;
        if (pendingHomeLead != null) {
          $('#homeLead').text(pendingHomeLead);
          pendingHomeLead = null;
        }
        if (typeof done === 'function') done();
      })
      .fail(function () {
        $('#homeMount').html('<section class="panel home-panel"><p class="home-lead">欢迎页加载失败：' + HOME_SRC + '</p></section>');
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
    $('#viewHome, #viewNavIntro, #viewPool, #viewSingle, #viewPortfolio, #viewTradePool, #viewTpHistory, #viewDbTable, #viewSchedule, #viewDataHealth, #viewSysParams, #viewAcctFunds, #viewAcctPositions, #viewAcctOrders, #viewAcctCashflows, #viewAcctRiskLogs').prop('hidden', true);
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
    resizeCharts();
  }

  function showSchedulePanel(panel) {
    panel = panel || lastSchedulePanel || 'jobs';
    if (panel !== 'jobs' && panel !== 'health' && panel !== 'params') panel = 'jobs';
    lastSchedulePanel = panel;
    lastWorkspaceMode = 'schedule';
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
    resizeCharts();
  }

  function showAccountPanel(panel) {
    panel = panel || lastAccountPanel || 'funds';
    if (panel !== 'funds' && panel !== 'positions' && panel !== 'orders'
        && panel !== 'cashflows' && panel !== 'risklogs') {
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
    } else {
      $('#viewAcctFunds').prop('hidden', false);
      loadAccountOverview();
    }
    resizeCharts();
  }

  function riskRuleLabel(t) {
    var map = {
      DRAWDOWN_HALT: '峰值回撤熔断',
      DAILY_LOSS: '单日亏损禁开',
      CONSECUTIVE_LOSS: '连亏禁开'
    };
    return map[t] || t || '—';
  }

  function renderAccountCashflows(data) {
    data = data || {};
    var rows = data.items || [];
    $('#acctCfBadge').text(String(data.count != null ? data.count : rows.length));
    $('#sideCfCount').text(String(data.count != null ? data.count : rows.length));
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
    $('#acctRiskBadge').text(String(data.count != null ? data.count : rows.length));
    $('#sideRiskCount').text(String(data.count != null ? data.count : rows.length));
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
    $('#acctModeBadge').text(data.source || data.mode || 'LOCAL_SIM');
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
    $('#acctHalted').text(data.halted ? '是（禁开）' : '否');
    $('#acctAllowOpen').text(data.allowNewOpen ? '是' : '否');
    $('#acctPosScale').text(data.positionScale == null ? '—' : num(data.positionScale, 2) + '×');
    $('#acctLossStreak').text(data.consecutiveLosses == null ? '—' : String(data.consecutiveLosses));
  }

  function pendingLabel(it) {
    var parts = [];
    if (it.pendingBuy) parts.push('待买' + (it.pendingBuyVol ? it.pendingBuyVol : ''));
    if (it.pendingSell) parts.push('待卖');
    return parts.length ? parts.join('/') : '—';
  }

  function renderAccountPositions(items) {
    items = items || [];
    $('#acctPosBadge').text(String(items.length));
    $('#sidePosCount').text(String(items.length));
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
          + '<td class="mono">' + escHtml(String(it.volume == null ? '—' : it.volume)) + '</td>'
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
    $('#acctPosHint').text('共 ' + items.length + ' 只 · 点击行展开批次');
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
    $('#acctOrderBadge').text(String(items.length));
    $('#sideOrderCount').text(String(items.length));
    var $body = $('#acctOrderBody').empty();
    if (!items.length) {
      $body.html('<tr><td colspan="13" class="empty-state">暂无委托</td></tr>');
      $('#acctOrderHint').text('无委托');
      return;
    }
    var rows = items.slice();
    if (rows.length && rows[0].source !== 'DB') {
      rows = rows.slice().reverse();
    }
    rows.forEach(function (it) {
      var filled = it.filledVolume != null ? it.filledVolume : (it.status === 'FILLED' ? it.volume : '—');
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
        + '</tr>'
      );
    });
    var src = rows[0] && rows[0].source === 'DB' ? '库表' : '内存';
    $('#acctOrderHint').text('共 ' + rows.length + ' 笔 · 来源 ' + src);
  }

  function loadAccountOverview() {
    $.getJSON('/api/account')
      .done(function (data) {
        renderAccountFunds(data);
        renderAccountPositions(data.positions || []);
        renderAccountOrders(data.orders || []);
        if (data.positionCount != null) $('#sidePosCount').text(String(data.positionCount));
        if (data.orderCount != null) $('#sideOrderCount').text(String(data.orderCount));
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
    setHomeLead(lead);
    // 进入欢迎页默认展开；若显式要求保持收起则沿用
    var collapsed = options.keepCollapsed ? homeCollapsed : false;
    setHomeCollapsed(collapsed);
    loadHomePanel(function () {
      setHomeLead(lead);
      setHomeCollapsed(collapsed);
    });
  }

  /**
   * @param {string} mode pool|single|portfolio|tradepool|dbtables|schedule|account
   * @param {{expandNav?: boolean, panel?: string, table?: string}} [options]
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
      renderStockPicker('single');
      setTimeout(function () { $('#singleStockQ').trigger('focus'); }, 80);
    } else if (lastWorkspaceMode === 'portfolio') {
      $('#viewPortfolio').prop('hidden', false);
      if (expandNav) setSideNavOpen('portfolioBody');
      renderStockPicker('portfolio');
      syncPortfolioCodes();
      loadPortfolioHistory();
      setTimeout(function () { $('#pfStockQ').trigger('focus'); }, 80);
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
    } else {
      lastWorkspaceMode = 'pool';
      $('#viewPool').prop('hidden', false);
      if (expandNav) setSideNavOpen('poolBody');
      renderStockPicker('pool');
      setTimeout(function () { $('#poolStockQ').trigger('focus'); }, 80);
    }
    resizeCharts();
  }

  var dbTableState = { name: '', page: 1, size: 20, pages: 0, total: 0 };

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
        var $li = $('<li role="button" tabindex="0"/>')
          .attr('data-table', t.name)
          .html(
            '<span class="db-tbl-title">' + escHtml(t.title || t.name)
            + ' <span class="trade-pool-count">' + escHtml(countTxt) + '</span></span>'
            + '<span class="db-tbl-name">' + escHtml(t.name) + '</span>'
          );
        if (t.exists === false) {
          $li.css('opacity', '0.55').attr('title', '表可能尚未创建');
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
      resizeCharts();
      return;
    }

    dbTableState.name = tableName;
    dbTableState.page = 1;
    dbTableState.size = parseInt($('#dbTablePageSize').val(), 10) || 20;
    $('#dbtablesMenu li[data-table="' + tableName + '"]').addClass('active');
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
        $('#dbTableSummary').text('共 ' + dbTableState.total + ' 行 · 第 ' + dbTableState.page + ' / ' + Math.max(1, dbTableState.pages) + ' 页');
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
    // 一行简略：模块 · 功能说明（超出省略）
    $('#dbMetaSummary').text(module + ' · ' + purpose);
    $('#dbTableMeta').prop('hidden', false);
    setDbMetaExpanded(false);
  }

  function clearDbTableMeta() {
    $('#dbTableMeta').prop('hidden', true);
    setDbMetaExpanded(false);
    $('#dbMetaSummary').text('—');
    $('#dbMetaModule, #dbMetaPurpose, #dbMetaSource, #dbMetaUsage, #dbMetaOrder').text('—');
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
  var SCHEDULE_COLSPAN = 8;

  function loadScheduleJobs() {
    var $body = $('#scheduleJobBody');
    $body.html('<tr><td colspan="8" class="empty-state">加载中…</td></tr>');
    $.getJSON('/api/schedule').done(function (data) {
      var masterOn = !!data.enabled;
      $('#scheduleMasterHint').text(masterOn
        ? '总闸已开 · 已注册 ' + (data.registeredCount || 0) + ' 个触发器'
        : '总闸 quant.schedule.enabled=false（改 yml 后需重启）');
      var baseHint = data.hint || '';
      $('#scheduleHint').text(baseHint + (baseHint ? ' · ' : '') + '点击行空白处展开任务详细介绍');
      var jobs = data.jobs || [];
      scheduleJobsByCode = {};
      if (!jobs.length) {
        $body.html('<tr><td colspan="8" class="empty-state">暂无任务（需 quant.db-enabled=true）</td></tr>');
        return;
      }
      var rows = jobs.map(function (j) {
        scheduleJobsByCode[j.jobCode] = j;
        var triggerVal = (j.triggerType || '').toUpperCase() === 'FIXED_RATE'
          ? (j.intervalMs != null ? String(j.intervalMs) : '')
          : (j.cronExpr || '');
        var impl = j.implemented ? '' : ' <span class="schedule-badge schedule-badge--todo">未实现</span>';
        var eff = j.effective
          ? '<span class="schedule-ok">调度中</span>'
          : (j.enabled ? '<span class="schedule-warn">未生效</span>' : '<span class="schedule-off">关闭</span>');
        return '<tr class="sch-job-row" data-code="' + escHtml(j.jobCode) + '" style="cursor:pointer;" title="点击空白处查看详细介绍">'
          + '<td><label class="schedule-switch"><input type="checkbox" class="sch-enabled" '
          + (j.enabled ? 'checked' : '') + '/><span></span></label></td>'
          + '<td><div class="schedule-name">' + escHtml(j.jobName) + impl + '</div>'
          + '<div class="schedule-code">' + escHtml(j.jobCode) + '</div></td>'
          + '<td><select class="sch-type">'
          + '<option value="CRON"' + ((j.triggerType || '').toUpperCase() === 'CRON' ? ' selected' : '') + '>CRON</option>'
          + '<option value="FIXED_RATE"' + ((j.triggerType || '').toUpperCase() === 'FIXED_RATE' ? ' selected' : '') + '>FIXED_RATE</option>'
          + '</select></td>'
          + '<td><input class="sch-trigger" type="text" value="' + escHtml(triggerVal) + '" '
          + 'placeholder="cron 或毫秒"/></td>'
          + '<td>' + eff + '</td>'
          + '<td class="mono">' + escHtml(j.lastRunAt || '—') + '</td>'
          + '<td><input class="sch-remark" type="text" value="' + escHtml(j.remark || '') + '"/></td>'
          + '<td class="schedule-actions">'
          + '<button type="button" class="secondary sch-save">保存</button> '
          + '<button type="button" class="secondary sch-run">执行一次</button>'
          + '</td></tr>';
      });
      $body.html(rows.join(''));
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || xhr.statusText || '加载失败';
      $body.html('<tr><td colspan="8" class="empty-state">' + escHtml(msg) + '</td></tr>');
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

  function renderScheduleJobDetail(job, $panel) {
    job = job || {};
    var d = job.detail || {};
    $panel.empty();
    var $head = $('<div class="analysis-detail-head"/>');
    $head.append($('<span/>').html(
      '<b>任务说明</b> · ' + escHtml(job.jobName || '') + ' <code>' + escHtml(job.jobCode || '') + '</code>'
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

    function row(tag, text) {
      $panel.append(
        $('<p class="sch-detail-line"/>').html(
          '<span class="db-meta-tag">' + escHtml(tag) + '</span> '
          + '<span>' + escHtml(text == null || text === '' ? '—' : String(text)) + '</span>'
        )
      );
    }
    row('功能', d.purpose);
    row('范围', d.scope);
    row('触发', d.triggerHint);
    row('落库', d.writes);
    row('说明', d.notes);
    if (job.remark) {
      row('备注', job.remark);
    }
    var trig = (job.triggerType || '').toUpperCase() === 'FIXED_RATE'
      ? ('FIXED_RATE · ' + (job.intervalMs != null ? job.intervalMs + ' ms' : '—'))
      : ('CRON · ' + (job.cronExpr || '—'));
    row('当前配置', (job.enabled ? '启用' : '关闭') + ' · ' + trig
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

  function schedulePayloadFromRow($tr) {
    var type = ($tr.find('.sch-type').val() || 'CRON').toUpperCase();
    var trigger = $.trim($tr.find('.sch-trigger').val() || '');
    var body = {
      enabled: $tr.find('.sch-enabled').prop('checked'),
      triggerType: type,
      remark: $tr.find('.sch-remark').val() || ''
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
    var wasOpen = $btn.attr('aria-expanded') === 'true';

    // 再次点击已展开的一级菜单：全部收起，右侧回到全局初始化页
    if (wasOpen) {
      showHome();
      return;
    }

    // 展开一级菜单：展示该菜单专属介绍页（不展示全局初始化页）
    var introSrc = $btn.attr('data-intro');
    var introTitle = $btn.attr('data-intro-title') || $btn.clone().children().remove().end().text().trim();
    if (introSrc) {
      showNavIntro({ bodyId: bodyId, title: introTitle, src: introSrc + (introSrc.indexOf('?') >= 0 ? '&' : '?') + 'v=20260720-nav-rename' });
      return;
    }
    // 无介绍配置时回退到原工作台
    showMode($btn.attr('data-mode'));
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

  function loadDataHealth() {
    $('#healthBody').html('<tr><td colspan="7" class="empty-state">检查中…</td></tr>');
    $.getJSON('/api/ops/data-health')
      .done(function (data) {
        if (data.hint) $('#healthHint').text(data.hint);
        $('#healthUniverse').text(String(data.universeSize == null ? '—' : data.universeSize));
        $('#healthOk').text(String(data.okCount == null ? '—' : data.okCount));
        $('#healthWarn').attr('class', 'value ' + (data.warnCount > 0 ? 'pnl-neg' : ''))
          .text(String(data.warnCount == null ? '—' : data.warnCount));
        $('#healthBadge').text(String(data.warnCount == null ? 0 : data.warnCount));
        $('#healthMeta').text(data.asOf ? ('检查时间：' + fmtDateTimeDisplay(data.asOf)) : '');
        var items = data.items || [];
        var $tb = $('#healthBody').empty();
        if (!items.length) {
          $tb.html('<tr><td colspan="7" class="empty-state">无标的或未启用数据库</td></tr>');
          return;
        }
        // 告警优先
        items.sort(function (a, b) {
          return (a.ok === b.ok) ? 0 : (a.ok ? 1 : -1);
        });
        items.forEach(function (it) {
          $tb.append(
            '<tr>'
            + '<td><b>' + escHtml(it.code) + '</b></td>'
            + '<td>' + (it.ok ? '<span class="tag-buy">正常</span>' : '<span class="tag-wait">告警</span>') + '</td>'
            + '<td class="mono">' + escHtml(String(it.dailyCount == null ? '—' : it.dailyCount)) + '</td>'
            + '<td class="mono">' + escHtml(it.maxDaily || '—') + '</td>'
            + '<td class="mono">' + escHtml(String(it.minuteCount == null ? '—' : it.minuteCount)) + '</td>'
            + '<td class="mono">' + escHtml(it.maxMinute ? fmtDateTimeDisplay(it.maxMinute) : '—') + '</td>'
            + '<td>' + escHtml(it.issueText || '—') + '</td>'
            + '</tr>'
          );
        });
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '数据健康检查失败';
        $('#healthHint').text(msg);
        toast(msg, 'err');
      });
  }

  function loadSysParams() {
    $.getJSON('/api/ops/params')
      .done(function (data) {
        if (data.hint) $('#paramsHint').text(data.hint);
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
            if (it.note) {
              $lab.append($('<span class="params-kv-note"/>').attr('title', it.note).text(it.note));
            }
            $sec.append(
              $('<div class="result-kv params-kv"/>')
                .append($lab)
                .append($('<span class="value mono"/>').text(it.value == null ? '—' : String(it.value)))
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
      })
      .fail(function (xhr) {
        var msg = (xhr.responseJSON && xhr.responseJSON.message) || '加载运行参数失败';
        $('#paramsHint').text(msg);
        toast(msg, 'err');
      });
  }

  $('#btnTpHistRefresh').on('click', loadTpScanHistory);
  $('#btnHealthRefresh').on('click', loadDataHealth);
  $('#btnParamsRefresh').on('click', loadSysParams);

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

  $('#btnAcctRefreshCf').on('click', function () {
    loadAccountCashflows();
  });

  $('#btnAcctRefreshRisk').on('click', function () {
    loadAccountRiskLogs();
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
  });

  function renderTpFunnel(res) {
    res = res || {};
    $('#tpFunnel').prop('hidden', false);
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
      $('#tpReportLink').prop('hidden', true).hide();
    }
  }

  $('#btnTpRebuild').on('click', function () {
    var $btn = $(this);
    $btn.prop('disabled', true).text('扫描中…');
    // analyze = 覆盖目标池 + 落盘 Markdown（含 rebuild 漏斗字段）
    $.post('/api/stock/trade-pool/analyze').done(function (res) {
      renderTpFunnel(res);
      toast('目标池已更新：' + (res.selected != null ? res.selected : 0)
        + ' 只 · 全市场 ' + (res.universe || 0)
        + ' → 入选 ' + (res.selected || 0), 'ok');
      loadTradePoolManage();
    }).fail(function (xhr) {
      toast((xhr.responseJSON && xhr.responseJSON.message) || '扫描失败', 'err');
    }).always(function () {
      $btn.prop('disabled', false).text('扫描更新');
    });
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
    if (!window.confirm('将 ' + code + ' 移出目标池？\n（不停仓、不卖出持仓）')) {
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

  $('#scheduleJobBody').on('click', 'tr.sch-job-row', function (e) {
    if ($(e.target).closest('input, select, button, a, label, textarea').length) return;
    var $tr = $(this);
    var expanded = $tr.hasClass('active') || $tr.attr('data-expanded') === '1';
    if (expanded) {
      collapseScheduleJobDetail();
      return;
    }
    collapseScheduleJobDetail();
    $tr.addClass('active').attr('data-expanded', '1');
    showScheduleJobDetail($tr);
  });

  $('#scheduleJobBody').on('change', '.sch-enabled', function () {
    var $tr = $(this).closest('tr');
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

  $('#scheduleJobBody').on('click', '.sch-save', function () {
    var $tr = $(this).closest('tr');
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
    var code = $(this).closest('tr').attr('data-code');
    $.post('/api/schedule/jobs/' + encodeURIComponent(code) + '/run').done(function () {
      toast('已触发 ' + code, 'ok');
      loadScheduleJobs();
    }).fail(function (xhr) {
      toast((xhr.responseJSON && xhr.responseJSON.message) || '执行失败', 'err');
    });
  });

  $('#viewNavIntro').on('click', '[data-download-docs]', function () {
    var group = $(this).attr('data-download-docs');
    if (group === 'stock' || group === 'app') {
      downloadDocsPdf(group, $(this));
    }
  });

  $('#viewHome').on('click', '[data-open-nav]', function () {
    var bodyId = $(this).attr('data-open-nav');
    var $btn = $('.side-nav-toggle[data-body="' + bodyId + '"]');
    if (!$btn.length) return;
    if ($btn.attr('aria-expanded') === 'true') return;
    $btn.trigger('click');
  });

  $('#viewHome').on('click', '#btnCollapseHome', function () {
    setHomeCollapsed(true);
    toast('欢迎页已收起，可切换主题欣赏背景', 'info', { place: 'theme' });
  });

  $('#btnExpandHome').on('click', function () {
    setHomeCollapsed(false);
    toast('已展开欢迎页', 'ok');
  });

  $('#btnBrandHome').on('click', function () {
    showHome({ lead: '已回到初始化页。点击下方入口，或展开左侧一级菜单进入对应功能。' });
    toast('已收起菜单，回到初始化页', 'ok');
  });

  $('#stockKnowledgeMenu, #appRelatedMenu').on('click', 'li', function () {
    openKnowledge($(this).data('id'));
  });

  $('#knowledgeClose').on('click', function () {
    showMode(lastWorkspaceMode || 'pool');
  });

  $('#btnEnterPool').on('click', function () {
    showMode('pool');
  });

  $('#btnEnterSingle').on('click', function () {
    showMode('single');
  });

  $('#btnEnterPortfolio').on('click', function () {
    showMode('portfolio');
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

  $('#pfStockQ').on('input', function () {
    clearTimeout(pfSearchTimer);
    pfSearchTimer = setTimeout(function () { renderStockPicker('portfolio'); }, 120);
  });

  $('#poolStockResults').on('click', 'li[data-code]', function () {
    var code = $(this).attr('data-code');
    showMode('pool');
    openPoolStock(code);
    renderStockPicker('pool');
  });

  $('#singleStockResults').on('click', 'li[data-code]', function () {
    var code = $(this).attr('data-code');
    showMode('single');
    selectSingleStock(code);
    renderStockPicker('single');
  });

  $('#pfStockResults').on('click', 'li[data-code]', function () {
    togglePortfolioStock($(this).attr('data-code'));
  });

  $('#poolStockResults, #singleStockResults, #pfStockResults').on('keydown', 'li[data-code]', function (e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $(this).trigger('click');
    }
  });

  $('#pfChips').on('click', '.pf-chip', function () {
    togglePortfolioStock($(this).attr('data-code'));
  });

  $('#btnPfPickTop3').on('click', function () {
    selectPortfolioTopN(3);
  });

  $('#btnPfClearPick').on('click', function () {
    clearPortfolioSelection();
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

  initKnowledge();
  initTheme();
  loadSummary();
  loadPool();
  loadDbTablesMenu();
  showHome();
  bindCapitalHint($('#initCapital'), $('#initCapitalHint'));
  bindCapitalHint($('#pfInitCapital'), $('#pfInitCapitalHint'));
})();
