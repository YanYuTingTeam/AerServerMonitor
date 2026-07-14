

const API = {
    basePath: '',

    getToken() {
        return localStorage.getItem('token');
    },

    async request(method, path, body) {
        const headers = { 'Content-Type': 'application/json' };
        const token = this.getToken();
        if (token) headers['Authorization'] = 'Bearer ' + token;

        if (path.startsWith('/player/') || path.startsWith('/server/') ||
            path.startsWith('/chat/') || path.startsWith('/system/') || path.startsWith('/log/')) {
            const now = Date.now().toString();
            headers['a'] = now;

            headers['b'] = await this.computeHmac(method, body);
        }

        const opts = { method, headers };
        if (body && method !== 'GET') opts.body = typeof body === 'string' ? body : JSON.stringify(body);

        try {
            const resp = await fetch(this.basePath + path, opts);
            if (resp.status === 401 && !path.includes('/auth/')) {
                localStorage.removeItem('token');
                location.href = this.basePath + '/dashboard/login.html';
                return null;
            }
            return await resp.json();
        } catch (e) {
            console.error('API error:', e);
            return { code: 0, message: 'Connection failed', data: {} };
        }
    },

    async computeHmac(method, body) {

        return 'dashboard-internal';
    },

    get(path) { return this.request('GET', path); },
    post(path, body) { return this.request('POST', path, body); },

    getPlayerInfo(uuid, name) {
        let params = [];
        if (uuid) params.push('uuid=' + encodeURIComponent(uuid));
        if (name) params.push('name=' + encodeURIComponent(name));
        return this.get('/player/info?' + params.join('&'));
    },
    kickPlayer(uuid, name, reason) {
        let params = [];
        if (uuid) params.push('uuid=' + encodeURIComponent(uuid));
        if (name) params.push('name=' + encodeURIComponent(name));
        return this.post('/player/kick?' + params.join('&'), reason || '');
    },

    getServerList(ping) {
        return this.get('/server/list?ping=' + (ping ? 'true' : 'false'));
    },
    getServerInfo(name, ping) {
        return this.get('/server/info?name=' + encodeURIComponent(name) + '&ping=' + (ping ? 'true' : 'false'));
    },

    getChatHistory(timeFrom, timeTo) {
        let params = [];
        if (timeFrom) params.push('timefrom=' + timeFrom);
        if (timeTo) params.push('timeto=' + timeTo);
        return this.get('/chat/history?' + params.join('&'));
    },

    getSystemStatus() { return this.get('/system/status'); },

    getLogs(opts) {
        let params = [];
        if (opts.t) params.push('t=' + opts.t);
        if (opts.offset) params.push('offset=' + opts.offset);
        if (opts.timefrom) params.push('timefrom=' + opts.timefrom);
        if (opts.timeto) params.push('timeto=' + opts.timeto);
        if (opts.type) params.push('type=' + opts.type);
        return this.get('/log/get?' + params.join('&'));
    },
    getLogById(id) { return this.get('/log/getlog?id=' + encodeURIComponent(id)); },
    getMsgById(id) { return this.get('/log/getmsg?id=' + encodeURIComponent(id)); },
    getEventById(id) { return this.get('/log/getevent?id=' + encodeURIComponent(id)); },

    login(username, password) {
        return this.post('/api/auth/login', { username, password });
    },
    changePassword(oldPw, newPw) {
        return this.post('/api/auth/change-password', { old: oldPw, new: newPw });
    },
    verify() { return this.get('/api/auth/verify'); }
};
