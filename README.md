# DevSecOps CI/CD Pipeline -- Jenkins, SonarQube, Trivy, Amazon ECR, Amazon EKS

![CI](https://img.shields.io/badge/CI-Jenkins-blue)
![Container](https://img.shields.io/badge/Container-Docker-blue)
![Orchestration](https://img.shields.io/badge/Orchestration-Kubernetes-blue)
![Cloud](https://img.shields.io/badge/Cloud-AWS-orange)


A production-grade DevSecOps pipeline that automates build, security scanning, container image management, and Kubernetes deployment. The pipeline enforces multiple security gates across source code, dependencies, and container images before any artifact reaches the cluster.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Pipeline Stages](#pipeline-stages)
- [Repository Structure](#repository-structure)
- [Prerequisites](#prerequisites)
- [Infrastructure Setup](#infrastructure-setup)
  - [Jenkins Controller](#jenkins-controller)
  - [Jenkins Agent](#jenkins-agent)
  - [SonarQube Server](#sonarqube-server)
  - [Amazon ECR](#amazon-ecr)
  - [Amazon EKS Cluster](#amazon-eks-cluster)
- [Jenkins Configuration](#jenkins-configuration)
- [Running the Pipeline](#running-the-pipeline)
- [Security Design](#security-design)
- [Post-Pipeline Actions](#post-pipeline-actions)
- [Triggering on Git Push](#triggering-on-git-push)
- [Potential Improvements](#potential-improvements)

---

## Overview

This project demonstrates an end-to-end DevSecOps pipeline built on AWS. The pipeline is designed to reflect real enterprise implementations where security is enforced at every stage -- not added as an afterthought.

Key capabilities:

- Static Application Security Testing (SAST) via SonarQube with enforced quality gates
- Software Composition Analysis (SCA) and filesystem scanning via Trivy
- Container image vulnerability scanning via Trivy before any push to the registry
- Immutable container image tagging with build numbers in Amazon ECR
- Automated deployment to a multi-AZ Amazon EKS cluster
- Topology-aware pod scheduling across availability zones
- Least-privilege IAM and Kubernetes RBAC throughout

---

## Architecture

```
Developer Push
      |
      v
GitHub (Private Repo)
      |
      v
Jenkins Controller (EC2)
      |
      v
Jenkins Agent (EC2) ---- Trivy FS Scan
      |                         |
      |                  SonarQube SAST (EC2)
      |                         |
      |                  Build Docker Image
      |                         |
      |                  Trivy Image Scan
      |                         |
      |                  Push to Amazon ECR
      |                         |
      |                  Update K8s Manifest
      |                         |
      v                         v
Amazon EKS (Multi-AZ) <-- kubectl apply
```

All build workloads run on the agent, not the controller. The Jenkins controller manages job orchestration only.

---

## Pipeline Stages

| Stage | Tool | Purpose |
|---|---|---|
| Trivy FS Scan | Trivy | Scan source code and dependencies for CVEs and secrets |
| Build and Code Analysis | Maven + SonarQube | Compile, run tests, collect coverage, enforce quality gate |
| Quality Gate | SonarQube Webhook | Pause pipeline and abort if SonarQube quality gate fails |
| ECR Login | AWS CLI + Docker | Authenticate agent to Amazon ECR using IAM role |
| Build Docker Image | Docker | Build and tag container image using eclipse-temurin:21 base |
| Trivy Image Scan | Trivy | Scan final container image for HIGH and CRITICAL CVEs |
| Push to ECR | Docker | Push versioned and latest tags to ECR |
| Update Deployment Manifest | sed | Stamp build number into Kubernetes manifest |
| Deploy to Kubernetes | kubectl | Apply manifest to EKS, wait for rollout confirmation |

---

## Repository Structure

```
devsecops-jenkins-ci-cd-pipeline/
|
|-- app/                                 # Java application source
|   |-- src/
|   |   |-- main/java/com/devsecops/demo/
|   |   |   `-- App.java                # Application logic (HTTP server)
|   |   `-- test/java/com/devsecops/demo/
|   |       `-- AppTest.java            # JUnit 5 unit tests
|   `-- pom.xml                         # Maven build config (Surefire, JaCoCo, Shade, Sonar)
|
|-- ci/
|   `-- Jenkinsfile                     # Full declarative pipeline definition
|
|-- docker/
|   `-- Dockerfile                      # Container image definition (eclipse-temurin:21)
|
|-- k8s/                                # Kubernetes and cluster configuration
|   |-- deploy-svc.yaml                 # Deployment and Service manifests
|   |-- clusterrole-binding.yaml        # RBAC for Jenkins agent
|   `-- eks-config.yaml                 # eksctl cluster config (ap-south-1, t3.small)
|
`-- README.md
```

- `app/` -- Java Maven application source code, unit tests, and build configuration.
- `ci/` -- Jenkins declarative pipeline definition.
- `docker/` -- Dockerfile for containerizing the application.
- `k8s/` -- Kubernetes manifests for deployment, service, RBAC, and EKS cluster provisioning.

---

## Prerequisites

The following tools and services are required before running this pipeline.

**AWS:**
- An AWS account with permissions to create EC2, EKS, ECR, and IAM resources
- AWS CLI v2 installed on the Jenkins agent

**Local machine:**
- eksctl installed (for creating the EKS cluster)
- kubectl installed (matching the EKS control plane version -- 1.32)

**EC2 instances required:**
- Jenkins Controller: Ubuntu 24.04, c7i-flex.large, 20 GB root volume
- Jenkins Agent: Ubuntu 24.04, c7i-flex.large, 30 GB root volume
- SonarQube Server: Ubuntu 22.04, c7i-flex.large, 20 GB root volume

---

## Infrastructure Setup

### Jenkins Controller

Install Java 21 and Jenkins on the controller instance.

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk

curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key | \
  sudo tee /usr/share/keyrings/jenkins-keyring.asc > /dev/null

echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/ | \
  sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null

sudo apt update
sudo apt install -y jenkins
sudo systemctl enable jenkins
sudo systemctl start jenkins
```

Access the Jenkins UI at `http://<controller-public-ip>:8080` and unlock with:

```bash
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

---

### Jenkins Agent

Install Java 21, Docker, AWS CLI v2, Trivy, and kubectl on the agent.

**Java 21:**
```bash
sudo apt update
sudo apt install -y openjdk-21-jdk
```

**Docker:**
```bash
sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo useradd -m -s /bin/bash jenkins
sudo usermod -aG docker jenkins
```

**AWS CLI v2:**
```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
sudo apt install -y unzip
unzip awscliv2.zip
sudo ./aws/install
```

**Trivy:**
```bash
wget https://github.com/aquasecurity/trivy/releases/download/v0.67.2/trivy_0.67.2_Linux-64bit.deb
sudo dpkg -i trivy_0.67.2_Linux-64bit.deb
```

**kubectl:**
```bash
curl -LO https://dl.k8s.io/release/v1.32.0/bin/linux/amd64/kubectl
chmod +x kubectl
sudo mv kubectl /usr/local/bin/
```

**Set up SSH-based agent connection:**

On the controller (as jenkins user):
```bash
sudo su - jenkins
ssh-keygen -t ed25519 -f /var/lib/jenkins/.ssh/jenkins-agent-key -C "jenkins-agent-access"
cat /var/lib/jenkins/.ssh/jenkins-agent-key.pub
```

On the agent (as jenkins user), append the public key to `~/.ssh/authorized_keys`:
```bash
sudo su - jenkins
mkdir -p ~/.ssh && chmod 700 ~/.ssh
vim ~/.ssh/authorized_keys  # paste public key here
chmod 600 ~/.ssh/authorized_keys
```

Add the agent in Jenkins: Manage Jenkins > Nodes > New Node. Set label `docker-maven-trivy`, remote root `/home/jenkins`, launch method SSH, and choose "Known hosts file Verification Strategy".

---

### SonarQube Server

**Install Java 21 and PostgreSQL:**
```bash
sudo apt update
sudo apt install -y openjdk-21-jre postgresql postgresql-contrib
sudo systemctl enable postgresql && sudo systemctl start postgresql
```

**Create SonarQube database:**
```bash
sudo -u postgres psql
```
```sql
CREATE ROLE sonar WITH LOGIN ENCRYPTED PASSWORD 'YourStrongPassword';
CREATE DATABASE sonarqube OWNER sonar;
GRANT ALL PRIVILEGES ON DATABASE sonarqube TO sonar;
\q
```

**Tune kernel settings:**
```bash
echo 'vm.max_map_count=524288' | sudo tee -a /etc/sysctl.d/99-sonarqube.conf
echo 'fs.file-max=131072'      | sudo tee -a /etc/sysctl.d/99-sonarqube.conf
sudo sysctl --system
echo 'sonar   -   nofile   131072' | sudo tee    /etc/security/limits.d/99-sonarqube.conf
echo 'sonar   -   nproc    8192'   | sudo tee -a /etc/security/limits.d/99-sonarqube.conf
```

**Install SonarQube:**
```bash
sudo useradd -m -d /opt/sonarqube -s /bin/bash sonar
cd /tmp
curl -L -o sonarqube.zip "https://binaries.sonarsource.com/Distribution/sonarqube/sonarqube-25.11.0.114957.zip"
sudo apt install -y unzip
unzip sonarqube.zip
sudo mv sonarqube-25.11.0.114957 /opt/sonarqube-current
sudo chown -R sonar:sonar /opt/sonarqube-current
```

Edit `/opt/sonarqube-current/conf/sonar.properties`:
```properties
sonar.jdbc.username=sonar
sonar.jdbc.password=YourStrongPassword
sonar.jdbc.url=jdbc:postgresql://localhost:5432/sonarqube
```

**Create a systemd service at `/etc/systemd/system/sonarqube.service`:**
```ini
[Unit]
Description=SonarQube
After=network.target postgresql.service

[Service]
Type=forking
User=sonar
Group=sonar
ExecStart=/opt/sonarqube-current/bin/linux-x86-64/sonar.sh start
ExecStop=/opt/sonarqube-current/bin/linux-x86-64/sonar.sh stop
LimitNOFILE=131072
LimitNPROC=8192

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable sonarqube
sudo systemctl start sonarqube
```

Access at `http://<sonarqube-ip>:9000`. Default credentials: admin / admin (you will be prompted to change them).

**Configure the webhook for Jenkins Quality Gate integration:**
In SonarQube, go to Administration > Configuration > Webhooks > Create. Set the URL to `http://<jenkins-controller-ip>:8080/sonarqube-webhook/`.

---

### Amazon ECR

Create a private repository in the AWS Console under Amazon ECR:
- Name: `devsecops-demo`
- Image tag mutability: Immutable
- Immutable tag exclusions: `latest`

---

### Amazon EKS Cluster

Create the cluster using eksctl with the config provided in this repository:
```bash
eksctl create cluster -f k8s/eks-config.yaml
```

The cluster configuration (`k8s/eks-config.yaml`) provisions:
- A Kubernetes 1.32 cluster named `devsecops-eks` in `ap-south-1`
- A managed node group with 2x `t3.small` instances (AmazonLinux2)
- OIDC provider enabled for IAM roles for service accounts
- Addons: cluster-autoscaler, aws-ebs-csi-driver, aws-load-balancer-controller, external-dns

Verify nodes are distributed across availability zones:
```bash
kubectl get nodes --show-labels | grep topology.kubernetes.io/zone
```

Apply RBAC for the Jenkins agent:
```bash
kubectl apply -f k8s/clusterrole-binding.yaml
```

---

## Jenkins Configuration

**Credentials to add in Manage Jenkins > Credentials > System > Global:**

| ID | Kind | Value |
|---|---|---|
| `sonarqube-token` | Secret text | SonarQube user token for Jenkins |

**Maven tool:**
Manage Jenkins > Global Tool Configuration > Maven. Add name `maven3` with automatic installation enabled.

**aws-auth ConfigMap:**
Add the Jenkins agent IAM role to the EKS aws-auth ConfigMap so it can authenticate to the cluster:
```bash
kubectl -n kube-system edit configmap aws-auth
```
Add under `mapRoles`:
```yaml
- rolearn: arn:aws:iam::<account-id>:role/jenkins-agent-role
  username: jenkins-agent-role
  groups:
    - system:authenticated
```

---

## Running the Pipeline

1. Update placeholder values in `ci/Jenkinsfile`:
   - `<YOUR_SONARQUBE_IP>` -- Private or public IP of the SonarQube server
   - `<YOUR_AWS_ACCOUNT_ID>` -- Your 12-digit AWS account ID
   - `<YOUR_REGION>` -- AWS region (e.g., `ap-south-1`)
   - `<YOUR_CLUSTER_NAME>` -- EKS cluster name (e.g., `devsecops-eks`)

2. Update `<YOUR_EC2_KEY_PAIR>` in `k8s/eks-config.yaml` with your EC2 key pair name.

3. Push the repository to your private GitHub repo.

4. Create a Jenkins Pipeline job pointing to the repo with script path `ci/Jenkinsfile`.

5. Click Build Now.

The pipeline will run all nine stages sequentially. If any stage fails, the pipeline stops and post-failure actions run.

---

## Security Design

The pipeline applies the principle of least privilege at every layer.

- The Jenkins agent uses an EC2 IAM role, not static credentials. No AWS access keys are stored anywhere.
- The IAM role is granted only `AmazonEC2ContainerRegistryPowerUser` and a minimal inline `eks:DescribeCluster` policy.
- The agent is mapped into the EKS cluster via aws-auth as `system:authenticated`, meaning it has no Kubernetes permissions by default.
- A dedicated ClusterRole (`jenkins-deployer-role`) grants the agent only the verbs it needs: get, list, watch, create, update, patch, and delete on deployments, replicasets, services, pods, and namespaces.
- SonarQube tokens are stored as Jenkins Secret Text credentials and injected at runtime via `withCredentials`. They never appear in console output.
- Container images use immutable tags (build number) in ECR so artifacts cannot be overwritten after validation.
- Trivy scans both the filesystem and the final container image at separate points in the pipeline, catching vulnerabilities in dependencies and in the built artifact.
- The SonarQube Quality Gate acts as a hard gate -- analysis failure stops the pipeline before any image is built or pushed.

---

## Post-Pipeline Actions

The `post` block in the Jenkinsfile defines the following actions:

- On success: logs the build number and confirms the deployment reached EKS.
- On failure: logs the failed build number for quick identification.
- Always: runs `cleanWs()` to wipe the workspace on the agent, preventing stale artifacts from affecting future builds.

In production these blocks are typically extended to send Slack or email notifications, publish JUnit and coverage reports, push build metrics to CloudWatch, and trigger rollback pipelines.

---

## Triggering on Git Push

To trigger the pipeline automatically on a git push, configure a GitHub webhook:

1. GitHub repository > Settings > Webhooks > Add webhook.
2. Payload URL: `http://<jenkins-controller-public-ip>:8080/github-webhook/`
3. Content type: `application/json`
4. Events: select "Just the push event".
5. Save.

For multi-branch setups, create a Multibranch Pipeline job in Jenkins. It will auto-discover branches and run CI on each push or pull request.

---

## Potential Improvements

The following improvements are recommended for production hardening:

- Replace NodePort with an Ingress controller (AWS Load Balancer Controller) with TLS termination.
- Use Multibranch Pipeline with branch protection rules so no code merges to main without a passing build.
- Adopt GitOps with ArgoCD so Kubernetes pulls from Git instead of being pushed by Jenkins.
- Use DNS names instead of IP addresses for all internal service references.
- Use image SHA256 digests instead of mutable tags for Kubernetes deployments.
- Integrate image signing with Cosign and enforce admission control with Kyverno or OPA Gatekeeper.
- Migrate secrets to AWS Secrets Manager or HashiCorp Vault for rotation and auditing.
- Add Helm or Kustomize to manage environment-specific Kubernetes configuration.
- Implement progressive delivery using Argo Rollouts for canary or blue-green deployments.
- Add observability integrations to correlate build numbers with runtime metrics in CloudWatch or Grafana.
- Move Trivy scan results to a dedicated reporting tool like DefectDojo.
- Add automated rollback on failed Kubernetes rollout using `kubectl rollout undo`.
