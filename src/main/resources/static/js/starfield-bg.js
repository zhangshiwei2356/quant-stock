/**
 * 青松主题 · Three.js 全屏 3D 星空
 * - BufferGeometry 数千随机星点
 * - 相机绕 Y 轴缓慢自转，鼠标微调视角
 * - 软点精灵 + 雾 + sizeAttenuation 营造景深模糊感
 * CDN 懒加载 three.js；pointer-events:none，不挡页面点击。
 */
(function (global) {
  'use strict';

  var THREE_SRC = 'https://cdnjs.cloudflare.com/ajax/libs/three.js/r134/three.min.js';
  var STAR_COUNT = 5000;
  var FIELD_RADIUS = 900;

  var el = null;
  var wantActive = false;
  var loading = null;
  var rafId = 0;
  var scene = null;
  var camera = null;
  var renderer = null;
  var points = null;
  var softPoints = null;
  var starTexture = null;
  var angle = 0;
  var mouseX = 0;
  var mouseY = 0;
  var targetMouseX = 0;
  var targetMouseY = 0;
  var bound = false;

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

  /** 径向软光晕贴图，远星看起来更「糊」 */
  function createStarTexture() {
    var c = document.createElement('canvas');
    c.width = 64;
    c.height = 64;
    var ctx = c.getContext('2d');
    var g = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)');
    g.addColorStop(0.15, 'rgba(220,255,235,0.95)');
    g.addColorStop(0.4, 'rgba(120,220,170,0.4)');
    g.addColorStop(0.7, 'rgba(40,120,90,0.12)');
    g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, 64, 64);
    var tex = new global.THREE.CanvasTexture(c);
    tex.needsUpdate = true;
    return tex;
  }

  function buildStarGeometry(count, radius, sizeScale) {
    var THREE = global.THREE;
    var positions = new Float32Array(count * 3);
    var colors = new Float32Array(count * 3);
    var sizes = new Float32Array(count);

    for (var i = 0; i < count; i++) {
      // 球壳内均匀随机
      var u = Math.random();
      var v = Math.random();
      var theta = 2 * Math.PI * u;
      var phi = Math.acos(2 * v - 1);
      var r = radius * Math.cbrt(Math.random());
      var x = r * Math.sin(phi) * Math.cos(theta);
      var y = r * Math.sin(phi) * Math.sin(theta);
      var z = r * Math.cos(phi);
      positions[i * 3] = x;
      positions[i * 3 + 1] = y;
      positions[i * 3 + 2] = z;

      // 青松系：青绿白微差
      var tint = Math.random();
      colors[i * 3] = 0.55 + tint * 0.35;
      colors[i * 3 + 1] = 0.85 + tint * 0.15;
      colors[i * 3 + 2] = 0.65 + tint * 0.25;

      // 远小近大：用距离近似景深
      var dist = Math.sqrt(x * x + y * y + z * z) / radius;
      sizes[i] = (0.6 + Math.random() * 2.2) * sizeScale * (1.35 - dist * 0.85);
    }

    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    geo.setAttribute('size', new THREE.BufferAttribute(sizes, 1));
    return geo;
  }

  function createPoints(count, size, opacity, sizeScale) {
    var THREE = global.THREE;
    var geo = buildStarGeometry(count, FIELD_RADIUS, sizeScale);
    var mat = new THREE.PointsMaterial({
      size: size,
      map: starTexture,
      transparent: true,
      opacity: opacity,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      vertexColors: true,
      sizeAttenuation: true
    });
    return new THREE.Points(geo, mat);
  }

  function onMouseMove(e) {
    targetMouseX = (e.clientX / window.innerWidth - 0.5) * 0.55;
    targetMouseY = (e.clientY / window.innerHeight - 0.5) * 0.35;
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
    if (obj.geometry) obj.geometry.dispose();
    if (obj.material) {
      if (obj.material.map) obj.material.map.dispose();
      obj.material.dispose();
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
    if (renderer) {
      try { renderer.dispose(); } catch (e) {}
      if (renderer.domElement && renderer.domElement.parentNode) {
        renderer.domElement.parentNode.removeChild(renderer.domElement);
      }
    }
    scene = null;
    camera = null;
    renderer = null;
    points = null;
    softPoints = null;
  }

  function tick() {
    if (!wantActive || !renderer || !scene || !camera) return;
    rafId = requestAnimationFrame(tick);

    // 绕 Y 轴缓慢自转 + 鼠标微调（平滑跟随）
    angle += 0.0007;
    mouseX += (targetMouseX - mouseX) * 0.04;
    mouseY += (targetMouseY - mouseY) * 0.04;

    var radius = 220;
    camera.position.x = Math.sin(angle + mouseX) * radius;
    camera.position.z = Math.cos(angle + mouseX) * radius;
    camera.position.y = 28 + mouseY * 90;
    camera.lookAt(0, 0, 0);

    // 远景层极慢反向漂一点，加强空间感
    if (softPoints) {
      softPoints.rotation.y -= 0.00015;
    }
    if (points) {
      points.rotation.y += 0.00005;
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
    scene.background = new THREE.Color(0x0a1411);
    // 指数雾：远处星点发糊、变淡 → 景深感
    scene.fog = new THREE.FogExp2(0x0a1411, 0.0018);

    camera = new THREE.PerspectiveCamera(60, w / h, 0.1, 3000);
    camera.position.set(0, 30, 220);

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

    // 主星层：较清晰
    points = createPoints(Math.floor(STAR_COUNT * 0.65), 3.2, 0.95, 1);
    scene.add(points);

    // 远景软星层：更大、更淡 → 模糊景深
    softPoints = createPoints(Math.floor(STAR_COUNT * 0.45), 6.5, 0.45, 1.4);
    softPoints.scale.set(1.15, 1.15, 1.15);
    scene.add(softPoints);

    // 极少量大星点缀
    var sparkle = createPoints(120, 10, 0.7, 2.2);
    scene.add(sparkle);

    angle = Math.random() * Math.PI * 2;
    mouseX = targetMouseX = 0;
    mouseY = targetMouseY = 0;

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
