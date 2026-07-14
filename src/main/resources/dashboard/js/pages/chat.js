

const ChatPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="chat-container">
                <div style="display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap;">
                    <input class="input" id="chatFilter" placeholder="Filter by player or content..." style="max-width:300px;">
                    <button class="btn" onclick="ChatPage.load()">Load History</button>
                    <button class="btn" onclick="ChatPage.clear()">Clear</button>
                </div>
                <div class="chat-messages" id="chatMessages"><div class="empty">Loading...</div></div>
            </div>
        `;
        this.load();

        WS.on('playerChat', (data) => {
            this.addMessage(data);
        });
    },

    async load() {
        const resp = await API.getChatHistory();
        if (!resp || resp.code !== 200) return;

        const msgs = resp.data.messages || [];
        const el = document.getElementById('chatMessages');
        el.innerHTML = '';
        msgs.reverse().forEach(m => this.addMessage(m, false));
        el.scrollTop = el.scrollHeight;
    },

    addMessage(data, scroll = true) {
        const el = document.getElementById('chatMessages');
        if (!el) return;

        const div = document.createElement('div');
        div.className = 'feed-item slide-in';
        div.innerHTML = `
            <span class="feed-time">${formatShortTime(data.time)}</span>
            ${data.server?.name ? '<span class="feed-server">' + escapeHTML(data.server.name) + '</span>' : ''}
            <span class="feed-name">${escapeHTML(data.name || data.player?.name)}</span>
            <span class="feed-content">${escapeHTML(data.content || data.message?.content)}</span>
        `;
        el.appendChild(div);
        if (scroll) el.scrollTop = el.scrollHeight;
    },

    clear() {
        const el = document.getElementById('chatMessages');
        if (el) el.innerHTML = '';
    }
};
