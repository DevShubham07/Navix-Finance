# aws.md — NAVIX AWS deployment reference

The single source of truth for **how NAVIX runs in the cloud**. Read this first when you need
to touch infra, redeploy the backend, debug an outage, or look up an endpoint/credential.

> Verified live & working **2026-06-27**: borrower + ADMIN staff JWT login, mock OTP `123456`,
> Vercel → BFF → ALB → ECS → RDS, all green end-to-end.
>
> Companion docs: `CLAUDE.md` (product/architecture), `handoff.md` §15 (the P0–P8 migration),
> `FUTURE.md` (go-live backlog). The legacy `DEPLOYMENT.md` / `deploy/deploy-backend.sh` describe
> an **EC2-on-box** model that is **NOT what's live** — see §9.

---

## 0. The live topology (one line)

```
Vercel (Next.js frontend + BFF)  →  AWS ALB :80  →  ECS Fargate task :8080  →  RDS Postgres + S3 + SSM + KMS
   https://frontend-ruby-two-78.vercel.app        http://navix-alb-148184383.ap-south-1.elb.amazonaws.com
```

The browser only ever talks to Vercel. The Next.js **BFF** (`src/app/api/*`) calls the backend
server-side via `BACKEND_BASE_URL` (= the ALB). The backend resolves all config + secrets from
**SSM Parameter Store** at boot (`spring.config.import=aws-parameterstore:/navix/${NAVIX_ENV}/`).

---

## 1. Account, identity, region

| Item | Value |
|---|---|
| AWS account | **382188661325** |
| Region | **ap-south-1** (Mumbai) |
| CLI profile | **`navix-dev`** → IAM user `arn:aws:iam::382188661325:user/navix-dev` |
| VPC | `vpc-0446be8c3d6a3941d` |

Every AWS command in this doc assumes:
```bash
export AWS_PROFILE=navix-dev AWS_REGION=ap-south-1
```

---

## 2. Compute — ECS Fargate (the live backend)

| Item | Value |
|---|---|
| Cluster | `navix-cluster` |
| Service | `navix-backend` (Fargate, desired **1**, `assignPublicIp=ENABLED`) |
| Task family | `navix-finance` — **current revision 3** |
| Task size | cpu **1024** / mem **2048** |
| Container port | **8080** (name `navix-backend-8080-tcp`, http) |
| Exec + task role | `navix-finance-task-role` (same role for both) |
| Subnets | `subnet-0341c9ce4312c066d`, `subnet-0dc2e83e75c4ac01e`, `subnet-0415f0d0f4c913dae` |
| Security group | `sg-040c4365f3a355186` (**shared with the ALB** — see §5) |
| Task env vars | `NAVIX_ENV=dev`, `AWS_REGION=ap-south-1`, `NAVIX_SMS_MOCK=true` |
| Logs | CloudWatch log group **`/ecs/navix-finance`**, stream `ecs/navix-backend/<taskId>` |

- `NAVIX_ENV=dev` → the app reads SSM under `/navix/dev/*` (§7). The Docker image's baked
  `NAVIX_ENV=prod` is **overridden** by the task-def env.
- `NAVIX_SMS_MOCK=true` → OTP is the fixed mock code **`123456`** (no real SMS; DLT-blocked).
  Added in **rev 3**; rev 2 (the previous live task) did not have it.

---

## 3. Image registry — ECR

| Item | Value |
|---|---|
| Repo URI | `382188661325.dkr.ecr.ap-south-1.amazonaws.com/navix-finance` |
| Tag in use | `latest` (tag mutability: **MUTABLE**) |

The image is a **runtime-only** image built from `Dockerfile.backend.runtime` (COPYs a host-built
JAR — fast). `Dockerfile.backend` is the full multi-stage build (Maven-in-Docker, slower) and still
works; both produce the same runnable `navix-app` JAR on `eclipse-temurin:21-jre-jammy`, port 8080.

---

## 4. Edge — Application Load Balancer

| Item | Value |
|---|---|
| Name | `navix-alb` (internet-facing) |
| DNS | **`navix-alb-148184383.ap-south-1.elb.amazonaws.com`** |
| ARN | `arn:aws:elasticloadbalancing:ap-south-1:382188661325:loadbalancer/app/navix-alb/da27d674bf73f69d` |
| Listener | **HTTP :80** → target group below (no HTTPS — see §8 gotchas) |
| Target group | `navix-backend-tg` (`.../navix-backend-tg/7efbd9f509c0d1c4`) |
| TG target port | **8080** (per-target), health `GET /actuator/health`, matcher **200** |
| Security group | `sg-040c4365f3a355186` (**same SG as the ECS tasks**) |

