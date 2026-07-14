

const LogsPage = {
    offset: 0,
    limit: 50,

    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="card fade-up">
                <div style="display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap;">
                    <select class="input" id="logType" style="max-width:150px;">
                        <option value="">All Types</option>
                        <option value="REQUEST">REQUEST</option>
                        <option value="RESPONSE">RESPONSE</option>
                        <option value="CHAT">CHAT</option>
                        <option value="GAME">GAME</option>
                        <option value="OPERATION">OPERATION</option>
                        <option value="OTHER">OTHER</option>
                    </select>
                    <input class="input" id="logSearch" placeholder="Search log content..." style="max-width:250px;">
                    <button class="btn btn-primary" onclick="LogsPage.search()">Search</button>
                    <button class="btn" onclick="LogsPage.reset()">Reset</button>
                </div>
                <div id="logContainer" style="max-height:calc(100vh - 260px);overflow-y:auto;border:1px solid var(--border);border-radius:8px;">
                    <div class="empty">Loading...</div>
                </div>
                <div class="pagination" id="logPagination"></div>
            </div>
        `;
        this.offset = 0;
        this.load();
    },

    async load() {
        const type = document.getElementById('logType')?.value || '';
        const resp = await API.getLogs({ t: this.limit, offset: this.offset, type: type });

        if (!resp || resp.code !== 200) return;

        const logs = resp.data.logs || [];
        const total = resp.data.total || 0;
        const container = document.getElementById('logContainer');

        if (logs.length === 0) {
            container.innerHTML = '<div class="empty">No logs found</div>';
            return;
        }

        container.innerHTML = logs.map(l => {
            let cls = '';
            const raw = l.raw || '';
            if (raw.startsWith('[REQUEST]') || raw.startsWith('[RESPONSE]')) cls = 'log-request';
            else if (raw.startsWith('[CHAT]')) cls = 'log-chat';
            else if (raw.startsWith('[GAME]')) cls = 'log-game';
            else cls = 'log-other';
            return `<div class="log-line ${cls}">${escapeHTML(raw)}</div>`;
        }).join('');

        const pages = Math.ceil(total / this.limit);
        const currentPage = Math.floor(this.offset / this.limit) + 1;
        let pagHtml = '';
        if (currentPage > 1) pagHtml += `<button class="btn" onclick="LogsPage.prev()">Prev</button>`;
        pagHtml += `<span class="page-info">${currentPage} / ${pages} (${total} total)</span>`;
        if (currentPage < pages) pagHtml += `<button class="btn" onclick="LogsPage.next()">Next</button>`;
        document.getElementById('logPagination').innerHTML = pagHtml;
    },

    search() {
        this.offset = 0;
        this.load();
    },

    reset() {
        document.getElementById('logType').value = '';
        document.getElementById('logSearch').value = '';
        this.offset = 0;
        this.load();
    },

    prev() {
        this.offset = Math.max(0, this.offset - this.limit);
        this.load();
    },

    next() {
        this.offset += this.limit;
        this.load();
    }
};
