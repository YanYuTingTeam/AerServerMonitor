

const EventsPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="card fade-up">
                <div id="eventList"><div class="empty">Loading...</div></div>
            </div>
        `;
        this.load();
        WS.on('*', (msg) => {
            if (msg.event && msg.event !== 'playerChat') {
                this.addEvent(msg);
            }
        });
    },

    async load() {
        const resp = await API.getLogs({ t: 100, type: 'GAME,OTHER' });
        if (!resp || resp.code !== 200) return;

        const logs = resp.data.logs || [];
        const el = document.getElementById('eventList');
        el.innerHTML = '';
        logs.forEach(l => {
            const div = document.createElement('div');
            div.className = 'feed-item';
            div.innerHTML = `<span class="feed-content" style="font-family:monospace;font-size:0.75rem;">${escapeHTML(l.raw)}</span>`;
            el.appendChild(div);
        });
    },

    addEvent(data) {
        const el = document.getElementById('eventList');
        if (!el) return;
        const div = document.createElement('div');
        div.className = 'feed-item slide-in';
        div.innerHTML = `<span class="feed-time">${formatShortTime(data.time)}</span>
            <span class="badge badge-info">${escapeHTML(data.event)}</span>
            <span class="feed-content">${escapeHTML(data.player?.name || data.ip || '')}</span>`;
        el.prepend(div);
        if (el.children.length > 200) el.lastChild.remove();
    }
};
