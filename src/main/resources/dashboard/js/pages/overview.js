

const OverviewPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="overview-grid">
                <div class="card fade-up" id="statOnline">
                    <div class="card-title">Online Players</div>
                    <div class="stat-value" id="onlineCount">-</div>
                    <div class="stat-label">loading...</div>
                </div>
                <div class="card fade-up" id="statServers">
                    <div class="card-title">Servers</div>
                    <div class="stat-value" id="serverCount">-</div>
                    <div class="stat-label">registered</div>
                </div>
                <div class="card fade-up" id="statMessages">
                    <div class="card-title">Messages Today</div>
                    <div class="stat-value" id="msgCount">-</div>
                    <div class="stat-label">chat messages</div>
                </div>
                <div class="card fade-up" id="statUptime">
                    <div class="card-title">System Uptime</div>
                    <div class="stat-value" id="uptime">-</div>
                    <div class="stat-label">jvm uptime</div>
                </div>
            </div>
            <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-top:16px;">
                <div class="card fade-up" id="serverListCard">
                    <div class="card-title">Server Status</div>
                    <div id="serverList" style="margin-top:8px;">loading...</div>
                </div>
                <div class="card fade-up" id="recentChatCard">
                    <div class="card-title">Recent Chat</div>
                    <div id="recentChat" style="margin-top:8px;">loading...</div>
                </div>
            </div>
        `;

        this.loadData();
    },

    async loadData() {

        const serverResp = await API.getServerList(true);
        if (serverResp && serverResp.code === 200 && serverResp.data) {
            const servers = serverResp.data.servers || [];
            document.getElementById('serverCount').textContent = servers.length;

            let totalOnline = 0;
            let serverHtml = '';
            servers.forEach(s => {
                totalOnline += s.online >= 0 ? s.online : 0;
                const status = s.online >= 0
                    ? '<span class="badge badge-success">Online</span>'
                    : '<span class="badge badge-danger">Offline</span>';
                serverHtml += `<div style="display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid var(--border);font-size:0.85rem;">
                    <span>${escapeHTML(s.name)}</span>
                    <span>${s.online >= 0 ? s.online + '/' + (s.max || '?') : '-'} ${status}</span>
                </div>`;
            });
            document.getElementById('serverList').innerHTML = serverHtml || '<div class="empty">No servers</div>';
            document.getElementById('onlineCount').textContent = totalOnline;
        }

        const sysResp = await API.getSystemStatus();
        if (sysResp && sysResp.code === 200 && sysResp.data) {
            const d = sysResp.data;
            const uptime = d.jvm_uptime || 0;
            const hours = Math.floor(uptime / 3600000);
            const mins = Math.floor((uptime % 3600000) / 60000);
            document.getElementById('uptime').textContent = hours + 'h ' + mins + 'm';
        }

        const chatResp = await API.getChatHistory();
        if (chatResp && chatResp.code === 200 && chatResp.data) {
            const msgs = chatResp.data.messages || [];
            let chatHtml = '';
            msgs.slice(0, 15).forEach(m => {
                chatHtml += `<div class="feed-item">
                    <span class="feed-time">${formatShortTime(m.time)}</span>
                    <span class="feed-name">${escapeHTML(m.name)}</span>
                    <span class="feed-content">${escapeHTML(m.content)}</span>
                </div>`;
            });
            document.getElementById('recentChat').innerHTML = chatHtml || '<div class="empty">No messages</div>';
            document.getElementById('msgCount').textContent = msgs.length;
        }

        WS.on('playerJoin', () => this.loadData());
        WS.on('playerLeave', () => this.loadData());
        WS.on('playerChat', (data) => {
            const el = document.getElementById('recentChat');
            if (el) {
                const item = document.createElement('div');
                item.className = 'feed-item slide-in';
                item.innerHTML = `<span class="feed-time">${formatShortTime(data.time)}</span>
                    <span class="feed-name">${escapeHTML(data.player?.name)}</span>
                    <span class="feed-content">${escapeHTML(data.message?.content)}</span>`;
                el.prepend(item);
                if (el.children.length > 15) el.lastChild.remove();
            }
        });
    }
};
