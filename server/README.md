# Scenerio Signaling Server

This server is for the live camera-sharing version of the app.

It does not carry video itself. The Android phones send media directly to each other with WebRTC. This server only:

- lets two people join the same room
- tells each phone when the other person joins
- relays WebRTC `offer`, `answer`, and `ice-candidate` messages

## 1. Install Node.js

Install Node.js 20 or newer.

## 2. Install dependencies

Run these commands inside the `server` folder:

```powershell
npm install
copy .env.example .env
```

## 3. Start the server

```powershell
npm start
```

## Render deployment

You can deploy this server on Render using the `render.yaml` file in the project root.

### Option 1: Blueprint deploy

1. Push this project to GitHub.
2. Open Render and choose **New +** > **Blueprint**.
3. Select your GitHub repository.
4. Render will detect `render.yaml` and create the web service.

### Option 2: Manual web service

Use these settings:

- Root Directory: `server`
- Runtime: `Node`
- Build Command: `npm install`
- Start Command: `npm start`
- Instance Type: `Free`

Environment variables:

- `PORT=8080`
- `CLIENT_ORIGIN=*`

After deploy, your server URL will look like:

```text
https://scenerio-signaling-server.onrender.com
```

Health check:

```text
https://YOUR-RENDER-SERVICE.onrender.com/health
```

Server URL examples:

- local PC from Android emulator: `http://10.0.2.2:8080`
- local PC from physical phone on same Wi-Fi: `http://YOUR_PC_LAN_IP:8080`
- cloud VPS: `http://YOUR_SERVER_IP:8080` or `https://YOUR_DOMAIN`

## 4. Verify it is running

Open:

```text
http://YOUR_SERVER_IP:8080/health
```

You should get JSON showing `ok: true`.

## 5. Required socket events

Client to server:

- `join-room`
- `offer`
- `answer`
- `ice-candidate`
- `leave-room`

Server to client:

- `connected`
- `room-state`
- `peer-joined`
- `peer-left`
- `offer`
- `answer`
- `ice-candidate`
- `join-error`

## 6. Production notes

For real cross-city use, you will also need:

- STUN servers
- TURN server for difficult networks
- HTTPS/WSS in production

Recommended next production step:

- keep this signaling server
- add a TURN server with `coturn`
- update the Android app to use WebRTC with this signaling server
