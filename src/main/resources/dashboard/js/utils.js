

function escapeHTML(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatTime(ms) {
    if (!ms) return '-';
    const d = new Date(ms);
    return d.toLocaleString();
}

function formatShortTime(ms) {
    if (!ms) return '-';
    const d = new Date(ms);
    return d.toLocaleTimeString();
}

function formatNumber(n) {
    if (n == null) return '-';
    return n.toLocaleString();
}

function timeAgo(ms) {
    if (!ms) return '-';
    const diff = Date.now() - ms;
    if (diff < 60000) return 'just now';
    if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
    if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
    return Math.floor(diff / 86400000) + 'd ago';
}

function parseHash() {
    const hash = location.hash.slice(1) || '/';
    const parts = hash.split('?').filter(Boolean);
    return { path: parts[0] || '/', query: parts[1] || '' };
}

function debounce(fn, ms) {
    let timer;
    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), ms);
    };
}
