# EABPMN Monorepo

This monorepo contains the complete EABPMN (Environment-Aware BPMN) ecosystem, including the Camunda Modeler with custom plugins, the BPENV Modeler library, and supporting infrastructure.

## 📁 Project Structure

```
eabpmn-monorepo/
├── camunda-modeler/          # Camunda Modeler fork with EABPMN plugins
│   ├── resources/
│   │   └── plugins/
│   │       └── BPMNEnv-Aware-Plugins/  # Custom BPMN plugin
│   ├── app/                  # Electron app
│   └── client/              # React client
├── bpenv-modeler/           # BPENV Modeler library (React components)
├── camnuda-bpmn-engine/     # Java BPMN engine
└── docker-compose.yml       # Keycloak & PostgreSQL setup

```

## 🚀 Quick Start

### Prerequisites

- **Node.js** (v18 or higher)
- **npm** (v9 or higher)
- **Docker** and **Docker Compose** (for Keycloak/PostgreSQL)
- **Java** (JDK 11+) - for the BPMN engine
- **Maven** - for building the Java engine

### 1. Install Dependencies

**Important:** Install and build `bpenv-modeler` first, as `camunda-modeler` depends on it.

#### BPENV Modeler Library (Required First)

```bash
cd bpenv-modeler
npm install
npm run build
```

#### Camunda Modeler with EABPMN Plugin

```bash
cd camunda-modeler
npm run install-all
```

This will:
1. Install dependencies for the EABPMN plugin (`resources/plugins/BPMNEnv-Aware-Plugins`)
2. Install dependencies for the Camunda Modeler

**Note:** Make sure `bpenv-modeler` is built before running `install-all`, as the Camunda Modeler references it via local file path.

#### Java BPMN Engine

```bash
cd camnuda-bpmn-engine
mvn clean install
```

## 🛠️ Development

### Camunda Modeler

#### Development with EABPMN Plugin

**⚠️ Windows Users:** If you're on Windows, use **Git Bash** to run these commands, as there is a bug when building on Windows devices with PowerShell/CMD. See [this forum discussion](https://forum.camunda.io/t/cant-build-camunda-modeler/15177/4) for more details.

**Initial Setup:**
```bash
cd camunda-modeler
npm run all
npm run build
npm run dev:plugin
```

**⚠️ Important:** After running `npm run dev:plugin` or `npm run dev:plugin:watch`, if the Electron app window appears empty or blank, **wait a bit** (30-60 seconds) as it may still be completing the initial setup and building processes. If it's still white/empty after waiting, then you can run the dev command again.

**Development with Hot Reload (Recommended):**
```bash
cd camunda-modeler
npm run dev:plugin:watch
```

This will:
1. **First** (sequentially): Build the EABPMN plugin, build `bpenv-modeler`, and build the preload script
2. **Then** (in parallel): Start watch modes for `bpenv-modeler` and the EABPMN plugin, and start the Electron app and React client in dev mode

**Note:** The `dev:plugin:watch` command ensures all dependencies are built first, then runs all watch processes in parallel. Changes to `bpenv-modeler` will automatically rebuild and be available in the Camunda Modeler without manual rebuilds. The initial sequential build prevents race conditions during startup.

**⚠️ Important:** After initial setup, when running `npm run dev:plugin` or `npm run dev:plugin:watch`, if the Electron app window appears empty or blank, **wait a bit** (30-60 seconds) as it may still be completing the initial setup and building processes. Do **not** run the dev command again immediately, as this could interfere with the ongoing build process.

### BPENV Modeler Library

```bash
cd bpenv-modeler
npm run dev      # Development mode with hot reload
npm run build    # Production build
npm run test     # Run tests
```

### Plugin Development

The EABPMN plugin is located at `camunda-modeler/resources/plugins/BPMNEnv-Aware-Plugins/`.

**Building the plugin:**

```bash
cd camunda-modeler/resources/plugins/BPMNEnv-Aware-Plugins
npm install
npm run build        # Production build
npm run dev          # Watch mode for development
```

**Note:** After making changes to the plugin, you need to rebuild it. The `dev:plugin` script handles this automatically.

## 📦 Components

### Camunda Modeler

A fork of Camunda Modeler 5.38+ with custom EABPMN extensions:
- Custom task types (MOVEMENT, BINDING, UNBINDING)
- Environment-aware properties panel
- Spatial BPMN modeling capabilities

