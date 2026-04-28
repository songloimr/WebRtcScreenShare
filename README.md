# WebRTC Screen Share to Web

Android application that allows screen sharing directly to web browsers via WebRTC.

## Installation

### 1. Install Signaling Server

```bash
cd signaling-server
npm install
npm start
```

Signaling server will run at `http://localhost:3333`

### 2. Install Android Application

1. Clone repository:
```bash
git clone <repository-url>
```

2. Open project in Android Studio

3. Sync project with Gradle files

4. Build and run the application

### 3. Connection Configuration

#### Same Network Setup
- Ensure Android device and computer running web client are on the same network
- Update signaling server address in the application if needed

#### Different Network Setup (Using ngrok)
If your Android device and signaling server are on different networks, use ngrok to expose your local server:

1. Install ngrok:
```bash
# macOS
brew install ngrok

# Or download from https://ngrok.com/download
```

2. Start ngrok tunnel:
```bash
ngrok http 3333
```

3. Copy the forwarding URL (e.g., `https://xxxx-xx-xx-xx-xx.ngrok-free.app`)

4. Update the signaling server URL:

**Android App:**
- Replace with your ngrok URL: `connect("https://your-ngrok-url.ngrok-free.app")` in `ScreenRecordingService.kt`


## Usage

### 1. Start Signaling Server
```bash
cd signaling-server
npm start
```

### 2. Open Web Client
- Open browser and navigate to `http://localhost:3333`
- Configure stream settings (optional):
  - **Resolution**: Choose between 480p, 720p (default), 1080p, or 1440p
  - **Frame Rate**: Select 15, 30 (default), or 60 FPS
  - **Bitrate**: Adjust from 0.5 to 8.0 Mbps using the slider
  - **Show Stats**: Toggle to display real-time FPS and bitrate statistics
- Click "Call" button to initiate connection

### 3. Use Android Application
1. Open the application
2. Click "Start Service" button to start screen recording service
3. Grant screen recording permission when prompted
4. Screen will be shared via WebRTC to web client

### 4. Adjust Settings (Optional)
- While streaming, you can modify resolution, FPS, or bitrate settings
- Click "Apply Settings" to send new configuration to the Android app
- Settings will take effect on the next stream session

## Notes

- Application requires screen recording permission
- Signaling server is required to establish WebRTC connection
- Works best on Android 6.0+
- **Web Client Features**:
  - Real-time stream quality monitoring (FPS and bitrate)
  - Dynamic quality adjustment during streaming
  - Connection status indicators with visual feedback
- For production use, consider deploying the signaling server to a cloud service (Heroku, AWS, etc.)
- When using ngrok, the free tier has connection limits and the URL changes on restart

## License

MIT License