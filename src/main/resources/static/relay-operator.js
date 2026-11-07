'use strict';
const el = id => document.getElementById(id);
let accessToken = '';
let selected = null;
let busy = false;
let releaseOperation = null;
const deviceId = sessionStorage.getItem('sequo_relay_device') || crypto.randomUUID();
sessionStorage.setItem('sequo_relay_device', deviceId);
const device = { deviceId, appSource: 'SEQUO_HUB', deviceLabel: 'Relay web' };
function message(text) { el('message').textContent = text; }
function clearSession() {
  accessToken = ''; selected = null; releaseOperation = null;
  sessionStorage.removeItem('sequo_relay_refresh');
  el('workspace').hidden = true; el('login').hidden = false; el('detail').hidden = true;
  el('parcels').replaceChildren(); el('site').replaceChildren(); el('history').replaceChildren();
  el('parcel-title').textContent = ''; el('parcel-state').textContent = '';
  for (const field of ['otp', 'numeric', 'qr-new', 'pickup', 'qr-pickup', 'reason']) el(field).value = '';
  el('identity').checked = false;
}
async function request(path, options = {}, authenticated = true) {
  const response = await fetch(path, { ...options, cache: 'no-store', headers: {
    ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(authenticated ? { Authorization: `Bearer ${accessToken}` } : {}),
  } });
  if (!response.ok) {
    if (authenticated && [401, 403].includes(response.status)) { clearSession(); throw Error('Session expirée ou accès refusé. Reconnectez-vous.'); }
    let error; try { error = await response.json(); } catch (_) { /* Empty error response. */ }
    throw Error(error?.message || `Opération refusée (${response.status}). Actualisez avant de réessayer.`);
  }
  return response.status === 204 ? null : response.json();
}
const post = (path, body, authenticated = true) => request(path, { method: 'POST', body: JSON.stringify(body) }, authenticated);
async function run(operation) {
  if (busy) return;
  busy = true; document.querySelectorAll('button').forEach(button => { button.disabled = true; });
  message('Chargement…');
  try { await operation(); message('À jour.'); }
  catch (error) { message(`${error.message} Les données affichées peuvent être anciennes.`); }
  finally { busy = false; document.querySelectorAll('button').forEach(button => { button.disabled = false; }); }
}
function bind(id, operation) { el(id).addEventListener('submit', event => { event.preventDefault(); void run(operation); }); }
function saveTokens(tokens) { accessToken = tokens.accessToken; sessionStorage.setItem('sequo_relay_refresh', tokens.refreshToken); }
async function openWorkspace() {
  const sites = await request('/api/relay/parcels/sites');
  if (!sites.length) { clearSession(); throw Error('Aucun point relais actif attribué à ce compte. Contactez SEQUO.'); }
  el('site').replaceChildren(...sites.map(site => { const option = document.createElement('option'); option.value = site.id; option.textContent = site.name; return option; }));
  el('login').hidden = true; el('workspace').hidden = false;
  await reload();
}
async function reload() {
  selected = null; el('detail').hidden = true; el('parcels').replaceChildren();
  const parcels = await request(`/api/relay/parcels?relayPointId=${encodeURIComponent(el('site').value)}`);
  el('empty').hidden = parcels.length !== 0;
  for (const parcel of parcels) {
    const item = document.createElement('li'); const button = document.createElement('button');
    button.dataset.parcelId = parcel.id; button.textContent = `${parcel.id} — ${statusLabel(parcel.status)}`;
    button.onclick = () => void run(() => showDetail(parcel.id)); item.append(button); el('parcels').append(item);
  }
}
function statusLabel(status) {
  return ({ Deposited: 'Déposé', PickedUp: 'Retiré', Problem: 'Problème signalé', ReturnedToSeller: 'Retourné au vendeur', Delayed: 'En retard', ReturnToSellerReview: 'À examiner par SEQUO', CollectedBySequo: 'Récupéré par SEQUO', Created: 'Créé' })[status] || status;
}
async function showDetail(id) {
  el('detail').hidden = true;
  const parcel = await request(`/api/relay/parcels/${encodeURIComponent(id)}`);
  if (selected?.id !== id) {
    for (const field of ['numeric', 'qr-new', 'pickup', 'qr-pickup', 'reason']) el(field).value = '';
    el('identity').checked = false;
  }
  selected = parcel;
  document.querySelectorAll('[data-parcel-id]').forEach(button => {
    if (button.dataset.parcelId === id) button.textContent = `${id} — ${statusLabel(selected.status)}`;
  });
  const awaitingPickup = ['Deposited', 'Delayed', 'ReturnToSellerReview'].includes(selected.status);
  el('issue').hidden = !awaitingPickup; el('release').hidden = !awaitingPickup;
  el('problem').hidden = ['PickedUp', 'CollectedBySequo', 'ReturnedToSeller'].includes(selected.status);
  el('parcel-title').textContent = selected.id;
  el('parcel-state').textContent = `${statusLabel(selected.status)} — ${selected.relayPointId}`;
  el('history').replaceChildren(...selected.history.map(event => { const li = document.createElement('li'); li.textContent = `${({Deposit:'Dépôt',RelayRelease:'Retrait validé',Pickup:'Retrait',SequoCollection:'Récupération SEQUO',ReturnDropoff:'Retour',Problem:'Problème'})[event.type] || event.type} — ${new Date(event.createdAt).toLocaleString('fr')}`; return li; }));
  el('detail').hidden = false;
}
bind('request-code', async () => { await post('/api/auth/whatsapp/request', { phoneNumber: el('phone').value }, false); el('verify-code').hidden = false; });
bind('verify-code', async () => { saveTokens(await post('/api/auth/whatsapp/verify', { phoneNumber: el('phone').value, code: el('otp').value, device }, false)); el('otp').value = ''; await openWorkspace(); });
bind('issue', async () => {
  if (!selected) return;
  const id = selected.id;
  await post(`/api/relay/parcels/${encodeURIComponent(id)}/pickup-code`, { codeId: crypto.randomUUID(), rawNumericCode: el('numeric').value, rawQrNonce: el('qr-new').value || null, identityCheckRequired: true, expiresAt: new Date(Date.now() + 86400000).toISOString() });
  el('numeric').value = ''; el('qr-new').value = ''; await showDetail(id);
});
bind('release', async () => {
  if (!selected) return;
  if (!el('pickup').value && !el('qr-pickup').value) throw Error('Présentez un code numérique ou QR.');
  const id = selected.id;
  // Keep the same key after an uncertain network outcome; changing proof creates a new attempt.
  const proof = JSON.stringify([id, el('pickup').value, el('qr-pickup').value]);
  if (releaseOperation?.proof !== proof) releaseOperation = { proof, key: crypto.randomUUID(), event: crypto.randomUUID() };
  await post(`/api/relay/parcels/${encodeURIComponent(id)}/release`, { relayPointId: selected.relayPointId, rawNumericCode: el('pickup').value || null, rawQrNonce: el('qr-pickup').value || null, identityDocumentMatched: el('identity').checked, eventId: releaseOperation.event, idempotencyKey: releaseOperation.key });
  el('pickup').value = ''; el('qr-pickup').value = ''; el('identity').checked = false; releaseOperation = null;
  await showDetail(id);
});
bind('problem', async () => { if (!selected) return; const id = selected.id; await post(`/api/relay/parcels/${encodeURIComponent(id)}/problem`, { eventId: crypto.randomUUID(), idempotencyKey: crypto.randomUUID(), metadata: el('reason').value }); el('reason').value = ''; await showDetail(id); });
el('reload').onclick = () => void run(reload);
el('site').onchange = () => void run(reload);
el('logout').onclick = () => void run(async () => { try { await post('/api/auth/logout', { refreshToken: sessionStorage.getItem('sequo_relay_refresh'), deviceId }); } finally { clearSession(); } });
const refreshToken = sessionStorage.getItem('sequo_relay_refresh');
if (refreshToken) void run(async () => { try { saveTokens(await post('/api/auth/refresh', { refreshToken, deviceId }, false)); await openWorkspace(); } catch (error) { clearSession(); throw error; } });
