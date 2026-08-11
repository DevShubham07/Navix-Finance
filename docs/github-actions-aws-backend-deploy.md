# GitHub Actions to AWS ECS backend deployment

The CI workflow builds the backend image and deploys it to the existing
`navix-backend` ECS service after backend tests pass.

## One-time AWS setup

Create a GitHub Actions OIDC provider in account `382188661325` for
`token.actions.githubusercontent.com`, then create a role trusted only by this
repository's `main` branch. Replace `OWNER/REPOSITORY` below with the real GitHub
repository path:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "arn:aws:iam::382188661325:oidc-provider/token.actions.githubusercontent.com"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com"},
      "StringLike": {"token.actions.githubusercontent.com:sub": "repo:OWNER/REPOSITORY:ref:refs/heads/main"}
    }
  }]
}
```

Attach a policy with only the permissions required by the workflow:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["ecr:GetAuthorizationToken"], "Resource": "*"},
    {
      "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload", "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart"],
      "Resource": "arn:aws:ecr:ap-south-1:382188661325:repository/navix-finance"
    },
    {
      "Effect": "Allow",
      "Action": ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition", "ecs:DescribeServices", "ecs:UpdateService"],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": ["iam:PassRole"],
      "Resource": [
        "arn:aws:iam::382188661325:role/navix-finance-task-role"
      ]
    }
  ]
}
```

In GitHub repository settings:

1. Create the `production` environment.
2. Add required reviewers to that environment.
3. Add `AWS_ROLE_TO_ASSUME` as an environment secret containing the role ARN.

## Normal deployment

A push to `main` runs the backend tests, builds a `linux/amd64` image, and pushes
both the commit SHA and `latest` tags to ECR. After production approval, the
workflow registers a new ECS task-definition revision and waits for the service to
be stable and healthy through the ALB.

## Rollback

Run the `CI` workflow manually from the `main` branch, enter a previously published ECR tag (normally a
commit SHA) in `deploy_tag`, and approve the `production` deployment. The workflow
reuses that image without rebuilding it.

The workflow preserves the existing task definition's roles, networking, SSM
configuration, logging, health check, and container settings; only the image
reference is changed.
