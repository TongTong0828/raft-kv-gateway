# Security

This is an educational distributed-systems project with a deliberately small protocol surface. The supplied control gRPC, Raft, management HTTP, and backend endpoints assume a trusted private network. They do not implement authentication, authorization, or transport TLS. Compose binds published ports to loopback; Kubernetes exposes only the gateway Service externally in the EKS example.

Do not publish administrative or configuration endpoints to untrusted clients. Configuration can select backend network destinations. Production use requires an independently reviewed identity, transport, network, and operational design.

Please report sensitive vulnerabilities privately using GitHub's **Report a vulnerability** facility if enabled, or request a private contact channel without including exploit details in a public issue. Do not attach credentials or private infrastructure data. No guaranteed response time or production support is offered.
