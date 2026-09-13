(() => {
  'use strict';

  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const profiles = {
    glass: { frequency: 1180, ratio: 2.01, decay: .28, noise: .04, cutoff: 4200, wave: 'sine' },
    metal: { frequency: 640, ratio: 2.7, decay: .37, noise: .08, cutoff: 2600, wave: 'sine' },
    brushed: { frequency: 460, ratio: 2.3, decay: .29, noise: .16, cutoff: 1900, wave: 'sine' },
    paper: { frequency: 155, ratio: 1.5, decay: .075, noise: .65, cutoff: 750, wave: 'sine' },
    matte: { frequency: 215, ratio: 2, decay: .105, noise: .35, cutoff: 950, wave: 'sine' },
    silk: { frequency: 310, ratio: 2, decay: .14, noise: .24, cutoff: 1200, wave: 'sine' },
    holo: { frequency: 740, ratio: 1.5, decay: .25, noise: .07, cutoff: 3000, wave: 'triangle' },
    pearl: { frequency: 520, ratio: 2, decay: .2, noise: .1, cutoff: 1800, wave: 'sine' },
    prismatic: { frequency: 960, ratio: 2, decay: .32, noise: .03, cutoff: 3800, wave: 'sine' }
  };
  const notes = [0, 2, 4, 7, 9, 12, 14, 16, 19, 21];

  window.createImpactFeedback = ({ arena, container }) => {
    const lifetime = new AbortController();
    const listen = (target, type, handler) => target.addEventListener(type, handler, { signal: lifetime.signal });
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const modeLabel = node('label', 'physics-control feedback-control');
    const modeInput = node('select');
    modeInput.id = 'impactSoundMode';
    modeLabel.htmlFor = modeInput.id;
    [['off', '静音'], ['material', '材质音'], ['music', '音乐音']].forEach(([value, text]) => {
      const option = node('option', '', text);
      option.value = value;
      modeInput.append(option);
    });
    modeInput.value = 'off';
    modeLabel.append(node('span', '', '碰撞音效'), modeInput);
    const volumeLabel = node('label', 'physics-control feedback-control');
    const volumeInput = node('input');
    volumeInput.id = 'impactVolume';
    volumeInput.type = 'range';
    volumeInput.min = '0';
    volumeInput.max = '100';
    volumeInput.step = '1';
    volumeInput.value = '18';
    volumeInput.setAttribute('aria-label', '音效音量');
    volumeInput.setAttribute('aria-valuetext', '18%');
    volumeLabel.htmlFor = volumeInput.id;
    const volumeOutput = node('output', '', '18%');
    volumeOutput.htmlFor = volumeInput.id;
    volumeLabel.append(node('span', '', '音效音量'), volumeInput, volumeOutput);
    const glowLabel = node('label', 'physics-control feedback-control');
    const glowInput = node('input');
    glowInput.id = 'impactGlow';
    glowInput.type = 'checkbox';
    glowInput.checked = !motion.matches;
    glowInput.disabled = motion.matches;
    glowLabel.htmlFor = glowInput.id;
    glowLabel.append(glowInput, node('span', '', '碰撞微光'));
    const status = node('p', 'physics-sensor-status physics-feedback-status');
    status.id = 'impactFeedbackStatus';
    status.setAttribute('role', 'status');
    modeInput.setAttribute('aria-describedby', status.id);
    glowInput.setAttribute('aria-describedby', status.id);
    container.append(modeLabel, volumeLabel, glowLabel, status);

    let destroyed = false;
    let context = null;
    let master = null;
    let noiseBuffer = null;
    let mode = 'off';
    let volume = .18;
    let pending = false;
    let request = 0;
    let lastHit = -Infinity;
    const cooldowns = new Map();
    const voices = new Set();
    const glows = new Set();
    const explanation = '音效为本机离线合成，并非藏品实物录音；仅主动选择后开启。';

    function updateStatus(message = '') {
      if (destroyed) return;
      const sound = pending ? '正在启用音效。' : mode === 'off' ? '当前静音。' : volume === 0 ? '音量为零，当前无声。' : `${mode === 'music' ? '音乐音' : '材质音'}已开启。`;
      const glow = motion.matches ? '系统减少动态效果已开启，微光已禁用。' : !glowInput.checked ? '微光已关闭，可手动开启。' : '';
      status.textContent = `${explanation}${sound}${glow}${message}`;
    }

    function removeGlow(effect) {
      glows.delete(effect);
      effect.animation.onfinish = null;
      effect.animation.oncancel = null;
      effect.animation.cancel();
      effect.element.remove();
    }

    function clearGlows() {
      Array.from(glows).forEach(removeGlow);
    }

    function releaseVoice(voice) {
      if (!voices.delete(voice)) return;
      voice.sources.forEach(source => { source.onended = null; });
      voice.nodes.forEach(value => {
        try { value.disconnect(); } catch (error) {}
      });
    }

    function stopVoices() {
      voices.forEach(voice => {
        if (voice.stopping) return;
        voice.stopping = true;
        const now = voice.context.currentTime;
        try {
          if (typeof voice.gain.gain.cancelAndHoldAtTime === 'function') voice.gain.gain.cancelAndHoldAtTime(now);
          else {
            const elapsed = Math.max(0, now - voice.start);
            const level = elapsed < .007 ? voice.level * elapsed / .007 : elapsed < voice.duration ? voice.level * Math.pow(.0001 / voice.level, (elapsed - .007) / (voice.duration - .007)) : .0001 * clamp(1 - (elapsed - voice.duration) / .012, 0, 1);
            voice.gain.gain.cancelScheduledValues(now);
            voice.gain.gain.setValueAtTime(level, now);
          }
          voice.gain.gain.linearRampToValueAtTime(0, now + .008);
          voice.sources.forEach(source => { try { source.stop(now + .009); } catch (error) {} });
        } catch (error) {
          voice.sources.forEach(source => { try { source.stop(); } catch (stopError) {} });
          releaseVoice(voice);
        }
        if (voice.context.state !== 'running') releaseVoice(voice);
      });
    }

    function silence() {
      request += 1;
      if (pending) {
        pending = false;
        mode = 'off';
        modeInput.value = 'off';
        updateStatus('音效启用已取消，请重新选择。');
      }
      stopVoices();
      clearGlows();
    }

    async function chooseMode() {
      const chosen = modeInput.value;
      silence();
      modeInput.value = chosen;
      mode = 'off';
      if (destroyed || (chosen !== 'material' && chosen !== 'music') || document.hidden) {
        modeInput.value = 'off';
        updateStatus();
        return;
      }
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (!AudioContext) {
        modeInput.value = 'off';
        updateStatus('当前浏览器不支持合成音效，微光仍可单独使用。');
        return;
      }
      const token = ++request;
      pending = true;
      updateStatus();
      try {
        if (!context || context.state === 'closed') {
          context = new AudioContext();
          master = context.createGain();
          master.gain.setValueAtTime(volume, context.currentTime);
          master.connect(context.destination);
          noiseBuffer = null;
        }
        const current = context;
        if (current.state !== 'running') await current.resume();
        if (destroyed || token !== request || current !== context) return;
        if (document.hidden || current.state !== 'running') throw new Error('Audio unavailable');
        pending = false;
        mode = chosen === 'music' ? 'music' : 'material';
        updateStatus();
      } catch (error) {
        if (destroyed || token !== request) return;
        pending = false;
        mode = 'off';
        modeInput.value = 'off';
        stopVoices();
        updateStatus('音效未能启用，请在此页面重新选择后再试。');
      }
    }

    function createGlow(x, y, strength) {
      if (!glowInput.checked || motion.matches || glows.size >= 6 || typeof arena.animate !== 'function') return;
      const width = arena.clientWidth;
      const height = arena.clientHeight;
      if (width <= 0 || height <= 0) return;
      const element = node('span', 'physics-impact-glow');
      element.setAttribute('aria-hidden', 'true');
      const size = 14 + strength * 10;
      element.style.cssText = `position:absolute;pointer-events:none;z-index:4;width:${size}px;height:${size}px;left:${clamp(x, 0, width)}px;top:${clamp(y, 0, height)}px;border:1px solid #e8c991;border-radius:50%;box-shadow:0 0 7px #e8c99144;opacity:0;`;
      arena.append(element);
      try {
        const animation = element.animate([
          { opacity: 0, transform: 'translate(-50%, -50%) scale(.45)', offset: 0 },
          { opacity: .2 + strength * .25, transform: 'translate(-50%, -50%) scale(.7)', offset: .15 },
          { opacity: 0, transform: 'translate(-50%, -50%) scale(1.55)', offset: 1 }
        ], { duration: 240 + strength * 120, easing: 'ease-out' });
        const effect = { element, animation };
        glows.add(effect);
        animation.onfinish = () => removeGlow(effect);
        animation.oncancel = () => removeGlow(effect);
      } catch (error) {
        element.remove();
      }
    }

    function playSound(x, strength, material, index) {
      if (mode === 'off' || volume === 0 || !context || context.state !== 'running' || !master || voices.size >= 8) return;
      const current = context;
      const now = current.currentTime;
      const profile = Object.prototype.hasOwnProperty.call(profiles, material) ? profiles[material] : profiles.matte;
      const music = mode === 'music';
      const noteIndex = Number.isFinite(index) ? ((Math.trunc(index) % notes.length) + notes.length) % notes.length : 0;
      const frequency = music ? 261.6256 * Math.pow(2, notes[noteIndex] / 12) : profile.frequency * (.94 + strength * .12);
      const duration = (music ? .32 : profile.decay) * (.7 + strength * .6);
      const level = Math.min(.1, .024 + strength * .076);
      const voice = { context: current, sources: [], nodes: [], gain: null, level, start: now, duration, stopping: false };
      const track = value => { voice.nodes.push(value); return value; };
      try {
        const envelope = track(current.createGain());
        voice.gain = envelope;
        envelope.gain.setValueAtTime(0, now);
        envelope.gain.linearRampToValueAtTime(level, now + .007);
        envelope.gain.exponentialRampToValueAtTime(.0001, now + duration);
        envelope.gain.linearRampToValueAtTime(0, now + duration + .012);
        if (typeof current.createStereoPanner === 'function') {
          const panner = track(current.createStereoPanner());
          const width = arena.clientWidth;
          panner.pan.setValueAtTime(width > 0 ? clamp(x / width * 2 - 1, -.85, .85) : 0, now);
          envelope.connect(panner);
          panner.connect(master);
        } else envelope.connect(master);
        const noise = music ? 0 : profile.noise;
        [1, music ? 2 : profile.ratio].forEach((ratio, part) => {
          const oscillator = track(current.createOscillator());
          const gain = track(current.createGain());
          oscillator.type = music ? 'sine' : profile.wave;
          oscillator.frequency.setValueAtTime(clamp(frequency * ratio, 60, current.sampleRate * .45), now);
          gain.gain.setValueAtTime((1 - noise) * (part === 0 ? .8 : .2), now);
          oscillator.connect(gain);
          gain.connect(envelope);
          voice.sources.push(oscillator);
        });
        if (noise > 0) {
          if (!noiseBuffer) {
            noiseBuffer = current.createBuffer(1, Math.ceil(current.sampleRate * .6), current.sampleRate);
            const data = noiseBuffer.getChannelData(0);
            for (let i = 0; i < data.length; i += 1) data[i] = Math.random() * 2 - 1;
          }
          const source = track(current.createBufferSource());
          const filter = track(current.createBiquadFilter());
          const gain = track(current.createGain());
          source.buffer = noiseBuffer;
          filter.type = 'lowpass';
          filter.frequency.setValueAtTime(Math.min(profile.cutoff, current.sampleRate * .45), now);
          filter.Q.setValueAtTime(.5, now);
          gain.gain.setValueAtTime(noise, now);
          source.connect(filter);
          filter.connect(gain);
          gain.connect(envelope);
          voice.sources.push(source);
        }
        voices.add(voice);
        let remaining = voice.sources.length;
        voice.sources.forEach(source => {
          source.onended = () => {
            remaining -= 1;
            if (remaining === 0) releaseVoice(voice);
          };
          source.start(now);
          source.stop(now + duration + .014);
        });
      } catch (error) {
        voice.sources.forEach(source => { try { source.stop(); } catch (stopError) {} });
        voices.add(voice);
        releaseVoice(voice);
        mode = 'off';
        modeInput.value = 'off';
        stopVoices();
        updateStatus('合成音效暂不可用，请重新选择后再试。');
      }
    }

    function hit({ key, x, y, speed, material, index } = {}) {
      if (destroyed || document.hidden || !Number.isFinite(speed) || speed < 70 || !Number.isFinite(x) || !Number.isFinite(y)) return;
      if ((typeof key !== 'string' && typeof key !== 'number') || (typeof key === 'number' && !Number.isFinite(key))) return;
      if ((mode === 'off' || volume === 0 || !context || context.state !== 'running') && (!glowInput.checked || motion.matches)) return;
      const now = performance.now();
      if (now - lastHit < 70 || now - (cooldowns.get(key) ?? -Infinity) < 150) return;
      lastHit = now;
      cooldowns.delete(key);
      if (cooldowns.size >= 64) cooldowns.delete(cooldowns.keys().next().value);
      cooldowns.set(key, now);
      const strength = (clamp(speed, 70, 850) - 70) / 780;
      createGlow(x, y, strength);
      playSound(x, strength, material, index);
    }

    listen(modeInput, 'change', chooseMode);
    listen(volumeInput, 'input', () => {
      const value = Number(volumeInput.value);
      volume = Number.isFinite(value) ? clamp(value, 0, 100) / 100 : 0;
      volumeOutput.value = `${Math.round(volume * 100)}%`;
      volumeInput.setAttribute('aria-valuetext', volumeOutput.value);
      if (volume === 0) stopVoices();
      if (master && context && context.state !== 'closed') {
        master.gain.cancelScheduledValues(context.currentTime);
        master.gain.setTargetAtTime(volume, context.currentTime, .008);
      }
      updateStatus();
    });
    listen(glowInput, 'change', () => {
      if (motion.matches) glowInput.checked = false;
      if (!glowInput.checked) clearGlows();
      updateStatus();
    });
    listen(document, 'visibilitychange', () => { if (document.hidden) silence(); });
    const motionChange = () => {
      glowInput.disabled = motion.matches;
      if (motion.matches) {
        glowInput.checked = false;
        silence();
      }
      updateStatus();
    };
    if (motion.addEventListener) motion.addEventListener('change', motionChange);
    else motion.addListener(motionChange);

    function destroy() {
      if (destroyed) return;
      destroyed = true;
      silence();
      lifetime.abort();
      if (motion.removeEventListener) motion.removeEventListener('change', motionChange);
      else motion.removeListener(motionChange);
      voices.forEach(releaseVoice);
      cooldowns.clear();
      [modeLabel, volumeLabel, glowLabel, status].forEach(element => element.remove());
      const closing = context;
      context = null;
      master = null;
      noiseBuffer = null;
      if (closing && closing.state !== 'closed') {
        try { Promise.resolve(closing.close()).catch(() => {}); } catch (error) {}
      }
    }

    updateStatus();
    return { hit, silence, destroy };
  };
})();
