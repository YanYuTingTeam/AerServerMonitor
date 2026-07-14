

function getTheme() {
    return localStorage.getItem('theme') ||
        (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
}

function applyTheme(t) {
    document.documentElement.setAttribute('data-theme', t);
    const btn = document.getElementById('themeBtn');
    if (btn) btn.textContent = t === 'dark' ? '\u2600' : '\u263e';
}

function toggleTheme() {
    const t = getTheme() === 'dark' ? 'light' : 'dark';
    localStorage.setItem('theme', t);
    applyTheme(t);
}

applyTheme(getTheme());
