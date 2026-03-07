# Camunda Location-Aware Workflow System
GPS-tracked workflows with WebSocket real-time tracking and geofencing.

## Quick Start
```
mvn clean install
mvn spring-boot:run
```

**Access:**
- Camunda: http://localhost:8082/camunda
- GPS Tracker: http://localhost:8082/gps.html

 ## How to Login

**Available Users:**

| User | Password | Role | Email |
|------|----------|------|-------|
| `acataluffi` | `a` | Student | andrea.cataluffi@studenti.unicam.it |
| `lmozzoni` | `a` | Tutor | luca.mozzoni@unicam.it |
| `a` | `a` | Admin | - |

## How to Start the GPS Process

### 1. Open Camunda Cockpit
- Go to: http://localhost:8082/camunda
- Login: `acataluffi` / `a`

### 2. Start Process Instance
1. Click **Cockpit** → **Processes**
2. Deploy from Camunda Modeler
4. Click **Start Instance**
5. Start processes with same Business Key

How to Simulate Coordinate Sending
1. Open GPS Tracker

Go to: http://localhost:8082/gps.html

2. Connect to Process

User ID: acataluffi (matches participantId)
Business Key: defined bk (matches process businessKey)
Click Connect

3. Send Location (Manual)
Set coordinates for a specific place

4. Send Location (Automatic)

Click Start Tracking
Server polls every 5 seconds
Use Randomize buttons to simulate movement
Click Stop Tracking when done