---

## 5. Networking — the two security groups (important!)

There are **exactly two** SGs that matter, and the first is shared:

**`sg-040c4365f3a355186` — ALB *and* ECS tasks (shared).** Must allow inbound:
| Port | Source | Why |
|---|---|---|
| **80** | `0.0.0.0/0` | users / Vercel BFF → ALB listener |
| **8080** | `0.0.0.0/0` | ALB → task container port |

> ⚠️ **The classic outage:** if only 8080 is open, the ALB target reads *healthy* (the ALB→task:8080
> hop works) but **external traffic to the ALB on :80 times out**. Both ports must be open. This was
> the actual bug fixed on 2026-06-27.

**`sg-082443872704e48e4` — RDS.** Allows inbound **5432** from:
- three `/32` developer IPs (office/home, for direct `psql`), and
- `sg-040c4365f3a355186` (so the ECS tasks reach the DB).

To let a **new dev IP** hit RDS directly:
```bash
aws ec2 authorize-security-group-ingress --group-id sg-082443872704e48e4 \
  --protocol tcp --port 5432 --cidr <YOUR_IP>/32
```

---

## 6. Data — RDS, S3, KMS

**RDS** (`navix-finance-dev`)
- Endpoint: `navix-finance-dev.cf22os0umu8l.ap-south-1.rds.amazonaws.com:5432/navix`
- Postgres **18.3**, `db.t4g.micro`, 20 GB, AZ `ap-south-1c`, **publicly accessible**, `sslmode=require`.
- Schema owned by **Flyway V1–V18** (`backend/navix-app/src/main/resources/db/migration/`); validated on boot.
- DB user/password come from SSM (§7), not hardcoded.

**S3** — bucket **`navix-finance-bucket`** (KYC/loan/collections documents; presigned URLs, SSE-KMS).

**KMS** — alias **`alias/navix-finance`** (S3 server-side encryption; granted via the task role).

---

## 7. Config & secrets — SSM Parameter Store

The backend imports `aws-parameterstore:/navix/dev/` (path = `/navix/${NAVIX_ENV}/`). A param named
`/navix/dev/spring/datasource/url` binds to Spring property `spring.datasource.url`, etc.
**Secrets are `SecureString`; never commit values.** Current keys:

| Parameter | Type | Binds to |
|---|---|---|
| `/navix/dev/spring/datasource/url` | String | `spring.datasource.url` |
| `/navix/dev/spring/datasource/username` | String | `spring.datasource.username` |
| `/navix/dev/spring/datasource/password` | **SecureString** | `spring.datasource.password` |
| `/navix/dev/navix/storage/bucket` | String | `navix.storage.bucket` |
| `/navix/dev/navix/storage/kms-key-id` | String | `navix.storage.kms-key-id` |
| `/navix/dev/navix/fintrix/client-id` · `client-secret` | **SecureString** | `navix.fintrix.*` |
| `/navix/dev/navix/digilocker/base-url` | String | `navix.digilocker.base-url` |
| `/navix/dev/navix/digilocker/client-id` · `client-secret` | **SecureString** | `navix.digilocker.*` |
| `/navix/dev/navix/sms/user` · `password` | **SecureString** | `navix.sms.*` |
| `/navix/dev/navix/sms/channel` | String | `navix.sms.channel` |

List them (names only): `aws ssm get-parameters-by-path --path /navix/dev/ --recursive --query 'Parameters[].Name'`
Set one: `aws ssm put-parameter --name /navix/dev/... --type SecureString --value '...' --overwrite`

**IAM** — `navix-finance-task-role` has: `AmazonECSTaskExecutionRolePolicy`, `AmazonSSMReadOnlyAccess`,
`AWSKeyManagementServicePowerUser`, `AmazonS3FullAccess`, + an inline `kms` policy. (S3FullAccess is
broader than needed — tighten to the one bucket before real prod.)

---

## 8. Redeploy the backend (the fast local-JAR recipe)

