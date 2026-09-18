/*
 * Notifly dashboard.
 *
 * Deliberately plain JavaScript with no framework and no build step. The API is the product here;
 * the dashboard exists so the system can be demonstrated without curl, and adding a toolchain
 * would mean a second thing to install, build and deploy for very little gain.
 *
 * It talks to exactly the same REST API as any other client, with no private endpoints.
 */

const API = '/api/v1';

const state = {
    token: null,
    user: null,
    templates: [],
    channels: [],
    pollTimer: null,
    lastStatuses: new Map(),   // notification id -> status, for highlighting what changed
};

/* ---------------------------------------------------------------- http */

async function api(path, options = {}) {
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (state.token) {
        headers['Authorization'] = `Bearer ${state.token}`;
    }

    const response = await fetch(API + path, { ...options, headers });

    if (response.status === 204) {
        return null;
    }

    const text = await response.text();
    const body = text ? JSON.parse(text) : null;

    if (!response.ok) {
        // The API returns RFC 7807 problem documents, so there is always something meaningful to
        // show. Falling back to a bare status code would waste that.
        const error = new Error(body?.detail || body?.title || `Request failed (${response.status})`);
        error.status = response.status;
        error.code = body?.code;
        error.body = body;
        throw error;
    }
    return body;
}

/* ---------------------------------------------------------------- auth */

async function login(email, password) {
    const result = await api('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
    });

    state.token = result.accessToken;
    state.user = result.user;

    // Survives a page refresh. The token is short-lived and this is a demo dashboard; a
    // production app would prefer an httpOnly cookie so script cannot read it at all.
    try {
        sessionStorage.setItem('notifly.token', result.accessToken);
        sessionStorage.setItem('notifly.user', JSON.stringify(result.user));
    } catch (ignored) {
        // Private browsing blocks storage. The session still works, it just will not survive a reload.
    }

    showApp();
}

function logout() {
    state.token = null;
    state.user = null;
    stopPolling();
    try {
        sessionStorage.removeItem('notifly.token');
        sessionStorage.removeItem('notifly.user');
    } catch (ignored) { /* nothing to clear */ }

    document.getElementById('app').hidden = true;
    document.getElementById('login-screen').hidden = false;
}

function restoreSession() {
    try {
        const token = sessionStorage.getItem('notifly.token');
        const user = sessionStorage.getItem('notifly.user');
        if (token && user) {
            state.token = token;
            state.user = JSON.parse(user);
            return true;
        }
    } catch (ignored) { /* storage unavailable */ }
    return false;
}

/* ---------------------------------------------------------------- shell */

const TABS = {
    PLATFORM_ADMIN: [
        { id: 'tenants', label: 'Tenants' },
    ],
    TENANT_ADMIN: [
        { id: 'send', label: 'Send' },
        { id: 'delivery', label: 'Delivery' },
        { id: 'templates', label: 'Templates' },
        { id: 'channels', label: 'Channels' },
    ],
};

function showApp() {
    document.getElementById('login-screen').hidden = true;
    document.getElementById('app').hidden = false;

    const badge = document.getElementById('user-badge');
    badge.textContent = state.user.role === 'PLATFORM_ADMIN' ? 'PLATFORM' : 'TENANT';
    badge.className = 'badge ' + (state.user.role === 'PLATFORM_ADMIN' ? 'badge-info' : 'badge-ok');
    document.getElementById('user-email').textContent = state.user.email;

    const tabs = TABS[state.user.role] || [];
    const nav = document.getElementById('tabs');
    nav.innerHTML = '';
    tabs.forEach((tab, index) => {
        const button = document.createElement('button');
        button.className = 'tab' + (index === 0 ? ' active' : '');
        button.textContent = tab.label;
        button.onclick = () => selectTab(tab.id, button);
        nav.appendChild(button);
    });

    if (tabs.length) {
        selectTab(tabs[0].id, nav.firstChild);
    }
}

