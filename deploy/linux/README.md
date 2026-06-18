# Linux Production Templates

These files are templates for a single Linux server deployment.

1. Copy `env/backend.env.example` to `/opt/syncLingo/backend.env` and replace every placeholder.
2. Copy `env/appsettings.Production.example.json` to `/opt/syncLingo/bot/CallingBotSample/appsettings.Production.json` or merge it into the server-only `appsettings.json`.
3. Install `systemd/si-speaker.service` and `systemd/si-bot.service` into `/etc/systemd/system/`.
4. Build frontend assets into `/var/www/si`.
5. Install `nginx/synclingo.conf` under `/etc/nginx/sites-available/`, replace `sync.example.com`, enable it, and issue a TLS certificate.

Azure Bot Messaging endpoint must be:

```text
https://<your-domain>/api/messages
```

No ngrok service is required.
