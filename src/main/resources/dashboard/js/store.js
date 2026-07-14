

const Store = {
    state: {
        servers: new Map(),
        players: new Map(),
        chat: [],
        stats: { totalOnline: 0, serverCount: 0, playerCount: 0, snapshotTime: 0 }
    },

    subscribers: new Set(),
    notifyTimer: null,
    notifyDelayMs: 50,

    subscribe(fn) {
        this.subscribers.add(fn);
        return () => this.subscribers.delete(fn);
    },

    notify() {
        if (this.notifyTimer) return;
        this.notifyTimer = setTimeout(() => {
            this.notifyTimer = null;
            this.subscribers.forEach(fn => {
                try { fn(this.state); } catch (e) { console.error('Store subscriber error:', e); }
            });
        }, this.notifyDelayMs);
    },

    notifyNow() {
        if (this.notifyTimer) {
            clearTimeout(this.notifyTimer);
            this.notifyTimer = null;
        }
        this.subscribers.forEach(fn => {
            try { fn(this.state); } catch (e) {}
        });
    },

    initFromSnapshot(snap) {
        if (!snap || !snap.data) return;
        const d = snap.data;
        this.state.servers.clear();
        this.state.players.clear();
        this.state.chat = [];

        (d.servers || []).forEach(s => {
            this.state.servers.set(s.name, s);
        });

        (d.players || []).forEach(p => {
            this.state.players.set(p.uuid, p);
        });

        this.state.stats = {
            totalOnline: d.total_online || 0,
            serverCount: d.server_count || 0,
            playerCount: d.player_count || 0,
            snapshotTime: d.snapshot_time || Date.now()
        };

        this.notifyNow();
    },

    applyEvent(event, data) {
        if (!event || !data) return;
        switch (event) {
            case 'playerJoin': {
                const p = data.player || {};
                this.state.players.set(p.uuid, {
                    uuid: p.uuid,
                    name: p.name,
                    server: '',
                    ip: p.ip || '',
                    firstjoin: data.time || Date.now(),
                    lastjoin: data.time || Date.now()
                });
                this.state.stats.totalOnline = this.state.players.size;
                this.state.stats.playerCount = this.state.players.size;
                this.notify();
                break;
            }
            case 'playerLeave': {
                const p = data.player || {};
                if (p.uuid) {
                    this.state.players.delete(p.uuid);
                    this.state.stats.totalOnline = this.state.players.size;
                    this.state.stats.playerCount = this.state.players.size;
                    this.notify();
                }
                break;
            }
            case 'playerJoinServer': {
                const p = data.player || {};
                const target = data.target || {};
                if (p.uuid) {
                    const existing = this.state.players.get(p.uuid);
                    if (existing) {
                        existing.server = target.name || '';
                        existing.lastjoin = data.time || Date.now();
                        this.state.players.set(p.uuid, existing);
                        this.notify();
                    }
                }
                break;
            }
            case 'playerChat': {
                const p = data.player || {};
                const m = data.message || {};
                const s = data.server || {};
                const msg = {
                    msgid: m.msgid,
                    time: data.time || Date.now(),
                    name: p.name,
                    uuid: p.uuid,
                    server: { name: s.name },
                    content: m.content
                };
                this.state.chat.push(msg);
                if (this.state.chat.length > 100) this.state.chat.shift();
                this.notify();
                break;
            }
            case 'playerExecuteCmd':
            case 'playerWasKicked':
            case 'proxyPing':

                this.notify();
                break;
            default:

                break;
        }
    },

    loadChatHistory(messages) {
        if (!Array.isArray(messages)) return;
        this.state.chat = messages.slice(0, 100);
        this.notify();
    },

    getPlayersByServer(serverName) {
        const result = [];
        for (const p of this.state.players.values()) {
            if (p.server === serverName) result.push(p);
        }
        return result;
    },

    reset() {
        this.state.servers.clear();
        this.state.players.clear();
        this.state.chat = [];
        this.state.stats = { totalOnline: 0, serverCount: 0, playerCount: 0, snapshotTime: 0 };
        this.subscribers.clear();
    }
};
