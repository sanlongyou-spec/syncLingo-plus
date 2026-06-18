# Server Rollback Baseline - 2026-06-19

This document records the production rollback point prepared on the Linux server
before deploying the next syncLingo Plus release.

## Baseline

- Server repository: `/opt/syncLingo`
- Production branch: `final-version`
- Rollback Git tag: `rollback-2b3319e-20260615`
- Rollback commit: `2b3319edd35333634de6fccdae79a1072ad70a88`
- Commit title: `fix: trim ASR overlap and adjust share playback`
- Backend rollback image: `si-backend:rollback-2b3319e-20260615`
- Backend rollback image ID: `sha256:855092bd0d5121157900d04cef27025a1079646177f8b98cbda1b8daf2bb743d`
- Current frontend nginx root: `/var/www/si`
- Rollback backup directory: `/opt/backups/synclingo-rollback-2b3319e-20260615`

Prepared backup contents:

```text
/opt/backups/synclingo-rollback-2b3319e-20260615/backend.env
/opt/backups/synclingo-rollback-2b3319e-20260615/frontend
/opt/backups/synclingo-rollback-2b3319e-20260615/frontend-nginx-root
/opt/backups/synclingo-rollback-2b3319e-20260615/si_backend.sql
```

The database dump was verified as an 84 MB MySQL dump for `si_backend`.

## Verify The Rollback Point

Run this before a risky deployment:

```bash
cd /opt/syncLingo
BACKUP=/opt/backups/synclingo-rollback-2b3319e-20260615

git tag --list 'rollback-2b3319e-20260615'
docker images si-backend --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.CreatedAt}}'
ls -lh "$BACKUP"
ls -lh "$BACKUP/si_backend.sql"
```

Expected:

- The Git tag `rollback-2b3319e-20260615` exists.
- The Docker image `si-backend:rollback-2b3319e-20260615` exists.
- `frontend-nginx-root` exists.
- `backend.env` exists.
- `si_backend.sql` is not empty and starts with `-- MySQL dump`.

## Roll Back Application Code And Services

Use this path when the new deployment has a code/runtime problem but the
database can still be kept.

```bash
cd /opt/syncLingo
BACKUP=/opt/backups/synclingo-rollback-2b3319e-20260615

git checkout rollback-2b3319e-20260615

docker rm -f si-backend 2>/dev/null || true
docker run -d --name si-backend --restart=always --network host \
  --env-file /opt/syncLingo/backend.env \
  -e JAVA_OPTS="-Xms512m -Xmx3g" \
  si-backend:rollback-2b3319e-20260615

rsync -a --delete "$BACKUP/frontend-nginx-root"/ /var/www/si/

cd /opt/syncLingo/bot/CallingBotSample
dotnet publish -c Release -o /opt/syncLingo/runtime/bot
cp appsettings.Production.json /opt/syncLingo/runtime/bot/appsettings.Production.json

systemctl restart si-speaker si-bot
systemctl reload nginx

curl -sf http://127.0.0.1:8080/api/health
systemctl is-active si-speaker si-bot nginx
```

## Roll Back The Database

Only restore the database when the new deployment changed schema or data in a
way that prevents the rollback code from running correctly. Database rollback
will discard writes made after the backup time.

Before restoring, create one extra last-chance dump of the failed state:

```bash
cd /opt/syncLingo
BACKUP=/opt/backups/synclingo-rollback-2b3319e-20260615

DB_NAME=$(grep -m1 '^DB_NAME=' backend.env | cut -d= -f2- | tr -d '\r')
DB_USER=$(grep -m1 '^DB_USERNAME=' backend.env | cut -d= -f2- | tr -d '\r')
DB_PASS=$(grep -m1 '^DB_PASSWORD=' backend.env | cut -d= -f2- | tr -d '\r')

docker exec -e MYSQL_PWD="$DB_PASS" si-mysql \
  mysqldump -u"$DB_USER" "$DB_NAME" \
  > "$BACKUP/failed-state-before-db-restore-$(date +%F_%H%M%S).sql"
```

Then stop writers and restore the rollback dump:

```bash
systemctl stop si-bot si-speaker
docker rm -f si-backend 2>/dev/null || true

cd /opt/syncLingo
BACKUP=/opt/backups/synclingo-rollback-2b3319e-20260615

DB_NAME=$(grep -m1 '^DB_NAME=' backend.env | cut -d= -f2- | tr -d '\r')
DB_USER=$(grep -m1 '^DB_USERNAME=' backend.env | cut -d= -f2- | tr -d '\r')
DB_PASS=$(grep -m1 '^DB_PASSWORD=' backend.env | cut -d= -f2- | tr -d '\r')

docker exec -e MYSQL_PWD="$DB_PASS" si-mysql \
  mysql -u"$DB_USER" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

docker exec -i -e MYSQL_PWD="$DB_PASS" si-mysql \
  mysql -u"$DB_USER" "$DB_NAME" < "$BACKUP/si_backend.sql"
```

After the import completes, run the application rollback section above.

## Post-Rollback Checks

```bash
cd /opt/syncLingo

git rev-parse HEAD
docker ps --filter name=si-backend --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
curl -sf http://127.0.0.1:8080/api/health
systemctl is-active si-speaker si-bot nginx
docker logs --tail 120 si-backend
journalctl -u si-speaker --since "10 minutes ago" --no-pager
journalctl -u si-bot --since "10 minutes ago" --no-pager
```

Expected:

- Git HEAD resolves to `2b3319edd35333634de6fccdae79a1072ad70a88`.
- `si-backend` runs from `si-backend:rollback-2b3319e-20260615`.
- `/api/health` returns `UP`.
- `si-speaker`, `si-bot`, and `nginx` are active.
- Recent logs have no startup failure.

## Notes

- The copied `frontend-nginx-root` mirrors the current `/var/www/si` directory.
  It may contain historical downloaded log files that should not be served
  long term. A normal new frontend deployment should use `rsync --delete` from
  `dist/` to clean those files from the public web root.
- The rollback backup does not include a separate `/opt/syncLingo/runtime`
  snapshot because that directory was not present in the final backup listing.
  The Bot runtime is rebuilt from the rollback Git tag if rollback is needed.
