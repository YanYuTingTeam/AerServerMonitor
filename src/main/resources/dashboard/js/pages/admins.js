

const AdminsPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="card fade-up">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
                    <div class="card-title" style="margin-bottom:0;">Admin Management</div>
                </div>
                <div id="adminList"><div class="empty">Admin management available in future update</div></div>
            </div>
        `;
    }
};
