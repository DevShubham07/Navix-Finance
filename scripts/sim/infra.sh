#!/usr/bin/env bash
# Builds and removes the isolated DhanBoost simulation environment.
# Every WRITE targets a resource with "sim" in its name. Production names appear only in READs.
set -euo pipefail
export MSYS_NO_PATHCONV=1 AWS_PROFILE=navix-dev AWS_REGION=ap-south-1 AWS_PAGER=

ACCOUNT=382188661325
VPC=vpc-0446be8c3d6a3941d
PRIVATE="subnet-04debb2364f89a58f subnet-0e88775cc900d5a94 subnet-0ec4c5696d5c2069f"
PUBLIC="subnet-0341c9ce4312c066d subnet-0dc2e83e75c4ac01e subnet-0415f0d0f4c913dae"
CF_PREFIX_LIST=pl-9aa247f3

# Production: read-only sources.
SOURCE_DB=navix-finance-dev
SOURCE_BUCKET=navix-finance-bucket
SOURCE_DOC_KEY=arn:aws:kms:ap-south-1:382188661325:key/ebef2503-c1d6-464c-b7b0-d3f49773b049

SIM_DB=navix-finance-sim
SIM_BUCKET=navix-finance-sim-documents
CLUSTER=navix-sim
SERVICE=navix-sim-backend
REPO=$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com/navix-sim
# Windows-form path: MSYS_NO_PATHCONV stops Git Bash translating /c/... for docker.exe.
ROOT=$(cd "$(dirname "$0")/../.." && pwd -W)