**Location:** `camunda-modeler/`

### BPENV Modeler Library

A React component library for environment-aware BPMN modeling:
- Map visualization components
- Environment management UI
- Spatial data handling

**Location:** `bpenv-modeler/`

**Version:** 0.0.23

### EABPMN Plugin

Custom Camunda Modeler plugin that extends BPMN modeling with:
- Space-aware task types
- Environment properties panel
- Custom palette entries
- Replace menu extensions

**Location:** `camunda-modeler/resources/plugins/BPMNEnv-Aware-Plugins/`

### Java BPMN Engine

Spring Boot application for executing environment-aware BPMN processes.

**Location:** `camnuda-bpmn-engine/`

## 🐳 Docker Services

### Keycloak & PostgreSQL Setup

This project provides a Docker Compose configuration to run **Keycloak** (Identity and Access Management) backed by a **PostgreSQL** database.

#### Prerequisites

- [Docker](https://www.docker.com/get-started) installed on your machine.
- [Docker Compose](https://docs.docker.com/compose/install/) (usually included with Docker Desktop).

#### Getting Started

**1. Environment Configuration**

The project relies on environment variables for configuration. Create a `.env` file in the root directory if it doesn't exist.

**Example `.env` file:**

```env
POSTGRES_DB=eabpmn
POSTGRES_USER=admin
POSTGRES_PASSWORD=admin
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
```

**2. Start the Services**

Run the following command to start the containers in the background:

```bash
docker-compose up -d
```

#### Accessing the Services

**Keycloak**
- **URL:** [http://localhost:8083](http://localhost:8083)
- **Admin Console:** Click on "Administration Console"
- **Username:** `admin` (or as defined in `.env`)
- **Password:** `admin` (or as defined in `.env`)

**PostgreSQL**
- **Host:** `localhost`
- **Port:** `5432`
- **Database:** `eabpmn`
- **Username:** `admin`
- **Password:** `admin`

#### Data Persistence

Database data is stored locally in the `./postgres_data` folder.
- This folder is **bind-mounted** to the PostgreSQL container.
- It is included in `.gitignore`, so your local data will **not** be pushed to the repository.
- If you delete this folder, your database will be reset.

## 🔗 Dependencies

### Local Package References

The `camunda-modeler` and the EABPMN plugin reference `bpenv-modeler` using the `file:` protocol:

- `camunda-modeler/package.json`: `"bpenv-modeler": "file:../bpenv-modeler"`
- `camunda-modeler/resources/plugins/BPMNEnv-Aware-Plugins/package.json`: `"bpenv-modeler": "file:../../../../bpenv-modeler"`

This ensures they use the local version of `bpenv-modeler` instead of the npm package.

## 📝 Development Workflow

1. **Initial Setup:**
   ```bash
   # Build bpenv-modeler first (required dependency)
   cd bpenv-modeler
   npm install && npm run build
   
   # Then install all dependencies for camunda-modeler
   cd ../camunda-modeler
   npm run install-all
   ```

2. **Daily Development:**
   ```bash
   cd camunda-modeler
   npm run dev:plugin:watch    # Recommended: with hot reload for bpenv-modeler and plugin
   # OR
   npm run dev:plugin          # Without watch mode (manual rebuilds needed)
   ```

3. **After Plugin Changes:**
   - If using `dev:plugin:watch`: Changes to `bpenv-modeler` and the plugin rebuild automatically
   - If using `dev:plugin`: The plugin needs to be rebuilt manually with `npm run eabpm-plugin:build`
   - Changes to `bpenv-modeler` require rebuilding it: `cd ../bpenv-modeler && npm run build`

## 🧪 Testing

### Camunda Modeler
```bash
cd camunda-modeler
npm test
```

### BPENV Modeler
```bash
cd bpenv-modeler
npm run test
```

## 📚 Additional Resources

- [Camunda Modeler Documentation](https://docs.camunda.io/docs/components/modeler/desktop-modeler/)
- [Plugin Development Guide](https://docs.camunda.io/docs/components/modeler/desktop-modeler/plugins/)

## 🤝 Contributing

When contributing:
1. Make sure to run `npm run install-all` after pulling changes
2. Rebuild the plugin if you modify it: `npm run eabpm-plugin:build`
3. Test your changes before committing

## 📄 License

See individual component licenses in their respective directories.
