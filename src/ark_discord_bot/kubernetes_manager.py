"""Kubernetes manager for ARK server operations."""

import asyncio
import logging
from datetime import datetime

from kubernetes import client, config
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)


def is_transient_k8s_error(exception: Exception) -> bool:
    """Check if a Kubernetes API error is transient (temporary infrastructure issue).

    Transient errors include:
    - etcd leader changes (HTTP 500 with specific error messages)
    - etcd request timeouts (HTTP 500 with specific error messages)
    - etcd no leader errors (HTTP 500 with specific error messages)

    Args:
        exception: The exception to check

    Returns:
        bool: True if the error is transient, False otherwise
    """
    if not isinstance(exception, ApiException):
        return False

    # HTTP 500 errors with specific etcd messages are transient
    if exception.status == 500:
        error_body = str(exception.body) if exception.body else ""
        transient_messages = [
            "etcdserver: leader changed",
            "etcdserver: request timed out",
            "etcdserver: no leader",
        ]
        return any(msg in error_body for msg in transient_messages)

    return False


class KubernetesManager:
    """Manages Kubernetes operations for ARK server."""

    def __init__(self, namespace: str, deployment_name: str, service_name: str):
        """Initialize KubernetesManager.

        Args:
            namespace: Kubernetes namespace
            deployment_name: Name of the deployment
            service_name: Name of the service
        """
        self.namespace = namespace
        self.deployment_name = deployment_name
        self.service_name = service_name

        # Load Kubernetes config
        try:
            config.load_incluster_config()
        except config.ConfigException:
            try:
                config.load_kube_config()
            except config.ConfigException:
                # In test environment, skip config loading
                pass

    async def restart_server(self) -> bool:
        """Restart the ARK server using rollout restart.

        Returns:
            bool: True if restart was successful, False otherwise
        """
        try:
            apps_v1 = client.AppsV1Api()

            # Create patch to trigger rollout restart
            patch_body = {
                "spec": {
                    "template": {
                        "metadata": {
                            "annotations": {
                                "kubectl.kubernetes.io/restartedAt": datetime.utcnow().isoformat()
                            }
                        }
                    }
                }
            }

            # Apply patch to trigger restart
            await asyncio.get_event_loop().run_in_executor(
                None,
                apps_v1.patch_namespaced_deployment,
                self.deployment_name,
                self.namespace,
                patch_body,
            )

            logger.info(f"Successfully triggered restart for {self.deployment_name}")
            return True

        except ApiException as e:
            logger.error(f"Failed to restart server: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error during restart: {e}")
            return False

    async def get_server_status(self) -> str:
        """Get the current status of the ARK server.

        Returns:
            str: Status of the server
                - 'running': Server deployment is running
                - 'not_ready': Server deployment is not ready
                - 'transient_error': Temporary infrastructure error (no Discord notification)
                - 'error': Persistent error (sends Discord notification)
        """
        try:
            apps_v1 = client.AppsV1Api()

            deployment = await asyncio.get_event_loop().run_in_executor(
                None,
                apps_v1.read_namespaced_deployment,
                self.deployment_name,
                self.namespace,
            )

            if (
                deployment.status.ready_replicas
                and deployment.status.ready_replicas > 0
            ):
                return "running"
            return "not_ready"

        except ApiException as e:
            # Check if this is a transient error (e.g., etcd leader change)
            if is_transient_k8s_error(e):
                logger.warning(
                    f"Transient K8s API error (no notification): {e.status} - {e.reason}"
                )
                return "transient_error"
            logger.error(f"Failed to get server status: {e}")
            return "error"
        except Exception as e:
            logger.error(f"Unexpected error getting server status: {e}")
            return "error"