sg() { aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC" "Name=group-name,Values=$1" --query 'SecurityGroups[0].GroupId' --output text; }
csv() { echo "$1" | tr ' ' ','; }
key_arn() { aws kms describe-key --key-id alias/navix-sim --query KeyMetadata.Arn --output text; }
put() { aws ssm put-parameter --name "$1" --type "$2" --value "$3" --overwrite >/dev/null; }
get() { aws ssm get-parameter --name "$1" --with-decryption --query Parameter.Value --output text; }
db_host() { aws rds describe-db-instances --db-instance-identifier $SIM_DB --query 'DBInstances[0].Endpoint.Address' --output text; }
network() { echo "awsvpcConfiguration={subnets=[$(csv "$PRIVATE")],securityGroups=[$(sg navix-sim-task)],assignPublicIp=DISABLED}"; }
trust='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

base() {
  KEY=$(aws kms create-key --description "DhanBoost simulation environment" --query KeyMetadata.KeyId --output text)
  aws kms create-alias --alias-name alias/navix-sim --target-key-id "$KEY"

  aws s3api create-bucket --bucket $SIM_BUCKET --create-bucket-configuration LocationConstraint=$AWS_REGION >/dev/null
  aws s3api put-public-access-block --bucket $SIM_BUCKET --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  aws s3api put-bucket-encryption --bucket $SIM_BUCKET --server-side-encryption-configuration "{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"aws:kms\",\"KMSMasterKeyID\":\"$(key_arn)\"},\"BucketKeyEnabled\":true}]}"
  aws s3api put-bucket-cors --bucket $SIM_BUCKET --cors-configuration '{"CORSRules":[{"AllowedHeaders":["*"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedOrigins":["https://dhanboost.com","https://app.dhanboost.com"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":3000}]}'

  aws ecr create-repository --repository-name navix-sim >/dev/null
  aws logs create-log-group --log-group-name /ecs/navix-sim
  aws ecs create-cluster --cluster-name $CLUSTER >/dev/null

  for n in navix-sim-alb navix-sim-task navix-sim-db; do
    aws ec2 create-security-group --group-name $n --description "$n" --vpc-id $VPC >/dev/null
  done
  aws ec2 authorize-security-group-ingress --group-id "$(sg navix-sim-alb)" --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,PrefixListIds=[{PrefixListId=$CF_PREFIX_LIST}]" >/dev/null
  aws ec2 authorize-security-group-ingress --group-id "$(sg navix-sim-task)" --protocol tcp --port 8080 --source-group "$(sg navix-sim-alb)" >/dev/null
  aws ec2 authorize-security-group-ingress --group-id "$(sg navix-sim-db)" --protocol tcp --port 5432 --source-group "$(sg navix-sim-task)" >/dev/null
  aws rds create-db-subnet-group --db-subnet-group-name navix-sim --db-subnet-group-description "Simulation private subnets" --subnet-ids $PRIVATE >/dev/null

  local key; key=$(key_arn)
  aws iam create-role --role-name navix-sim-exec-role --assume-role-policy-document "$trust" >/dev/null
  aws iam attach-role-policy --role-name navix-sim-exec-role --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
  aws iam put-role-policy --role-name navix-sim-exec-role --policy-name sim-secrets --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:GetParameters\",\"Resource\":[\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix-sim-tools/*\",\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix/sim/spring/datasource/password\"]}]}"

  aws iam create-role --role-name navix-sim-task-role --assume-role-policy-document "$trust" >/dev/null
  aws iam put-role-policy --role-name navix-sim-task-role --policy-name sim-only --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"ssm:GetParameter\",\"ssm:GetParameters\",\"ssm:GetParametersByPath\"],\"Resource\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix/sim*\"},{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::$SIM_BUCKET\"},{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\"],\"Resource\":\"arn:aws:s3:::$SIM_BUCKET/*\"},{\"Effect\":\"Allow\",\"Action\":[\"kms:Decrypt\",\"kms:GenerateDataKey\"],\"Resource\":\"$key\"}]}"

  # The ONLY identity with access to production documents: three read actions there, writes only to the sim bucket.
  aws iam create-role --role-name navix-sim-copy-role --assume-role-policy-document "$trust" >/dev/null
  aws iam put-role-policy --role-name navix-sim-copy-role --policy-name read-source-write-sim --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":[\"arn:aws:s3:::$SOURCE_BUCKET\",\"arn:aws:s3:::$SIM_BUCKET\"]},{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::$SOURCE_BUCKET/*\"},{\"Effect\":\"Allow\",\"Action\":\"kms:Decrypt\",\"Resource\":\"$SOURCE_DOC_KEY\"},{\"Effect\":\"Allow\",\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::$SIM_BUCKET/*\"},{\"Effect\":\"Allow\",\"Action\":[\"kms:GenerateDataKey\",\"kms:Decrypt\"],\"Resource\":\"$key\"}]}"
}

secrets() {
  put /navix/sim/navix/auth/secret SecureString "$(openssl rand -base64 48)"
  put /navix/sim/spring/datasource/username String navix_app
  put /navix/sim/spring/datasource/password SecureString "$(openssl rand -hex 24)"
  put /navix/sim/navix/storage/bucket String $SIM_BUCKET
  put /navix/sim/navix/storage/kms-key-id String "$(key_arn)"
  put /navix/sim/navix/app/frontend-base-url String https://dhanboost.com
  put /navix-sim-tools/db-master-password SecureString "$(openssl rand -hex 24)"
  put /navix-sim-tools/admin-password SecureString "$(openssl rand -hex 12)"
  put /navix-sim-tools/gateway-key SecureString "$(openssl rand -hex 32)"
}

tools_image() {
  DOCKER_CONFIG=$(cygpath -m "$(mktemp -d)"); export DOCKER_CONFIG
  trap 'rm -rf "$DOCKER_CONFIG"' EXIT
  printf '{"auths":{"%s":{"auth":"%s"}}}' "$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com" \
    "$(printf 'AWS:%s' "$(aws ecr get-login-password)" | base64 -w0)" > "$DOCKER_CONFIG/config.json"
  docker build -t "$REPO:tools" "$ROOT/scripts/sim"
  docker push "$REPO:tools"
}

images() {
  # Docker's Windows credential store rejects ECR's long token, so skip `docker login` and hand the
  # token to a throwaway config that is removed on exit.
  DOCKER_CONFIG=$(cygpath -m "$(mktemp -d)"); export DOCKER_CONFIG
  trap 'rm -rf "$DOCKER_CONFIG"' EXIT
  printf '{"auths":{"%s":{"auth":"%s"}}}' "$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com" \
    "$(printf 'AWS:%s' "$(aws ecr get-login-password)" | base64 -w0)" > "$DOCKER_CONFIG/config.json"
  # mvnw builds its classpath with POSIX paths, which need Git Bash's translation.
  (cd "$ROOT/backend" && unset MSYS_NO_PATHCONV && ./mvnw -q -pl navix-app -am package -DskipTests)
  local tag; tag=$(git -C "$ROOT" rev-parse --short HEAD)
  docker build -f "$ROOT/Dockerfile.backend.runtime" -t "$REPO:backend-$tag" -t "$REPO:backend" "$ROOT"
  docker build -t "$REPO:tools" "$ROOT/scripts/sim"
  docker push "$REPO:backend-$tag"; docker push "$REPO:backend"; docker push "$REPO:tools"
}

edge() {
  local alb tg listener dns key
  alb=$(aws elbv2 create-load-balancer --name navix-sim-alb --type application --scheme internet-facing --subnets $PUBLIC --security-groups "$(sg navix-sim-alb)" --query 'LoadBalancers[0].LoadBalancerArn' --output text)
  tg=$(aws elbv2 create-target-group --name navix-sim-tg --protocol HTTP --port 8080 --vpc-id $VPC --target-type ip --health-check-path /actuator/health --query 'TargetGroups[0].TargetGroupArn' --output text)
  listener=$(aws elbv2 create-listener --load-balancer-arn "$alb" --protocol HTTP --port 80 --default-actions 'Type=fixed-response,FixedResponseConfig={StatusCode=403,ContentType=text/plain,MessageBody=Forbidden}' --query 'Listeners[0].ListenerArn' --output text)
  key=$(get /navix-sim-tools/gateway-key)
  aws elbv2 create-rule --listener-arn "$listener" --priority 1 --conditions '[{"Field":"path-pattern","PathPatternConfig":{"Values":["/api/auth/borrower/*"]}}]' --actions 'Type=fixed-response,FixedResponseConfig={StatusCode=403,ContentType=text/plain,MessageBody=Forbidden}' >/dev/null
  aws elbv2 create-rule --listener-arn "$listener" --priority 2 --conditions "[{\"Field\":\"http-header\",\"HttpHeaderConfig\":{\"HttpHeaderName\":\"X-Sim-Gateway\",\"Values\":[\"$key\"]}}]" --actions "Type=forward,TargetGroupArn=$tg" >/dev/null
  dns=$(aws elbv2 describe-load-balancers --load-balancer-arns "$alb" --query 'LoadBalancers[0].DNSName' --output text)
  aws cloudfront create-distribution --distribution-config "{\"CallerReference\":\"navix-sim-$(date +%s)\",\"Comment\":\"navix-sim\",\"Enabled\":true,\"PriceClass\":\"PriceClass_200\",\"Origins\":{\"Quantity\":1,\"Items\":[{\"Id\":\"navix-sim-alb\",\"DomainName\":\"$dns\",\"CustomOriginConfig\":{\"HTTPPort\":80,\"HTTPSPort\":443,\"OriginProtocolPolicy\":\"http-only\",\"OriginReadTimeout\":60}}]},\"DefaultCacheBehavior\":{\"TargetOriginId\":\"navix-sim-alb\",\"ViewerProtocolPolicy\":\"https-only\",\"CachePolicyId\":\"4135ea2d-6df8-44a3-9df3-4b5a84be39ad\",\"OriginRequestPolicyId\":\"216adef6-5c7f-47e4-b989-5492eafa07d3\",\"AllowedMethods\":{\"Quantity\":7,\"Items\":[\"GET\",\"HEAD\",\"OPTIONS\",\"PUT\",\"PATCH\",\"POST\",\"DELETE\"],\"CachedMethods\":{\"Quantity\":2,\"Items\":[\"GET\",\"HEAD\"]}}}}" --query 'Distribution.DomainName' --output text
}

require_signoff() {
  [ "${COMPLIANCE_SIGNOFF_CONFIRMED_BY_OWNER:-}" = yes ] || { echo "REFUSED: owner has not confirmed compliance sign-off" >&2; exit 3; }
}

restore() {
  require_signoff
  local src copy
  src=$(aws rds describe-db-snapshots --db-instance-identifier $SOURCE_DB --snapshot-type automated --query 'reverse(sort_by(DBSnapshots,&SnapshotCreateTime))[0].DBSnapshotIdentifier' --output text)
  [ "$src" != None ] || { echo "STOP: production has no automated backup. Ask the owner." >&2; exit 4; }
  copy="navix-sim-seed-$(date +%Y%m%d%H%M)"
  aws rds copy-db-snapshot --source-db-snapshot-identifier "$src" --target-db-snapshot-identifier "$copy" --kms-key-id alias/navix-sim >/dev/null
  aws rds wait db-snapshot-available --db-snapshot-identifier "$copy"
  aws rds restore-db-instance-from-db-snapshot --db-instance-identifier $SIM_DB --db-snapshot-identifier "$copy" --db-instance-class db.t4g.micro --db-subnet-group-name navix-sim --vpc-security-group-ids "$(sg navix-sim-db)" --no-publicly-accessible --no-multi-az >/dev/null
  aws rds wait db-instance-available --db-instance-identifier $SIM_DB
  finish_restore
}

# Separate and safe to re-run: a dropped connection after the restore must not leave production's password in place.
finish_restore() {
  # A restored instance keeps production's master password until this runs.
  aws rds modify-db-instance --db-instance-identifier $SIM_DB --master-user-password "$(get /navix-sim-tools/db-master-password)" --backup-retention-period 1 --apply-immediately >/dev/null
  aws rds wait db-instance-available --db-instance-identifier $SIM_DB
  put /navix/sim/spring/datasource/url String "jdbc:postgresql://$(db_host):5432/navix?sslmode=require"
  local seed
  for seed in $(aws rds describe-db-snapshots --snapshot-type manual --query "DBSnapshots[?starts_with(DBSnapshotIdentifier,'navix-sim-seed')].DBSnapshotIdentifier" --output text); do
    aws rds delete-db-snapshot --db-snapshot-identifier "$seed" >/dev/null
  done
  register_tools
}

register_tools() {
  aws ecs register-task-definition --cli-input-json "{\"family\":\"navix-sim-tools\",\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\",\"executionRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-exec-role\",\"containerDefinitions\":[{\"name\":\"tools\",\"image\":\"$REPO:tools\",\"environment\":[{\"name\":\"PGHOST\",\"value\":\"$(db_host)\"},{\"name\":\"PGDATABASE\",\"value\":\"navix\"},{\"name\":\"PGUSER\",\"value\":\"navix_admin\"},{\"name\":\"PGSSLMODE\",\"value\":\"require\"}],\"secrets\":[{\"name\":\"PGPASSWORD\",\"valueFrom\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix-sim-tools/db-master-password\"},{\"name\":\"SIM_ADMIN_PASSWORD\",\"valueFrom\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix-sim-tools/admin-password\"},{\"name\":\"SIM_APP_DB_PASSWORD\",\"valueFrom\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix/sim/spring/datasource/password\"}],\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{\"awslogs-group\":\"/ecs/navix-sim\",\"awslogs-region\":\"$AWS_REGION\",\"awslogs-stream-prefix\":\"tools\"}}}]}" >/dev/null
}

run_and_wait() { # args: task-definition, run-task --overrides JSON or empty
  local arn code
  if [ -n "$2" ]; then
    arn=$(aws ecs run-task --cluster $CLUSTER --launch-type FARGATE --task-definition "$1" --network-configuration "$(network)" --overrides "$2" --query 'tasks[0].taskArn' --output text)
  else
    arn=$(aws ecs run-task --cluster $CLUSTER --launch-type FARGATE --task-definition "$1" --network-configuration "$(network)" --query 'tasks[0].taskArn' --output text)
  fi
  echo "task: $arn"
  until aws ecs describe-tasks --cluster $CLUSTER --tasks "$arn" --query 'tasks[0].lastStatus' --output text | grep -q STOPPED; do sleep 15; done
  code=$(aws ecs describe-tasks --cluster $CLUSTER --tasks "$arn" --query 'tasks[0].containers[0].exitCode' --output text)
  echo "exit code: $code"
  [ "$code" = 0 ]
}

copy_docs() {
  require_signoff
  aws ecs register-task-definition --cli-input-json "{\"family\":\"navix-sim-copy\",\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\",\"cpu\":\"512\",\"memory\":\"1024\",\"executionRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-exec-role\",\"taskRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-copy-role\",\"containerDefinitions\":[{\"name\":\"copy\",\"image\":\"public.ecr.aws/aws-cli/aws-cli:latest\",\"command\":[\"s3\",\"sync\",\"s3://$SOURCE_BUCKET\",\"s3://$SIM_BUCKET\",\"--sse\",\"aws:kms\",\"--sse-kms-key-id\",\"$(key_arn)\",\"--copy-props\",\"none\",\"--only-show-errors\"],\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{\"awslogs-group\":\"/ecs/navix-sim\",\"awslogs-region\":\"$AWS_REGION\",\"awslogs-stream-prefix\":\"copy\"}}}]}" >/dev/null
  run_and_wait navix-sim-copy ""
}

tools() { # args: prepare | loans | verify
  run_and_wait navix-sim-tools "{\"containerOverrides\":[{\"name\":\"tools\",\"command\":[\"$1\"]}]}"
}

service() {
  local e=""
  for kv in NAVIX_ENV=sim AWS_REGION=$AWS_REGION NAVIX_ALLOW_DEV_DEFAULTS=true NAVIX_SMS_ENABLED=false NAVIX_SMS_MOCK=false NAVIX_SMS_DEV_ECHO=false NAVIX_WHATSAPP_ENABLED=false NAVIX_EMAIL_ENABLED=false NAVIX_EMAIL_PROVIDER=log NAVIX_SES_EVENTS_ENABLED=false NAVIX_ESIGN_PROVIDER=mock NAVIX_DIGIO_PROVIDER=mock NAVIX_BUREAU_FIXTURE=1 NAVIX_REMINDERS_CRON=- NAVIX_EMPLOYMENT_RETRY_CRON=- NAVIX_PROVIDER_HEALTH_CRON=- NAVIX_DIGIO_DEBIT_CRON=- NAVIX_PAYU_RECONCILE_CRON=-; do
    e="$e{\"name\":\"${kv%%=*}\",\"value\":\"${kv#*=}\"},"
  done
  aws ecs register-task-definition --cli-input-json "{\"family\":\"navix-sim-backend\",\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\",\"cpu\":\"1024\",\"memory\":\"2048\",\"executionRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-exec-role\",\"taskRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-task-role\",\"containerDefinitions\":[{\"name\":\"backend\",\"image\":\"$REPO:backend\",\"portMappings\":[{\"containerPort\":8080}],\"environment\":[${e%,}],\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{\"awslogs-group\":\"/ecs/navix-sim\",\"awslogs-region\":\"$AWS_REGION\",\"awslogs-stream-prefix\":\"backend\"}}}]}" >/dev/null
  local tg; tg=$(aws elbv2 describe-target-groups --names navix-sim-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
  aws ecs create-service --cluster $CLUSTER --service-name $SERVICE --task-definition navix-sim-backend --desired-count 1 --launch-type FARGATE --health-check-grace-period-seconds 240 --network-configuration "$(network)" --load-balancers "targetGroupArn=$tg,containerName=backend,containerPort=8080" >/dev/null
  aws ecs wait services-stable --cluster $CLUSTER --services $SERVICE
}

status() {
  aws ecs describe-services --cluster $CLUSTER --services $SERVICE --query 'services[0].{desired:desiredCount,running:runningCount}' --output json
  aws cloudfront list-distributions --query "DistributionList.Items[?Comment=='navix-sim'].{domain:DomainName,status:Status}" --output json
  aws rds describe-db-instances --db-instance-identifier $SIM_DB --query 'DBInstances[0].{status:DBInstanceStatus,public:PubliclyAccessible,encrypted:StorageEncrypted}' --output json
}

teardown() {
  [ "${CONFIRM_TEARDOWN:-}" = navix-sim ] || { echo "Set CONFIRM_TEARDOWN=navix-sim" >&2; exit 5; }
  aws ecs update-service --cluster $CLUSTER --service $SERVICE --desired-count 0 >/dev/null || true
  aws ecs delete-service --cluster $CLUSTER --service $SERVICE --force >/dev/null || true
  aws rds delete-db-instance --db-instance-identifier $SIM_DB --skip-final-snapshot --delete-automated-backups >/dev/null || true
  aws rds wait db-instance-deleted --db-instance-identifier $SIM_DB || true
  aws s3 rm "s3://$SIM_BUCKET" --recursive --only-show-errors || true
  aws s3api delete-bucket --bucket $SIM_BUCKET || true
  aws ssm delete-parameters --names $(aws ssm describe-parameters --parameter-filters "Key=Path,Option=Recursive,Values=/navix/sim/" --query 'Parameters[].Name' --output text) || true
  aws ssm delete-parameters --names $(aws ssm describe-parameters --parameter-filters "Key=Path,Option=Recursive,Values=/navix-sim-tools/" --query 'Parameters[].Name' --output text) || true

  local id etag cfg
  id=$(aws cloudfront list-distributions --query "DistributionList.Items[?Comment=='navix-sim'].Id | [0]" --output text)
  if [ "$id" != None ]; then
    etag=$(aws cloudfront get-distribution-config --id "$id" --query ETag --output text)
    cfg=$(aws cloudfront get-distribution-config --id "$id" --query DistributionConfig --output json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const c=JSON.parse(s);c.Enabled=false;console.log(JSON.stringify(c))})')
    aws cloudfront update-distribution --id "$id" --if-match "$etag" --distribution-config "$cfg" >/dev/null
    aws cloudfront wait distribution-deployed --id "$id"
    aws cloudfront delete-distribution --id "$id" --if-match "$(aws cloudfront get-distribution-config --id "$id" --query ETag --output text)"
  fi

  local alb; alb=$(aws elbv2 describe-load-balancers --names navix-sim-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)
  aws elbv2 delete-load-balancer --load-balancer-arn "$alb"
  aws elbv2 wait load-balancers-deleted --load-balancer-arns "$alb"
  aws elbv2 delete-target-group --target-group-arn "$(aws elbv2 describe-target-groups --names navix-sim-tg --query 'TargetGroups[0].TargetGroupArn' --output text)"
  aws ecs delete-cluster --cluster $CLUSTER >/dev/null
  aws rds delete-db-subnet-group --db-subnet-group-name navix-sim
  sleep 120 # task network interfaces release after the service stops
  aws ec2 revoke-security-group-ingress --group-id "$(sg navix-sim-db)" --protocol tcp --port 5432 --source-group "$(sg navix-sim-task)" || true
  aws ec2 revoke-security-group-ingress --group-id "$(sg navix-sim-task)" --protocol tcp --port 8080 --source-group "$(sg navix-sim-alb)" || true
  for n in navix-sim-db navix-sim-task navix-sim-alb; do aws ec2 delete-security-group --group-id "$(sg $n)"; done
  aws iam delete-role-policy --role-name navix-sim-exec-role --policy-name sim-secrets
  aws iam detach-role-policy --role-name navix-sim-exec-role --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
  aws iam delete-role --role-name navix-sim-exec-role
  aws iam delete-role-policy --role-name navix-sim-task-role --policy-name sim-only
  aws iam delete-role --role-name navix-sim-task-role
  aws iam delete-role-policy --role-name navix-sim-copy-role --policy-name read-source-write-sim
  aws iam delete-role --role-name navix-sim-copy-role
  aws ecr delete-repository --repository-name navix-sim --force >/dev/null
  aws logs delete-log-group --log-group-name /ecs/navix-sim
  local key; key=$(key_arn)
  aws kms delete-alias --alias-name alias/navix-sim
  aws kms schedule-key-deletion --key-id "$key" --pending-window-in-days 7 >/dev/null
}

case "${1:-}" in
  base|secrets|images|edge|restore|service|status|teardown) "$1" ;;
  finish-restore) finish_restore ;;
  tools-image) tools_image ;;
  copy-docs) copy_docs ;;
  tools) tools "$2" ;;
  *) echo "usage: infra.sh base|secrets|images|edge|restore|copy-docs|tools <prepare|loans|verify>|service|status|teardown" >&2; exit 64 ;;
esac