function selectTab(id, button) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    button.classList.add('active');
    document.querySelectorAll('.panel').forEach(p => p.hidden = true);
    document.querySelector(`[data-panel="${id}"]`).hidden = false;

    // Polling only runs while the delivery table is on screen. Continuing in the background would
    // be a request every two seconds that nobody is looking at.
    stopPolling();

    if (id === 'tenants') loadTenants();
    if (id === 'channels') loadChannels();
    if (id === 'templates') loadTemplates();
    if (id === 'send') loadSendForm();
    if (id === 'delivery') { loadDelivery(); startPolling(); }
}

/* ---------------------------------------------------------------- tenants */

async function loadTenants() {
    const target = document.getElementById('tenants-list');
    target.innerHTML = '<p class="muted">Loading…</p>';
    try {
        const page = await api('/admin/tenants?size=100');
        if (!page.content.length) {
            target.innerHTML = '<p class="empty muted">No tenants yet. Create one to get started.</p>';
            return;
        }
        target.innerHTML = page.content.map(tenant => `
            <div class="list-item">
                <div class="list-item-main">
                    <div class="list-item-title">${escapeHtml(tenant.name)}
                        <span class="badge ${tenant.status === 'ACTIVE' ? 'badge-ok' : 'badge-warn'}">${tenant.status}</span>
                    </div>
                    <div class="muted"><code>${escapeHtml(tenant.slug)}</code> · dispatch weight ${tenant.dispatchWeight}</div>
                </div>
                <div class="list-item-actions">
                    <button class="btn btn-sm ${tenant.status === 'ACTIVE' ? 'btn-danger' : ''}"
                            onclick="toggleTenant('${tenant.id}', '${tenant.status}')">
                        ${tenant.status === 'ACTIVE' ? 'Suspend' : 'Activate'}
                    </button>
                </div>
            </div>`).join('');
    } catch (error) {
        target.innerHTML = `<div class="alert alert-error">${escapeHtml(error.message)}</div>`;
    }
}

async function toggleTenant(id, status) {
    const action = status === 'ACTIVE' ? 'suspend' : 'activate';
    try {
        await api(`/admin/tenants/${id}/${action}`, { method: 'POST' });
        toast(`Tenant ${action}d`, 'ok');
        loadTenants();
    } catch (error) {
        toast(error.message, 'err');
    }
}

async function createTenant(event) {
    event.preventDefault();
    const payload = {
        slug: document.getElementById('tenant-slug').value.trim(),
        name: document.getElementById('tenant-name').value.trim(),
        dispatchWeight: Number(document.getElementById('tenant-weight').value) || 1,
        adminEmail: document.getElementById('tenant-admin-email').value.trim(),
        adminPassword: document.getElementById('tenant-admin-password').value,
        adminName: document.getElementById('tenant-name').value.trim() + ' Admin',
    };
    try {
        await api('/admin/tenants', { method: 'POST', body: JSON.stringify(payload) });
        toast(`Tenant created. Sign in as ${payload.adminEmail} to send notifications.`, 'ok');
        document.getElementById('tenant-form').hidden = true;
        document.getElementById('tenant-form').reset();
        loadTenants();
    } catch (error) {
        toast(error.message, 'err');
    }
}

/* ---------------------------------------------------------------- channels */

async function loadChannels() {
    const target = document.getElementById('channels-list');
    target.innerHTML = '<p class="muted">Loading…</p>';
    try {
        state.channels = await api('/channels');
        target.innerHTML = state.channels.map(channel => `
            <div class="list-item">
                <div class="list-item-main">
                    <div class="list-item-title">${channel.channel}
                        <span class="badge ${channel.configured ? 'badge-ok' : ''}">${channel.configured ? 'ENABLED' : 'NOT CONFIGURED'}</span>
                    </div>
                    <div class="muted">
                        ${channel.senderIdentity ? 'Sends as ' + escapeHtml(channel.senderIdentity) : 'No sender identity set'}
                        ${channel.credentialsSet ? ' · credential ' + escapeHtml(channel.credentialsHint) : ''}
                    </div>
                </div>
                <div class="list-item-actions">
                    <button class="btn btn-sm ${channel.configured ? 'btn-danger' : 'btn-primary'}"
                            onclick="configureChannel('${channel.channel}', ${!channel.configured})">
                        ${channel.configured ? 'Disable' : 'Set up'}
                    </button>
                </div>
            </div>`).join('');
    } catch (error) {
        target.innerHTML = `<div class="alert alert-error">${escapeHtml(error.message)}</div>`;
    }
}

