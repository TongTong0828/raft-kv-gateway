# Required project acceptance targets

On 2026-10-18 the project owner clarified that all three supplied project bullets are required outcomes. This supersedes the initial v1 decision to treat 8,000 successful requests/s as an optional experiment and EKS as a render-only example. The current implementation is a verified milestone; final acceptance is still pending the performance gate and real AWS execution described here. Existing results remain unchanged historical evidence.

## Acceptance matrix

| Required outcome | Current evidence | Remaining acceptance |
|---|---|---|
| Three-node Apache Ratis KV, revision checks, persistent logs and snapshots; recovery under leader failure, restart and network partition | Implemented; real Docker fault suite, snapshot installation and recovery passed | Preserve these guarantees in the cloud deployment; record EKS recovery evidence |
| Nonblocking Netty HTTP gateway, pooled connections, health-aware balancing and per-service concurrency limits | Implemented; HTTP/lifecycle tests, overload and isolation experiments passed | Formal sustained successful-throughput and latency gate below |
| StatefulSets and Deployments running on AWS EKS; versioned gRPC configuration, atomic route replacement and cached forwarding during control outages | Kubernetes kind validation and local configuration-outage tests passed; EKS overlay renders | Provision actual EKS infrastructure, deploy the committed image, exercise the real load balancer and cloud recovery cases |

The previous highest-load runs delivered 7,996.17–7,998.47 successful requests/s over 30-second measurement scenarios. They do not establish at least 8,000 successful requests/s for a sustained acceptance interval. Their HTTP timing also excludes connection establishment, and their scenario summaries can include completion during graceful drain. These runs remain useful local observations, not final cloud acceptance.

## Performance gate

Use a dedicated load-generator EC2 instance in the same AWS Region/VPC, separate from the EKS worker nodes, and address the actual gateway Network Load Balancer DNS name. Record its instance type, architecture, client location, network path, HTTP/TLS mode, connection reuse, Pod limits and image digests. Same-VPC latency must not be presented as Internet-client latency.

For each of three consecutive runs:

1. Warm up for 30 seconds, then measure for 300 seconds using exactly 1,024-byte response bodies.
2. Count correct successful responses completed inside the fixed measurement window. Report started requests, failures, late completions and interrupted iterations separately; graceful-drain completions do not increase window throughput.
3. Require at least 2,400,000 successful window completions, equivalent to at least 8,000 successful requests/s. Record 10-second completion buckets to demonstrate sustained behavior rather than a short burst.
4. Require P99 no greater than 15 ms for successful client calls, with timing from immediately before the HTTP call through complete response receipt. Preserve the narrower HTTP transport timing separately for comparison with historical runs.
5. Require correct status and payload, and no application/transport failures in the acceptance workload. Retain generator-drop counts and resource observations; configured arrival rate alone never establishes success. Keep all attempted-request accounting, including failures during drain.

A calibration rate above 8,000/s, initially 8,400/s, provides scheduling margin. VUs must be allocated before measurement; their count and generator headroom are verified rather than assumed. If a run misses the gate, investigate the bottleneck and repeat the gate after a justified change. Do not lower durability or change the latency boundary to manufacture a passing result.

## Concrete AWS experiment proposal

The proposed starting Region is `us-east-1`, using standard-support EKS 1.37 after verifying regional availability and instance offerings in the authenticated account:

- One new, tagged VPC with three public subnets in three available AZs and an Internet Gateway; no NAT Gateway.
- Three single-AZ managed node groups, one On-Demand `c7g.large` node per AZ. Fixed-performance instances avoid burst-credit ambiguity.
- Three encrypted 1 GiB gp3 control PVCs plus approximately 20 GiB root storage per worker.
- Standard EBS CSI add-on with its own IAM role/Pod Identity; no Auto Mode provisioner mismatch.
- An ECR image published by immutable digest, with source-revision metadata checked against actual running Pods.
- AWS Load Balancer Controller with explicit IAM and an internal NLB using IP targets. Same-VPC access fits the project's trusted-network boundary; the experiment does not require unauthenticated public administrative endpoints.
- Two gateway replicas placed on different workers/AZs; explicit NLB cross-zone behavior and recorded backend placement.
- One separate On-Demand EC2 load generator for the measurement period, initially `c7g.large`, resized if calibration shows insufficient headroom. Use SSM access; no public SSH management rule is required.
- A private Kubernetes endpoint enabled, with any public API access restricted to the operator's address. Derive account identity from the authenticated AWS profile; credentials do not belong in this repository.

