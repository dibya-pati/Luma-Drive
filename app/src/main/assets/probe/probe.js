// SPDX-License-Identifier: GPL-3.0-or-later
(() => {
  let socket, timer, lastPong = 0, echoes = 0;
  const state = document.getElementById('state');
  function connect() {
    clearInterval(timer);
    if (socket) { socket.onclose = null; socket.close(); }
    state.textContent = 'Checking two-way WebSocket traffic…';
    const current = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/echo`);
    socket = current;
    current.onopen = () => {
      if (socket !== current) return;
      lastPong = Date.now(); current.send('ping');
      timer = setInterval(() => {
        if (Date.now() - lastPong > 5000) state.textContent = 'No echo reply. Retry the connection.';
        if (current.readyState === WebSocket.OPEN) current.send('ping');
      }, 1000);
    };
    current.onmessage = event => {
      if (socket !== current || event.data !== 'pong') return;
      lastPong = Date.now(); echoes++;
      state.textContent = 'Two-way connection works.';
      document.getElementById('count').textContent = `Echoes: ${echoes}`;
    };
    current.onerror = () => { if (socket === current) state.textContent = 'WebSocket failed. The page loaded, but the streaming connection did not.'; };
    current.onclose = () => {
      if (socket !== current) return;
      clearInterval(timer); state.textContent = 'Connection closed. Tap Retry connection.';
    };
  }
  function stop() {
    clearInterval(timer);
    const old=socket;socket=null;
    if(old){old.onclose=null;old.close();}
    state.textContent='Check stopped. Tap Retry connection to start again.';
  }
  document.getElementById('stop').onclick=stop;
  document.getElementById('retry').onclick = connect;
  window.addEventListener('pagehide',stop);
  connect();
})();
