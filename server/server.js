require("dotenv").config();

const express = require("express");
const http = require("http");
const cors = require("cors");
const { Server } = require("socket.io");

const app = express();
const server = http.createServer(app);

const allowedOrigin = process.env.CLIENT_ORIGIN || "*";
const port = Number(process.env.PORT || 8080);

app.use(cors({ origin: allowedOrigin }));
app.use(express.json({ limit: "1mb" }));

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    service: "scenerio-signaling-server"
  });
});

const io = new Server(server, {
  cors: {
    origin: allowedOrigin,
    methods: ["GET", "POST"]
  }
});

const roomMembers = new Map();

function ensureRoom(roomCode) {
  if (!roomMembers.has(roomCode)) {
    roomMembers.set(roomCode, new Map());
  }
  return roomMembers.get(roomCode);
}

function emitRoomState(roomCode) {
  const room = roomMembers.get(roomCode);
  const participants = room
    ? Array.from(room.values()).map(({ socketId, ...participant }) => participant)
    : [];

  io.to(roomCode).emit("room-state", {
    roomCode,
    participants
  });
}

function removeParticipant(socket) {
  const roomCode = socket.data.roomCode;
  const participantId = socket.data.participantId;
  if (!roomCode || !participantId) {
    return;
  }

  const room = roomMembers.get(roomCode);
  if (!room) {
    return;
  }

  room.delete(participantId);
  socket.leave(roomCode);
  socket.to(roomCode).emit("peer-left", { participantId });

  if (room.size === 0) {
    roomMembers.delete(roomCode);
  } else {
    emitRoomState(roomCode);
  }
}

io.on("connection", (socket) => {
  socket.emit("connected", { socketId: socket.id });

  socket.on("join-room", (payload = {}) => {
    const roomCode = String(payload.roomCode || "").trim();
    const participantId = String(payload.participantId || "").trim();
    const participantName = String(payload.participantName || "").trim();

    if (!roomCode || !participantId || !participantName) {
      socket.emit("join-error", {
        message: "roomCode, participantId, and participantName are required."
      });
      return;
    }

    removeParticipant(socket);

    const room = ensureRoom(roomCode);
    room.set(participantId, {
      participantId,
      participantName,
      socketId: socket.id
    });

    socket.data.roomCode = roomCode;
    socket.data.participantId = participantId;
    socket.data.participantName = participantName;
    socket.join(roomCode);

    socket.to(roomCode).emit("peer-joined", {
      participantId,
      participantName
    });

    emitRoomState(roomCode);
  });

  socket.on("offer", ({ targetParticipantId, sdp } = {}) => {
    const roomCode = socket.data.roomCode;
    const senderId = socket.data.participantId;
    const senderName = socket.data.participantName;
    const room = roomMembers.get(roomCode);

    if (!room || !targetParticipantId || !sdp) {
      return;
    }

    const target = room.get(targetParticipantId);
    if (!target) {
      return;
    }

    io.to(target.socketId).emit("offer", {
      fromParticipantId: senderId,
      fromParticipantName: senderName,
      sdp
    });
  });

  socket.on("answer", ({ targetParticipantId, sdp } = {}) => {
    const roomCode = socket.data.roomCode;
    const senderId = socket.data.participantId;
    const room = roomMembers.get(roomCode);

    if (!room || !targetParticipantId || !sdp) {
      return;
    }

    const target = room.get(targetParticipantId);
    if (!target) {
      return;
    }

    io.to(target.socketId).emit("answer", {
      fromParticipantId: senderId,
      sdp
    });
  });

  socket.on("ice-candidate", ({ targetParticipantId, candidate } = {}) => {
    const roomCode = socket.data.roomCode;
    const senderId = socket.data.participantId;
    const room = roomMembers.get(roomCode);

    if (!room || !targetParticipantId || !candidate) {
      return;
    }

    const target = room.get(targetParticipantId);
    if (!target) {
      return;
    }

    io.to(target.socketId).emit("ice-candidate", {
      fromParticipantId: senderId,
      candidate
    });
  });

  socket.on("leave-room", () => {
    removeParticipant(socket);
  });

  socket.on("disconnect", () => {
    removeParticipant(socket);
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Scenerio signaling server listening on port ${port}`);
});