async function configureChannel(channel, enable) {
    try {
        if (enable) {
            // Sensible defaults so a demo works in one click. A real setup would collect these.
            const sender = channel === 'EMAIL' ? `no-reply@${state.user.email.split('@')[1]}`
                : channel === 'SMS' ? 'NOTIFLY' : 'notifly-app';
            await api(`/channels/${channel}`, {
                method: 'PUT',
                body: JSON.stringify({
                    enabled: true,
                    senderIdentity: sender,
                    credentials: 'demo-provider-credential',
                    providerCode: 'SIMULATOR',
                }),
            });
            toast(`${channel} configured`, 'ok');
        } else {
            await api(`/channels/${channel}/disable`, { method: 'POST' });
            toast(`${channel} disabled`, 'ok');
        }
        loadChannels();
    } catch (error) {
        toast(error.message, 'err');
    }
}

/* ---------------------------------------------------------------- templates */

async function loadTemplates() {
    const target = document.getElementById('templates-list');
    target.innerHTML = '<p class="muted">Loading…</p>';
    try {
        const page = await api('/templates?size=100');
        state.templates = page.content;

        if (!page.content.length) {
            target.innerHTML = '<p class="empty muted">No templates yet. Create one to send notifications.</p>';
            return;
        }
        target.innerHTML = page.content.map(template => `
            <div class="list-item">
                <div class="list-item-main">
                    <div class="list-item-title">${escapeHtml(template.name)}
                        <span class="badge ${template.publishedVersion ? 'badge-ok' : 'badge-warn'}">
                            ${template.publishedVersion ? 'v' + template.publishedVersion + ' PUBLISHED' : 'DRAFT ONLY'}
                        </span>
                    </div>
                    <div class="muted"><code>${escapeHtml(template.code)}</code>
                        ${template.latestVersion ? ' · ' + template.latestVersion + ' version(s)' : ''}</div>
                </div>
                <div class="list-item-actions">
                    ${template.publishedVersion ? '' :
                      `<button class="btn btn-sm btn-primary" onclick="publishTemplate('${template.id}', ${template.latestVersion})">Publish v${template.latestVersion}</button>`}
                </div>
            </div>`).join('');
    } catch (error) {
        target.innerHTML = `<div class="alert alert-error">${escapeHtml(error.message)}</div>`;
    }
}

async function publishTemplate(id, version) {
    try {
        await api(`/templates/${id}/versions/${version}/publish`, { method: 'POST' });
        toast('Template published', 'ok');
        loadTemplates();
    } catch (error) {
        toast(error.message, 'err');
    }
}

async function createTemplate(event) {
    event.preventDefault();

    const variables = document.getElementById('tpl-vars').value
        .split(',').map(v => v.trim()).filter(Boolean)
        .map(name => ({ name, required: true }));

    const subject = document.getElementById('tpl-subject').value.trim();
    const body = document.getElementById('tpl-body').value.trim();

    const payload = {
        code: document.getElementById('tpl-code').value.trim(),
        name: document.getElementById('tpl-name').value.trim(),
        initialVersion: {
            variables,
            bodies: {
                EMAIL: { subject: subject || null, bodyText: body, bodyHtml: `<p>${escapeHtml(body)}</p>` },
                SMS: { bodyText: body },
            },
        },
    };

    try {
        const created = await api('/templates', { method: 'POST', body: JSON.stringify(payload) });

        if (document.getElementById('tpl-publish').checked) {
            // Publishing is where strict validation runs: an undeclared {{placeholder}} is
            // rejected here rather than silently rendering as blank at send time.
            await api(`/templates/${created.id}/versions/1/publish`, { method: 'POST' });
        }

        toast('Template created', 'ok');
        document.getElementById('template-form').hidden = true;
        document.getElementById('template-form').reset();
        loadTemplates();
    } catch (error) {
        toast(error.message, 'err');
    }
}

