#!/usr/bin/env bash
# TicketRush on a local kind cluster with Helm (NFR-DEP-03):
#   ./deploy/kind/up.sh     # create the cluster, build and load the images, install the chart
#   ./deploy/kind/down.sh   # delete the cluster
# Needs docker, kind, helm and kubectl. The gateway then answers on http://localhost:28080.
set -euo pipefail
cd "$(dirname "$0")/../.."

CLUSTER=ticketrush
NAMESPACE=ticketrush
MODULES=(api-gateway event-service booking-service payment-service ticket-service notification-service waiting-room-service)

if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --config deploy/kind/cluster.yaml
fi

echo "Building images..."
for module in "${MODULES[@]}"; do
  docker build -q -t "ticket-rush-$module:latest" --build-arg MODULE="$module" . > /dev/null &
done
wait
for module in "${MODULES[@]}"; do
  kind load docker-image "ticket-rush-$module:latest" --name "$CLUSTER" > /dev/null
done

helm upgrade --install ticketrush deploy/helm/ticketrush --namespace "$NAMESPACE" --create-namespace \
  --set images.pullPolicy=Never "$@"

echo "Waiting for every pod to be ready (first start pulls the infrastructure images)..."
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=5m
kubectl -n "$NAMESPACE" rollout status statefulset/kafka --timeout=5m
kubectl -n "$NAMESPACE" rollout status deployment/keycloak --timeout=5m
for module in "${MODULES[@]}"; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$module" --timeout=8m
done
kubectl -n "$NAMESPACE" get pods -o wide
echo
echo "Gateway: http://localhost:28080   Keycloak: http://localhost:28180   Mailpit: http://localhost:28025"
echo "Smoke test: GATEWAY=http://localhost:28080 KEYCLOAK=http://localhost:28180 MAILPIT=http://localhost:28025 \\"
echo "  DEMO_USER_PASSWORD=\$(kubectl -n $NAMESPACE get secret ticketrush-secrets -o jsonpath='{.data.DEMO_USER_PASSWORD}' | base64 -d) ./scripts/smoke-test.sh"