This is the recipe used on 2026-06-27. Needs Docker running, Java 21
(`JAVA_HOME=~/.sdkman/candidates/java/21.0.11-tem`), and `AWS_PROFILE=navix-dev`.

```bash
cd /Users/shubham.1/Navix-Finance
export AWS_PROFILE=navix-dev AWS_REGION=ap-south-1
ECR=382188661325.dkr.ecr.ap-south-1.amazonaws.com

# 1. Build the bootable JAR locally (deps cached in ~/.m2 → ~1 min)
( cd backend && JAVA_HOME=~/.sdkman/candidates/java/21.0.11-tem \
    ./mvnw -pl navix-app -am package -DskipTests )

# 2. Build a linux/amd64 image (Fargate) WITHOUT buildx attestation, from the host JAR
docker build --platform linux/amd64 --provenance=false --sbom=false \
  -f Dockerfile.backend.runtime -t navix-backend:latest .

# 3. Push to ECR
aws ecr get-login-password | docker login --username AWS --password-stdin "$ECR"
docker tag navix-backend:latest "$ECR/navix-finance:latest"
docker push "$ECR/navix-finance:latest"

# 4. Roll the service (image tag is :latest, so force a new deployment)
aws ecs update-service --cluster navix-cluster --service navix-backend \
  --force-new-deployment

# 5. Watch rollout (app boots ~40s; target is 'unhealthy' until boot finishes — NOT a failure)
aws ecs describe-services --cluster navix-cluster --services navix-backend \
  --query "services[0].deployments[].{td:taskDefinition,state:rolloutState,run:runningCount}"
```

**Changing task env / size** → register a new task-def revision, then
`update-service --task-definition navix-finance:<rev>`. Recipe (adds an env var):
```bash
aws ecs describe-task-definition --task-definition navix-finance --query taskDefinition > td.json
# strip taskDefinitionArn/revision/status/requiresAttributes/compatibilities/registeredAt/registeredBy,
# add the env var, then:
aws ecs register-task-definition --cli-input-json file://td-new.json
```

**Gotchas (all hit at least once):**
- Build **`--platform linux/amd64`** — a Mac is arm64; an arm image → Fargate `exec format error`.
- Build **`--provenance=false --sbom=false`** — buildx's default attestation manifest *list* can make
  the ECS image pull fail; use a plain single-arch manifest.
- The SG must open **both 80 and 8080** (§5).
- Boot takes ~40s (Flyway V1–V19 + SSM + RDS connect) → the target shows `unhealthy` then flips
  `healthy`. Don't mistake the boot window for a crash — check CloudWatch logs.
- **Deploy can flap on the micro RDS:** during the rolling deploy *two* tasks (old + new) both open
  Hikari pools against the tiny `db.t4g.micro`, and `/actuator/health` includes a DB ping — under that
  contention the 5s ELB health timeout can trip (2 fails = unhealthy → ECS kills the task → it cycles).
  It self-heals once the deploy settles to one task. If it won't converge, raise the target-group
  health-check timeout/threshold or bump the RDS instance.
- `aws logs ... --max-items 1` **corrupts** the returned stream name; use
  `--query 'logStreams[0].logStreamName'` without `--max-items`.
- The ALB is **HTTP only** (no ACM cert / HTTPS:443). Server-side BFF calls are fine; a *browser*
  calling the ALB directly from the HTTPS Vercel page would be blocked as mixed content. Adding an
  ACM cert + HTTPS listener is the natural next step (see `FUTURE.md`).

---

## 9. Vercel (frontend)

| Item | Value |
|---|---|
| URL | `https://frontend-ruby-two-78.vercel.app` |
| Key env | `BACKEND_BASE_URL` = `http://navix-alb-148184383.ap-south-1.elb.amazonaws.com` (server-only) |

Redeploy = `npx vercel@latest --prod --yes` from `frontend/` (the project is linked via
`frontend/.vercel/`). If you ever re-create the ALB (new DNS), update `BACKEND_BASE_URL` in Vercel
project settings and redeploy.

