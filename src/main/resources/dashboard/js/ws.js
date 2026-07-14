

const WS = {
    socket: null,
    listeners: {},
    reconnectTimer: null,
    reconnectDelay: 1000,

    connect() {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsPort = parseInt(location.port) + 1;
        const url = protocol + '//' + location.hostname + ':' + wsPort;

        try {
            this.socket = new WebSocket(url);
            this.socket.onopen = () => {
                this.reconnectDelay = 1000;
                this.socket.send(JSON.stringify({ action: 'subscribe', events: ['all'] }));
            };
            this.socket.onmessage = (e) => {
                try {
                    const msg = JSON.parse(e.data);
                    if (msg.event && this.listeners[msg.event]) {
                        this.listeners[msg.event].forEach(fn => fn(msg.data));
                    }
                    if (this.listeners['*']) {
                        this.listeners['*'].forEach(fn => fn(msg));
                    }
                } catch (err) {}
            };
            this.socket.onclose = () => {
                this.scheduleReconnect();
            };
            this.socket.onerror = () => {
                this.socket.close();
            };
        } catch (e) {
            this.scheduleReconnect();
        }
    },

    scheduleReconnect() {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = setTimeout(() => {
            this.reconnectDelay = Math.min(this.reconnectDelay * 1.5, 30000);
            this.connect();
        }, this.reconnectDelay);
    },

    on(event, callback) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(callback);
    },

    off(event, callback) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(fn => fn !== callback);
    },

    clearAll() {
        this.listeners = {};
    }
};
