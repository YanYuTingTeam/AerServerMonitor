

const ServersPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div style="margin-bottom:16px;">
                <button class="btn btn-primary" onclick="ServersPage.refresh(true)">Refresh with Ping</button>
                <button class="btn" onclick="ServersPage.refresh(false)" style="margin-left:8px;">Refresh</button>
            </div>
            <div class="grid grid-3" id="serverGrid"><div class="empty">Loading...</div></div>
        `;
        this.refresh(true);
    },

    async refresh(ping) {
        const resp = await API.getServerList(ping);
        if (!resp || resp.code !== 200) return;

        const servers = resp.data.servers || [];
        let html = '';
        servers.forEach(s => {
            const statusClass = s.online >= 0 ? 'badge-success' : 'badge-danger';
            const statusText = s.online >= 0 ? 'Online' : 'Offline';
            const playerCount = s.online >= 0 ? s.online : (s.players?.length || 0);

            html += `<div class="card fade-up">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
                    <strong>${escapeHTML(s.name)}</strong>
                    <span class="badge ${statusClass}">${statusText}</span>
                </div>
                <div style="font-size:0.8rem;color:var(--text-secondary);margin-bottom:8px;">
                    ${escapeHTML(s.ip)}:${s.port}
                </div>
                ${s.describe ? '<div style="font-size:0.8rem;color:var(--text-tertiary);margin-bottom:8px;">' + escapeHTML(s.describe) + '</div>' : ''}
                <div style="display:flex;gap:16px;font-size:0.85rem;">
                    <span>Players: <strong>${playerCount}${s.max ? '/' + s.max : ''}</strong></span>
                    ${s.version ? '<span>Version: ' + escapeHTML(s.version) + '</span>' : ''}
                </div>
                ${s.ico ? '<img src="' + s.ico + '" style="width:64px;height:64px;margin-top:8px;border-radius:4px;">' : ''}
                ${(s.players && s.players.length > 0) ? '<div style="margin-top:12px;border-top:1px solid var(--border);padding-top:8px;font-size:0.8rem;">' +
                    s.players.map(p => '<div style="padding:2px 0;">' + escapeHTML(p.name) + '</div>').join('') + '</div>' : ''}
            </div>`;
        });
        document.getElementById('serverGrid').innerHTML = html || '<div class="empty">No servers</div>';
    }
};
