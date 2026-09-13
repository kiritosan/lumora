(() => {
  'use strict';

  let opened = false;
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  };

  function openPlayground(trigger) {
    if (opened) return;
    const api = window.shiguang;
    if (!api || typeof api.getItems !== 'function' || typeof api.createCard !== 'function') return;
    const source = api.getItems();
    if (!Array.isArray(source) || !source.length) {
      api.showHint?.('先添加一件藏品，再来物理把玩。');
      return;
    }
    if (typeof HTMLDialogElement === 'undefined' || !HTMLDialogElement.prototype.showModal) {
      api.showHint?.('当前浏览器不支持把玩展台，请使用新版浏览器。');
      return;
    }

    const snapshot = source.slice(0, 8).map(item => ({ ...item }));
    const dialog = node('dialog', 'physics-dialog');
    dialog.setAttribute('aria-labelledby', 'physicsTitle');
    dialog.setAttribute('aria-describedby', 'physicsHelp physicsModel');
    const content = node('div', 'physics-content');
    const header = node('div', 'physics-header');
    const heading = node('div');
    heading.append(node('p', 'physics-eyebrow', 'SHIGUANG / INTERACTIVE STUDY'));
    const title = node('h2', '', '掌心之间 · 物理把玩');
    title.id = 'physicsTitle';
    heading.append(title);
    const tools = node('div', 'physics-tools');
    tools.setAttribute('role', 'group');
    tools.setAttribute('aria-label', '展台控制');
    const button = text => {
      const element = node('button', '', text);
      element.type = 'button';
      return element;
    };
    const resetButton = button('重新摆放');
    const pauseButton = button('暂停');
    const closeButton = button('关闭');
    closeButton.setAttribute('aria-label', '关闭物理把玩');
    closeButton.autofocus = true;
    tools.append(resetButton, pauseButton, closeButton);
    header.append(heading, tools);

    const controls = node('div', 'physics-controls');
    const gravityLabel = node('label', 'physics-control');
    const gravityInput = node('input');
    gravityInput.type = 'checkbox';
    gravityInput.checked = true;
    gravityLabel.append(gravityInput, node('span', '', '重力'));
    const bounceLabel = node('label', 'physics-control');
    const bounceInput = node('input');
    bounceInput.id = 'physicsBounce';
    bounceLabel.htmlFor = bounceInput.id;
    bounceInput.setAttribute('aria-valuetext', '45% 回弹');
    bounceInput.type = 'range';
    bounceInput.min = '0';
    bounceInput.max = '85';
    bounceInput.step = '5';
    bounceInput.value = '45';
    const bounceOutput = node('output', '', '45%');
    bounceOutput.htmlFor = bounceInput.id;
    bounceLabel.append(node('span', '', '回弹'), bounceInput, bounceOutput);
    const sensorButton = button('启用倾斜重力');
    sensorButton.setAttribute('aria-pressed', 'false');
    sensorButton.setAttribute('aria-describedby', 'physicsSensorStatus');
    controls.append(gravityLabel, bounceLabel, sensorButton);
    const sensorStatus = node('p', 'physics-sensor-status', '倾斜重力需设备支持；仅在你启用后读取，不保存传感器数据。');
    sensorStatus.id = 'physicsSensorStatus';
    sensorStatus.setAttribute('role', 'status');
    const arena = node('div', 'physics-arena');
    arena.tabIndex = 0;
    arena.setAttribute('role', 'group');
    arena.setAttribute('aria-label', '藏品物理展台');
    arena.setAttribute('aria-describedby', 'physicsHelp physicsModel');
    const readout = node('p', 'physics-readout');
    readout.setAttribute('role', 'status');
    const help = node('p', 'physics-help', '拖动并松手抛掷 · Tab 选择藏品，方向键移动（Shift 加大步幅），展台内空格暂停 / 继续 · Esc 关闭');
    help.id = 'physicsHelp';
    const model = node('p', 'physics-help', `本次展出 ${snapshot.length} / ${source.length} 件（最多前 8 件快照），不改动收藏。简化模型：徽章为圆形，其他形态为不旋转的矩形包围盒（含拱门空角）；质量按面积估算。`);
    model.id = 'physicsModel';
    content.append(header, controls, sensorStatus, arena, readout, help, model);
    dialog.append(content);

    const lifetime = new AbortController();
    const listen = (target, type, handler, options = {}) => target.addEventListener(type, handler, { ...options, signal: lifetime.signal });
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    let active = true;
    let paused = motion.matches;
    let sleeping = false;
    let selected = null;
    let drag = null;
    let width = 0;
    let height = 0;
    let raf = 0;
    let lastTime = 0;
    let accumulator = 0;
    let idleTime = 0;
    let restitution = .45;
    let gx = 0;
    let gy = 820;
    let sensorState = 'off';
    let sensorToken = 0;
    let sensorTimer = 0;
    let sensorListener = null;
    let resizeObserver = null;
    const stepSize = 1 / 120;
    const speedLimit = 850;
    const bodies = [];

    snapshot.forEach((item, index) => {
      const element = node('div', 'physics-body');
      element.tabIndex = 0;
      element.setAttribute('role', 'button');
      element.setAttribute('aria-pressed', 'false');
      element.setAttribute('aria-describedby', 'physicsHelp');
      element.setAttribute('aria-keyshortcuts', 'ArrowUp ArrowDown ArrowLeft ArrowRight Space');
      const name = item.title || '未命名藏品';
      const card = api.createCard(item);
      card.setAttribute('aria-hidden', 'true');
      card.querySelectorAll('img').forEach(image => { image.draggable = false; });
      const circle = card.dataset.shape === 'badge';
      element.dataset.circle = String(circle);
      element.dataset.form = card.dataset.shape;
      const shapeName = { card: '卡片', badge: '徽章', square: '方形画砖', arch: '拱门藏画', polaroid: '即影相纸' }[card.dataset.shape] || '藏品';
      element.setAttribute('aria-label', `${index + 1}. ${name}，${shapeName}，方向键移动，空格暂停或继续`);
      element.append(card);
      arena.append(element);
      bodies.push({ element, card, name, circle, ratio: circle ? 1 : .71, w: 0, h: 0, x: 0, y: 0, vx: 0, vy: 0, mass: 1, invMass: 1 });
    });

    function updateReadout(note = '') {
      pauseButton.textContent = paused ? '继续' : '暂停';
      pauseButton.setAttribute('aria-pressed', String(paused));
      pauseButton.setAttribute('aria-label', paused ? '继续物理模拟' : '暂停物理模拟');
      readout.textContent = `${paused ? '已暂停，可静态移动' : '模拟中'} · ${selected ? `已选：${selected.name}` : '选择一件藏品，感受重量与回弹'}${note ? ` · ${note}` : ''}`;
    }

    function select(body) {
      selected = body;
      bodies.forEach(value => value.element.setAttribute('aria-pressed', String(value === body)));
      updateReadout();
    }

    function limitVelocity(body) {
      const speed = Math.hypot(body.vx, body.vy);
      if (speed > speedLimit) {
        body.vx *= speedLimit / speed;
        body.vy *= speedLimit / speed;
      }
    }

    function render() {
      bodies.forEach(body => {
        body.element.style.transform = `translate3d(${(body.x - body.w / 2).toFixed(2)}px, ${(body.y - body.h / 2).toFixed(2)}px, 0)`;
        body.card.style.setProperty('--mx', `${clamp(80 - body.x / Math.max(1, width) * 60 + body.vx * .02, 10, 90).toFixed(1)}%`);
        body.card.style.setProperty('--my', `${clamp(70 - body.y / Math.max(1, height) * 45 + body.vy * .015, 10, 90).toFixed(1)}%`);
      });
    }

    function contain(body, bounce = true) {
      const minX = body.w / 2 + 2;
      const maxX = Math.max(minX, width - minX);
      const minY = body.h / 2 + 2;
      const maxY = Math.max(minY, height - minY);
      const edgeBounce = value => bounce && Math.abs(value) > 45 ? -value * restitution : 0;
      if (body.x < minX) {
        body.x = minX;
        if (body.vx < 0) { body.vx = edgeBounce(body.vx); body.vy *= .82; }
      } else if (body.x > maxX) {
        body.x = maxX;
        if (body.vx > 0) { body.vx = edgeBounce(body.vx); body.vy *= .82; }
      }
      if (body.y < minY) {
        body.y = minY;
        if (body.vy < 0) { body.vy = edgeBounce(body.vy); body.vx *= .82; }
      } else if (body.y > maxY) {
        body.y = maxY;
        if (body.vy > 0) { body.vy = edgeBounce(body.vy); body.vx *= .82; }
      }
    }

    function circleRect(circle, rect) {
      const dx = clamp(circle.x, rect.x - rect.w / 2, rect.x + rect.w / 2) - circle.x;
      const dy = clamp(circle.y, rect.y - rect.h / 2, rect.y + rect.h / 2) - circle.y;
      const distance = Math.hypot(dx, dy);
      const radius = circle.w / 2;
      if (distance >= radius) return null;
      if (distance > .0001) return { nx: dx / distance, ny: dy / distance, depth: radius - distance };
      const sides = [
        { gap: circle.x - rect.x + rect.w / 2, nx: 1, ny: 0 },
        { gap: rect.x + rect.w / 2 - circle.x, nx: -1, ny: 0 },
        { gap: circle.y - rect.y + rect.h / 2, nx: 0, ny: 1 },
        { gap: rect.y + rect.h / 2 - circle.y, nx: 0, ny: -1 }
      ];
      const side = sides.reduce((best, value) => value.gap < best.gap ? value : best);
      return { nx: side.nx, ny: side.ny, depth: radius + side.gap };
    }

    function contact(a, b) {
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const overlapX = (a.w + b.w) / 2 - Math.abs(dx);
      const overlapY = (a.h + b.h) / 2 - Math.abs(dy);
      if (overlapX <= 0 || overlapY <= 0) return null;
      if (a.circle && b.circle) {
        const distance = Math.hypot(dx, dy);
        const radius = (a.w + b.w) / 2;
        if (distance >= radius) return null;
        return { nx: distance > .0001 ? dx / distance : 1, ny: distance > .0001 ? dy / distance : 0, depth: radius - distance };
      }
      if (a.circle) return circleRect(a, b);
      if (b.circle) {
        const hit = circleRect(b, a);
        return hit ? { nx: -hit.nx, ny: -hit.ny, depth: hit.depth } : null;
      }
      return overlapX < overlapY
        ? { nx: dx >= 0 ? 1 : -1, ny: 0, depth: overlapX }
        : { nx: 0, ny: dy >= 0 ? 1 : -1, depth: overlapY };
    }

    function resolve(a, b, hit, impulses) {
      const { nx, ny, depth } = hit;
      const inverse = a.invMass + b.invMass;
      const correction = Math.max(0, depth - .015) * .85 / inverse;
      a.x -= nx * correction * a.invMass;
      a.y -= ny * correction * a.invMass;
      b.x += nx * correction * b.invMass;
      b.y += ny * correction * b.invMass;
      if (!impulses) return;
      const relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
      if (relative >= 0) return;
      const bounce = relative < -45 ? restitution : 0;
      const impulse = -(1 + bounce) * relative / inverse;
      a.vx -= impulse * nx * a.invMass;
      a.vy -= impulse * ny * a.invMass;
      b.vx += impulse * nx * b.invMass;
      b.vy += impulse * ny * b.invMass;
      const tangent = (b.vx - a.vx) * -ny + (b.vy - a.vy) * nx;
      const friction = clamp(-tangent / inverse, -impulse * .45, impulse * .45);
      a.vx -= friction * -ny * a.invMass;
      a.vy -= friction * nx * a.invMass;
      b.vx += friction * -ny * b.invMass;
      b.vy += friction * nx * b.invMass;
    }

    function solve(impulses = true) {
      for (let pass = 0; pass < 8; pass++) {
        for (let i = 0; i < bodies.length; i++) {
          for (let j = i + 1; j < bodies.length; j++) {
            const hit = contact(bodies[i], bodies[j]);
            if (hit) resolve(bodies[i], bodies[j], hit, impulses);
          }
        }
        bodies.forEach(body => contain(body, impulses));
      }
    }

    function stopFrame() {
      cancelAnimationFrame(raf);
      raf = 0;
      lastTime = 0;
      accumulator = 0;
    }

    function wake() {
      sleeping = false;
      idleTime = 0;
      if (active && dialog.open && !paused && !document.hidden && !raf) {
        lastTime = 0;
        accumulator = 0;
        raf = requestAnimationFrame(frame);
      }
    }

    function integrate() {
      const damping = Math.exp(-.62 * stepSize);
      let quiet = true;
      bodies.forEach(body => {
        body.previousX = body.x;
        body.previousY = body.y;
        if (drag?.body === body) {
          const stiffness = 160 / body.mass;
          const resistance = 23 / Math.sqrt(body.mass);
          body.vx += ((drag.tx - body.x) * stiffness - body.vx * resistance) * stepSize;
          body.vy += ((drag.ty - body.y) * stiffness - body.vy * resistance) * stepSize;
        } else if (gravityInput.checked) {
          body.vx += gx * stepSize;
          body.vy += gy * stepSize;
        }
        body.vx *= damping;
        body.vy *= damping;
        limitVelocity(body);
        body.x += body.vx * stepSize;
        body.y += body.vy * stepSize;
      });
      solve();
      bodies.forEach(body => {
        limitVelocity(body);
        if (Math.hypot(body.vx, body.vy) > 7 || Math.hypot(body.x - body.previousX, body.y - body.previousY) > .15) quiet = false;
      });
      idleTime = quiet && !drag ? idleTime + stepSize : 0;
      if (idleTime > .65) {
        sleeping = true;
        bodies.forEach(body => { body.vx = 0; body.vy = 0; });
      }
    }

    function frame(time) {
      raf = 0;
      if (!active || !dialog.open || paused || document.hidden) return;
      if (!lastTime) lastTime = time;
      accumulator = Math.min(.08, accumulator + Math.min(.08, (time - lastTime) / 1000));
      lastTime = time;
      let steps = 0;
      while (accumulator >= stepSize && steps < 10) {
        integrate();
        accumulator -= stepSize;
        steps++;
        if (sleeping) break;
      }
      render();
      if (!sleeping) raf = requestAnimationFrame(frame);
    }

    function endDrag(launch = false) {
      if (!drag) return;
      const previous = drag;
      drag = null;
      previous.body.element.removeAttribute('data-dragging');
      if (launch && !paused && performance.now() - previous.sampleTime < 110) {
        previous.body.vx = previous.vx;
        previous.body.vy = previous.vy;
        limitVelocity(previous.body);
      } else {
        previous.body.vx = 0;
        previous.body.vy = 0;
      }
      if (previous.body.element.hasPointerCapture(previous.id)) previous.body.element.releasePointerCapture(previous.id);
      wake();
    }

    function arrange(note = '') {
      endDrag(false);
      width = arena.clientWidth;
      height = arena.clientHeight;
      if (!width || !height) return;
      let best = { size: 0, columns: 1, rows: bodies.length };
      for (let columns = 1; columns <= Math.min(4, bodies.length); columns++) {
        const rows = Math.ceil(bodies.length / columns);
        const cellWidth = (width - 20) / columns;
        const cellHeight = (height - 20) / rows;
        const size = Math.min(116, ...bodies.map(body => Math.min(cellWidth - 14, (cellHeight - 14) * body.ratio)));
        if (size > best.size) best = { size, columns, rows };
      }
      const cellWidth = (width - 20) / best.columns;
      const cellHeight = (height - 20) / best.rows;
      bodies.forEach((body, index) => {
        body.w = Math.max(12, best.size);
        body.h = body.w / body.ratio;
        body.mass = clamp(body.w * body.h * (body.circle ? Math.PI / 4 : 1) / 7000, .35, 3);
        body.invMass = 1 / body.mass;
        body.element.style.width = `${body.w}px`;
        body.element.style.height = `${body.h}px`;
        body.x = 10 + cellWidth * (index % best.columns + .5);
        body.y = 10 + cellHeight * (Math.floor(index / best.columns) + .5);
        body.vx = 0;
        body.vy = 0;
        contain(body, false);
      });
      render();
      updateReadout(note);
      wake();
    }

    function stopSensor(message = '') {
      sensorToken++;
      clearTimeout(sensorTimer);
      sensorTimer = 0;
      if (sensorListener) window.removeEventListener('deviceorientation', sensorListener);
      sensorListener = null;
      sensorState = 'off';
      sensorButton.disabled = false;
      sensorButton.textContent = '启用倾斜重力';
      sensorButton.setAttribute('aria-pressed', 'false');
      gx = 0;
      gy = 820;
      if (message) sensorStatus.textContent = message;
    }

    async function startSensor() {
      if (sensorState !== 'off') {
        stopSensor('倾斜重力已关闭，恢复向下重力。');
        wake();
        return;
      }
      if (!window.isSecureContext) {
        sensorStatus.textContent = '此环境不能读取传感器；请使用 HTTPS 或受信任的本地环境。拖动与键盘仍可使用。';
        return;
      }
      const orientation = window.DeviceOrientationEvent;
      if (!orientation) {
        sensorStatus.textContent = '此浏览器没有提供方向传感器；请使用拖动或键盘。';
        return;
      }
      const token = ++sensorToken;
      sensorState = 'pending';
      sensorButton.disabled = true;
      sensorStatus.textContent = '正在请求方向传感器；可能需要你在系统提示中允许。';
      try {
        if (typeof orientation.requestPermission === 'function') {
          const result = await orientation.requestPermission();
          if (token !== sensorToken || !active) return;
          if (result !== 'granted') {
            stopSensor('未获得传感器权限；不会读取设备方向。拖动与键盘仍可使用。');
            return;
          }
        }
        if (token !== sensorToken || !active || document.hidden) {
          if (active && token === sensorToken) stopSensor('页面不可见，传感器未启用；返回后可重新启用。');
          return;
        }
        sensorState = 'waiting';
        sensorButton.disabled = false;
        sensorButton.textContent = '关闭倾斜重力';
        sensorButton.setAttribute('aria-pressed', 'true');
        gravityInput.checked = true;
        sensorStatus.textContent = '正在等待方向数据，请轻轻倾斜设备；无可用数据时将自动关闭。';
        sensorListener = event => {
          if (!active || document.hidden || !Number.isFinite(event.beta) || !Number.isFinite(event.gamma)) return;
          if (sensorState === 'waiting') {
            clearTimeout(sensorTimer);
            sensorTimer = 0;
            sensorState = 'on';
            sensorStatus.textContent = paused ? '倾斜重力已启用；模拟仍暂停，点击“继续”后生效。' : '倾斜重力已启用；平放时接近无重力，轻倾设备改变方向。';
          }
          const angle = (window.screen.orientation?.angle || window.orientation || 0) * Math.PI / 180;
          const x = Math.sin(clamp(event.gamma, -90, 90) * Math.PI / 180) * 820;
          const y = Math.sin(clamp(event.beta, -90, 90) * Math.PI / 180) * 820;
          const nextX = x * Math.cos(angle) + y * Math.sin(angle);
          const nextY = y * Math.cos(angle) - x * Math.sin(angle);
          if (Math.hypot(nextX - gx, nextY - gy) > 28) {
            gx = nextX;
            gy = nextY;
            wake();
          }
        };
        window.addEventListener('deviceorientation', sensorListener);
        sensorTimer = window.setTimeout(() => {
          if (active && token === sensorToken && sensorState === 'waiting') {
            stopSensor('未收到可用方向数据，倾斜重力已关闭。设备可能无传感器、权限受限或不受支持。');
            wake();
          }
        }, 4000);
      } catch (error) {
        if (active && token === sensorToken) stopSensor('传感器请求未成功；请检查浏览器权限，也可以继续拖动与键盘把玩。');
      }
    }

    function setPaused(value, note = '') {
      paused = value;
      endDrag(false);
      if (paused) {
        stopFrame();
        if (sensorState !== 'off') stopSensor('模拟已暂停，倾斜重力同时关闭；需要时可重新启用。');
      } else {
        if (sensorState === 'on') sensorStatus.textContent = '倾斜重力已启用；平放时接近无重力，轻倾设备改变方向。';
        wake();
      }
      updateReadout(note);
    }

    function pointerPosition(event) {
      const rect = arena.getBoundingClientRect();
      return { x: event.clientX - rect.left - arena.clientLeft, y: event.clientY - rect.top - arena.clientTop };
    }

    listen(arena, 'pointerdown', event => {
      if (event.button !== 0 || !event.isPrimary || drag) return;
      const body = bodies.find(value => value.element.contains(event.target));
      if (!body) return;
      event.preventDefault();
      select(body);
      body.element.focus({ preventScroll: true });
      const point = pointerPosition(event);
      drag = { id: event.pointerId, body, ox: point.x - body.x, oy: point.y - body.y, tx: body.x, ty: body.y, vx: 0, vy: 0, sampleTime: performance.now() };
      body.vx = 0;
      body.vy = 0;
      body.element.dataset.dragging = 'true';
      try {
        body.element.setPointerCapture(event.pointerId);
      } catch (error) {
        endDrag(false);
        return;
      }
      wake();
    });

    listen(arena, 'pointermove', event => {
      if (!drag || event.pointerId !== drag.id) return;
      event.preventDefault();
      const point = pointerPosition(event);
      const body = drag.body;
      const tx = clamp(point.x - drag.ox, body.w / 2 + 2, width - body.w / 2 - 2);
      const ty = clamp(point.y - drag.oy, body.h / 2 + 2, height - body.h / 2 - 2);
      const now = performance.now();
      const elapsed = (now - drag.sampleTime) / 1000;
      if (elapsed > 0) {
        const weight = elapsed > .12 ? 1 : .65;
        const vx = elapsed > .12 ? 0 : (tx - drag.tx) / Math.max(.008, elapsed);
        const vy = elapsed > .12 ? 0 : (ty - drag.ty) / Math.max(.008, elapsed);
        drag.vx = drag.vx * (1 - weight) + vx * weight;
        drag.vy = drag.vy * (1 - weight) + vy * weight;
      }
      drag.tx = tx;
      drag.ty = ty;
      drag.sampleTime = now;
      if (paused) {
        body.x = tx;
        body.y = ty;
        solve(false);
        render();
      } else wake();
    });
    listen(arena, 'pointerup', event => { if (drag?.id === event.pointerId) endDrag(true); });
    listen(arena, 'pointercancel', event => { if (drag?.id === event.pointerId) endDrag(false); });
    listen(arena, 'lostpointercapture', event => { if (drag?.id === event.pointerId) endDrag(false); });
    listen(arena, 'dragstart', event => event.preventDefault());
    listen(arena, 'focusin', event => {
      const body = bodies.find(value => value.element === event.target);
      if (body && body !== selected) select(body);
    });
    listen(arena, 'click', event => {
      const body = bodies.find(value => value.element.contains(event.target));
      if (body && body !== selected) select(body);
    });
    listen(dialog, 'keydown', event => {
      if (event.key === 'Escape') event.stopPropagation();
    });
    listen(arena, 'keydown', event => {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.code === 'Space' || event.key === ' ') {
        event.preventDefault();
        if (!event.repeat) setPaused(!paused);
        return;
      }
      const body = bodies.find(value => value.element === event.target) || selected;
      if (!body) return;
      if (event.key === 'Enter') {
        event.preventDefault();
        select(body);
        return;
      }
      const moves = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
      const move = moves[event.key];
      if (!move) return;
      event.preventDefault();
      endDrag(false);
      const distance = event.shiftKey ? 28 : 12;
      body.x += move[0] * distance;
      body.y += move[1] * distance;
      body.vx = 0;
      body.vy = 0;
      contain(body, false);
      solve(false);
      render();
      wake();
    });
    listen(resetButton, 'click', () => arrange('已重新摆放'));
    listen(pauseButton, 'click', () => setPaused(!paused));
    listen(closeButton, 'click', () => dialog.close());
    listen(gravityInput, 'change', () => {
      if (!gravityInput.checked && sensorState !== 'off') stopSensor('重力已关闭，停止读取设备方向。');
      updateReadout(gravityInput.checked ? '重力开启' : '重力关闭');
      wake();
    });
    listen(bounceInput, 'input', () => {
      restitution = Number(bounceInput.value) / 100;
      bounceOutput.value = `${bounceInput.value}%`;
      bounceInput.setAttribute('aria-valuetext', `${bounceInput.value}% 回弹`);
      wake();
    });
    listen(sensorButton, 'click', startSensor);
    listen(document, 'visibilitychange', () => {
      if (document.hidden) {
        endDrag(false);
        stopFrame();
        if (sensorState !== 'off') stopSensor('页面已隐藏，倾斜重力已关闭；返回后需主动重新启用。');
      } else wake();
    });
    listen(window, 'blur', () => endDrag(false));
    const motionChange = event => {
      if (event.matches) setPaused(true, '已遵循系统减少动态效果设置');
    };
    if (motion.addEventListener) motion.addEventListener('change', motionChange);
    else motion.addListener(motionChange);

    const previousOverflow = document.body.style.overflow;
    function cleanup() {
      if (!active) return;
      active = false;
      endDrag(false);
      stopFrame();
      stopSensor();
      resizeObserver?.disconnect();
      if (motion.removeEventListener) motion.removeEventListener('change', motionChange);
      else motion.removeListener(motionChange);
      lifetime.abort();
      dialog.remove();
      document.body.style.overflow = previousOverflow;
      opened = false;
      if (trigger.isConnected) trigger.focus({ preventScroll: true });
    }
    listen(dialog, 'close', cleanup);
    listen(window, 'pagehide', () => { dialog.close(); cleanup(); });
    document.body.append(dialog);
    try {
      dialog.showModal();
    } catch (error) {
      cleanup();
      api.showHint?.('展台暂时无法打开，请关闭其他弹窗后重试。');
      return;
    }
    opened = true;
    document.body.style.overflow = 'hidden';
    bodies.forEach(body => {
      const parts = getComputedStyle(body.card).aspectRatio.split('/').map(value => Number(value.trim()));
      const ratio = parts.length === 2 ? parts[0] / parts[1] : parts[0];
      if (Number.isFinite(ratio) && ratio > 0) body.ratio = clamp(ratio, .4, 2);
      if (body.circle) body.ratio = 1;
    });
    arrange(paused ? '系统减少动态效果已开启；可主动继续' : '拖动藏品开始把玩');
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => {
        if (active && (arena.clientWidth !== width || arena.clientHeight !== height)) arrange('展台尺寸变化，已重新摆放');
      });
      resizeObserver.observe(arena);
    } else {
      listen(window, 'resize', () => arrange('展台尺寸变化，已重新摆放'));
    }
  }

  document.addEventListener('click', event => {
    const trigger = event.target.closest?.('#physicsBtn');
    if (trigger) openPlayground(trigger);
  });
})();
