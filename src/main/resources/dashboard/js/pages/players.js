

const PlayersPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="card fade-up">
                <div style="display:flex;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
                    <input class="input" id="playerSearch" placeholder="Search by name or UUID..." style="max-width:300px;">
                    <button class="btn btn-primary" onclick="PlayersPage.search()">Search</button>
                </div>
                <div class="table-container">
                    <table>
                        <thead>
                            <tr><th>Name</th><th>UUID</th><th>Server</th><th>Status</th><th>Last Join</th><th>Actions</th></tr>
                        </thead>
                        <tbody id="playerTable"><tr><td colspan="6" class="empty">Loading...</td></tr></tbody>
                    </table>
                </div>
            </div>
        `;
        this.loadPlayers();

        document.getElementById('playerSearch').addEventListener('keydown', (e) => {
            if (e.key === 'Enter') this.search();
        });
    },

    async loadPlayers() {
        const resp = await API.getServerList(false);
        if (!resp || resp.code !== 200) return;

        const servers = resp.data.servers || [];
        let html = '';
        servers.forEach(s => {
            (s.players || []).forEach(p => {
                html += `<tr>
                    <td><strong>${escapeHTML(p.name)}</strong></td>
                    <td style="font-family:monospace;font-size:0.75rem;">${escapeHTML(p.uuid)}</td>
                    <td>${escapeHTML(p.server?.name || s.name)}</td>
                    <td><span class="badge badge-success">Online</span></td>
                    <td>-</td>
                    <td><button class="btn" onclick="PlayersPage.kick('${escapeHTML(p.uuid)}','${escapeHTML(p.name)}')">Kick</button></td>
                </tr>`;
            });
        });
        document.getElementById('playerTable').innerHTML = html || '<tr><td colspan="6" class="empty">No online players</td></tr>';
    },

    async search() {
        const query = document.getElementById('playerSearch').value.trim();
        if (!query) { this.loadPlayers(); return; }

        const isUuid = query.includes('-') && query.length > 20;
        const resp = await API.getPlayerInfo(isUuid ? query : null, isUuid ? null : query);
        if (!resp || resp.code !== 200) {
            document.getElementById('playerTable').innerHTML = '<tr><td colspan="6" class="empty">Player not found</td></tr>';
            return;
        }

        const p = resp.data;
        document.getElementById('playerTable').innerHTML = `<tr>
            <td><strong>${escapeHTML(p.name)}</strong></td>
            <td style="font-family:monospace;font-size:0.75rem;">${escapeHTML(p.uuid)}</td>
            <td>${escapeHTML(p.server?.name || '-')}</td>
            <td>${p.online ? '<span class="badge badge-success">Online</span>' : '<span class="badge badge-danger">Offline</span>'}</td>
            <td>${timeAgo(p.lastjoin)}</td>
            <td>${p.online ? '<button class="btn" onclick="PlayersPage.kick(\'' + escapeHTML(p.uuid) + '\',\'' + escapeHTML(p.name) + '\')">Kick</button>' : ''}</td>
        </tr>`;
    },

    async kick(uuid, name) {
        const reason = prompt('Kick reason:');
        if (reason === null) return;
        const resp = await API.kickPlayer(uuid, name, reason);
        if (resp && resp.code === 200) {
            this.loadPlayers();
        } else {
            alert(resp?.message || 'Kick failed');
        }
    }
};