/* ---------------------------------------------------------------- send */

async function loadSendForm() {
    try {
        const [templatePage, channels] = await Promise.all([
            api('/templates?size=100'),
            api('/channels'),
        ]);
        state.templates = templatePage.content.filter(t => t.publishedVersion);
        state.channels = channels;

        const select = document.getElementById('send-template');
        select.innerHTML = state.templates.length
            ? state.templates.map(t => `<option value="${t.id}">${escapeHtml(t.name)} (${escapeHtml(t.code)})</option>`).join('')
            : '<option value="">No published templates — create one first</option>';

        const usable = channels.filter(c => c.configured);
        document.getElementById('send-channels').innerHTML = channels.map(c => `
            <span class="chip ${c.configured ? (usable[0]?.channel === c.channel ? 'on' : '') : 'disabled'}"
                  data-channel="${c.channel}"
                  title="${c.configured ? '' : 'Configure this channel first'}"
                  onclick="${c.configured ? 'toggleChip(this)' : ''}">${c.channel}</span>`).join('');

        if (state.templates.length) {
            await loadTemplateVariables();
            select.onchange = loadTemplateVariables;
        } else {
            document.getElementById('send-vars').innerHTML = '';
        }
    } catch (error) {
        toast(error.message, 'err');
    }
}

function toggleChip(element) {
    element.classList.toggle('on');
}

async function loadTemplateVariables() {
    const templateId = document.getElementById('send-template').value;
    if (!templateId) return;

    const template = state.templates.find(t => t.id === templateId);
    const version = await api(`/templates/${templateId}/versions/${template.publishedVersion}`);

    document.getElementById('send-vars').innerHTML = version.variables.map(v => `
        <div>
            <label for="var-${v.name}">${escapeHtml(v.name)}${v.required ? ' *' : ''}</label>
            <input id="var-${v.name}" data-var="${escapeHtml(v.name)}" placeholder="value">
        </div>`).join('');
}

async function sendNotification(event) {
    event.preventDefault();

    const templateId = document.getElementById('send-template').value;
    const template = state.templates.find(t => t.id === templateId);
    if (!template) {
        toast('Create and publish a template first', 'err');
        return;
    }

    const channels = [...document.querySelectorAll('#send-channels .chip.on')].map(c => c.dataset.channel);
    if (!channels.length) {
        toast('Select at least one channel', 'err');
        return;
    }

    const addresses = document.getElementById('send-recipients').value
        .split('\n').map(line => line.trim()).filter(Boolean);
    if (!addresses.length) {
        toast('Add at least one recipient', 'err');
        return;
    }

    const variables = {};
    document.querySelectorAll('#send-vars input[data-var]').forEach(input => {
        if (input.value.trim()) variables[input.dataset.var] = input.value.trim();
    });

    // One recipient entry per line, with the same address offered to every selected channel.
    // The server ignores channels a recipient has no usable address for.
    const recipients = addresses.map((address, index) => {
        const perChannel = {};
        channels.forEach(channel => perChannel[channel] = address);
        return { ref: `ui-${index}`, addresses: perChannel };
    });

    const delay = Number(document.getElementById('send-schedule').value);
    const payload = { templateCode: template.code, recipients, variables, channels };
    if (delay) {
        payload.scheduledAt = new Date(Date.now() + delay * 1000).toISOString();
    }

    const result = document.getElementById('send-result');
    try {
        const response = await api('/notifications', {
            method: 'POST',
            // A fresh key per click: it protects against a double submit or a network retry of
            // this exact request, not against deliberately sending the same message twice.
            headers: { 'Idempotency-Key': crypto.randomUUID() },
            body: JSON.stringify(payload),
        });

        result.innerHTML = `<div class="alert alert-ok">
            Accepted ${response.accepted} notification(s)${response.suppressed ? `, ${response.suppressed} suppressed` : ''}.
            ${response.scheduledAt ? 'Scheduled for ' + new Date(response.scheduledAt).toLocaleString() + '.' : 'Dispatching now.'}
            <a href="#" onclick="goToDelivery();return false;">Watch delivery →</a>
        </div>`;
        toast(`${response.accepted} notification(s) queued`, 'ok');

    } catch (error) {
        const detail = error.code === 'RATE_LIMIT_EXCEEDED'
            ? `${error.message} (this is the rate limiter working)`
            : error.message;
        result.innerHTML = `<div class="alert alert-error"><strong>${escapeHtml(error.code || 'Error')}</strong> — ${escapeHtml(detail)}</div>`;
    }
}

