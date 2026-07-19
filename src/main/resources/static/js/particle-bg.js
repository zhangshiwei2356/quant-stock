/**
 * 多主题动态背景（无第三方依赖）
 * - night / matrix：夜盘深色 + 慢速代码雨
 * - day / interact：日间浅色 + 鼠标跟随拖尾 + 点击爆炸
 * - forest / wave：背景由 starfield-bg.js（Three.js 银河）负责，界面名「银河」
 * - cosmos：粒子连线 + 流星 + 极光带，界面名「极光」
 * pointer-events:none，交互监听挂在 window，不挡点击。
 */
(function (global) {
  'use strict';

  var canvas = null;
  var ctx = null;
  var width = 0;
  var height = 0;
  var particles = [];
  var meteors = [];
  var bursts = [];
  var waves = [];
  var matrixCols = [];
  var rafId = 0;
  var running = false;
  var theme = 'day';
  var t0 = 0;
  var mouse = { x: -9999, y: -9999, active: false };
  var mouseBound = false;
  var clickBound = false;

  var THEMES = {
    /* 夜盘 · 黑客帝国（分层磷光雨 / 白字头 / 长残影） */
    night: {
      mode: 'matrix',
      classic: true,
      bg: '0, 2, 0',
      color: '#00ff41',
      head: '#f4fff4',
      speed: 0.92,
      trail: 0.042,
      fontSize: 15,
      colGap: 1.0,
      density: 0.9,
      tailMin: 16,
      tailMax: 44,
      layers: true
    },
    /* 兼容旧主题 key：与 night 相同 */
    matrix: {
      mode: 'matrix',
      classic: true,
      bg: '0, 2, 0',
      color: '#00ff41',
      head: '#f4fff4',
      speed: 0.92,
      trail: 0.042,
      fontSize: 15,
      colGap: 1.0,
      density: 0.9,
      tailMin: 16,
      tailMax: 44,
      layers: true
    },
    /* 日间浅色 · 科技感交互粒子（电青网络 / 利落拖尾 / HUD） */
    day: {
      mode: 'interact', count: 64, speed: 1.45,
      bg: '232, 239, 247', trail: 0.2,
      particle: [47, 130, 246],
      glow: [56, 189, 248, 0.06],
      follow: 0.16, explode: true,
      comet: true, trailLen: 26, rainbow: false,
      soft: true, tech: true,
      trailAlpha: 0.7,
      glowScale: 2.8,
      headScale: 0.9,
      connect: 118, line: '56, 189, 248', lineAlpha: 0.2,
      grid: true, gridStep: 56,
      palette: [
        [47, 130, 246],
        [14, 165, 233],
        [6, 182, 212],
        [56, 189, 248],
        [99, 102, 241],
        [34, 211, 238]
      ],
      aurora: true, auroraSpeed: 0.0011,
      auroraColors: [
        [56, 189, 248, 0.09],
        [47, 130, 246, 0.07],
        [34, 211, 238, 0.05],
        [99, 102, 241, 0.04]
      ]
    },
    /* 兼容旧主题 key：与 day 相同 */
    interact: {
      mode: 'interact', count: 64, speed: 1.45,
      bg: '232, 239, 247', trail: 0.2,
      particle: [47, 130, 246],
      glow: [56, 189, 248, 0.06],
      follow: 0.16, explode: true,
      comet: true, trailLen: 26, rainbow: false,
      soft: true, tech: true,
      trailAlpha: 0.7,
      glowScale: 2.8,
      headScale: 0.9,
      connect: 118, line: '56, 189, 248', lineAlpha: 0.2,
      grid: true, gridStep: 56,
      palette: [
        [47, 130, 246],
        [14, 165, 233],
        [6, 182, 212],
        [56, 189, 248],
        [99, 102, 241],
        [34, 211, 238]
      ],
      aurora: true, auroraSpeed: 0.0011,
      auroraColors: [
        [56, 189, 248, 0.09],
        [47, 130, 246, 0.07],
        [34, 211, 238, 0.05],
        [99, 102, 241, 0.04]
      ]
    },
    /* 银河：Canvas 侧停用，由 QuantStarfieldBg 绘制（保留 key 防误调） */
    forest: {
      mode: 'off',
      bg: '10, 20, 17'
    },
    wave: {
      mode: 'off',
      bg: '10, 20, 17'
    },
    cosmos: {
      mode: 'net', count: 130, connect: 170, speed: 1.25,
      bg: '5, 8, 16', trail: 0.14, particle: [210, 230, 255],
      line: '140, 190, 255', lineAlpha: 0.75, glow: [40, 70, 160, 0.24],
      stars: true, meteors: true, aurora: true, auroraSpeed: 0.0011,
      auroraColors: [[80, 140, 255, 0.22], [160, 100, 255, 0.16], [40, 220, 255, 0.12]]
    }
  };

  /* 半角片假名主导，贴近电影字幕雨字形 */
  var MATRIX_CHARS = 'ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜﾂｵﾘｱﾎﾃﾏｹﾒｴｶｷﾑﾕﾗｾﾈｽﾀﾇﾍｦｧｨｩｪｫｬｭｮｯｰ012345789Z:・.*=+<>¦';
  var MATRIX_CHAR_LEN = MATRIX_CHARS.length;

  function matrixRandChar() {
    return MATRIX_CHARS.charAt((Math.random() * MATRIX_CHAR_LEN) | 0);
  }

  function createMatrixColumn(i, gap, fs, c) {
    var tMin = c.tailMin || 16;
    var tMax = c.tailMax || 44;
    var len = tMin + ((Math.random() * (tMax - tMin + 1)) | 0);
    // 三层景深：远(暗慢) / 中 / 近(亮快)
    var roll = Math.random();
    var layer = roll < 0.22 ? 2 : (roll < 0.55 ? 1 : 0);
    var glyphs = new Array(len);
    var g;
    for (g = 0; g < len; g++) glyphs[g] = matrixRandChar();
    var speedBase = (c.speed || 1) * (0.45 + Math.random() * 1.35);
    if (layer === 0) speedBase *= 0.55;
    else if (layer === 2) speedBase *= 1.35;
    return {
      x: i * gap + fs * 0.5,
      y: Math.random() * -height * 1.2,
      speed: speedBase,
      font: fs,
      len: len,
      glyphs: glyphs,
      layer: layer,
      lastRow: -9999,
      wait: (Math.random() * 90) | 0,
      alphaMul: layer === 2 ? 1 : (layer === 1 ? 0.72 : 0.38),
      bloom: layer === 2 && Math.random() < 0.55
    };
  }

  function prefersReducedMotion() {
    try {
      return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (e) {
      return false;
    }
  }

  function cfg() { return THEMES[theme] || THEMES.day; }

  function rgba(arr, a) {
    return 'rgba(' + arr[0] + ',' + arr[1] + ',' + arr[2] + ',' + (a != null ? a : arr[3]) + ')';
  }

  function lerp(a, b, t) { return a + (b - a) * t; }

  function lerpColor(c1, c2, t) {
    return [
      Math.round(lerp(c1[0], c2[0], t)),
      Math.round(lerp(c1[1], c2[1], t)),
      Math.round(lerp(c1[2], c2[2], t))
    ];
  }

  /** HSL → RGB，用于彗星彩虹尾 */
  function hslToRgb(h, s, l) {
    h = ((h % 360) + 360) % 360;
    s = Math.max(0, Math.min(1, s));
    l = Math.max(0, Math.min(1, l));
    var c = (1 - Math.abs(2 * l - 1)) * s;
    var x = c * (1 - Math.abs((h / 60) % 2 - 1));
    var m = l - c / 2;
    var r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; }
    else if (h < 120) { r = x; g = c; }
    else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; }
    else if (h < 300) { r = x; b = c; }
    else { r = c; b = x; }
    return [
      Math.round((r + m) * 255),
      Math.round((g + m) * 255),
      Math.round((b + m) * 255)
    ];
  }

  function pickPalette(c) {
    if (c.palette && c.palette.length) {
      return c.palette[Math.floor(Math.random() * c.palette.length)].slice();
    }
    return (c.particle || [47, 111, 237]).slice();
  }

  /* ---------- shared particle (net / interact) ---------- */
  function Particle(c, mode) {
    this.mode = mode || 'net';
    this.trail = [];
    this.color = null;
    this.color2 = null;
    this.hue = 210;
    this.reset(c, true);
  }

  Particle.prototype.reset = function (c, anywhere) {
    this.x = Math.random() * width;
    this.y = anywhere ? Math.random() * height : height + Math.random() * 40;
    var s = (c.speed || 1) * (0.55 + Math.random() * 0.9);
    this.vx = (Math.random() - 0.5) * s * 2;
    this.vy = (Math.random() - 0.5) * s * 2;
    this.radius = Math.random() * 2.4 + 0.8;
    this.phase = Math.random() * Math.PI * 2;
    this.pulse = 0.8 + Math.random() * 1.4;
    this.trail.length = 0;
    this.color = pickPalette(c);
    this.color2 = pickPalette(c);
    this.hue = Math.random() * 360;
    this.trailLen = c.trailLen || 12;
    if (c.firefly) {
      this.vy = -(0.55 + Math.random() * c.speed * 1.1);
      this.vx = (Math.random() - 0.5) * c.speed * 1.4;
      this.radius = Math.random() * 2.8 + 1;
    }
    if (c.bokeh) {
      this.radius = Math.random() * 6 + 3;
      this.vx *= 0.85;
      this.vy *= 0.85;
    }
    if (c.stars) this.radius = Math.random() * 2.4 + 0.5;
    if (this.mode === 'interact') {
      if (c.tech) {
        this.radius = Math.random() * 1.15 + 0.7;
        this.vx *= 1.28;
        this.vy *= 1.28;
        this.trailLen = c.trailLen || 26;
        this.hue = 195 + Math.random() * 40; // 电青色相带
      } else if (c.soft) {
        this.radius = Math.random() * 1.1 + 0.55;
        this.vx *= 1.2;
        this.vy *= 1.2;
        this.trailLen = c.trailLen || 22;
      } else {
        this.radius = Math.random() * 2.4 + 1.4;
        this.vx *= 1.45;
        this.vy *= 1.45;
        this.trailLen = c.trailLen || 36;
      }
    }
  };

  Particle.prototype.update = function (c, time) {
    if (mouse.active) {
      var mdx = mouse.x - this.x;
      var mdy = mouse.y - this.y;
      var md = Math.sqrt(mdx * mdx + mdy * mdy) || 1;
      var range = this.mode === 'interact' ? 300 : 220;
      var strength = this.mode === 'interact' ? (c.follow || 0.14) : 0.085;
      if (md < range) {
        var pull = (1 - md / range) * strength * (c.speed || 1);
        this.vx += (mdx / md) * pull * (this.mode === 'interact' ? 2.6 : 1);
        this.vy += (mdy / md) * pull * (this.mode === 'interact' ? 2.6 : 1);
      }
      // 靠近鼠标时轻微侧向甩尾，彗星感更强
      if (this.mode === 'interact' && md < 160) {
        this.vx += (-mdy / md) * 0.08;
        this.vy += (mdx / md) * 0.08;
      }
    }

    if (c.firefly) {
      this.x += this.vx + Math.sin(time * 0.004 + this.phase) * 0.85;
      this.y += this.vy;
      if (this.y < -20) this.reset(c, false);
      if (this.x < -20 || this.x > width + 20) this.vx *= -1;
    } else {
      this.x += this.vx;
      this.y += this.vy;
      this.vx *= this.mode === 'interact' ? 0.985 : 0.995;
      this.vy *= this.mode === 'interact' ? 0.985 : 0.995;
      // 交互模式：保持一点游荡速度，避免拖尾塌缩成点
      if (this.mode === 'interact') {
        var spd = Math.sqrt(this.vx * this.vx + this.vy * this.vy);
        if (spd < 0.55) {
          var ang = Math.atan2(this.vy, this.vx) + (Math.random() - 0.5) * 0.8;
          this.vx += Math.cos(ang) * 0.12;
          this.vy += Math.sin(ang) * 0.12;
        }
        if (spd > 7.5) {
          this.vx *= 0.92;
          this.vy *= 0.92;
        }
      }
      if (this.x < 0 || this.x > width) this.vx *= -1;
      if (this.y < 0 || this.y > height) this.vy *= -1;
      this.x = Math.max(0, Math.min(width, this.x));
      this.y = Math.max(0, Math.min(height, this.y));
    }

    if (this.mode === 'interact') {
      this.trail.push({ x: this.x, y: this.y });
      var maxLen = this.trailLen || (c.trailLen || 36);
      var spd2 = Math.sqrt(this.vx * this.vx + this.vy * this.vy);
      if (c.soft) {
        if (spd2 > 2.8) maxLen = Math.min(30, maxLen + 6);
      } else if (spd2 > 3.2) {
        maxLen = Math.min(52, maxLen + 12);
      }
      while (this.trail.length > maxLen) this.trail.shift();
      if (c.rainbow) this.hue += (c.soft ? 0.22 : 0.45) + spd2 * (c.soft ? 0.04 : 0.08);
    }
  };

  Particle.prototype.drawCometTrail = function (c, r) {
    var n = this.trail.length;
    if (n < 2) return;
    var head = this.color || c.particle;
    var tail = this.color2 || head;
    var soft = !!c.soft;
    var tech = !!c.tech;
    var aMul = c.trailAlpha != null ? c.trailAlpha : 1;
    ctx.lineCap = tech ? 'butt' : 'round';
    ctx.lineJoin = 'round';

    for (var t = 1; t < n; t++) {
      var prev = this.trail[t - 1];
      var cur = this.trail[t];
      var prog = t / (n - 1);
      var col;
      if (tech) {
        // 电青渐变拖尾：尾端更青、头端更亮蓝
        col = lerpColor(tail, head, prog * prog);
      } else if (c.rainbow) {
        col = soft
          ? hslToRgb(this.hue - (1 - prog) * 70, 0.42, 0.58 + prog * 0.08)
          : hslToRgb(this.hue - (1 - prog) * 90, 0.78, 0.52 + prog * 0.12);
      } else {
        col = lerpColor(tail, head, prog);
      }
      ctx.beginPath();
      ctx.moveTo(prev.x, prev.y);
      ctx.lineTo(cur.x, cur.y);
      if (tech) {
        ctx.strokeStyle = rgba(col, (0.04 + prog * 0.28) * aMul);
        ctx.lineWidth = r * (0.15 + prog * 1.35);
      } else {
        ctx.strokeStyle = rgba(col, (soft ? 0.03 + prog * 0.18 : 0.06 + prog * 0.42) * aMul);
        ctx.lineWidth = r * (soft ? (0.12 + prog * 1.05) : (0.2 + prog * 2.1));
      }
      ctx.stroke();
    }

    // 科技感：头段亮芯丝（更细更亮）
    var midStart = tech ? Math.max(1, n - 12) : (soft ? Math.max(1, n - 10) : Math.max(1, n - 18));
    ctx.lineCap = 'round';
    for (t = midStart; t < n; t++) {
      prev = this.trail[t - 1];
      cur = this.trail[t];
      prog = t / (n - 1);
      if (tech) {
        col = hslToRgb(this.hue || 205, 0.75, 0.58 + prog * 0.12);
      } else if (c.rainbow) {
        col = soft
          ? hslToRgb(this.hue - (1 - prog) * 40, 0.48, 0.62)
          : hslToRgb(this.hue - (1 - prog) * 50, 0.85, 0.62);
      } else {
        col = lerpColor(tail, head, prog);
      }
      ctx.beginPath();
      ctx.moveTo(prev.x, prev.y);
      ctx.lineTo(cur.x, cur.y);
      if (tech) {
        ctx.strokeStyle = rgba(col, (0.12 + prog * 0.38) * aMul);
        ctx.lineWidth = r * (0.12 + prog * 0.65);
      } else {
        ctx.strokeStyle = rgba(col, (soft ? 0.08 + prog * 0.22 : 0.2 + prog * 0.55) * aMul);
        ctx.lineWidth = r * (soft ? (0.1 + prog * 0.55) : (0.15 + prog * 1.05));
      }
      ctx.stroke();
    }
  };

  Particle.prototype.drawTechCore = function (c, r, p, flicker) {
    var s = r * 1.35;
    // 菱形节点
    ctx.beginPath();
    ctx.moveTo(this.x, this.y - s);
    ctx.lineTo(this.x + s * 0.75, this.y);
    ctx.lineTo(this.x, this.y + s);
    ctx.lineTo(this.x - s * 0.75, this.y);
    ctx.closePath();
    ctx.fillStyle = rgba([255, 255, 255], 0.55 + 0.25 * flicker);
    ctx.fill();
    ctx.strokeStyle = rgba(p, 0.65);
    ctx.lineWidth = 1;
    ctx.stroke();
    // 十字准星
    var tick = s * 1.6;
    ctx.beginPath();
    ctx.moveTo(this.x - tick, this.y);
    ctx.lineTo(this.x + tick, this.y);
    ctx.moveTo(this.x, this.y - tick);
    ctx.lineTo(this.x, this.y + tick);
    ctx.strokeStyle = rgba(p, 0.28 + 0.15 * flicker);
    ctx.lineWidth = 0.8;
    ctx.stroke();
  };

  Particle.prototype.draw = function (c, time) {
    var flicker = 0.55 + 0.45 * Math.sin(time * 0.008 * this.pulse + this.phase);
    if (c.firefly) {
      flicker = 0.25 + 0.75 * (0.5 + 0.5 * Math.sin(time * 0.012 * this.pulse + this.phase));
    }
    if (c.soft) flicker = 0.7 + 0.3 * flicker; // 减弱闪烁幅度
    var headScale = c.headScale != null ? c.headScale : 1;
    var r = this.radius * headScale * (c.pulseGlow ? (0.85 + 0.35 * flicker) : 1);
    var p = this.color || c.particle;
    var soft = !!c.soft;

    if (this.mode === 'interact' && this.trail.length > 1) {
      if (c.comet) {
        this.drawCometTrail(c, r);
      } else {
        ctx.beginPath();
        ctx.moveTo(this.trail[0].x, this.trail[0].y);
        for (var ti = 1; ti < this.trail.length; ti++) {
          ctx.lineTo(this.trail[ti].x, this.trail[ti].y);
        }
        ctx.strokeStyle = rgba(p, soft ? 0.18 : 0.35);
        ctx.lineWidth = r * (soft ? 0.55 : 0.9);
        ctx.lineCap = 'round';
        ctx.stroke();
      }
    }

    if (c.bokeh) {
      var g = ctx.createRadialGradient(this.x, this.y, 0, this.x, this.y, r * 2.6);
      g.addColorStop(0, rgba([47, 111, 237], 0.38 * flicker));
      g.addColorStop(0.45, rgba([13, 180, 160], 0.18 * flicker));
      g.addColorStop(1, rgba([47, 111, 237], 0));
      ctx.beginPath();
      ctx.fillStyle = g;
      ctx.arc(this.x, this.y, r * 2.6, 0, Math.PI * 2);
      ctx.fill();
      return;
    }

    var glowScale = c.glowScale != null
      ? c.glowScale
      : (c.firefly ? 5.5 : this.mode === 'interact' ? (c.comet ? 5.5 : 4.2) : c.stars ? 3.8 : 3.2);
    var glowR = r * glowScale;
    var gg = ctx.createRadialGradient(this.x, this.y, 0, this.x, this.y, glowR);
    if (c.firefly) {
      gg.addColorStop(0, rgba([200, 255, 210], 0.85 * flicker));
      gg.addColorStop(0.35, rgba([80, 230, 160], 0.35 * flicker));
      gg.addColorStop(1, rgba([61, 186, 140], 0));
    } else if (this.mode === 'interact' && c.comet) {
      var tip = c.rainbow
        ? hslToRgb(this.hue, soft ? 0.45 : 0.9, soft ? 0.62 : 0.72)
        : (c.tech ? hslToRgb(this.hue || 205, 0.7, 0.62) : p);
      if (c.tech) {
        gg.addColorStop(0, rgba([220, 245, 255], 0.55 * flicker));
        gg.addColorStop(0.25, rgba(tip, 0.4 * flicker));
        gg.addColorStop(0.6, rgba(p, 0.12 * flicker));
        gg.addColorStop(1, rgba(p, 0));
      } else if (soft) {
        gg.addColorStop(0, rgba(tip, 0.42 * flicker));
        gg.addColorStop(0.35, rgba(p, 0.16 * flicker));
        gg.addColorStop(1, rgba(p, 0));
      } else {
        gg.addColorStop(0, rgba([255, 255, 255], 0.95));
        gg.addColorStop(0.22, rgba(tip, 0.85 * flicker));
        gg.addColorStop(0.55, rgba(p, 0.28 * flicker));
        gg.addColorStop(1, rgba(p, 0));
      }
    } else {
      gg.addColorStop(0, rgba(p, soft ? 0.55 : 0.95));
      gg.addColorStop(0.3, rgba(p, (soft ? 0.18 : 0.35) * flicker));
      gg.addColorStop(1, rgba(p, 0));
    }
    ctx.beginPath();
    ctx.fillStyle = gg;
    ctx.arc(this.x, this.y, glowR, 0, Math.PI * 2);
    ctx.fill();

    if (c.tech && this.mode === 'interact') {
      this.drawTechCore(c, r, p, flicker);
      return;
    }

    var coreR = r * (c.comet && this.mode === 'interact' ? (soft ? 0.75 : 1.15) : 1);
    ctx.beginPath();
    ctx.arc(this.x, this.y, coreR, 0, Math.PI * 2);
    if (c.firefly) {
      ctx.fillStyle = rgba([210, 255, 230], 0.9 * flicker);
    } else if (c.comet && this.mode === 'interact') {
      ctx.fillStyle = soft ? rgba(p, 0.55 + 0.2 * flicker) : rgba([255, 255, 255], 0.95);
    } else {
      ctx.fillStyle = rgba(p, soft ? 0.55 : 0.92);
    }
    ctx.fill();
  };

  /* ---------- burst (click explode) ---------- */
  function Burst(x, y, color, palette, soft) {
    this.shards = [];
    this.soft = !!soft;
    var n = soft ? (16 + Math.floor(Math.random() * 10)) : (36 + Math.floor(Math.random() * 20));
    for (var i = 0; i < n; i++) {
      var ang = (Math.PI * 2 * i) / n + Math.random() * 0.45;
      var sp = soft ? (1.6 + Math.random() * 3.8) : (2.8 + Math.random() * 7.5);
      var col = color;
      if (palette && palette.length) {
        col = palette[Math.floor(Math.random() * palette.length)].slice();
      }
      this.shards.push({
        x: x, y: y,
        vx: Math.cos(ang) * sp,
        vy: Math.sin(ang) * sp,
        life: 1,
        r: soft ? (0.7 + Math.random() * 1.2) : (1.6 + Math.random() * 2.8),
        color: col,
        trail: [{ x: x, y: y }]
      });
    }
  }

  Burst.prototype.update = function () {
    var alive = false;
    for (var i = 0; i < this.shards.length; i++) {
      var s = this.shards[i];
      if (s.life <= 0) continue;
      alive = true;
      s.x += s.vx;
      s.y += s.vy;
      s.vx *= 0.96;
      s.vy *= 0.96;
      s.vy += 0.045;
      s.life -= 0.018;
      s.trail.push({ x: s.x, y: s.y });
      if (s.trail.length > 8) s.trail.shift();
    }
    return alive;
  };

  Burst.prototype.draw = function () {
    for (var i = 0; i < this.shards.length; i++) {
      var s = this.shards[i];
      if (s.life <= 0) continue;
      if (s.trail.length > 1) {
        ctx.beginPath();
        ctx.moveTo(s.trail[0].x, s.trail[0].y);
        for (var t = 1; t < s.trail.length; t++) {
          ctx.lineTo(s.trail[t].x, s.trail[t].y);
        }
        ctx.strokeStyle = rgba(s.color, s.life * (this.soft ? 0.28 : 0.55));
        ctx.lineWidth = s.r * s.life * (this.soft ? 0.5 : 0.85);
        ctx.lineCap = 'round';
        ctx.stroke();
      }
      ctx.beginPath();
      ctx.fillStyle = rgba(s.color, s.life * (this.soft ? 0.55 : 1));
      ctx.arc(s.x, s.y, s.r * s.life, 0, Math.PI * 2);
      ctx.fill();
    }
  };

  /* ---------- meteor ---------- */
  function Meteor() {
    this.reset();
    this.life = 0;
    this._wait = 30 + Math.random() * 90;
  }

  Meteor.prototype.reset = function () {
    this.x = Math.random() * width * 0.8;
    this.y = -20 - Math.random() * 80;
    this.len = 60 + Math.random() * 100;
    this.speed = 8 + Math.random() * 10;
    this.angle = Math.PI * 0.22 + Math.random() * 0.12;
    this.life = 1;
    this.fade = 0.012 + Math.random() * 0.01;
  };

  Meteor.prototype.update = function () {
    if (this._wait > 0) { this._wait--; return; }
    this.x += Math.cos(this.angle) * this.speed;
    this.y += Math.sin(this.angle) * this.speed;
    this.life -= this.fade;
    if (this.life <= 0 || this.y > height + 40 || this.x > width + 40) {
      this.reset();
      this.life = 0;
      this._wait = 40 + Math.random() * 120;
    }
  };

  Meteor.prototype.draw = function () {
    if (this.life <= 0 || this._wait > 0) return;
    var tx = this.x - Math.cos(this.angle) * this.len;
    var ty = this.y - Math.sin(this.angle) * this.len;
    var g = ctx.createLinearGradient(tx, ty, this.x, this.y);
    g.addColorStop(0, 'rgba(180, 220, 255, 0)');
    g.addColorStop(0.6, 'rgba(160, 200, 255, ' + (0.45 * this.life) + ')');
    g.addColorStop(1, 'rgba(255, 255, 255, ' + (0.95 * this.life) + ')');
    ctx.beginPath();
    ctx.strokeStyle = g;
    ctx.lineWidth = 2;
    ctx.moveTo(tx, ty);
    ctx.lineTo(this.x, this.y);
    ctx.stroke();
  };

  /* ---------- init helpers ---------- */
  function ensureCanvas() {
    canvas = document.getElementById('particle-canvas');
    if (!canvas) {
      canvas = document.createElement('canvas');
      canvas.id = 'particle-canvas';
      canvas.setAttribute('aria-hidden', 'true');
      document.body.insertBefore(canvas, document.body.firstChild);
    }
    ctx = canvas.getContext('2d');
  }

  function resize() {
    if (!canvas) return;
    width = window.innerWidth || document.documentElement.clientWidth || 800;
    height = window.innerHeight || document.documentElement.clientHeight || 600;
    canvas.width = width;
    canvas.height = height;
  }

  function initScene() {
    var c = cfg();
    particles.length = 0;
    meteors.length = 0;
    bursts.length = 0;
    waves.length = 0;
    matrixCols.length = 0;

    if (c.mode === 'matrix') {
      var fs = c.fontSize || 15;
      if (width < 768) fs = Math.max(12, (fs * 0.85) | 0);
      var gap = (c.colGap != null ? c.colGap : 1) * fs;
      var cols = ((width / gap) | 0) + 1;
      var density = c.density != null ? c.density : 0.9;
      for (var i = 0; i < cols; i++) {
        if (Math.random() > density) continue;
        matrixCols.push(createMatrixColumn(i, gap, fs, c));
      }
      return;
    }

    if (c.mode === 'wave') {
      var colors = c.waveColors || [];
      for (var w = 0; w < colors.length; w++) {
        waves.push({
          color: colors[w],
          amp: 14 + w * 8,
          len: 0.008 + w * 0.002,
          speed: 0.018 + w * 0.006,
          phase: Math.random() * Math.PI * 2,
          base: 0.55 + w * 0.08
        });
      }
      return;
    }

    var count = c.count || 80;
    if (width < 768) count = Math.max(40, Math.floor(count * 0.5));
    var mode = c.mode === 'interact' ? 'interact' : 'net';
    for (var p = 0; p < count; p++) {
      particles.push(new Particle(c, mode));
    }
    if (c.meteors) {
      var mc = width < 768 ? 2 : 4;
      for (var m = 0; m < mc; m++) meteors.push(new Meteor());
    }
  }

  function connectParticles(c) {
    var maxD = c.connect;
    if (!maxD) return;
    var step = particles.length > 100 ? 2 : 1;
    var lineW = c.tech ? 0.7 : (c.bokeh ? 1 : 0.85);
    var i, j, dx, dy, dist, alpha;
    for (i = 0; i < particles.length; i += step) {
      for (j = i + 1; j < particles.length; j += step) {
        dx = particles[i].x - particles[j].x;
        dy = particles[i].y - particles[j].y;
        dist = dx * dx + dy * dy;
        if (dist < maxD * maxD) {
          dist = Math.sqrt(dist);
          alpha = (1 - dist / maxD) * c.lineAlpha;
          ctx.beginPath();
          ctx.moveTo(particles[i].x, particles[i].y);
          ctx.lineTo(particles[j].x, particles[j].y);
          ctx.strokeStyle = 'rgba(' + c.line + ', ' + alpha + ')';
          ctx.lineWidth = lineW;
          ctx.stroke();
        }
      }
    }
  }

  function drawTechGrid(c, time) {
    if (!c.tech || !c.grid) return;
    var step = c.gridStep || 56;
    var drift = (time * 0.012) % step;
    ctx.save();
    ctx.strokeStyle = 'rgba(56, 189, 248, 0.045)';
    ctx.lineWidth = 1;
    var x, y;
    for (x = -step + drift; x < width + step; x += step) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }
    for (y = -step + drift * 0.5; y < height + step; y += step) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }
    // 扫描线
    var scanY = ((time * 0.045) % (height + 80)) - 40;
    var sg = ctx.createLinearGradient(0, scanY - 30, 0, scanY + 30);
    sg.addColorStop(0, 'rgba(56, 189, 248, 0)');
    sg.addColorStop(0.5, 'rgba(56, 189, 248, 0.04)');
    sg.addColorStop(1, 'rgba(56, 189, 248, 0)');
    ctx.fillStyle = sg;
    ctx.fillRect(0, scanY - 30, width, 60);
    ctx.restore();
  }

  function drawAurora(c, time) {
    if (!c.aurora || !c.auroraColors) return;
    var sp = c.auroraSpeed || 0.001;
    for (var i = 0; i < c.auroraColors.length; i++) {
      var col = c.auroraColors[i];
      var cx = width * (0.2 + 0.3 * i) + Math.sin(time * sp * 2.2 + i * 1.7) * width * 0.22;
      var cy = height * (0.18 + 0.12 * i) + Math.cos(time * sp * 1.8 + i * 2.1) * height * 0.16;
      var radius = Math.max(width, height) * (0.32 + 0.1 * Math.sin(time * sp + i));
      var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
      var pulse = 0.75 + 0.25 * Math.sin(time * sp * 3 + i);
      g.addColorStop(0, rgba(col, col[3] * pulse));
      g.addColorStop(0.55, rgba(col, col[3] * 0.35 * pulse));
      g.addColorStop(1, rgba(col, 0));
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, width, height);
    }
  }

  function drawTrailBase(c) {
    ctx.fillStyle = 'rgba(' + c.bg + ',' + (c.trail != null ? c.trail : 0.2) + ')';
    ctx.fillRect(0, 0, width, height);
    if (!c.glow) return;
    var g = ctx.createRadialGradient(width * 0.5, height * 0.35, 0, width * 0.5, height * 0.5, Math.max(width, height) * 0.8);
    g.addColorStop(0, rgba(c.glow, c.glow[3]));
    g.addColorStop(1, rgba(c.glow, 0));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, width, height);
  }

  function drawMatrix(c) {
    // 低透明度磷光黑幕：残影拖尾（经典数字雨）
    var fade = c.trail != null ? c.trail : 0.042;
    ctx.fillStyle = 'rgba(' + (c.bg || '0, 2, 0') + ', ' + fade + ')';
    ctx.fillRect(0, 0, width, height);

    var headColor = c.head || '#f4fff4';
    var green = c.color || '#00ff41';
    var fs0 = (matrixCols[0] && matrixCols[0].font) || c.fontSize || 15;
    ctx.font = fs0 + 'px "Consolas", "Cascadia Mono", "Courier New", monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';

    var mouseBoost = mouse.active;
    var i, col, fs, len, r, y, ch, t, aMul, headRow, gi;

    for (i = 0; i < matrixCols.length; i++) {
      col = matrixCols[i];
      if (col.wait > 0) {
        col.wait--;
        continue;
      }

      fs = col.font;
      len = col.len;
      aMul = col.alphaMul || 1;

      // 鼠标附近列略提亮（不挡交互，仅视觉）
      if (mouseBoost) {
        var mdx = mouse.x - col.x;
        if (mdx * mdx < 140 * 140) aMul = Math.min(1.15, aMul * 1.25);
      }

      // 跨行时推进字形缓冲：字头换新字，尾部挤出
      headRow = (col.y / fs) | 0;
      if (headRow !== col.lastRow) {
        col.lastRow = headRow;
        col.glyphs.unshift(matrixRandChar());
        while (col.glyphs.length > len) col.glyphs.pop();
        // 中段偶尔闪变，像数据流扰动
        if (col.glyphs.length > 4 && Math.random() < 0.22) {
          gi = 2 + ((Math.random() * (col.glyphs.length - 3)) | 0);
          col.glyphs[gi] = matrixRandChar();
        }
      }

      if (fs !== fs0) {
        ctx.font = fs + 'px "Consolas", "Cascadia Mono", "Courier New", monospace';
        fs0 = fs;
      }

      for (r = 0; r < len; r++) {
        y = col.y - r * fs;
        if (y < -fs || y > height + fs) continue;
        ch = col.glyphs[r];
        if (!ch) continue;

        if (r === 0) {
          // 字头：近白 + 绿磷光晕（仅近景层全开 bloom，控性能）
          if (col.bloom || col.layer === 2) {
            ctx.shadowColor = 'rgba(0, 255, 70, 0.85)';
            ctx.shadowBlur = col.layer === 2 ? 14 : 8;
          } else {
            ctx.shadowBlur = 0;
          }
          ctx.globalAlpha = Math.min(1, 0.92 * aMul + 0.08);
          ctx.fillStyle = headColor;
        } else if (r === 1) {
          ctx.shadowBlur = col.layer === 2 ? 4 : 0;
          ctx.shadowColor = 'rgba(0, 255, 65, 0.4)';
          ctx.globalAlpha = 0.9 * aMul;
          ctx.fillStyle = '#b8ffc0';
        } else if (r < 5) {
          ctx.shadowBlur = 0;
          ctx.globalAlpha = (0.82 - (r - 2) * 0.06) * aMul;
          ctx.fillStyle = green;
        } else {
          ctx.shadowBlur = 0;
          t = r / len;
          // 二次衰减：尾部沉入墨绿，靠残影叠出纵深
          ctx.globalAlpha = Math.max(0.06, (1 - t) * (1 - t) * 0.75) * aMul;
          ctx.fillStyle = col.layer === 0 ? '#009922' : green;
        }
        ctx.fillText(ch, col.x, y);
      }

      ctx.shadowBlur = 0;
      ctx.globalAlpha = 1;

      // 速度：像素/帧，近景更快
      col.y += col.speed * (0.85 + fs * 0.04);

      if (col.y - len * fs > height + fs * 2) {
        // 多数快速重生；少数停顿后换参数，疏密呼吸
        if (Math.random() > 0.88) {
          var next = createMatrixColumn(i, fs * (c.colGap != null ? c.colGap : 1), fs, c);
          next.x = col.x;
          next.wait = 20 + ((Math.random() * 100) | 0);
          matrixCols[i] = next;
        } else {
          col.y = -fs * (1 + Math.random() * 10);
          col.lastRow = -9999;
          col.glyphs.unshift(matrixRandChar());
          while (col.glyphs.length > len) col.glyphs.pop();
        }
      }
    }

    // 极淡四角暗角（每帧低 alpha，配合 trail 不会堆死）
    var vg = ctx.createRadialGradient(
      width * 0.5, height * 0.45, Math.min(width, height) * 0.25,
      width * 0.5, height * 0.5, Math.max(width, height) * 0.72
    );
    vg.addColorStop(0, 'rgba(0,0,0,0)');
    vg.addColorStop(1, 'rgba(0,0,0,0.045)');
    ctx.fillStyle = vg;
    ctx.fillRect(0, 0, width, height);
  }

  function drawWaves(c, time) {
    ctx.fillStyle = 'rgb(' + c.bg + ')';
    ctx.fillRect(0, 0, width, height);
    // 上层淡光（青松绿 / 可配置）
    var sky = c.skyGlow || [[40, 120, 200, 0.2], [6, 18, 32, 0]];
    var g = ctx.createRadialGradient(width * 0.5, height * 0.2, 0, width * 0.5, 0, height * 0.7);
    g.addColorStop(0, rgba(sky[0], sky[0][3]));
    g.addColorStop(1, rgba(sky[1], sky[1][3] != null ? sky[1][3] : 0));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, width, height);

    for (var i = waves.length - 1; i >= 0; i--) {
      var w = waves[i];
      var baseY = height * w.base;
      ctx.beginPath();
      ctx.moveTo(0, height);
      for (var x = 0; x <= width; x += 8) {
        var y = baseY
          + Math.sin(x * w.len + time * w.speed + w.phase) * w.amp
          + Math.sin(x * w.len * 1.7 + time * w.speed * 1.3) * (w.amp * 0.35);
        ctx.lineTo(x, y);
      }
      ctx.lineTo(width, height);
      ctx.closePath();
      ctx.fillStyle = w.color;
      ctx.fill();
      w.phase += 0.012;
    }
  }

  function drawNetOrInteract(c, time) {
    drawTrailBase(c);
    drawTechGrid(c, time);
    drawAurora(c, time);
    var i;
    for (i = 0; i < particles.length; i++) {
      particles[i].update(c, time);
    }
    if (c.mode === 'net' || (c.tech && c.connect)) connectParticles(c);
    for (i = 0; i < particles.length; i++) {
      particles[i].draw(c, time);
    }
    for (i = 0; i < meteors.length; i++) {
      meteors[i].update();
      meteors[i].draw();
    }
    for (i = bursts.length - 1; i >= 0; i--) {
      if (!bursts[i].update()) bursts.splice(i, 1);
      else bursts[i].draw();
    }
    if (mouse.active) {
      var soft = !!c.soft;
      var tech = !!c.tech;
      var mr = c.mode === 'interact' ? (c.comet ? (soft ? 140 : 200) : 160) : 140;
      if (tech && c.mode === 'interact') {
        // HUD：双环 + 准星
        var pulse = 0.75 + 0.25 * Math.sin(time * 0.005);
        var r1 = 52 + 8 * pulse;
        var r2 = 88 + 12 * pulse;
        ctx.beginPath();
        ctx.arc(mouse.x, mouse.y, r2, 0, Math.PI * 2);
        ctx.strokeStyle = rgba([56, 189, 248], 0.12 * pulse);
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 6]);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.beginPath();
        ctx.arc(mouse.x, mouse.y, r1, 0, Math.PI * 2);
        ctx.strokeStyle = rgba([47, 130, 246], 0.22 * pulse);
        ctx.lineWidth = 1.2;
        ctx.stroke();
        var hg = ctx.createRadialGradient(mouse.x, mouse.y, 0, mouse.x, mouse.y, r2);
        hg.addColorStop(0, rgba([56, 189, 248], 0.08 * pulse));
        hg.addColorStop(0.55, rgba([47, 130, 246], 0.03));
        hg.addColorStop(1, rgba([47, 130, 246], 0));
        ctx.fillStyle = hg;
        ctx.beginPath();
        ctx.arc(mouse.x, mouse.y, r2, 0, Math.PI * 2);
        ctx.fill();
      } else if (c.mode === 'interact' && c.comet && c.palette && c.palette.length) {
        var pulse2 = 0.85 + 0.15 * Math.sin(time * 0.004);
        var a0 = soft ? 0.06 : 0.16;
        var a1 = soft ? 0.025 : 0.06;
        for (var pi = 0; pi < Math.min(soft ? 2 : 3, c.palette.length); pi++) {
          var pc = c.palette[pi];
          var pr = mr * (0.4 + pi * (soft ? 0.22 : 0.28));
          var mg2 = ctx.createRadialGradient(mouse.x, mouse.y, 0, mouse.x, mouse.y, pr);
          mg2.addColorStop(0, rgba(pc, a0 * pulse2 / (pi + 1)));
          mg2.addColorStop(0.5, rgba(pc, a1 * pulse2 / (pi + 1)));
          mg2.addColorStop(1, rgba(pc, 0));
          ctx.fillStyle = mg2;
          ctx.beginPath();
          ctx.arc(mouse.x, mouse.y, pr, 0, Math.PI * 2);
          ctx.fill();
        }
      } else {
        var mg = ctx.createRadialGradient(mouse.x, mouse.y, 0, mouse.x, mouse.y, mr);
        mg.addColorStop(0, rgba(c.particle, soft ? 0.12 : 0.28));
        mg.addColorStop(0.4, rgba(c.particle, soft ? 0.04 : 0.1));
        mg.addColorStop(1, rgba(c.particle, 0));
        ctx.fillStyle = mg;
        ctx.beginPath();
        ctx.arc(mouse.x, mouse.y, mr, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  function frame(now) {
    if (!running || !ctx) return;
    if (!t0) t0 = now || performance.now();
    var time = (now || performance.now()) - t0;
    var c = cfg();

    if (c.mode === 'matrix') drawMatrix(c);
    else if (c.mode === 'wave') drawWaves(c, time);
    else drawNetOrInteract(c, time);

    rafId = requestAnimationFrame(frame);
  }

  function onResize() {
    if (!running) return;
    resize();
    initScene();
  }

  function onVisibility() {
    if (document.hidden) {
      if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
    } else if (running && !rafId) {
      rafId = requestAnimationFrame(frame);
    }
  }

  function onMouseMove(e) {
    mouse.x = e.clientX;
    mouse.y = e.clientY;
    mouse.active = true;
  }

  function onMouseLeave() { mouse.active = false; }

  function onClick(e) {
    var c = cfg();
    if (c.mode !== 'interact' || !c.explode) return;
    bursts.push(new Burst(e.clientX, e.clientY, c.particle, c.palette, c.soft));
    // 额外冲开附近粒子
    for (var i = 0; i < particles.length; i++) {
      var p = particles[i];
      var dx = p.x - e.clientX;
      var dy = p.y - e.clientY;
      var d = Math.sqrt(dx * dx + dy * dy) || 1;
      if (d < 180) {
        var f = (1 - d / 180) * 8;
        p.vx += (dx / d) * f;
        p.vy += (dy / d) * f;
      }
    }
  }

  function bindInput() {
    if (!mouseBound) {
      mouseBound = true;
      window.addEventListener('mousemove', onMouseMove, { passive: true });
      window.addEventListener('mouseleave', onMouseLeave);
      document.addEventListener('mouseleave', onMouseLeave);
    }
    if (!clickBound) {
      clickBound = true;
      window.addEventListener('click', onClick, { passive: true });
    }
  }

  function unbindInput() {
    if (mouseBound) {
      mouseBound = false;
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseleave', onMouseLeave);
      document.removeEventListener('mouseleave', onMouseLeave);
    }
    if (clickBound) {
      clickBound = false;
      window.removeEventListener('click', onClick);
    }
  }

  function start(nextTheme) {
    if (prefersReducedMotion()) { stop(); return; }
    if (nextTheme && THEMES[nextTheme]) theme = nextTheme;
    var c = cfg();
    if (c.mode === 'off') { stop(); return; }

    ensureCanvas();
    resize();
    initScene();
    if (ctx && c.bg) {
      ctx.fillStyle = 'rgb(' + c.bg + ')';
      ctx.fillRect(0, 0, width, height);
    }
    t0 = 0;
    if (!running) {
      running = true;
      window.addEventListener('resize', onResize);
      document.addEventListener('visibilitychange', onVisibility);
      bindInput();
      rafId = requestAnimationFrame(frame);
    }
    canvas.style.display = 'block';
  }

  function stop() {
    running = false;
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
    window.removeEventListener('resize', onResize);
    document.removeEventListener('visibilitychange', onVisibility);
    unbindInput();
    if (canvas) {
      if (ctx) ctx.clearRect(0, 0, canvas.width || 0, canvas.height || 0);
      canvas.style.display = 'none';
    }
  }

  function setTheme(name) {
    if (!name || !THEMES[name]) name = 'day';
    theme = name;
    if (prefersReducedMotion()) { stop(); return; }
    if (THEMES[name] && THEMES[name].mode === 'off') { stop(); return; }
    start(name);
  }

  global.QuantParticleBg = {
    start: start,
    stop: stop,
    setTheme: setTheme,
    setActive: function (on) { if (on) start(theme); else stop(); },
    themes: function () { return Object.keys(THEMES); }
  };
})(window);
