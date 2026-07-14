

const ConsolePage = {
    history: [],
    historyIndex: -1,

    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="console-container">
                <div class="console-output" id="consoleOutput">Ready. Type a command below.</div>
                <div class="console-input-area">
                    <input class="input" id="consoleInput" placeholder="Enter command..." autocomplete="off">
                    <button class="btn btn-primary" onclick="ConsolePage.execute()">Execute</button>
                </div>
            </div>
        `;

        const input = document.getElementById('consoleInput');
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') this.execute();
            if (e.key === 'ArrowUp') { e.preventDefault(); this.navigateHistory(-1); }
            if (e.key === 'ArrowDown') { e.preventDefault(); this.navigateHistory(1); }
        });
        input.focus();
    },

    async execute() {
        const input = document.getElementById('consoleInput');
        const cmd = input.value.trim();
        if (!cmd) return;

        this.history.push(cmd);
        this.historyIndex = this.history.length;
        input.value = '';

        const output = document.getElementById('consoleOutput');
        output.textContent += '\n> ' + cmd;
        output.scrollTop = output.scrollHeight;

        const resp = await API.post('/chat/command', cmd);
        if (resp) {
            const msg = resp.message || 'No response';
            output.textContent += '\n' + msg;
        } else {
            output.textContent += '\n[Error] Request failed';
        }
        output.scrollTop = output.scrollHeight;
    },

    navigateHistory(dir) {
        if (this.history.length === 0) return;
        this.historyIndex = Math.max(0, Math.min(this.history.length, this.historyIndex + dir));
        const input = document.getElementById('consoleInput');
        input.value = this.history[this.historyIndex] || '';
    }
};