> ⚠️ **Deploying through the corporate (Netskope) proxy is flaky** — the CLI's upload/poll to
> `api.vercel.com` gets "socket hang up", leaving deployments stuck `UNKNOWN`. Use
> **`vercel --prod --yes --archive=tgz`** (one tarball upload, far more resilient), or deploy from a
> non-proxied network. The build itself runs server-side; if the CLI drops after upload, check
> `vercel ls` and `vercel promote <url>` once the deployment is `Ready`. (Git auto-deploys from `main`
> currently fail — the project Root Directory isn't set to `frontend`; the app lives in `frontend/`.)

> **Legacy / not live:** `deploy/deploy-backend.sh` + `deploy/backend.env.example` describe a
> single-EC2 box running the container via `docker run` (not ECS). It still works as an alternative
> but is **not** the current deployment. `scripts/p0-aws-setup.sh` is the (user-run) one-time infra
> bootstrap (RDS SG ingress, S3 CORS, SSM secret writes).

---

## 10. Smoke tests (paste-and-run health checks)

```bash
ALB=http://navix-alb-148184383.ap-south-1.elb.amazonaws.com
curl -s "$ALB/actuator/health"                         # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' "$ALB/api/applications?status=KYC_PENDING"   # 401 (auth gate live)

# borrower (mock OTP)
curl -s -X POST "$ALB/api/auth/borrower/otp/request" -H 'Content-Type: application/json' -d '{"mobile":"9819000001"}'
curl -s -X POST "$ALB/api/auth/borrower/login"       -H 'Content-Type: application/json' -d '{"mobile":"9819000001","otp":"123456"}'

# staff ADMIN — the PRIMARY login is the real email+password admin (Flyway V19)
TOK=$(curl -s -X POST "$ALB/api/auth/staff/login" -H 'Content-Type: application/json' \
  -d '{"email":"navixfinance@gmail.com","password":"demo"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

# admin creates a staff account that can then log in (ADMIN-only; non-admin → 422 FORBIDDEN_ROLE)
curl -s -X POST "$ALB/api/staff" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d '{"email":"new.staff@navix.test","name":"New Staff","role":"KYC_APPROVER","password":"pass1234"}'
```

**Primary admin (Flyway V19):** **`navixfinance@gmail.com`** / **`demo`** (ADMIN). This is the real
email+password login the staff console uses (the `/staff/login` page is a plain email+password form —
the old role-picker demo is gone; the floating "Act as role" bar still uses the seeded personas below).
Admins can mint more email+password staff via `POST /api/staff` (UI: `/staff/admin/staff` → "Create
staff account"). Rotate `demo` before any real use.

**Seeded demo personas** (kept for the maker-checker / SoD demo; all password **`Admin@12345`**, Flyway
V17; emails from V10): `meera.krishnan@navix.example`=ADMIN · `ananya.rao`=KYC_APPROVER ·
`priya.nair`=CREDIT_HEAD · `rahul.mehta`/`kabir.singh`/`neha.gupta`=CREDIT_EXECUTIVE ·
`vikram.shah`=DISBURSEMENT_HEAD · `deepa.iyer`=ACCOUNTANT · `arjun.patel`=COLLECTION_HEAD ·
`sana.khan`=COLLECTION_EXECUTIVE · `dev.ops`=DEVELOPER. Borrower demo: mobile any 10 digits, OTP
**`123456`** (mock).

---

## 11. Quick reference — every resource id

```
Account            382188661325            Region        ap-south-1        Profile  navix-dev
VPC                vpc-0446be8c3d6a3941d
ECS cluster        navix-cluster           Service       navix-backend     Task fam navix-finance:3
ECR                382188661325.dkr.ecr.ap-south-1.amazonaws.com/navix-finance:latest
ALB                navix-alb  →  navix-alb-148184383.ap-south-1.elb.amazonaws.com  (HTTP :80)
Target group       navix-backend-tg/7efbd9f509c0d1c4   (:8080, /actuator/health)
SG (ALB + ECS)     sg-040c4365f3a355186    (open 80 + 8080)
SG (RDS)           sg-082443872704e48e4    (open 5432 from dev IPs + the ALB/ECS SG)
RDS                navix-finance-dev.cf22os0umu8l.ap-south-1.rds.amazonaws.com:5432/navix  (PG 18.3)
S3                 navix-finance-bucket    KMS  alias/navix-finance
SSM prefix         /navix/dev/             Task role  navix-finance-task-role
CloudWatch logs    /ecs/navix-finance
Subnets            subnet-0341c9ce4312c066d  subnet-0dc2e83e75c4ac01e  subnet-0415f0d0f4c913dae
Frontend (Vercel)  https://frontend-ruby-two-78.vercel.app
```
