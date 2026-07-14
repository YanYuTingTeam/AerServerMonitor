

const SystemPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `<div class="system-grid" id="sysGrid"><div class="empty">Loading...</div></div>`;
        this.load();
    },

    async load() {
        const resp = await API.getSystemStatus();
        if (!resp || resp.code !== 200) return;

        const d = resp.data;
        const usedMem = d.used_memory || 0;
        const maxMem = d.max_memory || 1;
        const memPercent = ((usedMem / maxMem) * 100).toFixed(1);
        const uptime = d.jvm_uptime || 0;
        const hours = Math.floor(uptime / 3600000);
        const mins = Math.floor((uptime % 3600000) / 60000);

        const items = [
            ['OS', (d.os_name || '-') + ' ' + (d.os_version || '')],
            ['Architecture', d.os_arch || '-'],
            ['CPU Cores', d.available_processors || '-'],
            ['CPU Load', d.cpu_load != null ? (d.cpu_load * 100).toFixed(1) + '%' : '-'],
            ['Total RAM', this.formatBytes(d.total_physical_memory)],
            ['Free RAM', this.formatBytes(d.free_physical_memory)],
            ['JVM Memory', this.formatBytes(usedMem) + ' / ' + this.formatBytes(maxMem) + ' (' + memPercent + '%)'],
            ['JVM Version', (d.jvm_name || '') + ' ' + (d.jvm_version || '')],
            ['JVM Uptime', hours + 'h ' + mins + 'm'],
        ];

        document.getElementById('sysGrid').innerHTML = items.map(([label, value]) =>
            `<div class="system-item"><span class="system-item-label">${label}</span><span class="system-item-value">${escapeHTML(String(value))}</span></div>`
        ).join('');
    },

    formatBytes(bytes) {
        if (!bytes) return '-';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let i = 0;
        let val = bytes;
        while (val >= 1024 && i < units.length - 1) { val /= 1024; i++; }
        return val.toFixed(1) + ' ' + units[i];
    }
};
