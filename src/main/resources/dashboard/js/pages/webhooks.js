

const WebhooksPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="card fade-up">
                <div class="card-title">Webhook History</div>
                <div class="table-container" id="webhookTable"><div class="empty">Loading...</div></div>
            </div>
        `;
        this.load();
    },

    async load() {

        document.getElementById('webhookTable').innerHTML =
            '<div class="empty">Webhook history will appear here once events are triggered.</div>';
    }
};
