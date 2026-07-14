

const SettingsPage = {
    async render() {
        const container = document.getElementById('pageContent');
        container.innerHTML = `
            <div class="grid grid-2">
                <div class="card fade-up">
                    <div class="card-title">Change Password</div>
                    <div style="margin-top:12px;">
                        <div class="form-group" style="margin-bottom:12px;">
                            <label style="font-size:0.75rem;color:var(--text-secondary);margin-bottom:4px;display:block;">Current Password</label>
                            <input class="input" type="password" id="settingsOldPw">
                        </div>
                        <div class="form-group" style="margin-bottom:12px;">
                            <label style="font-size:0.75rem;color:var(--text-secondary);margin-bottom:4px;display:block;">New Password</label>
                            <input class="input" type="password" id="settingsNewPw">
                        </div>
                        <div class="form-group" style="margin-bottom:12px;">
                            <label style="font-size:0.75rem;color:var(--text-secondary);margin-bottom:4px;display:block;">Confirm</label>
                            <input class="input" type="password" id="settingsConfirmPw">
                        </div>
                        <button class="btn btn-primary" onclick="SettingsPage.changePw()">Change Password</button>
                        <div id="settingsPwMsg" style="margin-top:8px;font-size:0.8rem;"></div>
                    </div>
                </div>
                <div class="card fade-up">
                    <div class="card-title">About</div>
                    <div style="margin-top:12px;font-size:0.85rem;color:var(--text-secondary);">
                        <p><strong>AerServerMonitor</strong> v1.0</p>
                        <p style="margin-top:8px;">BungeeCord server monitoring plugin with HTTP API, WebSocket, and Dashboard.</p>
                        <p style="margin-top:8px;">
                            <a href="https://github.com/YanYuTingTeam/AerServerMonitor" target="_blank">GitHub Repository</a>
                        </p>
                    </div>
                </div>
            </div>
        `;
    },

    async changePw() {
        const oldPw = document.getElementById('settingsOldPw').value;
        const newPw = document.getElementById('settingsNewPw').value;
        const confirmPw = document.getElementById('settingsConfirmPw').value;
        const msg = document.getElementById('settingsPwMsg');

        if (newPw !== confirmPw) {
            msg.style.color = 'var(--danger)';
            msg.textContent = 'Passwords do not match';
            return;
        }

        const resp = await API.changePassword(oldPw, newPw);
        if (resp && resp.code === 200) {
            msg.style.color = 'var(--success)';
            msg.textContent = 'Password changed successfully';
        } else {
            msg.style.color = 'var(--danger)';
            msg.textContent = resp?.message || 'Failed';
        }
    }
};
