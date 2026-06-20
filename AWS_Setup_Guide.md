# NAVIX Finance — AWS Setup Guide (RDS + S3)

How to provision and connect the AWS resources the backend needs. Chosen setup:

| Decision | Choice |
|---|---|
| Compute (prod) | **ECS Fargate** → app auth via **ECS task role** (no static keys) |
| Provisioning | **AWS Console** (this guide), app wiring already done in code |
| Secrets / config | **SSM Parameter Store** (`/navix/<env>/…`) |
| Local dev | **Real AWS dev account** via your `AWS_PROFILE` |

> **Region / data residency:** Indian digital-lending data should stay in India. Create **everything in one Indian region** — this guide assumes `ap-south-1` (Mumbai). If you use another, set `AWS_REGION` accordingly.

The app authenticates with the **default AWS credential provider chain**: the ECS task role in production, and your local AWS profile during development. **No access keys are ever stored in the repo.**

---

## 0. Prerequisites

- An AWS account; note your **12-digit account ID**.
- AWS CLI v2 installed locally (`aws --version`).
- Decide an environment name: `dev` (used in SSM paths and `NAVIX_ENV`).

---

## 1. KMS key (encryption for S3 + SSM secrets)

Console → **KMS** → Customer managed keys → **Create key** → Symmetric → Encrypt/decrypt.
- Alias: `alias/navix-finance`
- Key administrators: your admin role/user.
- Key users: the dev IAM user (Step 2a) and the ECS task role (Step 2b) — you can add the task role after creating it.
- **Note the key ARN** → `arn:aws:kms:ap-south-1:<ACCOUNT_ID>:key/<key-id>`.

---

## 2. IAM

### 2a. Dev IAM user (local development)
Console → **IAM** → Users → **Create user** `navix-dev` → **no console access**.
After creating, open the user → **Security credentials** → **Create access key** → "Application running outside AWS". Save the key/secret **into your local AWS profile only** (do not paste anywhere in the repo):

```bash
aws configure --profile navix-dev      # enter the key, secret, region ap-south-1
```

Attach the inline policy below (same policy used by the task role).

