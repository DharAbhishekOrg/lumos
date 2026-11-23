<!--
  Lumos - docs/ops / Owner runbook: deploying the staging backend to Google Cloud.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-BCK-001, TRS-BCK-008, TRS-REP-005, SRS-DAT-003
  ADRs: ADR-001
  Threading: n/a
  Notes: WP-05 S8. Owner-executed: the agent has no access to the cloud
  project and has NOT run any command below. What the agent did run is the
  same image against PostgreSQL and Redis locally and in CI
  (backend/deploy/staging/docker-compose.yml, the `staging integration` check). This
  runbook moves that image to Cloud Run with managed stores.
-->

# Staging deploy (owner-executed)

**Status:** not executed. Every command below is for the owner to run in
`lumos-staging-515413` (`asia-south1`, see [environments.md](environments.md)).
None was run by the agent. The image, the stores' required settings and the
verification suite are the ones CI runs on every pull request.

## What gets deployed

| Piece | Cloud form | Local and CI form |
| --- | --- | --- |
| `lumosd` | Cloud Run service `lumosd-staging`, image from `backend/deploy/staging/Dockerfile` | the same image, from the same Dockerfile |
| Registry and capabilities | Cloud SQL for PostgreSQL 16, private IP | `postgres:16-alpine` |
| Envelope mailboxes | Memorystore for Redis 7.2, private IP | `redis:7.2-alpine` with `noeviction` and an append-only file |
| Secrets | Secret Manager: `lumos-database-url`, `lumos-redis-url` | none (a private compose network) |

## Two settings that are not optional

1. **Redis `maxmemory-policy` must be `noeviction`.** Memorystore's default
   evicts keys under memory pressure, which would silently drop people's
   queued messages. Set it when creating the instance and read it back.
2. **Redis must persist.** The CI check proves queued mail survives a restart
   *because* Redis keeps an append-only file. A Memorystore instance without
   persistence loses every queued message at each maintenance restart. Choose
   a tier and configuration with persistence, or accept and record that loss
   for staging.

## Cost: check before creating

The budget alert is ₹2,000 a month (alerts only; it stops nothing). Cloud SQL
and Memorystore both bill around the clock whether or not anyone uses them,
and Memorystore is likely the largest line. Price both in the Google Cloud
pricing calculator for `asia-south1` before creating them. If Memorystore
does not fit, a self-managed Redis 7.2 on the smallest Compute Engine VM, with
the same two settings, is the alternative; say so and the runbook will be
adjusted.

## Steps

Run from the repository root, on a machine with `gcloud` signed in as the owner.

```sh
PROJECT=lumos-staging-515413
REGION=asia-south1
gcloud config set project "$PROJECT"

# 1. APIs
gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  sqladmin.googleapis.com redis.googleapis.com secretmanager.googleapis.com \
  servicenetworking.googleapis.com

# 2. An image registry, and the image (tagged with the commit it was built from)
gcloud artifacts repositories create lumos --repository-format=docker --location="$REGION"
gcloud auth configure-docker "$REGION-docker.pkg.dev"
REV=$(git rev-parse HEAD)
IMAGE="$REGION-docker.pkg.dev/$PROJECT/lumos/lumosd:$REV"
docker build -f backend/deploy/staging/Dockerfile \
  --build-arg REVISION="$REV" --build-arg BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t "$IMAGE" .
docker push "$IMAGE"
```

3. **Private services access** for the `default` VPC (once per project), so
   Cloud SQL and Memorystore get private IPs and nothing is on a public address.
4. **Cloud SQL:** PostgreSQL 16, the smallest tier the calculator approves,
   **private IP only**, a database `lumos` and a user `lumos` with a generated
   password. `lumosd` creates its own tables at startup (forward-only
   migrations).
5. **Memorystore for Redis 7.2** on the `default` network, with the two
   settings above.
6. **Secrets.** Never paste them into a terminal history you keep, a ticket or
   the repository:

```sh
printf '%s' 'postgres://lumos:<password>@<cloud-sql-private-ip>:5432/lumos?sslmode=require' \
  | gcloud secrets create lumos-database-url --data-file=-
printf '%s' 'redis://<memorystore-private-ip>:6379/0' \
  | gcloud secrets create lumos-redis-url --data-file=-
```

7. **A service account** with only what it needs:

```sh
gcloud iam service-accounts create lumosd-staging
SA="lumosd-staging@$PROJECT.iam.gserviceaccount.com"
for s in lumos-database-url lumos-redis-url; do
  gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$SA" \
    --role=roles/secretmanager.secretAccessor
done
```

8. **Deploy.** The API is public by design (every route but the health check
   needs a per-device credential), so the service allows unauthenticated
   invocations. Direct VPC egress reaches the private IPs.

```sh
gcloud run deploy lumosd-staging --image "$IMAGE" --region "$REGION" \
  --service-account "$SA" --port 8080 --allow-unauthenticated \
  --set-secrets LUMOS_DATABASE_URL=lumos-database-url:latest,LUMOS_REDIS_URL=lumos-redis-url:latest \
  --network default --subnet default --vpc-egress private-ranges-only \
  --min-instances 0 --max-instances 2
```

## Verify

```sh
URL=$(gcloud run services describe lumosd-staging --region "$REGION" --format 'value(status.url)')
curl -s "$URL/healthz"
# expect: "environment":"staging", "revision":"<REV>", and the constants_hash of this build

LUMOS_STAGING_URL="$URL" go test -count=1 -v ./backend/integration/
# Expect every test to pass except two skips: the Redis-expiry test (Memorystore
# is not reachable from your machine, by design) and the restart phases.
```

Then send the agent the service URL (it isn't a secret). The agent sets it as
the staging build's `RELAY_BASE_URL` on Android, and in the iOS app target when
WP-03 creates it, and runs the Android cross-stack test against it.

## After it works

- The push steps in [environments.md](environments.md) (FCM API and role;
  APNs key once Apple approves the enrolment).
- The TLS posture scan (SRS-NFR-SEC-008) against `$URL`. Owner-executed.
- To redeploy a new commit: repeat step 2 and step 8 with the new `REV`.
