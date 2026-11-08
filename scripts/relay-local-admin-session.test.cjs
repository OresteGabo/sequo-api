const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const source = fs.readFileSync('src/main/resources/static/relay-operator.js', 'utf8');

function storage(values = {}) {
  const valuesByKey = new Map(Object.entries(values));
  return { getItem: key => valuesByKey.get(key) ?? null,
    setItem: (key, value) => valuesByKey.set(key, String(value)), removeItem: key => valuesByKey.delete(key) };
}
function harness(options = {}) {
  const nodes = new Map(); const calls = []; let sequence = 0; let expired = options.expired;
  function element(id) {
    if (!nodes.has(id)) nodes.set(id, { id, hidden: id !== 'login', value: '', checked: false, dataset: {}, textContent: '',
      addEventListener() {}, append() {}, replaceChildren(...children) { this.children = children; if (id === 'site') this.value = children[0]?.value ?? ''; } });
    return nodes.get(id);
  }
  const session = storage(options.session ?? { sequo_admin_access_token: 'admin-access', sequo_admin_refresh_token: 'admin-refresh' });
  const local = storage(options.local ?? { sequo_admin_device_id: 'admin-browser-device' });
  const response = (data, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => data });
  const fetch = async (path, init = {}) => {
    calls.push({ path, ...init, body: init.body ? JSON.parse(init.body) : undefined });
    if (path === 'http://127.0.0.1:55840/api/admin-auth/development-access') {
      return response({ enabled: options.enabled !== false, mode: 'LOCAL_DEVELOPMENT' });
    }
    if (path === '/api/auth/refresh') return response({ accessToken: 'rotated-access', refreshToken: 'rotated-refresh' }, options.refreshStatus ?? 200);
    if (path === '/api/auth/me') {
      if (options.offline) throw Error('offline');
      if (expired) { expired = false; return response({}, 401); }
      return response({ active: true, status: 'ACTIVE', roles: options.roles ?? ['SUPER_ADMIN'] });
    }
    if (path === '/api/relay/parcels/sites') return response([{ id: 'site-test', name: 'Synthetic site' }]);
    if (path.startsWith('/api/relay/') || path.startsWith('/api/returns/relay')) return response([]);
    return response({});
  };
  const context = vm.createContext({ console, fetch, sessionStorage: session, localStorage: local,
    location: { protocol: 'http:', hostname: '127.0.0.1', port: '5173', search: '?session=local-admin', ...options.location },
    crypto: { randomUUID: () => `fixture-${++sequence}` }, URLSearchParams, window: { confirm: () => true },
    document: { getElementById: element, createElement: tag => element(`created-${tag}-${++sequence}`), querySelectorAll: () => [] } });
  vm.runInContext(source, context);
  return { calls, session, local, element, context, async flush() {
    for (let i = 0; i < 6; i++) await new Promise(setImmediate);
    assert.equal(vm.runInContext('busy', context), false);
  } };
}

test('local same-tab mode validates server capability and current admin, without copying relay credentials', async () => {
  const h = harness(); await h.flush();
  assert.equal(h.element('workspace').hidden, false);
  assert.equal(h.element('login').hidden, true);
  const capability = h.calls.find(c => c.path.includes('development-access'));
  assert.equal(capability.headers?.Authorization, undefined);
  assert.equal(h.calls.find(c => c.path === '/api/auth/me').headers.Authorization, 'Bearer admin-access');
  assert.equal(h.calls.find(c => c.path.endsWith('/sites')).headers.Authorization, 'Bearer admin-access');
  assert.equal(h.session.getItem('sequo_relay_refresh'), null);
  assert.equal(h.calls.some(c => c.path.includes('whatsapp') || c.path.includes('development-session')), false);
});

test('admin expiry rotates the original admin device/session and stores canonical tokens', async () => {
  const h = harness({ expired: true }); await h.flush();
  assert.deepEqual(h.calls.find(c => c.path === '/api/auth/refresh').body,
    { refreshToken: 'admin-refresh', deviceId: 'admin-browser-device' });
  assert.equal(h.session.getItem('sequo_admin_access_token'), 'rotated-access');
  assert.equal(h.session.getItem('sequo_admin_refresh_token'), 'rotated-refresh');
  assert.equal(h.session.getItem('sequo_relay_refresh'), null);
  assert.equal(h.element('workspace').hidden, false);
  assert.equal(h.calls.filter(c => c.path === '/api/auth/me').length, 2);
});

test('missing admin tokens or disabled server capability never opens a workspace or starts OTP', async () => {
  for (const options of [{ session: {} }, { enabled: false }]) {
    const h = harness(options); await h.flush();
    assert.equal(h.element('workspace').hidden, true);
    assert.equal(h.element('login').hidden, false);
    assert.equal(h.calls.some(c => c.path.startsWith('/api/')), false);
  }
});

test('non-admin identity and rejected refresh fail closed and clear shared admin tokens', async () => {
  for (const options of [{ roles: ['CUSTOMER'] }, { expired: true, refreshStatus: 401 }]) {
    const h = harness(options); await h.flush();
    assert.equal(h.element('workspace').hidden, true);
    assert.equal(h.session.getItem('sequo_admin_access_token'), null);
    assert.equal(h.session.getItem('sequo_admin_refresh_token'), null);
    assert.equal(h.calls.some(c => c.path.endsWith('/sites')), false);
  }
});

test('offline bootstrap hides workspace while retaining valid shared credentials for retry', async () => {
  const h = harness({ offline: true }); await h.flush();
  assert.equal(h.element('workspace').hidden, true);
  assert.equal(h.session.getItem('sequo_admin_access_token'), 'admin-access');
  assert.equal(h.session.getItem('sequo_admin_refresh_token'), 'admin-refresh');
});

test('shared admin logout revokes the current rotated session without a redundant authenticated refresh', async () => {
  const h = harness({ expired: true }); await h.flush();
  h.element('logout').onclick(); await h.flush();
  const logout = h.calls.find(c => c.path === '/api/auth/logout');
  assert.deepEqual(logout.body, { refreshToken: 'rotated-refresh', deviceId: 'admin-browser-device' });
  assert.equal(logout.headers.Authorization, undefined);
  assert.equal(h.session.getItem('sequo_admin_access_token'), null);
  assert.equal(h.element('workspace').hidden, true);
});

test('production, direct API and ordinary URLs retain the independent phone session flow', async () => {
  for (const location of [{ hostname: 'admin.example.com', protocol: 'https:', port: '' }, { port: '55840' }, { search: '' }]) {
    const h = harness({ location, session: { sequo_relay_refresh: 'relay-refresh' } }); await h.flush();
    assert.equal(h.calls.some(c => c.path.includes('development-access') || c.path === '/api/auth/me'), false);
    const refresh = h.calls.find(c => c.path === '/api/auth/refresh');
    assert.equal(refresh.body.refreshToken, 'relay-refresh');
    assert.equal(refresh.body.deviceId, h.session.getItem('sequo_relay_device'));
    assert.equal(h.session.getItem('sequo_admin_access_token'), null);
    assert.equal(h.element('workspace').hidden, false);
  }
});
