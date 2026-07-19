/**
 * 银河主题 · Three.js 全屏 3D 星空（银河可见版）
 * - 倾斜银河盘：密星带 + 雾状光带 + 银心增亮
 * - 前景亮星 / 背景晕星分层
 * - Shader 闪烁 + 偶发流星
 * - 相机缓转，始终能看见横贯天际的银河
 */
(function (global) {
  'use strict';

  var THREE_SRC = 'https://cdnjs.cloudflare.com/ajax/libs/three.js/r134/three.min.js';
  var FIELD_RADIUS = 900;

  /* 银河带倾斜：绕 Z、X 旋转，让光带斜贯视野 */
  var BAND_TILT_Z = 0.55;
  var BAND_TILT_X = -0.28;

  var el = null;
  var wantActive = false;
  var loading = null;
  var rafId = 0;
  var scene = null;
  var camera = null;
  var renderer = null;
  var starLayers = [];
  var nebulae = [];
  var meteors = [];
  var galaxyRoot = null;
  var starTexture = null;
  var softTexture = null;
  var dustTexture = null;
  var uniformsList = [];
  var t0 = 0;
  var angle = 0;
  var mouseX = 0;
  var mouseY = 0;
  var targetMouseX = 0;
  var targetMouseY = 0;
  var bound = false;
  var meteorWait = 0;

  function prefersReducedMotion() {
    try {
      return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (e) {
      return false;
    }
  }

  function ensureEl() {
    el = document.getElementById('starfield-bg');
    if (!el) {
      el = document.createElement('div');
      el.id = 'starfield-bg';
      el.setAttribute('aria-hidden', 'true');
      document.body.insertBefore(el, document.body.firstChild);
    }
    return el;
  }

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      if (global.THREE) return resolve();
      var existed = document.querySelector('script[src="' + src + '"]');
      if (existed) {
        if (global.THREE) return resolve();
        existed.addEventListener('load', function () { resolve(); });
        existed.addEventListener('error', function () { reject(new Error('load fail: ' + src)); });
        return;
      }
      var s = document.createElement('script');
      s.src = src;
      s.async = true;
      s.onload = function () { resolve(); };
      s.onerror = function () { reject(new Error('load fail: ' + src)); };
      document.head.appendChild(s);
    });
  }

  function ensureLibs() {
    if (global.THREE) return Promise.resolve();
    if (loading) return loading;
    loading = loadScript(THREE_SRC)
      .then(function () { loading = null; })
      .catch(function (err) {
        loading = null;
        throw err;
      });
    return loading;
  }

  function gauss() {
    var u = 1 - Math.random();
    var v = 1 - Math.random();
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  }

  /** 银道坐标 → 倾斜后的世界坐标（球面壳上） */
  function galacticToWorld(lon, lat, radius) {
    var cl = Math.cos(lat);
    var x = radius * cl * Math.cos(lon);
    var y = radius * Math.sin(lat);
    var z = radius * cl * Math.sin(lon);

    // 绕 X
    var cosX = Math.cos(BAND_TILT_X);
    var sinX = Math.sin(BAND_TILT_X);
    var y1 = y * cosX - z * sinX;
    var z1 = y * sinX + z * cosX;
    y = y1;
    z = z1;

    // 绕 Z
    var cosZ = Math.cos(BAND_TILT_Z);
    var sinZ = Math.sin(BAND_TILT_Z);
    var x2 = x * cosZ - y * sinZ;
    var y2 = x * sinZ + y * cosZ;

    return { x: x2, y: y2, z: z };
  }

  /** 银心附近更密：lon≈0 */
  function coreBoost(lon) {
    var d = Math.abs(Math.atan2(Math.sin(lon), Math.cos(lon)));
    return 1 + 2.8 * Math.exp(-d * d * 2.2);
  }

  function createStarTexture() {
    var c = document.createElement('canvas');
    c.width = 64;
    c.height = 64;
    var ctx = c.getContext('2d');
    var g = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)');
    g.addColorStop(0.12, 'rgba(255,255,250,0.95)');
    g.addColorStop(0.35, 'rgba(200,240,220,0.45)');
    g.addColorStop(0.65, 'rgba(120,200,170,0.12)');
    g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, 64, 64);
    var tex = new global.THREE.CanvasTexture(c);
    tex.needsUpdate = true;
    return tex;
  }

  /** 大而柔的光斑：拼出可见银河雾带 */
  function createSoftTexture() {
    var c = document.createElement('canvas');
    c.width = 256;
    c.height = 256;
    var ctx = c.getContext('2d');
    var g = ctx.createRadialGradient(128, 128, 0, 128, 128, 128);
    g.addColorStop(0, 'rgba(230,245,255,0.95)');
    g.addColorStop(0.18, 'rgba(180,230,210,0.55)');
    g.addColorStop(0.45, 'rgba(90,180,150,0.22)');
    g.addColorStop(0.75, 'rgba(40,100,80,0.06)');
    g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, 256, 256);
    var tex = new global.THREE.CanvasTexture(c);
    tex.needsUpdate = true;
    return tex;
  }

  /** 细长尘埃条，增强银河纹理 */
  function createDustTexture() {
    var c = document.createElement('canvas');
    c.width = 512;
    c.height = 128;
    var ctx = c.getContext('2d');
    ctx.clearRect(0, 0, 512, 128);
    var i;
    for (i = 0; i < 40; i++) {
      var x = Math.random() * 512;
      var y = 40 + Math.random() * 48;
      var rx = 40 + Math.random() * 120;
      var ry = 6 + Math.random() * 18;
      var g = ctx.createRadialGradient(x, y, 0, x, y, rx);
      var a = 0.08 + Math.random() * 0.18;
      g.addColorStop(0, 'rgba(200,230,220,' + a + ')');
      g.addColorStop(0.5, 'rgba(120,190,160,' + (a * 0.4) + ')');
      g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.ellipse(x, y, rx, ry, (Math.random() - 0.5) * 0.4, 0, Math.PI * 2);
      ctx.fill();
    }
    var tex = new global.THREE.CanvasTexture(c);
    tex.needsUpdate = true;
    return tex;
  }

  function pickStarColor(inBand, nearCore) {
    var roll = Math.random();
    if (nearCore && roll < 0.35) {
      // 银心：暖黄白
      return [1.0, 0.92 + Math.random() * 0.08, 0.72 + Math.random() * 0.15];
    }
    if (inBand && roll < 0.4) {
      return [0.85 + Math.random() * 0.15, 0.95, 0.9 + Math.random() * 0.1];
    }
    if (roll < 0.6) {
      return [0.8 + Math.random() * 0.2, 0.92 + Math.random() * 0.08, 0.88 + Math.random() * 0.12];
    }
    if (roll < 0.85) {
      return [0.55 + Math.random() * 0.25, 0.9 + Math.random() * 0.1, 0.7 + Math.random() * 0.2];
    }
    return [0.95, 0.88 + Math.random() * 0.1, 0.7 + Math.random() * 0.2];
  }

  /**
   * @param {'halo'|'band'|'core'} kind
   */
  function buildStarGeometry(count, kind, sizeScale) {
    var THREE = global.THREE;
    var positions = new Float32Array(count * 3);
    var colors = new Float32Array(count * 3);
    var sizes = new Float32Array(count);
    var phases = new Float32Array(count);
    var i = 0;
    var attempts = 0;
    var maxAttempts = count * 8;

    while (i < count && attempts < maxAttempts) {
      attempts++;
      var lon = Math.random() * Math.PI * 2;
      var lat;
      var accept = true;
      var nearCore = false;
      var inBand = kind === 'band' || kind === 'core';

      if (kind === 'halo') {
        // 全天稀疏晕星
        lat = Math.asin(2 * Math.random() - 1) * 0.92;
        if (Math.abs(lat) < 0.12 && Math.random() < 0.55) accept = false;
      } else if (kind === 'core') {
        lon = gauss() * 0.55;
        lat = gauss() * 0.1;
        nearCore = true;
      } else {
        // 银河盘：极薄纬度 + 银心增密采样
        lat = gauss() * 0.07;
        var boost = coreBoost(lon);
        if (Math.random() * 3.8 > boost) accept = false;
        nearCore = Math.abs(lon) < 0.7;
        // 略微加厚银心
        if (nearCore) lat = gauss() * 0.14;
      }

      if (!accept) continue;

      var r = FIELD_RADIUS * (0.72 + Math.random() * 0.28);
      if (kind === 'halo') r = FIELD_RADIUS * (0.85 + Math.random() * 0.35);

      var p = galacticToWorld(lon, lat, r);
      positions[i * 3] = p.x;
      positions[i * 3 + 1] = p.y;
      positions[i * 3 + 2] = p.z;

      var col = pickStarColor(inBand, nearCore);
      // 带内整体更亮
      var brightMul = inBand ? 1.15 : 0.85;
      if (nearCore) brightMul *= 1.25;
      colors[i * 3] = Math.min(1, col[0] * brightMul);
      colors[i * 3 + 1] = Math.min(1, col[1] * brightMul);
      colors[i * 3 + 2] = Math.min(1, col[2] * brightMul);

      var brightness = Math.pow(Math.random(), kind === 'halo' ? 2.8 : 1.6);
      var base = kind === 'core' ? 2.2 : kind === 'band' ? 1.6 : 1.1;
      sizes[i] = (base + brightness * 4.5) * sizeScale;
      if (nearCore && Math.random() < 0.15) sizes[i] *= 1.8;
      phases[i] = Math.random() * Math.PI * 2;
      i++;
    }

    // 若拒绝采样不够，补满
    while (i < count) {
      var lon2 = Math.random() * Math.PI * 2;
      var lat2 = gauss() * (kind === 'halo' ? 0.9 : 0.08);
      var p2 = galacticToWorld(lon2, lat2, FIELD_RADIUS);
      positions[i * 3] = p2.x;
      positions[i * 3 + 1] = p2.y;
      positions[i * 3 + 2] = p2.z;
      var c2 = pickStarColor(true, false);
      colors[i * 3] = c2[0];
      colors[i * 3 + 1] = c2[1];
      colors[i * 3 + 2] = c2[2];
      sizes[i] = (1.2 + Math.random() * 3) * sizeScale;
      phases[i] = Math.random() * Math.PI * 2;
      i++;
    }

    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    geo.setAttribute('size', new THREE.BufferAttribute(sizes, 1));
    geo.setAttribute('phase', new THREE.BufferAttribute(phases, 1));
    return geo;
  }

  function createStarMaterial(baseOpacity, sizeAtten) {
    var THREE = global.THREE;
    var uniforms = {
      pointTexture: { value: starTexture },
      time: { value: 0 },
      opacity: { value: baseOpacity },
      sizeAtten: { value: sizeAtten || 420 }
    };
    uniformsList.push(uniforms);

    return new THREE.ShaderMaterial({
      uniforms: uniforms,
      vertexShader: [
        'attribute float size;',
        'attribute float phase;',
        'attribute vec3 color;',
        'varying vec3 vColor;',
        'varying float vAlpha;',
        'uniform float time;',
        'uniform float opacity;',
        'uniform float sizeAtten;',
        'void main() {',
        '  vColor = color;',
        '  float tw = 0.78 + 0.22 * sin(time * 1.6 + phase * 2.7);',
        '  vAlpha = opacity * tw;',
        '  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);',
        '  float atten = sizeAtten / max(40.0, -mvPosition.z);',
        '  gl_PointSize = size * atten * (0.9 + 0.2 * tw);',
        '  gl_PointSize = clamp(gl_PointSize, 1.2, 72.0);',
        '  gl_Position = projectionMatrix * mvPosition;',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform sampler2D pointTexture;',
        'varying vec3 vColor;',
        'varying float vAlpha;',
        'void main() {',
        '  vec4 tex = texture2D(pointTexture, gl_PointCoord);',
        '  if (tex.a < 0.015) discard;',
        '  gl_FragColor = vec4(vColor, 1.0) * tex * vAlpha;',
        '}'
      ].join('\n'),
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      transparent: true
    });
  }

  function createStarLayer(count, kind, sizeScale, opacity, atten, rotSpeed) {
    var geo = buildStarGeometry(count, kind, sizeScale);
    var mat = createStarMaterial(opacity, atten);
    var pts = new global.THREE.Points(geo, mat);
    pts.userData.rotSpeed = rotSpeed || 0;
    return pts;
  }

  /** 用大量柔光点拼出连续可见的银河雾带 */
  function createMilkyBandGlow() {
    var THREE = global.THREE;
    var count = 900;
    var positions = new Float32Array(count * 3);
    var colors = new Float32Array(count * 3);
    var sizes = new Float32Array(count);
    var phases = new Float32Array(count);

    for (var i = 0; i < count; i++) {
      var lon = (i / count) * Math.PI * 2 + (Math.random() - 0.5) * 0.08;
      // 多层厚度
      var lat = gauss() * 0.09;
      var boost = coreBoost(lon);
      var r = FIELD_RADIUS * (0.78 + Math.random() * 0.2);
      var p = galacticToWorld(lon, lat, r);
      positions[i * 3] = p.x;
      positions[i * 3 + 1] = p.y;
      positions[i * 3 + 2] = p.z;

      var warm = Math.exp(-lon * lon * 1.8);
      colors[i * 3] = 0.55 + warm * 0.45;
      colors[i * 3 + 1] = 0.75 + warm * 0.2;
      colors[i * 3 + 2] = 0.7 + (1 - warm) * 0.15;

      sizes[i] = (18 + Math.random() * 28) * (0.7 + boost * 0.35);
      phases[i] = Math.random() * Math.PI * 2;
    }

    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    geo.setAttribute('size', new THREE.BufferAttribute(sizes, 1));
    geo.setAttribute('phase', new THREE.BufferAttribute(phases, 1));

    var uniforms = {
      pointTexture: { value: softTexture },
      time: { value: 0 },
      opacity: { value: 0.42 },
      sizeAtten: { value: 380 }
    };
    uniformsList.push(uniforms);

    var mat = new THREE.ShaderMaterial({
      uniforms: uniforms,
      vertexShader: [
        'attribute float size;',
        'attribute float phase;',
        'attribute vec3 color;',
        'varying vec3 vColor;',
        'varying float vAlpha;',
        'uniform float time;',
        'uniform float opacity;',
        'uniform float sizeAtten;',
        'void main() {',
        '  vColor = color;',
        '  float tw = 0.88 + 0.12 * sin(time * 0.5 + phase);',
        '  vAlpha = opacity * tw;',
        '  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);',
        '  float atten = sizeAtten / max(50.0, -mvPosition.z);',
        '  gl_PointSize = size * atten;',
        '  gl_PointSize = clamp(gl_PointSize, 8.0, 160.0);',
        '  gl_Position = projectionMatrix * mvPosition;',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform sampler2D pointTexture;',
        'varying vec3 vColor;',
        'varying float vAlpha;',
        'void main() {',
        '  vec4 tex = texture2D(pointTexture, gl_PointCoord);',
        '  if (tex.a < 0.01) discard;',
        '  gl_FragColor = vec4(vColor, 1.0) * tex * vAlpha;',
        '}'
      ].join('\n'),
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      transparent: true
    });

    var pts = new THREE.Points(geo, mat);
    pts.userData.rotSpeed = 0.00006;
    return pts;
  }

  /** 银心大光晕 */
  function createGalacticCore() {
    var THREE = global.THREE;
    var group = new THREE.Group();
    var p = galacticToWorld(0, 0, FIELD_RADIUS * 0.82);

    var make = function (scale, opacity, color) {
      var mat = new THREE.SpriteMaterial({
        map: softTexture,
        color: new THREE.Color(color),
        transparent: true,
        opacity: opacity,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      });
      var spr = new THREE.Sprite(mat);
      spr.position.set(p.x, p.y, p.z);
      spr.scale.set(scale, scale * 0.55, 1);
      spr.userData.pulse = Math.random() * Math.PI * 2;
      spr.userData.baseOpacity = opacity;
      return spr;
    };

    group.add(make(420, 0.55, 0xffe8b0));
    group.add(make(280, 0.4, 0xc8ffe0));
    group.add(make(160, 0.35, 0xffffff));
    return group;
  }

  /** 沿银道铺开的尘埃精灵，加强「带」的轮廓 */
  function createDustSprites() {
    var THREE = global.THREE;
    var list = [];
    var n = 28;
    for (var i = 0; i < n; i++) {
      var lon = (i / n) * Math.PI * 2;
      var lat = (Math.random() - 0.5) * 0.06;
      var boost = coreBoost(lon);
      var p = galacticToWorld(lon, lat, FIELD_RADIUS * 0.8);
      var mat = new THREE.SpriteMaterial({
        map: dustTexture,
        color: new THREE.Color().setHSL(0.35 + Math.random() * 0.08, 0.25, 0.55 + boost * 0.08),
        transparent: true,
        opacity: 0.22 + boost * 0.1,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      });
      var spr = new THREE.Sprite(mat);
      spr.position.set(p.x, p.y, p.z);
      var w = 220 + Math.random() * 180 + boost * 40;
      spr.scale.set(w, w * (0.22 + Math.random() * 0.12), 1);
      spr.userData.pulse = Math.random() * Math.PI * 2;
      spr.userData.baseOpacity = mat.opacity;
      list.push(spr);
    }
    return list;
  }

  function spawnMeteor() {
    var THREE = global.THREE;
    var geo = new THREE.BufferGeometry();
    var from = new THREE.Vector3(
      (Math.random() - 0.5) * FIELD_RADIUS * 0.9,
      FIELD_RADIUS * (0.1 + Math.random() * 0.4),
      (Math.random() - 0.5) * FIELD_RADIUS * 0.9
    );
    var dir = new THREE.Vector3(
      (Math.random() - 0.5) * 2,
      -1.2 - Math.random(),
      (Math.random() - 0.5) * 2
    ).normalize();
    var len = 50 + Math.random() * 100;
    var to = from.clone().add(dir.clone().multiplyScalar(len));
    geo.setFromPoints([from, to]);

    var mat = new THREE.LineBasicMaterial({
      color: 0xd0ffe8,
      transparent: true,
      opacity: 0.9,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    });
    var line = new THREE.Line(geo, mat);
    line.userData = {
      vel: dir.multiplyScalar(7 + Math.random() * 9),
      life: 1,
      fade: 0.02 + Math.random() * 0.015
    };
    return line;
  }

  function onMouseMove(e) {
    targetMouseX = (e.clientX / window.innerWidth - 0.5) * 0.5;
    targetMouseY = (e.clientY / window.innerHeight - 0.5) * 0.32;
  }

  function onResize() {
    if (!camera || !renderer || !el) return;
    var w = window.innerWidth || 800;
    var h = window.innerHeight || 600;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  }

  function bindInput() {
    if (bound) return;
    bound = true;
    window.addEventListener('mousemove', onMouseMove, { passive: true });
    window.addEventListener('resize', onResize);
  }

  function unbindInput() {
    if (!bound) return;
    bound = false;
    window.removeEventListener('mousemove', onMouseMove);
    window.removeEventListener('resize', onResize);
  }

  function disposeObject(obj) {
    if (!obj) return;
    if (obj.children && obj.children.length) {
      while (obj.children.length) {
        var c = obj.children[0];
        obj.remove(c);
        disposeObject(c);
      }
    }
    if (obj.geometry) obj.geometry.dispose();
    if (obj.material) {
      if (Array.isArray(obj.material)) {
        obj.material.forEach(function (m) {
          if (m.map && m.map !== starTexture && m.map !== softTexture && m.map !== dustTexture) {
            m.map.dispose();
          }
          m.dispose();
        });
      } else {
        var map = obj.material.map;
        if (map && map !== starTexture && map !== softTexture && map !== dustTexture) {
          map.dispose();
        }
        obj.material.dispose();
      }
    }
  }

  function destroyScene() {
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = 0;
    }
    unbindInput();
    if (scene) {
      while (scene.children.length) {
        var child = scene.children[0];
        scene.remove(child);
        disposeObject(child);
      }
    }
    if (starTexture) {
      try { starTexture.dispose(); } catch (e) {}
      starTexture = null;
    }
    if (softTexture) {
      try { softTexture.dispose(); } catch (e) {}
      softTexture = null;
    }
    if (dustTexture) {
      try { dustTexture.dispose(); } catch (e) {}
      dustTexture = null;
    }
    if (renderer) {
      try { renderer.dispose(); } catch (e) {}
      if (renderer.domElement && renderer.domElement.parentNode) {
        renderer.domElement.parentNode.removeChild(renderer.domElement);
      }
    }
    scene = null;
    camera = null;
    renderer = null;
    galaxyRoot = null;
    starLayers = [];
    nebulae = [];
    meteors = [];
    uniformsList = [];
  }

  function tick(now) {
    if (!wantActive || !renderer || !scene || !camera) return;
    rafId = requestAnimationFrame(tick);

    if (!t0) t0 = now || performance.now();
    var time = ((now || performance.now()) - t0) * 0.001;

    var u;
    for (u = 0; u < uniformsList.length; u++) {
      uniformsList[u].time.value = time;
    }

    // 慢旋：保证银河带持续横贯视野
    angle += 0.00038;
    mouseX += (targetMouseX - mouseX) * 0.04;
    mouseY += (targetMouseY - mouseY) * 0.04;

    var radius = 40;
    var bob = Math.sin(time * 0.28) * 4;
    // 相机在球心附近朝外看天空，像仰望银河
    camera.position.set(
      Math.sin(angle) * radius * 0.15 + mouseX * 12,
      8 + bob + mouseY * 20,
      Math.cos(angle) * radius * 0.15
    );
    var lookLon = angle + 0.35 + mouseX * 0.8;
    var look = galacticToWorld(lookLon, 0.02 + mouseY * 0.15, 400);
    camera.lookAt(look.x, look.y, look.z);

    if (galaxyRoot) {
      galaxyRoot.rotation.y = Math.sin(time * 0.04) * 0.04;
    }

    var i;
    for (i = 0; i < starLayers.length; i++) {
      starLayers[i].rotation.y += starLayers[i].userData.rotSpeed || 0;
    }

    for (i = 0; i < nebulae.length; i++) {
      var n = nebulae[i];
      if (n.userData && n.userData.baseOpacity != null) {
        n.material.opacity = n.userData.baseOpacity * (0.82 + 0.18 * Math.sin(time * 0.35 + n.userData.pulse));
      }
    }

    meteorWait -= 1;
    if (meteorWait <= 0 && Math.random() < 0.01) {
      var m = spawnMeteor();
      scene.add(m);
      meteors.push(m);
      meteorWait = 50 + Math.random() * 100;
    }
    for (i = meteors.length - 1; i >= 0; i--) {
      var met = meteors[i];
      met.position.add(met.userData.vel);
      met.userData.life -= met.userData.fade;
      met.material.opacity = Math.max(0, met.userData.life);
      if (met.userData.life <= 0) {
        scene.remove(met);
        disposeObject(met);
        meteors.splice(i, 1);
      }
    }

    renderer.render(scene, camera);
  }

  function initScene() {
    var THREE = global.THREE;
    var host = ensureEl();
    host.innerHTML = '';
    host.style.display = 'block';

    var w = window.innerWidth || 800;
    var h = window.innerHeight || 600;

    scene = new THREE.Scene();
    // 更深底色，雾很轻，避免把银河糊掉
    scene.background = new THREE.Color(0x030806);
    scene.fog = new THREE.FogExp2(0x030806, 0.00035);

    camera = new THREE.PerspectiveCamera(72, w / h, 0.1, 5000);
    camera.position.set(0, 10, 8);

    renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: false,
      powerPreference: 'high-performance'
    });
    renderer.setSize(w, h, false);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.domElement.style.cssText = 'display:block;width:100%;height:100%;pointer-events:none;';
    host.appendChild(renderer.domElement);

    starTexture = createStarTexture();
    softTexture = createSoftTexture();
    dustTexture = createDustTexture();
    uniformsList = [];
    starLayers = [];
    nebulae = [];
    meteors = [];

    galaxyRoot = new THREE.Group();
    scene.add(galaxyRoot);

    // 1) 银河雾带（最先画，垫底）
    var glow = createMilkyBandGlow();
    galaxyRoot.add(glow);
    starLayers.push(glow);

    // 2) 尘埃纹理带
    var dusts = createDustSprites();
    for (var d = 0; d < dusts.length; d++) {
      galaxyRoot.add(dusts[d]);
      nebulae.push(dusts[d]);
    }

    // 3) 银心光晕
    var coreGlow = createGalacticCore();
    galaxyRoot.add(coreGlow);
    for (var ci = 0; ci < coreGlow.children.length; ci++) {
      nebulae.push(coreGlow.children[ci]);
    }

    // 4) 银河密星带（大量清晰星点）
    var band = createStarLayer(9000, 'band', 1.35, 1.0, 480, 0.00004);
    galaxyRoot.add(band);
    starLayers.push(band);

    // 5) 银心特密星
    var core = createStarLayer(2200, 'core', 1.7, 1.0, 500, 0.00005);
    galaxyRoot.add(core);
    starLayers.push(core);

    // 6) 全天晕星（较少，衬托银河）
    var halo = createStarLayer(2800, 'halo', 1.1, 0.75, 400, 0.00002);
    galaxyRoot.add(halo);
    starLayers.push(halo);

    // 7) 少量特亮前景星
    var bright = createStarLayer(120, 'band', 3.4, 1.0, 520, 0.00003);
    galaxyRoot.add(bright);
    starLayers.push(bright);

    angle = Math.random() * Math.PI * 2;
    mouseX = targetMouseX = 0;
    mouseY = targetMouseY = 0;
    t0 = 0;
    meteorWait = 80;

    bindInput();
    tick();
  }

  function stop() {
    wantActive = false;
    destroyScene();
    if (el) el.style.display = 'none';
  }

  function start() {
    if (prefersReducedMotion()) {
      stop();
      return Promise.resolve();
    }
    wantActive = true;
    ensureEl().style.display = 'block';
    return ensureLibs().then(function () {
      if (!wantActive || !global.THREE) return;
      destroyScene();
      wantActive = true;
      initScene();
    }).catch(function (err) {
      console.warn('[QuantStarfieldBg]', err);
      stop();
    });
  }

  global.QuantStarfieldBg = {
    start: start,
    stop: stop,
    setActive: function (on) { if (on) start(); else stop(); }
  };

  window.addEventListener('beforeunload', stop);
})(window);