This is a proposal, not infrastructure already created. Actual node availability, IAM access, existing service quotas, EKS version and account restrictions must pass preflight before creation. An authenticated local AWS profile is not currently configured.

### Cost and lifetime

Official prices checked on 2026-10-18: standard-support EKS is $0.10/cluster-hour, `c7g.large` Linux On-Demand in `us-east-1` is $0.0725/instance-hour, gp3 is $0.08/GiB-month, public IPv4 is $0.005/address-hour, and NLB is $0.0225/hour plus $0.006/NLCU-hour. See [EKS pricing](https://aws.amazon.com/eks/pricing/), [official EC2 prices](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2.json), [official EBS prices](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ebs.json), [IPv4 pricing](https://aws.amazon.com/vpc/pricing/), and [NLB pricing](https://aws.amazon.com/elasticloadbalancing/pricing/).

For the proposed internal NLB, three worker IPv4 addresses and 63 GiB of gp3, the fixed cluster portion is approximately $0.362/hour, or $2.17 for six hours. One hour of a `c7g.large` generator plus its IPv4/root disk adds about $0.08. NLCUs, cross-AZ transfer, ECR, logs, results storage and any generator resizing are additional. These are estimates, not a bill guarantee.

Proposed authorization: a **$10 operating budget**, a **six-hour experiment deadline**, and deletion of resources created specifically for this experiment after evidence collection. The budget is not an AWS-enforced spending cap. Obtain the account access and budget/lifetime decision before creating paid resources.

## Cloud evidence and cleanup

Record cluster identity/version, source commit, image digests, three-AZ node/Pod placement, three bound PVCs and their EBS volume IDs, healthy NLB targets, real HTTP responses, and all raw performance results. Exercise a leader Pod failure, persistent restart/replacement, gateway rollout, atomic route update under traffic, and cached forwarding while all application control-plane Pods are down. If repeating Raft network isolation in EKS, first prove that the chosen network-policy implementation actually blocks the intended Raft traffic while application gRPC remains reachable.

Keep Kubernetes/EKS control-plane availability distinct from this application's three Raft control Pods. Recovery evidence must include retained value/revision or positive snapshot-install/index observations, not merely a ready Pod.

Delete the owned LoadBalancer Service first and wait for AWS load-balancer/target resources to disappear. Then remove the owned application and cluster resources. The current StorageClass uses `Retain`: deleting a PVC or cluster alone does not establish EBS deletion. Explicitly check the recorded EBS volume IDs, load balancers, EC2 instances, ENIs, ECR repository, temporary result storage, log groups and experiment-specific IAM resources. Touch only resources owned by this experiment and record the cleanup outcome.

## Execution order

1. Authenticate and verify the selected AWS account/profile; confirm the experiment budget, lifetime and cleanup scope.
2. Prepare and validate reproducible infrastructure/bootstrap, deployment, strict benchmark and owned-cleanup scripts against this acceptance document.
3. Run a local calibration of the stricter measurement instrumentation.
4. Create the isolated AWS environment, deploy by digest, and perform real EKS smoke/recovery/configuration tests.
5. Run all three sustained acceptance measurements through the actual NLB. Investigate and resolve any missed gate rather than treating the offered rate as a result.
6. Export sanitized evidence, verify cleanup, and update the English README and result tables with actual outcomes. Mark final acceptance complete only when the cloud and performance gates pass.
