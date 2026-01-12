const express = require('express');
const WebSocket = require('ws');
const http = require('http');
const cors = require('cors');
const path = require('path');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static('public'));

// Store active connections
const connections = new Map();
const deviceStatus = new Map();

// WebSocket handling
wss.on('connection', (ws, req) => {
  // Extract device ID from URL
  const deviceId = req.url.split('/').pop();
  console.log(`Device connected: ${deviceId}`);
  
  connections.set(deviceId, ws);
  deviceStatus.set(deviceId, 'connected');
  
  // Send welcome message
  ws.send(JSON.stringify({
    type: 'welcome',
    message: 'Connected to Dasher Automate server',
    timestamp: Date.now()
  }));
  
  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message);
      handleDeviceMessage(deviceId, data);
    } catch (error) {
      console.error('Error parsing message:', error);
    }
  });
  
  ws.on('close', () => {
    console.log(`Device disconnected: ${deviceId}`);
    connections.delete(deviceId);
    deviceStatus.set(deviceId, 'disconnected');
  });
  
  ws.on('error', (error) => {
    console.error(`WebSocket error for ${deviceId}:`, error);
  });
});

// Handle messages from devices
function handleDeviceMessage(deviceId, data) {
  console.log(`Message from ${deviceId}:`, data.type);
  
  switch(data.type) {
    case 'status_update':
      deviceStatus.set(deviceId, data.status);
      broadcastToWebClients(deviceId, data);
      break;
      
    case 'offer_received':
    case 'action_taken':
      broadcastToWebClients(deviceId, data);
      break;
      
    case 'ping':
      const ws = connections.get(deviceId);
      if (ws) {
        ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
      }
      break;
  }
}

// Broadcast to web clients (same device ID)
function broadcastToWebClients(deviceId, message) {
  // In a real implementation, you'd send to web clients
  // connected to this specific device
  const ws = connections.get(`web-${deviceId}`);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
}

// REST API
app.get('/api/status/:deviceId', (req, res) => {
  const deviceId = req.params.deviceId;
  res.json({
    deviceId,
    status: deviceStatus.get(deviceId) || 'offline',
    timestamp: Date.now()
  });
});

app.post('/api/rules/:deviceId', (req, res) => {
  const deviceId = req.params.deviceId;
  const rules = req.body.rules;
  
  const ws = connections.get(deviceId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: 'update_rules',
      rules: rules,
      timestamp: Date.now()
    }));
    res.json({ success: true });
  } else {
    res.status(404).json({ error: 'Device not connected' });
  }
});

app.post('/api/control/:deviceId', (req, res) => {
  const deviceId = req.params.deviceId;
  const { action, enabled } = req.body;
  
  const ws = connections.get(deviceId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: action === 'toggle' ? 'toggle_service' : 'control',
      enabled: enabled,
      timestamp: Date.now()
    }));
    res.json({ success: true });
  } else {
    res.status(404).json({ error: 'Device not connected' });
  }
});

app.get('/connect/:code', (req, res) => {
  // Serve a connection page
  res.sendFile(path.join(__dirname, 'public', 'connect.html'));
});

// Serve main app
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Start server
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