function goToDelivery() {
    const tabs = [...document.querySelectorAll('.tab')];
    const target = tabs.find(t => t.textContent === 'Delivery');
    if (target) selectTab('delivery', target);
}

/* ---------------------------------------------------------------- delivery */

const STATUS_STYLE = {
    DELIVERED: ['badge-ok', '✅'],
    SENT: ['badge-ok', '✅'],
    SENDING: ['badge-info', '📤'],
    QUEUED: ['badge-info', '⏳'],
    SCHEDULED: ['badge-warn', '🕐'],
    RETRY_SCHEDULED: ['badge-warn', '🔁'],
    FAILED: ['badge-err', '❌'],
    CANCELLED: ['', '🚫'],
    SUPPRESSED: ['', '🔕'],
    CREATED: ['badge-info', '•'],
};

async function loadDelivery() {
    try {
        const status = document.getElementById('delivery-filter').value;
        const [page, summary] = await Promise.all([
            api(`/notifications?size=50${status ? '&status=' + status : ''}`),
            api('/notifications/summary'),
        ]);

        renderSummary(summary);
        renderRows(page.content);
    } catch (error) {
        if (error.status === 401) {
            logout();
            return;
        }
        toast(error.message, 'err');
    }
}

function renderSummary(summary) {
    const tiles = [
        { label: 'Total', value: summary.total },
        { label: 'Delivered', value: summary.delivered },
        { label: 'Failed', value: summary.failed },
    ];
    Object.entries(summary.byChannel || {}).forEach(([channel, count]) =>
        tiles.push({ label: channel, value: count }));

    document.getElementById('summary-tiles').innerHTML = tiles.map(tile => `
        <div class="tile">
            <div class="tile-value">${tile.value}</div>
            <div class="tile-label">${tile.label}</div>
        </div>`).join('');
}

function renderRows(rows) {
    const body = document.getElementById('delivery-rows');
    document.getElementById('delivery-empty').hidden = rows.length > 0;

    body.innerHTML = rows.map(row => {
        const [badgeClass, icon] = STATUS_STYLE[row.status] || ['', '•'];
        // Highlight rows whose status moved since the last poll, so progress is visible without
        // watching continuously.
        const changed = state.lastStatuses.has(row.id) && state.lastStatuses.get(row.id) !== row.status;
        state.lastStatuses.set(row.id, row.status);

        return `<tr class="${changed ? 'row-changed' : ''}">
            <td class="mono">${escapeHtml(row.recipient)}</td>
            <td>${row.channel}</td>
            <td><span class="badge ${badgeClass}">${icon} ${row.status}</span></td>
            <td>${row.attemptCount}/${row.maxAttempts}</td>
            <td class="muted">${new Date(row.createdAt).toLocaleTimeString()}</td>
            <td><button class="btn btn-sm btn-ghost" onclick="showAttempts('${row.id}')">History</button></td>
        </tr>`;
    }).join('');
}

function startPolling() {
    stopPolling();
    // Two seconds is a compromise: fast enough that a notification visibly moves through its
    // states, slow enough not to hammer a free-tier instance.
    state.pollTimer = setInterval(loadDelivery, 2000);
    document.getElementById('live-dot').hidden = false;
    document.getElementById('live-label').textContent = 'Live';
}

function stopPolling() {
    if (state.pollTimer) {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
    }
}

