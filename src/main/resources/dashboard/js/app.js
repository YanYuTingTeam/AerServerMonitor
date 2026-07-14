

const App = {
    currentPage: null,

    pages: {
        'overview': { title: 'Overview', render: () => OverviewPage.render() },
        'players': { title: 'Players', render: () => PlayersPage.render() },
        'servers': { title: 'Servers', render: () => ServersPage.render() },
        'chat': { title: 'Chat', render: () => ChatPage.render() },
        'events': { title: 'Events', render: () => EventsPage.render() },
        'console': { title: 'Console', render: () => ConsolePage.render() },
        'system': { title: 'System', render: () => SystemPage.render() },
        'webhooks': { title: 'Webhooks', render: () => WebhooksPage.render() },
        'logs': { title: 'Logs', render: () => LogsPage.render() },
        'admins': { title: 'Admins', render: () => AdminsPage.render() },
        'settings': { title: 'Settings', render: () => SettingsPage.render() },
    },

    init() {

        this.checkAuth();

        window.addEventListener('hashchange', () => this.route());

        this.route();

        WS.connect();

        const token = localStorage.getItem('token');
        if (token) {
            try {
                const payload = JSON.parse(atob(token.split('.')[1]));
                document.getElementById('currentUser').textContent = payload.username || 'admin';
            } catch (e) {}
        }
    },

    async checkAuth() {
        const token = localStorage.getItem('token');
        if (!token) {
            location.href = API.basePath + '/dashboard/login.html';
            return;
        }
        const resp = await API.verify();
        if (!resp || resp.code !== 200) {
            localStorage.removeItem('token');
            location.href = API.basePath + '/dashboard/login.html';
            return;
        }
        if (resp.data && resp.data.need_password_change) {
            location.href = API.basePath + '/dashboard/setup.html';
        }
    },

    route() {
        const { path } = parseHash();
        const pageName = path.replace(/^\//, '') || 'overview';

        if (!this.pages[pageName]) {
            location.hash = '#/';
            return;
        }

        document.querySelectorAll('.nav-item').forEach(el => {
            el.classList.toggle('active', el.dataset.page === pageName);
        });

        document.getElementById('pageTitle').textContent = this.pages[pageName].title;

        WS.clearAll();

        this.currentPage = pageName;
        this.pages[pageName].render();
    }
};

function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('open');
}

function logout() {
    localStorage.removeItem('token');
    location.href = API.basePath + '/dashboard/login.html';
}

document.addEventListener('DOMContentLoaded', () => App.init());
