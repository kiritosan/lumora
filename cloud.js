(() => {
  'use strict';

  const CONFIG_KEY = 'shiguang.sync.config';
  const PENDING_KEY = 'shiguang.sync.pending';
  const PUSH_DELAY = 2500;
  const META_ID = '__meta__';

  function sha256(bytes) {
    const msg = Array.from(bytes);
    const bitLen = msg.length * 8;
    msg.push(0x80);
    while (msg.length % 64 !== 56) msg.push(0);
    for (let i = 7; i >= 0; i--) msg.push((bitLen / Math.pow(256, i)) & 0xff);
    const K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    const rotr = (x, n) => (x >>> n) | (x << (32 - n));
    for (let i = 0; i < msg.length; i += 64) {
      const w = new Array(64);
      for (let t = 0; t < 16; t++) w[t] = (msg[i + t * 4] << 24) | (msg[i + t * 4 + 1] << 16) | (msg[i + t * 4 + 2] << 8) | msg[i + t * 4 + 3];
      for (let t = 16; t < 64; t++) {
        const s0 = rotr(w[t - 15], 7) ^ rotr(w[t - 15], 18) ^ (w[t - 15] >>> 3);
        const s1 = rotr(w[t - 2], 17) ^ rotr(w[t - 2], 19) ^ (w[t - 2] >>> 10);
        w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
      }
      let [a, b, c, d, e, f, g, h] = H;
      for (let t = 0; t < 64; t++) {
        const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        const ch = (e & f) ^ (~e & g);
        const t1 = (h + s1 + ch + K[t] + w[t]) | 0;
        const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (s0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    return H.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
  }

  const utf8 = new TextEncoder();
  const utf8d = new TextDecoder();
  const b64 = buffer => btoa(String.fromCharCode(...new Uint8Array(buffer)));
  const unb64 = text => Uint8Array.from(atob(text), c => c.charCodeAt(0));
  const hexOf = text => sha256(utf8.encode(text));

  let api = null;
  let config = loadConfig();
  let pending = loadPending();
  let syncing = null;
  let pushTimer = 0;
  let cryptoKey = null;
  let metaData = null;

  function loadConfig() {
    try { return JSON.parse(localStorage.getItem(CONFIG_KEY)) || null; } catch (error) { return null; }
  }
  function saveConfig(value) {
    config = value;
    if (value) localStorage.setItem(CONFIG_KEY, JSON.stringify(value));
    else localStorage.removeItem(CONFIG_KEY);
  }
  function loadPending() {
    try {
      const raw = JSON.parse(localStorage.getItem(PENDING_KEY));
      return { dirty: new Set(raw?.dirty || []), deleted: raw?.deleted || {} };
    } catch (error) { return { dirty: new Set(), deleted: {} }; }
  }
  function savePending() {
    localStorage.setItem(PENDING_KEY, JSON.stringify({ dirty: [...pending.dirty], deleted: pending.deleted }));
  }

  function configured() { return !!(config && config.url && config.anon && config.key); }
  function endpoint() { return config.url.replace(/\/+$/, '') + '/rest/v1/shiguang_items'; }
  function headers() {
    return { apikey: config.anon, Authorization: `Bearer ${config.anon}`, 'Content-Type': 'application/json' };
  }
  async function request(path, options = {}) {
    const res = await fetch(endpoint() + path, { ...options, headers: { ...headers(), ...(options.headers || {}) } });
    if (!res.ok) {
      let detail = '';
      try {
        const body = await res.json();
        detail = body.message || body.hint || body.details || '';
      } catch (error) {}
      const hint = { 401: '（anon 密钥不正确，请核对 Project Settings → API Keys）', 404: '（表不存在，请先在 SQL Editor 运行 supabase-setup.sql）', 403: '（权限不足）' }[res.status] || '';
      throw new Error(`云端响应 ${res.status}${detail ? `：${detail}` : hint}`);
    }
    return res;
  }

  async function collectionId() { return 'c' + hexOf(config.key); }

  async function dataKey() {
    if (cryptoKey) return cryptoKey;
    if (!crypto.subtle) return null;
    const raw = utf8.encode('shiguang-data:' + config.key);
    const digest = await crypto.subtle.digest('SHA-256', raw);
    cryptoKey = await crypto.subtle.importKey('raw', digest, 'AES-GCM', false, ['encrypt', 'decrypt']);
    return cryptoKey;
  }

  async function packItem(item) {
    const json = JSON.stringify(item);
    const key = await dataKey();
    if (!key) return { v: 1, enc: 'none', json };
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, utf8.encode(json));
    return { v: 1, enc: 'gcm', iv: b64(iv), ct: b64(ct) };
  }

  async function unpackItem(data) {
    if (!data || data.v !== 1) return null;
    if (data.enc === 'none') return JSON.parse(data.json);
    if (data.enc !== 'gcm' || !crypto.subtle) return null;
    const key = await dataKey();
    if (!key) return null;
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(data.iv) }, key, unb64(data.ct));
    return JSON.parse(utf8d.decode(plain));
  }

  function status(text, tone = 'info') { api?.onStatus?.(text, tone); }

  async function pull(cid) {
    const res = await request(`?collection=eq.${encodeURIComponent(cid)}&select=id,data,updated,deleted`);
    return res.json();
  }

  async function push(cid, rows) {
    if (!rows.length) return;
    await request('', {
      method: 'POST',
      headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
      body: JSON.stringify(rows)
    });
  }

  async function syncNow() {
    if (!configured() || !api) return false;
    if (syncing) return syncing;
    syncing = (async () => {
      status('正在同步…');
      try {
        const cid = await collectionId();
        const remote = await pull(cid);
        const remoteMap = new Map(remote.map(row => [row.id, row]));
        let applied = 0, failed = 0;
        for (const row of remote) {
          if (row.id === META_ID) {
            try {
              const meta = await unpackItem(row.data);
              if (meta) api.applyMeta?.(meta);
            } catch (error) { failed++; }
            continue;
          }
          const local = api.getItem(row.id);
          if (row.deleted) {
            if (local && (local.updated || local.created || 0) <= row.updated) { api.removeLocal(row.id); applied++; }
            continue;
          }
          try {
            const item = await unpackItem(row.data);
            if (!item || !item.id) { failed++; continue; }
            if (!local || (item.updated || item.created || 0) > (local.updated || local.created || 0)) {
              api.upsertLocal(item);
              applied++;
            }
          } catch (error) { failed++; }
        }
        const rows = [];
        for (const id of pending.dirty) {
          if (id === META_ID) {
            if (metaData) rows.push({ collection: cid, id, data: await packItem(metaData), updated: Date.now(), deleted: false });
            continue;
          }
          const item = api.getItem(id);
          if (!item) { pending.deleted[id] = Date.now(); continue; }
          rows.push({ collection: cid, id, data: await packItem(item), updated: item.updated || item.created || Date.now(), deleted: false });
        }
        for (const [id, ts] of Object.entries(pending.deleted)) {
          rows.push({ collection: cid, id, data: { v: 1, enc: 'none', json: '{}' }, updated: ts, deleted: true });
        }
        await push(cid, rows);
        pending.dirty.clear();
        pending.deleted = {};
        savePending();
        config.lastSync = Date.now();
        saveConfig(config);
        status(`云端同步完成 · ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}${applied ? ` · 更新 ${applied} 件` : ''}${failed ? ` · ${failed} 件无法解密（密钥可能不一致）` : ''}`);
        return true;
      } catch (error) {
        status(`同步失败：${error.message === 'Failed to fetch' ? '无法连接云端，请检查网络与配置' : error.message}`, 'error');
        return false;
      } finally { syncing = null; }
    })();
    return syncing;
  }

  function schedulePush() {
    if (!configured()) return;
    clearTimeout(pushTimer);
    pushTimer = setTimeout(syncNow, PUSH_DELAY);
  }

  window.shiguangCloud = {
    init(module) {
      api = module;
      if (configured()) syncNow();
      window.addEventListener('online', () => { if (configured()) syncNow(); });
      document.addEventListener('visibilitychange', () => {
        if (!document.hidden && configured() && Date.now() - (config.lastSync || 0) > 20000) syncNow();
      });
    },
    isConfigured: configured,
    getConfig: () => config ? { ...config } : null,
    encrypted: () => !!crypto.subtle,
    setConfig(value) {
      cryptoKey = null;
      saveConfig(value);
      if (configured()) syncNow();
    },
    disconnect() {
      clearTimeout(pushTimer);
      cryptoKey = null;
      saveConfig(null);
      status('已断开云同步，本地数据不受影响');
    },
    notifyChange(item) {
      pending.dirty.add(item.id);
      delete pending.deleted[item.id];
      savePending();
      schedulePush();
    },
    notifyDelete(id) {
      pending.dirty.delete(id);
      pending.deleted[id] = Date.now();
      savePending();
      schedulePush();
    },
    notifyMeta(data) {
      metaData = data;
      pending.dirty.add(META_ID);
      savePending();
      schedulePush();
    },
    syncNow,
    generateKey() {
      const bytes = new Uint8Array(18);
      if (crypto.getRandomValues) crypto.getRandomValues(bytes);
      else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
      return b64(bytes).replace(/[+/=]/g, '').slice(0, 24);
    }
  };
})();
