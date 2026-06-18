# syncLingo Frontend

React 18 + TypeScript + Vite frontend for syncLingo Plus.

## Routes

- `/login` - login
- `/` - live interpretation workspace
- `/meetings` - meeting management and PDF/Word meeting files
- `/history` - meeting history, full-session recording, summaries, action items, and Teams text notifications
- `/terminology` - terminology and ASR hotwords
- `/voice-clone` - speaker voice enrollment
- `/cost-analysis` - usage and cost analysis
- `/admin` - user management and security operations for admin accounts
- `/share/:sessionId` - public session share page
- `/share/user/:userId` - public user share page

The old standalone Teams Bot page has been removed. Teams notifications are now triggered from meeting/history workflows and are routed through the Java backend `/bot-api/**` authorization proxy.

## Development

```bash
npm install
npm run dev
```

The dev server runs at `http://localhost:5173`.

Proxy behavior:

- `/api/**` -> `http://localhost:8080`
- `/bot-api/**` -> `http://localhost:8080`
- `/ws/**` -> `ws://localhost:8080`

## Build

```bash
npm run build
```

Deploy `dist/` to the Nginx static root, normally `/var/www/synclingo`.