### 2b. ECS task role (production)
Console → **IAM** → Roles → **Create role** → Trusted entity **AWS service** → **Elastic Container Service** → **Elastic Container Service Task**.
- Name: `navix-finance-ecs-task-role`
- Attach the same inline policy below.
- (ECS also needs a separate **task execution role** — the AWS-managed `AmazonECSTaskExecutionRolePolicy` — for pulling images/logs. That's standard ECS plumbing, separate from app permissions.)

### 2c. The least-privilege policy (attach to both 2a and 2b)
Replace `<ACCOUNT_ID>`, `<KMS_KEY_ID>`, and the bucket name as needed.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "S3Objects",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::navix-finance-documents-dev/*"
    },
    {
      "Sid": "S3List",
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::navix-finance-documents-dev"
    },
    {
      "Sid": "SsmRead",
      "Effect": "Allow",
      "Action": ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"],
      "Resource": "arn:aws:ssm:ap-south-1:<ACCOUNT_ID>:parameter/navix/*"
    },
    {
      "Sid": "KmsForS3AndSsm",
      "Effect": "Allow",
      "Action": ["kms:Decrypt", "kms:GenerateDataKey"],
      "Resource": "arn:aws:kms:ap-south-1:<ACCOUNT_ID>:key/<KMS_KEY_ID>"
    }
  ]
}
```

---

## 3. S3 bucket

Console → **S3** → **Create bucket**.
- Name: `navix-finance-documents-dev` (globally unique — adjust if taken).
- Region: `ap-south-1`.
- **Block ALL public access: ON** (access is only ever via presigned URLs).
- Default encryption: **SSE-KMS**, key `alias/navix-finance`. (Bucket Key: enabled.)
- Versioning: optional (recommended for document retention).

**CORS** (so the browser can PUT/GET via presigned URLs) — bucket → Permissions → CORS:

```json
[
  {
    "AllowedHeaders": ["*"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedOrigins": ["http://localhost:3000", "https://YOUR_FRONTEND_DOMAIN"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

**Lifecycle (optional):** add retention rules per your lending-document policy (e.g. keep KYC/loan docs N years, expire transient proofs).

---

## 4. RDS PostgreSQL

Console → **RDS** → **Create database** → Standard create → **PostgreSQL** (v16).
- Template: Dev/Test; instance e.g. `db.t4g.micro` for dev.
- DB instance identifier: `navix-finance-dev`; master username e.g. `navix_admin`; set a strong master password (you'll store the **app** user/password in SSM, Step 5).
- Storage: **encryption ON** with `alias/navix-finance`.
- Initial database name: `navix`.
- **Public access:**
  - **Prod:** No (private subnets; reachable only from the ECS task security group on 5432).
  - **Dev (to connect from your laptop):** either set *Publicly accessible = Yes* with a security group that allows **5432 from your IP only**, or keep it private and tunnel via a bastion/VPN. Public+IP-restricted is simplest for dev.
- **Note the endpoint** → `navix-finance-dev.xxxx.ap-south-1.rds.amazonaws.com`.

Create an **app DB user** (least privilege) once connected:
```sql
CREATE USER navix_app WITH PASSWORD '<choose-a-strong-password>';
GRANT ALL PRIVILEGES ON DATABASE navix TO navix_app;
```

**TLS:** the JDBC URL uses `?sslmode=require` (encrypts in transit). To also verify the server cert (recommended for prod), download the RDS CA bundle and use `?sslmode=verify-full&sslrootcert=/path/rds-global-bundle.pem`.

---

## 5. SSM Parameter Store

Console → **Systems Manager** → **Parameter Store** → create these under `/navix/dev/`. The app maps `/navix/dev/<a>/<b>` → property `a.b`.

| Parameter name | Type | Value |
|---|---|---|
| `/navix/dev/spring/datasource/url` | String | `jdbc:postgresql://<RDS_ENDPOINT>:5432/navix?sslmode=require` |
| `/navix/dev/spring/datasource/username` | String | `navix_app` |
| `/navix/dev/spring/datasource/password` | **SecureString** (KMS `alias/navix-finance`) | the app DB password |
| `/navix/dev/navix/storage/bucket` | String | `navix-finance-documents-dev` |
| `/navix/dev/navix/storage/kms-key-id` | String | *(optional)* the KMS key ARN |

CLI equivalent:
```bash
aws ssm put-parameter --profile navix-dev --region ap-south-1 \
  --name "/navix/dev/spring/datasource/url" --type String \
  --value "jdbc:postgresql://<RDS_ENDPOINT>:5432/navix?sslmode=require"

aws ssm put-parameter --profile navix-dev --region ap-south-1 \
  --name "/navix/dev/spring/datasource/password" --type SecureString \
  --key-id alias/navix-finance --value "<app-db-password>"
# ...repeat for username, storage/bucket
```

Also add the same tree under `/navix/prod/` for the production environment.

---

## 6. Run the backend

**Locally (against real AWS dev):**
```bash
cd backend
cp .env.example .env            # then `set -a; source .env; set +a` (or use your env loader)
export AWS_PROFILE=navix-dev
export AWS_REGION=ap-south-1
export NAVIX_ENV=dev
./mvnw -pl navix-app spring-boot:run
```
On startup, `spring.config.import` pulls the datasource + bucket from SSM; the S3 client uses your `navix-dev` profile. Flyway runs migrations against RDS.

**On ECS Fargate (prod):** set only `AWS_REGION` and `NAVIX_ENV=prod` in the task definition and attach `navix-finance-ecs-task-role`. No keys, no DB password in the task def — they come from `/navix/prod/*` in SSM.

---

## 7. Verify connectivity

```bash
# Health (DB up if status is UP)
curl localhost:8080/actuator/health

# Get a presigned upload URL, then PUT a file straight to S3
curl -s -X POST localhost:8080/api/storage/presign-upload \
  -H 'Content-Type: application/json' \
  -d '{"category":"KYC_SELFIE","filename":"selfie.jpg","contentType":"image/jpeg"}'
#   -> { "key": "...", "url": "https://...s3...", "method": "PUT", "expiresInSeconds": 900 }

curl -X PUT --upload-file ./selfie.jpg -H 'Content-Type: image/jpeg' "<presigned-url>"
# Confirm the object appears in the bucket (encrypted with your KMS key).
```

---

## 8. Security checklist

- [ ] All resources in an Indian region (data residency).
- [ ] S3: Block Public Access ON; access only via presigned URLs; SSE-KMS default encryption.
- [ ] RDS: storage encrypted; TLS (`sslmode=require`, `verify-full` in prod); private in prod.
- [ ] Secrets only in SSM SecureString (KMS) — never in git, never in chat. App reads them at runtime.
- [ ] IAM least-privilege; prod uses the ECS task role (no static keys); rotate the dev user's key periodically.
- [ ] Presigned-URL TTL kept short (default 15 min via `NAVIX_S3_PRESIGN_TTL`).
- [ ] Per the product spec: full Aadhaar number is never stored — only masked references.

---

## What to send me to finalise

Mostly the app reads these at runtime, so little hardcoding is needed. If you want me to pin defaults or write the ECS task definition / Terraform later, send: **account ID**, **region** (if not `ap-south-1`), **RDS endpoint**, **bucket name**, **KMS key ARN**. Do **not** send the DB password or AWS keys — those stay in SSM / your AWS profile.
