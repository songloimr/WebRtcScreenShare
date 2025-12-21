const express = require('express');
const { createServer } = require("http");
const { Server } = require("socket.io");

const app = express();
app.use(express.static('public'));
app.use(express.json());

const httpServer = createServer(app);
const io = new Server(httpServer, {
    cors: {
        origin: "*",
    }
});

io.on("connection", (socket) => {
    socket.on("offer", (offer) => {
        socket.broadcast.emit("offer", offer);
    })
    socket.on("answer", (answer) => {
        socket.broadcast.emit("answer", answer);
    })
    socket.on("ice", (candidate) => {
        socket.broadcast.emit("ice", candidate);
    })
});

httpServer.listen(3333);
