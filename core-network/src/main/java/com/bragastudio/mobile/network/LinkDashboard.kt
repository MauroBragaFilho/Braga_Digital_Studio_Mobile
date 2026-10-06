package com.bragastudio.mobile.network

/** Página HTML do dashboard do Link (servida em `GET /`, mesma origem do WebSocket). */
internal object LinkDashboard {
    val html: String by lazy {
        """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>BDSM Link Dashboard</title>
            <style>
                :root {
                    --bg-color: #0F172A;
                    --panel-bg: rgba(30, 41, 59, 0.7);
                    --accent-color: #38BDF8;
                    --text-primary: #F8FAFC;
                    --text-secondary: #94A3B8;
                    --border-color: rgba(255, 255, 255, 0.1);
                    --success: #10B981;
                    --warning: #F59E0B;
                    --danger: #EF4444;
                }

                body {
                    margin: 0;
                    padding: 0;
                    background-color: transparent;
                    color: var(--text-primary);
                    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                    overflow: hidden;
                }

                /* When loaded in OBS, the body might have a default bg. We want to be transparent if possible, or use our dark bg */
                .dashboard-container {
                    width: 100vw;
                    height: 100vh;
                    background: var(--bg-color);
                    display: flex;
                    flex-direction: column;
                    box-sizing: border-box;
                    padding: 16px;
                }

                .header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    margin-bottom: 16px;
                    padding-bottom: 12px;
                    border-bottom: 1px solid var(--border-color);
                }

                .title-area {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }

                .logo {
                    width: 12px;
                    height: 12px;
                    border-radius: 50%;
                    background-color: var(--text-secondary);
                }

                /* Indicador REC: reflete a gravação REAL do aparelho (campo isRecording). */
                .logo.recording {
                    background-color: var(--danger);
                    box-shadow: 0 0 10px var(--danger);
                    animation: pulse 2s infinite;
                }

                .tally-off { color: var(--text-secondary); }
                .tally-preview { color: var(--success); font-weight: 700; }
                .tally-program { color: var(--danger); font-weight: 700; }

                .title {
                    font-size: 1.1rem;
                    font-weight: 600;
                    letter-spacing: 0.5px;
                }

                .device-name {
                    font-size: 0.85rem;
                    color: var(--accent-color);
                    font-weight: 500;
                }

                .grid {
                    display: grid;
                    grid-template-columns: repeat(2, 1fr);
                    gap: 12px;
                    flex-grow: 1;
                }

                .card {
                    background: var(--panel-bg);
                    border: 1px solid var(--border-color);
                    border-radius: 12px;
                    padding: 16px;
                    display: flex;
                    flex-direction: column;
                    justify-content: center;
                    backdrop-filter: blur(10px);
                    -webkit-backdrop-filter: blur(10px);
                    transition: transform 0.2s, background-color 0.2s;
                }

                .card:hover {
                    background: rgba(30, 41, 59, 0.9);
                    transform: translateY(-2px);
                }

                .card-title {
                    font-size: 0.75rem;
                    text-transform: uppercase;
                    color: var(--text-secondary);
                    letter-spacing: 1px;
                    margin-bottom: 8px;
                }

                .card-value {
                    font-size: 1.8rem;
                    font-weight: 700;
                    display: flex;
                    align-items: baseline;
                    gap: 4px;
                }

                .card-unit {
                    font-size: 0.9rem;
                    font-weight: 500;
                    color: var(--text-secondary);
                }

                .battery-indicator {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .battery-icon {
                    width: 24px;
                    height: 12px;
                    border: 2px solid var(--text-primary);
                    border-radius: 3px;
                    position: relative;
                    padding: 2px;
                }

                .battery-icon::after {
                    content: '';
                    position: absolute;
                    right: -5px;
                    top: 2px;
                    width: 3px;
                    height: 8px;
                    background: var(--text-primary);
                    border-radius: 0 2px 2px 0;
                }

                .battery-level {
                    height: 100%;
                    background: var(--success);
                    border-radius: 1px;
                    transition: width 0.5s ease-in-out, background-color 0.5s ease-in-out;
                }

                .status-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 50%;
                    background-color: var(--text-secondary);
                    margin-right: 6px;
                    display: inline-block;
                }

                .status-connected {
                    background-color: var(--success);
                    box-shadow: 0 0 8px var(--success);
                }

                .status-disconnected {
                    background-color: var(--danger);
                    box-shadow: 0 0 8px var(--danger);
                }

                .footer {
                    margin-top: 16px;
                    font-size: 0.75rem;
                    color: var(--text-secondary);
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }

                @keyframes pulse {
                    0% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.7); }
                    70% { box-shadow: 0 0 0 6px rgba(239, 68, 68, 0); }
                    100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0); }
                }

                /* Google Fonts */
                @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
            </style>
        </head>
        <body>
            <div class="dashboard-container">
                <div class="header">
                    <div class="title-area">
                        <div class="logo" id="rec-indicator"></div>
                        <div class="title">BDSM Monitor</div>
                    </div>
                    <div class="device-name" id="device-name">Waiting...</div>
                </div>

                <div class="grid">
                    <div class="card">
                        <div class="card-title">FPS</div>
                        <div class="card-value">
                            <span id="fps-val">--</span>
                            <span class="card-unit">fps</span>
                        </div>
                    </div>

                    <div class="card">
                        <div class="card-title">Battery</div>
                        <div class="battery-indicator">
                            <div class="battery-icon">
                                <div class="battery-level" id="battery-bar" style="width: 0%;"></div>
                            </div>
                            <div class="card-value" style="font-size: 1.2rem;">
                                <span id="battery-val">--</span><span class="card-unit">%</span>
                            </div>
                        </div>
                        <div id="charging-status" style="font-size: 0.75rem; color: var(--accent-color); margin-top: 4px;"></div>
                    </div>

                    <div class="card" style="grid-column: span 2;">
                        <div class="card-title">Camera & Source</div>
                        <div style="display: flex; justify-content: space-between; align-items: flex-end;">
                            <div class="card-value" style="font-size: 1.2rem;">
                                <span id="lens-val">--</span>
                            </div>
                            <div style="font-size: 0.9rem; color: var(--accent-color);" id="source-val">--</div>
                        </div>
                    </div>
                </div>

                <div class="footer">
                    <div>
                        <span class="status-dot" id="conn-dot"></span>
                        <span id="conn-text">Connecting...</span>
                    </div>
                    <div><span id="tally-val" class="tally-off">TALLY: OFF</span> &nbsp;|&nbsp; <span id="mic-val">Mic: --</span></div>
                </div>
            </div>

            <script>
                const UI = {
                    deviceName: document.getElementById('device-name'),
                    fps: document.getElementById('fps-val'),
                    batteryBar: document.getElementById('battery-bar'),
                    batteryVal: document.getElementById('battery-val'),
                    charging: document.getElementById('charging-status'),
                    lens: document.getElementById('lens-val'),
                    source: document.getElementById('source-val'),
                    mic: document.getElementById('mic-val'),
                    connDot: document.getElementById('conn-dot'),
                    connText: document.getElementById('conn-text'),
                    recIndicator: document.getElementById('rec-indicator'),
                    tally: document.getElementById('tally-val')
                };

                let ws = null;
                let reconnectInterval = null;

                let everOpened = false;
                let failCount = 0;
                let pairing = false;

                function getToken() {
                    const q = new URLSearchParams(window.location.search).get('token');
                    if (q) { try { localStorage.setItem('bdsm_token', q); } catch(e) {} return q; }
                    try { return localStorage.getItem('bdsm_token'); } catch(e) { return null; }
                }

                function clientId() {
                    try {
                        let id = localStorage.getItem('bdsm_client_id');
                        if (!id) { id = Math.random().toString(16).slice(2) + Date.now().toString(16); localStorage.setItem('bdsm_client_id', id); }
                        return id;
                    } catch(e) { return 'web-' + Date.now(); }
                }

                // Consentimento duplo: o operador clica aqui (lado OBS/navegador) e
                // depois aceita o mesmo codigo no celular.
                function startPairing() {
                    if (pairing) return;
                    pairing = true;
                    UI.connDot.className = 'status-dot';
                    UI.connText.innerHTML = 'Pareamento necessario. <a href="#" id="pair-btn" style="color:var(--accent-color)">Solicitar conexao</a>';
                    document.getElementById('pair-btn').onclick = (ev) => {
                        ev.preventDefault();
                        fetch('/api/pair/request', {method: 'POST', headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({clientId: clientId(), clientName: 'OBS / Navegador'})})
                          .then(r => r.ok ? r.json() : Promise.reject(r.status))
                          .then(j => {
                            UI.connText.innerText = 'Aceite no celular. Codigo: ' + j.code;
                            const t = setInterval(() => {
                                fetch('/api/pair/status/' + j.requestId).then(r => r.ok ? r.json() : Promise.reject(r.status)).then(st => {
                                    if (st.state === 'APPROVED' && st.token) {
                                        clearInterval(t);
                                        try { localStorage.setItem('bdsm_token', st.token); } catch(e) {}
                                        pairing = false; failCount = 0; connect();
                                    } else if (st.state !== 'PENDING') {
                                        clearInterval(t); pairing = false; UI.connText.innerText = 'Pedido recusado ou expirado'; setTimeout(startPairing, 2000);
                                    }
                                }).catch(() => { clearInterval(t); pairing = false; startPairing(); });
                            }, 1000);
                          })
                          .catch(() => { pairing = false; UI.connText.innerText = 'Ja existe um pedido pendente. Aguarde.'; setTimeout(startPairing, 4000); });
                    };
                }

                function connect() {
                    const token = getToken();
                    if (!token) { startPairing(); return; }
                    everOpened = false;
                    const wsUrl = 'ws://' + window.location.host + '/ws/link?token=' + encodeURIComponent(token);
                    ws = new WebSocket(wsUrl);

                    ws.onopen = () => {
                        everOpened = true; failCount = 0;
                        UI.connDot.className = 'status-dot status-connected';
                        UI.connText.innerText = 'Connected';
                        if (reconnectInterval) {
                            clearInterval(reconnectInterval);
                            reconnectInterval = null;
                        }
                    };

                    ws.onmessage = (event) => {
                        try {
                            const data = JSON.parse(event.data);
                            updateUI(data);
                        } catch(e) {
                            console.error("Error parsing WS data", e);
                        }
                    };

                    ws.onclose = () => {
                        if (!everOpened && ++failCount >= 2) {
                            // Token invalido/revogado: apaga e pede novo pareamento.
                            try { localStorage.removeItem('bdsm_token'); } catch(e) {}
                            if (reconnectInterval) { clearInterval(reconnectInterval); reconnectInterval = null; }
                            startPairing();
                            return;
                        }
                        UI.connDot.className = 'status-dot status-disconnected';
                        UI.connText.innerText = 'Disconnected';
                        setRecording(false);

                        // Try to reconnect every 3 seconds
                        if (!reconnectInterval) {
                            reconnectInterval = setInterval(connect, 3000);
                        }
                    };

                    ws.onerror = (err) => {
                        console.error("WebSocket error", err);
                        ws.close();
                    };
                }

                function setRecording(on) {
                    UI.recIndicator.classList.toggle('recording', !!on);
                }

                function updateUI(data) {
                    setRecording(data.isRecording);
                    const tally = (data.tally || 'OFF').toUpperCase();
                    UI.tally.innerText = 'TALLY: ' + tally;
                    UI.tally.className = 'tally-' + tally.toLowerCase();
                    UI.deviceName.innerText = data.deviceName || "BDSM Device";
                    UI.fps.innerText = data.fps || 0;

                    const bat = data.batteryLevel || 0;
                    UI.batteryVal.innerText = bat;
                    UI.batteryBar.style.width = bat + '%';

                    if (bat <= 20) {
                        UI.batteryBar.style.backgroundColor = 'var(--danger)';
                    } else if (bat <= 50) {
                        UI.batteryBar.style.backgroundColor = 'var(--warning)';
                    } else {
                        UI.batteryBar.style.backgroundColor = 'var(--success)';
                    }

                    if (data.isCharging) {
                        UI.charging.innerText = "⚡ Charging";
                    } else {
                        UI.charging.innerText = "";
                    }

                    UI.lens.innerText = data.cameraLens || "Unknown";
                    UI.source.innerText = (data.captureSource || "Unknown") + (data.ndiStreamName ? " | NDI: " + data.ndiStreamName : "");
                    UI.mic.innerText = "Mic: " + (data.microphone || "Unknown");
                }

                // Start connection
                connect();
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
