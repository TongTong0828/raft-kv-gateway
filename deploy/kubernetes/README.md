# Kubernetes deployment

The base runs three fixed Raft voters in a `Parallel` StatefulSet, one gateway Deployment, and two deterministic backend Deployments. Each voter has its own 1 GiB PVC. A headless Service provides stable peer DNS before readiness, and a PodDisruptionBudget requires two available voters. Losing quorum does not make a healthy control process fail its liveness probe. Gateway readiness requires an installed configuration; cached routes remain usable during a control-plane outage.

## Reproduce the local validation

Requirements: Docker, Bash, curl, and Python 3. The helper downloads checksum-verified kind **v0.33.0** and kubectl **v1.37.0** into the ignored `work/tools/` directory. It never changes the global Kubernetes context.

```bash
./scripts/dev.sh build
./scripts/kind-smoke.sh --output work/kind-results.json
```

The script creates a uniquely named kind cluster with a pinned Kubernetes v1.37.0 image, loads the local application image, installs the kind overlay, and asserts:

- Three ready voters and a working elected leader.
- A committed service configuration and a real 1 KiB HTTP response.
- Identical KV value and modification revision after replacing `cp-0`, with all three PVCs retained.
- A successful gateway rollout followed by configuration-backed forwarding.

It saves JSON evidence and removes its own cluster on exit. Use `--keep --name kv-local` to retain the cluster for inspection. Its private kubeconfig is `work/kubeconfig-kv-local`; use it explicitly:

```bash
work/tools/kubectl --kubeconfig work/kubeconfig-kv-local -n kv-gateway get pods,pvc
work/tools/kubectl --kubeconfig work/kubeconfig-kv-local -n kv-gateway port-forward service/gateway 8080:8080
work/tools/kind delete cluster --name kv-local
```

Render either overlay without creating resources:

```bash
work/tools/kubectl kustomize deploy/kubernetes/overlays/kind
work/tools/kubectl kustomize deploy/kubernetes/overlays/eks
```

## EKS example — render validated, not cloud deployed

The EKS overlay demonstrates two gateway replicas, a gateway LoadBalancer Service, three-zone control-plane placement, and encrypted gp3 volumes. It requires an existing EC2-backed EKS cluster spanning at least three schedulable availability zones, the standard Amazon EBS CSI driver with its IAM permissions, and a registry image accessible to the nodes. Build and push the application image to your chosen registry, then configure a Kustomize `images` replacement for `raft-kv-gateway:local` before applying. No image registry or AWS infrastructure is created by these scripts.

The example StorageClass uses `ebs.csi.aws.com`, `WaitForFirstConsumer`, and a `Retain` reclaim policy. EKS Auto Mode uses a different provisioner, `ebs.csi.eks.amazonaws.com`; adjust the example for that environment. See [AWS EBS CSI prerequisites](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html).

For an initial empty installation, seed the route from inside a control pod after the voters are ready:

```bash
kubectl -n kv-gateway exec cp-0 -- java -jar /app/cli.jar \
  --seeds cp-0.control:50051,cp-1.control:50051,cp-2.control:50051 \
  service put /config/orders.json --expected-revision 0
```

For subsequent changes, read the current service revision and supply that revision in the update. The gateway keeps its incoming path unchanged, including the `/orders` prefix. Raft and application gRPC endpoints and management ports stay internal. The supplied gateway serves plain HTTP/1.1; ingress TLS, authentication, and cloud networking policy are deployment responsibilities.

PVC deletion and Raft membership changes are separate operations. These manifests support the three configured voters only. A PDB governs voluntary evictions; it does not make direct pod deletion or simultaneous node failure safe.