async function showAttempts(notificationId) {
    const drawer = document.getElementById('drawer');
    const body = document.getElementById('drawer-body');
    drawer.hidden = false;
    body.innerHTML = '<p class="muted">Loading…</p>';

    try {
        const [notification, attempts] = await Promise.all([
            api(`/notifications/${notificationId}`),
            api(`/notifications/${notificationId}/attempts`),
        ]);

        const header = `
            <div class="card">
                <div class="muted">Recipient</div>
                <div class="mono">${escapeHtml(notification.recipient)}</div>
                <div class="muted" style="margin-top:10px">Status</div>
                <div>${notification.status}${notification.lastErrorCode ? ' — ' + escapeHtml(notification.lastErrorCode) : ''}</div>
                ${notification.providerMessageId ? `<div class="muted" style="margin-top:10px">Provider message id</div>
                    <div class="mono">${escapeHtml(notification.providerMessageId)}</div>` : ''}
            </div>`;

        const list = attempts.length ? attempts.map(attempt => {
            const tone = attempt.outcome === 'SUCCESS' ? 'ok'
                : attempt.outcome === 'PERMANENT_FAILURE' ? 'err' : 'warn';
            return `<div class="attempt ${tone}">
                <div class="attempt-head">
                    <strong>Attempt ${attempt.attemptNumber}</strong>
                    <span class="badge">${attempt.outcome || 'IN FLIGHT'}</span>
                </div>
                <div class="muted">${new Date(attempt.startedAt).toLocaleString()}
                    ${attempt.latencyMs != null ? ' · ' + attempt.latencyMs + 'ms' : ''}</div>
                ${attempt.errorMessage ? `<div style="margin-top:6px">${escapeHtml(attempt.errorMessage)}</div>` : ''}
            </div>`;
        }).join('') : '<p class="muted">No attempts yet — still waiting to be dispatched.</p>';

        body.innerHTML = header + list;
    } catch (error) {
        body.innerHTML = `<div class="alert alert-error">${escapeHtml(error.message)}</div>`;
    }
}

/* ---------------------------------------------------------------- helpers */

function escapeHtml(value) {
    if (value == null) return '';
    return String(value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

let toastTimer = null;
function toast(message, tone = '') {
    const element = document.getElementById('toast');
    element.textContent = message;
    element.className = 'toast ' + tone;
    element.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => element.hidden = true, 4000);
}

/* ---------------------------------------------------------------- wiring */

document.getElementById('login-form').onsubmit = async (event) => {
    event.preventDefault();
    const button = document.getElementById('login-btn');
    const error = document.getElementById('login-error');
    button.disabled = true;
    button.textContent = 'Signing in…';
    error.hidden = true;

    try {
        await login(
            document.getElementById('login-email').value.trim(),
            document.getElementById('login-password').value);
    } catch (caught) {
        error.textContent = caught.message;
        error.hidden = false;
    } finally {
        button.disabled = false;
        button.textContent = 'Sign in';
    }
};

document.getElementById('logout-btn').onclick = logout;
// Open explicitly rather than toggling. With a toggle, a stray double-click closes the form
// again and the user is left staring at a button that appears to do nothing.
document.getElementById('new-tenant-btn').onclick = () =>
    document.getElementById('tenant-form').hidden = false;
document.getElementById('new-template-btn').onclick = () =>
    document.getElementById('template-form').hidden = false;
document.getElementById('tenant-form').onsubmit = createTenant;
document.getElementById('template-form').onsubmit = createTemplate;
document.getElementById('send-form').onsubmit = sendNotification;
document.getElementById('delivery-filter').onchange = loadDelivery;
document.getElementById('drawer-close').onclick = () => document.getElementById('drawer').hidden = true;
document.getElementById('drawer').onclick = (event) => {
    if (event.target.id === 'drawer') document.getElementById('drawer').hidden = true;
};
document.querySelectorAll('[data-cancel]').forEach(button =>
    button.onclick = () => document.getElementById(button.dataset.cancel).hidden = true);

// Stop polling when the tab is hidden. A backgrounded browser tab quietly making a request every
// two seconds for hours is exactly the kind of thing that burns a free-tier allowance.
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        stopPolling();
    } else if (!document.querySelector('[data-panel="delivery"]').hidden && state.token) {
        startPolling();
    }
});

if (restoreSession()) {
    showApp();
}
